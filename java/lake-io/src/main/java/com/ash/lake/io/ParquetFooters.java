/*
 * Drishti Lake · Delta, Iceberg and plain files, loaded and read as one lake.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.lake.io;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.conf.ParquetConfiguration;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

/**
 * The footer cache shared by both lake engines, and the Hadoop-free read options they open files with.
 *
 * <p>A lake's data file never changes once written (a rewrite is a new file), so its footer is read once, not on every
 * single-entity read; a file of many row groups has a large footer (hundreds of KB to parse). Keyed by path and length;
 * weighed in bytes (2.9.9): a parsed footer holds about 700 bytes a column chunk (its statistics, encodings and offsets) and
 * 1 KB of its own, so a lake of 34 columns costs about 23 KB a row group (measured at 1,000,000 positions: 385 MB for 16,487 row
 * groups when the cache was weighed by row groups). At most {@code -Dlake.parquet.footer-cache-mb} (default 64) are kept,
 * across every connector of the process.
 */
public final class ParquetFooters {

    /** Parquet's options without a Hadoop {@code Configuration}; read-only after construction, so shared. */
    public static final ParquetConfiguration PLAIN = new PlainParquetConfiguration();

    /** The bytes a parsed footer is weighed at per column chunk, and on its own. */
    static final int CHUNK_BYTES = 700;
    static final int FOOTER_BYTES = 1024;
    /** The most bytes of parsed footers kept: {@code lake.parquet.footer-cache-mb} (64). */
    final long limitBytes;

    private final Cache<String, ParquetMetadata> footers;

    ParquetFooters(long limitBytes) {
        this.limitBytes = limitBytes;
        this.footers = Caffeine.newBuilder().maximumWeight(limitBytes).weigher((String k, ParquetMetadata m) -> weight(m)).recordStats().build();
    }

    /** The parsed footers {@code runtime} keeps (one cache per runtime, sized by its options). */
    public static ParquetFooters of(com.ash.lake.LakeRuntime runtime) {
        return runtime.part(ParquetFooters.class, () -> new ParquetFooters(runtime.options().footerCacheBytes()));
    }

    /** A parsed footer's weight in bytes: about {@link #CHUNK_BYTES} a column chunk (every row group's columns), plus its own. */
    static int weight(ParquetMetadata m) {
        long b = FOOTER_BYTES;
        for (var block : m.getBlocks()) {
            b += 64L + (long) CHUNK_BYTES * block.getColumns().size();
        }
        return (int) Math.min(Integer.MAX_VALUE, b);
    }

    /** The bytes of parsed footers kept now (their weights), for Health and tests. */
    public long weightBytes() {
        footers.cleanUp();                                         // Caffeine weighs writes on its maintenance thread: settle them first
        return footers.policy().eviction().map(e -> e.weightedSize().orElse(0L)).orElse(0L);
    }

    /**
     * Options for a read: row groups pruned by statistics only (a reader filters rows itself), no dictionary, bloom or
     * page-index reads unless a caller turns them on (extra I/O for a sorted table), no Hadoop vectored I/O.
     */
    public static ParquetReadOptions.Builder options(CompressionCodecFactory codecs) {
        return ParquetReadOptions.builder(PLAIN).withCodecFactory(codecs).useStatsFilter(true).useDictionaryFilter(false)
                .useBloomFilter(false).useColumnIndexFilter(false).withUseHadoopVectoredIo(false);
    }

    /** The footer of {@code in}, from the cache or read through {@code stream} (which stays open). */
    public ParquetMetadata footer(String path, long length, InputFile in, SeekableInputStream stream, ParquetReadOptions options)
            throws IOException {
        String key = path + "|" + length;
        ParquetMetadata footer = footers.getIfPresent(key);
        if (footer == null) {
            footer = ParquetFileReader.readFooter(in, options, stream);
            footers.put(key, footer);
        }
        return footer;
    }

    /** Forgets every parsed footer (a purge of a connector's caches; a file changed in place is read again). */
    public void purge() {
        footers.invalidateAll();
    }

    /** Footers kept and the share of reads that found theirs here (for Health and tests). */
    public String stats() {
        var s = footers.stats();
        return footers.estimatedSize() + " footers, hits " + s.hitCount() + ", misses " + s.missCount();
    }

    /** Footer reads answered from the cache so far. */
    public long hits() {
        return footers.stats().hitCount();
    }
}
