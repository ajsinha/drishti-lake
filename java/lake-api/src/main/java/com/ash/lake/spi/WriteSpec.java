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
package com.ash.lake.spi;

import com.ash.lake.LakeRuntime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a {@link TableStore} needs to give the loader a {@link TableWriter} for one load of one table.
 *
 * @param runtime    the instance the load runs in (its options, storages)
 * @param format     the store's format name
 * @param path       the table's location ({@code <root>/<domain>/<table>}, or the path the load names)
 * @param root       the lake's root
 * @param domain     the table's domain
 * @param table      the table's name
 * @param storage    the storage options (S3 endpoint, region, credentials)
 * @param formatOptions the format's own options from the load (an Iceberg catalog's settings)
 * @param properties table properties for a new table
 * @param appends    whether the load appends (else it replaces each business date)
 * @param batchId    an append's batch id (committed once per date), or null
 * @param slice      a shared table's slice of this load (by column): a replace takes out only the slice's rows of the date
 * @param partition  the partition columns after {@code business_date}, in order (the values a row gives by {@link LoadRow#partition})
 * @param promoted   the promoted columns, in order (the values a row gives by {@link LoadRow#tag})
 * @param vectors    the vector columns, in order ({@link LoadRow#vector})
 * @param sortBy     the columns the rows are sorted by ({@code id}: the row id)
 * @param sortedById whether the rows are sorted by the id alone
 * @param loadColumns whether the rows carry {@code _load_id} and {@code _source_file}
 * @param loadIds    the load ids of this load's input files
 * @param layout     the data files' encoding
 */
public record WriteSpec(LakeRuntime runtime, String format, String path, String root, String domain, String table, Map<String, String> storage,
        Map<String, String> formatOptions, Map<String, String> properties, boolean appends, String batchId, Map<String, String> slice,
        List<String> partition, List<String> promoted, List<String> vectors, List<String> sortBy, boolean sortedById, boolean loadColumns,
        List<String> loadIds, FileLayout layout) {

    public WriteSpec {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(path, "path");
        storage = copy(storage);
        formatOptions = copy(formatOptions);
        properties = copy(properties);
        slice = copy(slice);
        partition = List.copyOf(partition);
        promoted = List.copyOf(promoted);
        vectors = List.copyOf(vectors);
        sortBy = List.copyOf(sortBy);
        loadIds = List.copyOf(loadIds);
        Objects.requireNonNull(layout, "layout");
    }

    /** A map kept in its order (a load's settings may hold a null value). */
    private static Map<String, String> copy(Map<String, String> m) {
        return m == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(m));
    }

    /**
     * The data files' encoding: the codec ({@code auto}: the format's default), row group bytes (0: the format's default), the vectors'
     * codec and whether their doubles are written byte-stream-split.
     */
    public record FileLayout(String compression, long rowGroupBytes, String vectorCompression, boolean byteStreamSplit) {}
}
