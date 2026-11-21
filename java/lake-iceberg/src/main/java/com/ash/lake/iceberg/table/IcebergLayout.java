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
package com.ash.lake.iceberg.table;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The table layout the connector reads fastest, shared by the connector, the loader (lake-load) and
 * {@link IcebergMaintenance}: one table per kind with columns {@code id}, {@code doc} (the whole JSON document),
 * {@code business_date} (an identity partition) and the pack's promoted fields beside them ({@code counterparty.id} is
 * column {@code counterparty__id}; numbers are {@code double}, everything else {@code string}; a vector field,
 * {@code layout.<kind>.vectors}, is an optional {@code list<double>} column whose missing elements are null, never 0); each business day
 * sorted by id and cut into files of {@code file-rows} rows, each file its own id range; small Parquet row groups, so
 * one entity is one small row group; column metrics (minimum and maximum) for every column but {@code doc}.
 */
public final class IcebergLayout {

    public static final String ID = "id";
    public static final String DOC = "doc";
    public static final String DATE = "business_date";
    /** Table property: the rows per file the layout cuts each day into (maintenance re-cuts drifted days to it). */
    public static final String FILE_ROWS = "layout.file-rows";   // a persisted name (com.ash.lake.PersistedNames)
    public static final int DEFAULT_FILE_ROWS = 250_000;
    /**
     * About 900 trade documents a row group (measured: zstd packs a 6.3 KB trade into about 0.7 KB, and the writer
     * measures a row group compressed), so one entity's read decodes about 6 MB of documents.
     */
    public static final long DEFAULT_ROW_GROUP_BYTES = 1024L * 1024;

    /** One entity's row for a business day: its document and its promoted fields (path to a Double, a String or null). */
    public record Row(String id, String doc, Map<String, Object> columns) {}

    private IcebergLayout() {
    }

    /** The column a promoted document path is stored in. */
    public static String column(String path) {
        return path.replace(".", "__");
    }

    /**
     * A vector value as a list of doubles, a null element kept null (never 0); null for anything that is not a list of numbers (the
     * loader rejected such a row already).
     */
    static List<Double> vector(Object v) {
        if (!(v instanceof List<?> list)) {
            return null;
        }
        List<Double> out = new ArrayList<>(list.size());
        for (Object e : list) {
            if (e != null && !(e instanceof Number)) {
                return null;
            }
            out.add(e == null ? null : ((Number) e).doubleValue());
        }
        return out;
    }

    /**
     * The index of the {@code business_date} identity field in {@code spec}. The layout's spec is that field, then an identity field per
     * {@code partition-by} field on a text column (READ_API.md section 5); a table another engine re-partitioned otherwise (another
     * transform, no date) is refused: a file's partition tuple must hold every field's value, and only these are known to the writers.
     */
    public static int dateIndex(com.ash.lake.iceberg.meta.PartitionSpec spec, com.ash.lake.iceberg.types.Schema schema, String table) {
        com.ash.lake.iceberg.types.NestedField date = schema.field(DATE);
        int found = -1;
        for (int i = 0; i < spec.fields().size(); i++) {
            com.ash.lake.iceberg.meta.PartitionSpec.Field f = spec.fields().get(i);
            com.ash.lake.iceberg.types.NestedField src = schema.field(f.sourceId());
            boolean identity = f.transform() instanceof com.ash.lake.iceberg.meta.Transform.Identity;
            if (found < 0 && date != null && f.sourceId() == date.id() && identity) {
                found = i;
            } else if (!(f.transform() instanceof com.ash.lake.iceberg.meta.Transform.Void)
                    && !(identity && src != null && com.ash.lake.iceberg.types.Type.Primitive.STRING.equals(src.type()))) {
                throw new IllegalStateException(table + " is not in the layout: its partition spec " + spec.specId() + " has the field '" + f.name()
                        + "' (" + f.transform() + ") beside the identity partition on " + DATE + "; the layout's tables are partitioned by " + DATE
                        + ", then by identity on the partition-by fields (write this table again so, or restore its spec)");
            }
        }
        if (found < 0) {
            throw new IllegalStateException(table + " is not in the layout: no identity partition on " + DATE);
        }
        return found;
    }
}
