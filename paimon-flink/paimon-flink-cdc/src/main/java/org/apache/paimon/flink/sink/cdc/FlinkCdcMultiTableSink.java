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

package org.apache.paimon.flink.sink.cdc;

import org.apache.paimon.catalog.CatalogLoader;
import org.apache.paimon.flink.sink.CommittableStateManager;
import org.apache.paimon.flink.sink.Committer;
import org.apache.paimon.flink.sink.CommitterOperatorFactory;
import org.apache.paimon.flink.sink.FlinkSink;
import org.apache.paimon.flink.sink.FlinkStreamPartitioner;
import org.apache.paimon.flink.sink.MultiTableCommittable;
import org.apache.paimon.flink.sink.MultiTableCommittableChannelComputer;
import org.apache.paimon.flink.sink.MultiTableCommittableTypeInfo;
import org.apache.paimon.flink.sink.RestoreAndFailCommittableStateManager;
import org.apache.paimon.flink.sink.StoreMultiCommitter;
import org.apache.paimon.flink.sink.StoreSinkWrite;
import org.apache.paimon.flink.sink.StoreSinkWriteImpl;
import org.apache.paimon.flink.sink.TableFilter;
import org.apache.paimon.flink.sink.WrappedManifestCommittableSerializer;
import org.apache.paimon.manifest.WrappedManifestCommittable;
import org.apache.paimon.options.MemorySize;
import org.apache.paimon.options.Options;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.apache.flink.streaming.api.operators.OneInputStreamOperatorFactory;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;

import static org.apache.paimon.flink.sink.FlinkSink.assertStreamingConfiguration;
import static org.apache.paimon.flink.sink.FlinkSink.configureSlotSharingGroup;
import static org.apache.paimon.flink.utils.ParallelismUtils.forwardParallelism;

/**
 * A {@link FlinkSink} which accepts {@link CdcRecord} and waits for a schema change if necessary.
 */
public class FlinkCdcMultiTableSink implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String WRITER_NAME = "CDC MultiplexWriter";
    private static final String GLOBAL_COMMITTER_NAME = "Multiplex Global Committer";

    private final boolean isOverwrite = false;
    private final CatalogLoader catalogLoader;
    private final double writeCpuCores;
    private final MemorySize writeHeapMemory;
    private final double commitCpuCores;
    @Nullable private final MemorySize commitHeapMemory;
    private final String commitUser;
    private boolean eagerInit = false;
    private TableFilter tableFilter;

    public FlinkCdcMultiTableSink(
            CatalogLoader catalogLoader,
            double writeCpuCores,
            @Nullable MemorySize writeHeapMemory,
            double commitCpuCores,
            @Nullable MemorySize commitHeapMemory,
            String commitUser,
            boolean eagerInit,
            TableFilter tableFilter) {
        this.catalogLoader = catalogLoader;
        this.writeCpuCores = writeCpuCores;
        this.writeHeapMemory = writeHeapMemory;
        this.commitCpuCores = commitCpuCores;
        this.commitHeapMemory = commitHeapMemory;
        this.commitUser = commitUser;
        this.eagerInit = eagerInit;
        this.tableFilter = tableFilter;
    }

    private StoreSinkWrite.WithWriteBufferProvider createWriteProvider() {
        // for now, no compaction for multiplexed sink
        return (table, commitUser, state, ioManager, memoryPoolFactory, metricGroup) ->
                new StoreSinkWriteImpl(
                        table,
                        commitUser,
                        state,
                        ioManager,
                        isOverwrite,
                        table.coreOptions().prepareCommitWaitCompaction(),
                        true,
                        memoryPoolFactory,
                        metricGroup);
    }

    public DataStreamSink<?> sinkFrom(DataStream<CdcMultiplexRecord> input) {
        // This commitUser is valid only for new jobs.
        // After the job starts, this commitUser will be recorded into the states of write and
        // commit operators.
        // When the job restarts, commitUser will be recovered from states and this value is
        // ignored.
        return sinkFrom(input, commitUser, createWriteProvider());
    }

    public DataStreamSink<?> sinkFrom(
            DataStream<CdcMultiplexRecord> input,
            String commitUser,
            StoreSinkWrite.WithWriteBufferProvider sinkProvider) {
        StreamExecutionEnvironment env = input.getExecutionEnvironment();
        assertStreamingConfiguration(env);
        MultiTableCommittableTypeInfo typeInfo = new MultiTableCommittableTypeInfo();
        SingleOutputStreamOperator<MultiTableCommittable> written =
                input.transform(
                        WRITER_NAME, typeInfo, createWriteOperator(sinkProvider, commitUser));
        forwardParallelism(written, input);
        configureSlotSharingGroup(written, writeCpuCores, writeHeapMemory);

        // shuffle committables by table
        DataStream<MultiTableCommittable> partitioned =
                FlinkStreamPartitioner.partition(
                        written,
                        new MultiTableCommittableChannelComputer(),
                        input.getParallelism());

        SingleOutputStreamOperator<?> committed =
                partitioned.transform(
                        GLOBAL_COMMITTER_NAME,
                        typeInfo,
                        new CommitterOperatorFactory<>(
                                true,
                                false,
                                commitUser,
                                createCommitterFactory(tableFilter),
                                createCommittableStateManager()));
        forwardParallelism(committed, input);
        configureSlotSharingGroup(committed, commitCpuCores, commitHeapMemory);
        return committed.sinkTo(new DiscardingSink<>()).name("end").setParallelism(1);
    }

    protected OneInputStreamOperatorFactory<CdcMultiplexRecord, MultiTableCommittable>
            createWriteOperator(
                    StoreSinkWrite.WithWriteBufferProvider writeProvider, String commitUser) {
        return new CdcRecordStoreMultiWriteOperator.Factory(
                catalogLoader, writeProvider, commitUser, new Options());
    }

    // Table committers are dynamically created at runtime
    protected Committer.Factory<MultiTableCommittable, WrappedManifestCommittable>
            createCommitterFactory(TableFilter tableFilter) {

        // If checkpoint is enabled for streaming job, we have to
        // commit new files list even if they're empty.
        // Otherwise we can't tell if the commit is successful after
        // a restart.
        return context ->
                new StoreMultiCommitter(
                        catalogLoader,
                        context,
                        false,
                        Collections.emptyMap(),
                        eagerInit,
                        tableFilter);
    }

    protected CommittableStateManager<WrappedManifestCommittable> createCommittableStateManager() {
        return new RestoreAndFailCommittableStateManager<>(
                WrappedManifestCommittableSerializer::new, true);
    }
}
