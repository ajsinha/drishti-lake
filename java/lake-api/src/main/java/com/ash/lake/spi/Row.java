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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row read from a table: its columns and their values as plain Java values ({@link String}, {@link Double}, {@link Long},
 * {@link Integer}, {@link Boolean}, {@link java.time.LocalDate}, {@code byte[]}, a {@link java.util.List} for a list or a vector; null
 * for a null). No table format's own value type appears.
 */
public record Row(Columns columns, Object[] values) {

    public Row {
        if (columns.size() != values.length) {
            throw new IllegalArgumentException(columns.size() + " columns, " + values.length + " values");
        }
    }

    public Object get(int i) {
        return values[i];
    }

    /** The value of column {@code name}; null when the row has no such column. */
    public Object get(String name) {
        int i = columns.indexOf(name);
        return i < 0 ? null : values[i];
    }

    /** The row as a map by column, in column order. */
    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            out.put(columns.get(i).name(), values[i]);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Row r && r.columns.equals(columns) && Arrays.deepEquals(r.values, values);
    }

    @Override
    public int hashCode() {
        return columns.hashCode() * 31 + Arrays.deepHashCode(values);
    }

    @Override
    public String toString() {
        return asMap().toString();
    }
}
