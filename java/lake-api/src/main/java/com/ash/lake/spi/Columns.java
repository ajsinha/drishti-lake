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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A table's or a scan's columns, in order. */
public record Columns(List<Column> all) {

    public Columns {
        all = List.copyOf(all);
    }

    public static Columns of(List<Column> all) {
        return new Columns(all);
    }

    public int size() {
        return all.size();
    }

    public Column get(int i) {
        return all.get(i);
    }

    /** The place of column {@code name}, or -1. */
    public int indexOf(String name) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The columns' names. */
    public List<String> names() {
        return all.stream().map(Column::name).toList();
    }

    /** By name. */
    public Map<String, ColumnType> types() {
        Map<String, ColumnType> out = new HashMap<>();
        all.forEach(c -> out.put(c.name(), c.type()));
        return out;
    }

    /** The columns every store's layout table has that are not a document's paths. */
    public static final Set<String> LAYOUT = Set.of("doc", "business_date", "_load_id", "_source_file");

    /**
     * The promoted paths, in column order: each column that is not a vector, a {@link #LAYOUT} column or {@code idColumn}, by its
     * document path ({@code a__b} is {@code a.b}) to its column name.
     */
    public Map<String, String> promoted(String idColumn) {
        Map<String, String> p = new LinkedHashMap<>();
        for (Column c : all) {
            if (!c.type().vector() && !LAYOUT.contains(c.name()) && !c.name().equals(idColumn)) {
                p.put(c.name().replace("__", "."), c.name());
            }
        }
        return Collections.unmodifiableMap(p);
    }

    /** The vector columns' names, in column order. */
    public Set<String> vectors() {
        Set<String> v = new LinkedHashSet<>();
        for (Column c : all) {
            if (c.type().vector()) {
                v.add(c.name());
            }
        }
        return Collections.unmodifiableSet(v);
    }
}
