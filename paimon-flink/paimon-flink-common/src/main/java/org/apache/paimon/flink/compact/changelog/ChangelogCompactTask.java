/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.compact.changelog;

import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.flink.compact.changelog.format.CompactedChangelogReadOnlyFormat;
import org.apache.paimon.flink.sink.Committable;
import org.apache.paimon.fs.Path;
import org.apache.paimon.fs.PositionOutputStream;
import org.apache.paimon.io.CompactIncrement;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.io.DataFilePathFactory;
import org.apache.paimon.io.DataIncrement;
import org.apache.paimon.options.MemorySize;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.sink.CommitMessageImpl;
import org.apache.paimon.utils.FileStorePathFactory;
import org.apache.paimon.utils.IOUtils;
import org.apache.paimon.utils.Preconditions;
import org.apache.paimon.utils.ThreadPoolUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

/**
 * {@link ChangelogCompactTask} to compact several changelog files from the same partition into one
 * file, in order to reduce the number of small files.
 */
public class ChangelogCompactTask implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(ChangelogCompactTask.class);

    private final long checkpointId;
    private final BinaryRow partition;
    private final int totalBuckets;
    private final Map<Integer, List<DataFileMeta>> newFileChangelogFiles;
    private final Map<Integer, List<DataFileMeta>> compactChangelogFiles;

    public ChangelogCompactTask(
            long checkpointId,
            BinaryRow partition,
            int totalBuckets,
            Map<Integer, List<DataFileMeta>> newFileChangelogFiles,
            Map<Integer, List<DataFileMeta>> compactChangelogFiles) {
        this.checkpointId = checkpointId;
        this.partition = partition;
        this.totalBuckets = totalBuckets;
        this.newFileChangelogFiles = newFileChangelogFiles;
        this.compactChangelogFiles = compactChangelogFiles;
        Preconditions.checkArgument(
                newFileChangelogFiles.isEmpty() || compactChangelogFiles.isEmpty(),
                "Both newFileChangelogFiles and compactChangelogFiles are not empty. "
                        + "There is no such table where changelog is produced both from new files and from compaction. "
                        + "This is unexpected.");
    }

    public long checkpointId() {
        return checkpointId;
    }

    public BinaryRow partition() {
        return partition;
    }

    public int totalBuckets() {
        return totalBuckets;
    }

    public Map<Integer, List<DataFileMeta>> newFileChangelogFiles() {
        return newFileChangelogFiles;
    }

    public Map<Integer, List<DataFileMeta>> compactChangelogFiles() {
        return compactChangelogFiles;
    }

    public List<Committable> doCompact(
            FileStoreTable table, ExecutorService executor, MemorySize bufferSize)
            throws Exception {
        Preconditions.checkArgument(
                bufferSize.getBytes() <= Integer.MAX_VALUE,
                "Changelog pre-commit compaction buffer size ({} bytes) too large! "
                        + "The maximum possible value is {} bytes.",
                bufferSize.getBytes(),
                Integer.MAX_VALUE);

        FileStorePathFactory pathFactory = table.store().pathFactory();
        List<ReadTask> tasks = new ArrayList<>();
        BiConsumer<Map<Integer, List<DataFileMeta>>, Boolean> addTasks =
                (files, isCompactResult) -> {
                    for (Map.Entry<Integer, List<DataFileMeta>> entry : files.entrySet()) {
                        int bucket = entry.getKey();
                        DataFilePathFactory dataFilePathFactory =
                                pathFactory.createDataFilePathFactory(partition, bucket);
                        for (DataFileMeta meta : entry.getValue()) {
                            ReadTask task =
                                    new ReadTask(
                                            table,
                                            dataFilePathFactory.toPath(meta),
                                            bucket,
                                            isCompactResult,
                                            meta);
                            Preconditions.checkArgument(
                                    meta.fileSize() <= bufferSize.getBytes(),
                                    "Trying to compact changelog file with size {} bytes, "
                                            + "while the buffer size is only {} bytes. This is unexpected.",
                                    meta.fileSize(),
                                    bufferSize.getBytes());
                            tasks.add(task);
                        }
                    }
                };
        addTasks.accept(newFileChangelogFiles, false);
        addTasks.accept(compactChangelogFiles, true);

        Semaphore semaphore = new Semaphore((int) bufferSize.getBytes());
        BlockingQueue<ReadTask> finishedTasks = new LinkedBlockingQueue<>();
        ThreadPoolUtils.submitAllTasks(
                executor,
                t -> {
                    // Why not create `finishedTasks` as a blocking queue and use it to limit the
                    // total size of bytes awaiting to be copied? Because finished tasks are added
                    // after their contents are read, so even if `finishedTasks` is full, each
                    // thread can still read one more file, and the limit will become
                    // `bytesInThreads + bufferSize`, not just `bufferSize`.
                    try {
                        semaphore.acquire((int) t.meta.fileSize());
                        t.readFully();
                        finishedTasks.put(t);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                },
                tasks);

        OutputStream outputStream = new OutputStream();
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            // copy all files into a new big file
            ReadTask task = finishedTasks.take();
            if (task.exception != null) {
                throw task.exception;
            }
            write(task, outputStream, results);
            semaphore.release((int) task.meta.fileSize());
        }
        outputStream.out.close();

        return produceNewCommittables(results, table, pathFactory, outputStream.path);
    }

    private void write(ReadTask task, OutputStream outputStream, List<Result> results)
            throws Exception {
        if (!outputStream.isInitialized) {
            Path outputPath =
                    new Path(task.path.getParent(), "tmp-compacted-changelog-" + UUID.randomUUID());
            outputStream.init(outputPath, task.table.fileIO().newOutputStream(outputPath, false));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Copying bytes from {} to {}", task.path, outputStream.path);
        }
        long offset = outputStream.out.getPos();
        outputStream.out.write(task.result);
        results.add(
                new Result(
                        task.bucket,
                        task.isCompactResult,
                        task.meta,
                        offset,
                        outputStream.out.getPos() - offset));
    }

    private List<Committable> produceNewCommittables(
            List<Result> results,
            FileStoreTable table,
            FileStorePathFactory pathFactory,
            Path changelogTempPath)
            throws IOException {
        Result baseResult = results.get(0);
        Preconditions.checkArgument(baseResult.offset == 0);
        DataFilePathFactory dataFilePathFactory =
                pathFactory.createDataFilePathFactory(partition, baseResult.bucket);
        // see Java docs of `CompactedChangelogFormatReaderFactory`
        String realName =
                "compacted-changelog-"
                        + UUID.randomUUID()
                        + "$"
                        + baseResult.bucket
                        + "-"
                        + baseResult.length;
        Path realPath =
                dataFilePathFactory.toAlignedPath(
                        realName
                                + "."
                                + CompactedChangelogReadOnlyFormat.getIdentifier(
                                        baseResult.meta.fileFormat()),
                        baseResult.meta);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Rename {} to {}", changelogTempPath, realPath);
        }
        table.fileIO().rename(changelogTempPath, realPath);

        Map<Integer, List<Result>> bucketedResults = new HashMap<>();
        for (Result result : results) {
            bucketedResults.computeIfAbsent(result.bucket, b -> new ArrayList<>()).add(result);
        }
        List<Committable> newCommittables = new ArrayList<>();
        for (Map.Entry<Integer, List<Result>> entry : bucketedResults.entrySet()) {
            List<DataFileMeta> newFilesChangelog = new ArrayList<>();
            List<DataFileMeta> compactChangelog = new ArrayList<>();
            for (Result result : entry.getValue()) {
                // see Java docs of `CompactedChangelogFormatReaderFactory`
                String name =
                        (result.offset == 0
                                        ? realName
                                        : realName + "-" + result.offset + "-" + result.length)
                                + "."
                                + CompactedChangelogReadOnlyFormat.getIdentifier(
                                        result.meta.fileFormat());
                if (result.isCompactResult) {
                    compactChangelog.add(result.meta.rename(name));
                } else {
                    newFilesChangelog.add(result.meta.rename(name));
                }
            }

            CommitMessageImpl newMessage =
                    new CommitMessageImpl(
                            partition,
                            entry.getKey(),
                            totalBuckets,
                            new DataIncrement(
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    newFilesChangelog),
                            new CompactIncrement(
                                    Collections.emptyList(),
                                    Collections.emptyList(),
                                    compactChangelog));
            newCommittables.add(new Committable(checkpointId, Committable.Kind.FILE, newMessage));
        }
        return newCommittables;
    }

    public int hashCode() {
        return Objects.hash(checkpointId, partition, newFileChangelogFiles, compactChangelogFiles);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ChangelogCompactTask that = (ChangelogCompactTask) o;
        return checkpointId == that.checkpointId
                && Objects.equals(partition, that.partition)
                && Objects.equals(newFileChangelogFiles, that.newFileChangelogFiles)
                && Objects.equals(compactChangelogFiles, that.compactChangelogFiles);
    }

    @Override
    public String toString() {
        return String.format(
                "ChangelogCompactionTask {"
                        + "partition = %s, "
                        + "newFileChangelogFiles = %s, "
                        + "compactChangelogFiles = %s}",
                partition, newFileChangelogFiles, compactChangelogFiles);
    }

    private static class ReadTask {

        private final FileStoreTable table;
        private final Path path;
        private final int bucket;
        private final boolean isCompactResult;
        private final DataFileMeta meta;

        private byte[] result = null;
        private Exception exception = null;

        private ReadTask(
                FileStoreTable table,
                Path path,
                int bucket,
                boolean isCompactResult,
                DataFileMeta meta) {
            this.table = table;
            this.path = path;
            this.bucket = bucket;
            this.isCompactResult = isCompactResult;
            this.meta = meta;
        }

        private void readFully() {
            try {
                result = IOUtils.readFully(table.fileIO().newInputStream(path), true);
                table.fileIO().deleteQuietly(path);
            } catch (Exception e) {
                exception = e;
            }
        }
    }

    private static class OutputStream {

        private Path path;
        private PositionOutputStream out;
        private boolean isInitialized;

        private OutputStream() {
            this.isInitialized = false;
        }

        private void init(Path path, PositionOutputStream out) {
            this.path = path;
            this.out = out;
            this.isInitialized = true;
        }
    }

    private static class Result {

        private final int bucket;
        private final boolean isCompactResult;
        private final DataFileMeta meta;
        private final long offset;
        private final long length;

        private Result(
                int bucket, boolean isCompactResult, DataFileMeta meta, long offset, long length) {
            this.bucket = bucket;
            this.isCompactResult = isCompactResult;
            this.meta = meta;
            this.offset = offset;
            this.length = length;
        }
    }
}
