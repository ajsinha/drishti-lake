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

}
