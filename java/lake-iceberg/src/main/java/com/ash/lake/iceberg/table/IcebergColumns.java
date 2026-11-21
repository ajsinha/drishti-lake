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

import com.ash.lake.iceberg.scan.FileTask;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.iceberg.types.Type;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import java.util.ArrayList;
import java.util.List;

/** Iceberg's schema in the product's types: one rule for the store's scans and the read API. */
public final class IcebergColumns {

    private IcebergColumns() {
    }

    /** An Iceberg schema as the product's columns. */
    public static Columns columns(Schema schema) {
        List<Column> out = new ArrayList<>();
        for (NestedField f : schema.columns()) {
            out.add(new Column(f.name(), type(f.type())));
        }
        return Columns.of(out);
    }

    /** An Iceberg type as the product's. */
    public static ColumnType type(Type t) {
        return switch (t.id()) {
            case STRING, UUID -> ColumnType.STRING;
            case DOUBLE, FLOAT -> ColumnType.DOUBLE;
            case LONG -> ColumnType.LONG;
            case INT -> ColumnType.INT;
            case BOOLEAN -> ColumnType.BOOLEAN;
            case DATE -> ColumnType.DATE;
            case TIMESTAMP, TIMESTAMPTZ -> ColumnType.TIMESTAMP;
            case BINARY, FIXED -> ColumnType.BINARY;
            case LIST -> {
                Type e = ((Type.ListType) t).element();
                yield e.id() == Type.Id.STRING ? ColumnType.STRING_LIST : e.id() == Type.Id.DOUBLE || e.id() == Type.Id.FLOAT ? ColumnType.DOUBLE_LIST
                        : ColumnType.OTHER;
            }
            default -> ColumnType.OTHER;
        };
    }

    /** A manifest entry as the read API's data file: the partition fields other than the date, and the text columns' bounds by logical name. */
    public static com.ash.lake.read.DataFile dataFile(Schema schema, Columns columns, FileTask t) {
        com.ash.lake.iceberg.manifest.DataFile f = t.file();
        java.util.Map<String, String> partition = new java.util.LinkedHashMap<>();
        com.ash.lake.iceberg.meta.PartitionSpec spec = t.spec();
        for (int i = 0; i < spec.fields().size(); i++) {
            NestedField src = schema.field(spec.fields().get(i).sourceId());
            if (src != null && !src.name().equals(IcebergLayout.DATE)) {
                Object v = f.partition()[i];
                partition.put(src.name(), v == null ? null : v.toString());
            }
        }
        java.util.Map<String, com.ash.lake.read.Bounds> bounds = new java.util.LinkedHashMap<>();
        for (var c : columns.all()) {
            if (c.type() == ColumnType.STRING && !c.name().equals(IcebergLayout.DOC) && !partition.containsKey(c.name())) {
                NestedField field = schema.field(c.name());
                byte[] lo = f.lowerBounds() == null ? null : f.lowerBounds().get(field.id());
                byte[] hi = f.upperBounds() == null ? null : f.upperBounds().get(field.id());
                Long nulls = f.nullValueCounts() == null ? null : f.nullValueCounts().get(field.id());
                if (lo != null || hi != null || nulls != null) {
                    bounds.put(c.name(), new com.ash.lake.read.Bounds(lo == null ? null : new String(lo, java.nio.charset.StandardCharsets.UTF_8),
                            hi == null ? null : new String(hi, java.nio.charset.StandardCharsets.UTF_8), nulls));
                }
            }
        }
        return new com.ash.lake.read.DataFile(f.path(), f.fileSizeInBytes(), com.ash.lake.read.FileFormat.PARQUET, f.recordCount(), t.hasDeletes(),
                partition.entrySet().stream().filter(e -> e.getValue() != null)
                        .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue, (a, b) -> a, java.util.LinkedHashMap::new)), bounds);
    }

}
