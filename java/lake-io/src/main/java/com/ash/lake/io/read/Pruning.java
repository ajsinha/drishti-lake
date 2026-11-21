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
package com.ash.lake.io.read;

import com.ash.lake.read.Bounds;
import com.ash.lake.spi.Expr;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;

/**
 * The one pushdown rule of the stores' scans (READ_API.md 2.7): whether a date, or a file of a date, may hold a row a scan's
 * {@code where} accepts, from what is known before it is read: the business date, the file's partition values and its text columns'
 * statistics. False only when no row can match; anything not understood may match (the per-row test stays the last word, so a store
 * that prunes nothing is still right).
 */
public final class Pruning {

    /** The business date column, as every store's rows name it. */
    public static final String DATE = "business_date";

    private Pruning() {
    }

    /** Whether rows of {@code date} may match {@code where} (only the date is known). */
    public static boolean mayHold(Expr where, LocalDate date) {
        return mayHold(where, date, Map.of(), c -> null);
    }

    /**
     * Whether a file of {@code date} with constant {@code partition} values (by column) and text {@code bounds} (by column; null: not
     * stated) may hold a row {@code where} accepts.
     */
    public static boolean mayHold(Expr where, LocalDate date, Map<String, String> partition, Function<String, Bounds> bounds) {
        return switch (where) {
            case Expr.All a -> true;
            case Expr.And a -> a.all().stream().allMatch(e -> mayHold(e, date, partition, bounds));
            case Expr.Or o -> o.any().stream().anyMatch(e -> mayHold(e, date, partition, bounds));
            case Expr.Not n -> true;                              // a negation of a range is not a range: not pruned
            case Expr.Eq e -> range(e.column(), e.value(), e.value(), date, partition, bounds);
            case Expr.In in -> in.values().stream().anyMatch(v -> v == null || range(in.column(), v, v, date, partition, bounds));
            case Expr.Between b -> range(b.column(), b.low(), b.high(), date, partition, bounds);
            case Expr.IsNull n -> isNull(n.column(), date, partition, bounds);
        };
    }

    /** Whether some value of {@code column} may lie in {@code [low, high]} (a null end is open). */
    private static boolean range(String column, Object low, Object high, LocalDate date, Map<String, String> partition,
            Function<String, Bounds> bounds) {
        if (column.equals(DATE)) {
            if (date == null || !dateLike(low) || !dateLike(high)) {
                return true;
            }
            String d = date.toString();
            return (low == null || d.compareTo(low.toString()) >= 0) && (high == null || d.compareTo(high.toString()) <= 0);
        }
        if (!text(low) || !text(high)) {
            return true;                                          // a number against a text column compares as Expr does: not pruned
        }
        if (partition.containsKey(column)) {
            String v = partition.get(column);
            if (v == null) {
                return false;                                     // the file's rows are all null there: no comparison holds
            }
            return plain(v) ? (low == null || v.compareTo((String) low) >= 0) && (high == null || v.compareTo((String) high) <= 0) : true;
        }
        Bounds b = bounds.apply(column);
        if (b == null) {
            return true;
        }
        if (b.min() == null && b.max() == null && b.nulls() != null && b.nulls() > 0) {
            return true;                                          // only a null count: the values are not stated
        }
        if (b.min() == null || b.max() == null || !plain(b.min()) || !plain(b.max())) {
            return true;
        }
        return (high == null || b.min().compareTo((String) high) <= 0) && (low == null || b.max().compareTo((String) low) >= 0);
    }

    private static boolean isNull(String column, LocalDate date, Map<String, String> partition, Function<String, Bounds> bounds) {
        if (column.equals(DATE)) {
            return date == null;
        }
        if (partition.containsKey(column)) {
            return partition.get(column) == null;
        }
        Bounds b = bounds.apply(column);
        return b == null || b.nulls() == null || b.nulls() > 0;
    }

    private static boolean dateLike(Object v) {
        return v == null || v instanceof LocalDate || (v instanceof String s && s.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    private static boolean text(Object v) {
        return v == null || (v instanceof String s && plain(s));
    }

    /**
     * Whether Java's order of {@code s} is the statistics' order: below U+D800 the UTF-16 order of {@link String#compareTo} is the code
     * point order a Parquet or Delta writer uses; beyond it (surrogates, U+E000 up) the two may differ, so such a value is not pruned on.
     */
    private static boolean plain(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= '\uD800') {
                return false;
            }
        }
        return true;
    }
}
