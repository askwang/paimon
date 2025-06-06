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

package org.apache.paimon.catalog;

import org.apache.paimon.options.Options;
import org.apache.paimon.partition.Partition;
import org.apache.paimon.table.Table;

import org.apache.paimon.shade.caffeine2.com.github.benmanes.caffeine.cache.Cache;
import org.apache.paimon.shade.caffeine2.com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.apache.paimon.options.CatalogOptions.CACHE_EXPIRE_AFTER_ACCESS;
import static org.apache.paimon.options.CatalogOptions.CACHE_EXPIRE_AFTER_WRITE;
import static org.apache.paimon.options.CatalogOptions.CACHE_PARTITION_MAX_NUM;

/**
 * A wrapper around CachingCatalog that provides accessor methods to test the underlying cache,
 * without making those fields public in the CachingCatalog itself.
 */
public class TestableCachingCatalog extends CachingCatalog {

    private final Duration cacheExpirationInterval;

    public TestableCachingCatalog(Catalog catalog, Duration expirationInterval, Ticker ticker) {
        this(catalog, expirationInterval, Duration.ofDays(1), ticker);
    }

    public TestableCachingCatalog(
            Catalog catalog, Duration expireAfterAccess, Duration expireAfterWrite, Ticker ticker) {
        super(catalog, createOptions(expireAfterAccess, expireAfterWrite));
        init(ticker);
        this.cacheExpirationInterval = expireAfterAccess;
    }

    private static Options createOptions(Duration expireAfterAccess, Duration expireAfterWrite) {
        Options options = new Options();
        options.set(CACHE_EXPIRE_AFTER_ACCESS, expireAfterAccess);
        options.set(CACHE_EXPIRE_AFTER_WRITE, expireAfterWrite);
        options.set(CACHE_PARTITION_MAX_NUM, 100L);
        return options;
    }

    public Cache<Identifier, Table> tableCache() {
        // cleanUp must be called as tests apply assertions directly on the underlying map, but
        // metadata table map entries are cleaned up asynchronously.
        tableCache.cleanUp();
        return tableCache;
    }

    public Cache<Identifier, List<Partition>> partitionCache() {
        partitionCache.cleanUp();
        return partitionCache;
    }

    public Optional<Duration> ageOf(Identifier identifier) {
        return tableCache.policy().expireAfterAccess().get().ageOf(identifier);
    }

    public Optional<Duration> remainingAgeFor(Identifier identifier) {
        return ageOf(identifier).map(cacheExpirationInterval::minus);
    }
}
