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

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A row filter in the product's own terms: equality, membership, a closed range, null tests and their combinations. A store may use it
 * to skip files; the rows it returns always satisfy {@link #test}. Values compare as numbers when both are numbers, else as text (a
 * {@link java.time.LocalDate} as its ISO text).
 */
public sealed interface Expr {

    /** Whether {@code row} satisfies the filter. */
    boolean test(Row row);

    /** Every row. */
    Expr TRUE = new All();

    static Expr eq(String column, Object value) {
        return new Eq(column, value);
    }

    static Expr in(String column, Set<?> values) {
        return new In(column, Set.copyOf(values));
    }

    /** {@code low <= column <= high}; a null bound is open. */
    static Expr between(String column, Object low, Object high) {
        return new Between(column, low, high);
    }

    static Expr isNull(String column) {
        return new IsNull(column);
    }

    static Expr and(Expr... all) {
        return new And(List.of(all));
    }

    static Expr or(Expr... any) {
        return new Or(List.of(any));
    }

    static Expr not(Expr e) {
        return new Not(e);
    }

    /** Compares two non-null values: as numbers when both are numbers, else as their text. */
    static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return a.toString().compareTo(b.toString());
    }

    record All() implements Expr {
        @Override
        public boolean test(Row row) {
            return true;
        }
    }

    record Eq(String column, Object value) implements Expr {
        public Eq {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(value, "value (Expr.isNull tests for null)");
        }

        @Override
        public boolean test(Row row) {
            Object v = row.get(column);
            return v != null && compare(v, value) == 0;
        }
    }

    record In(String column, Set<?> values) implements Expr {
        @Override
        public boolean test(Row row) {
            Object v = row.get(column);
            if (v == null) {
                return false;
            }
            for (Object w : values) {
                if (compare(v, w) == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    record Between(String column, Object low, Object high) implements Expr {
        @Override
        public boolean test(Row row) {
            Object v = row.get(column);
            return v != null && (low == null || compare(v, low) >= 0) && (high == null || compare(v, high) <= 0);
        }
    }

    record IsNull(String column) implements Expr {
        @Override
        public boolean test(Row row) {
            return row.get(column) == null;
        }
    }

    record And(List<Expr> all) implements Expr {
        @Override
        public boolean test(Row row) {
            for (Expr e : all) {
                if (!e.test(row)) {
                    return false;
                }
            }
            return true;
        }
    }

    record Or(List<Expr> any) implements Expr {
        @Override
        public boolean test(Row row) {
            for (Expr e : any) {
                if (e.test(row)) {
                    return true;
                }
            }
            return false;
        }
    }

    record Not(Expr e) implements Expr {
        @Override
        public boolean test(Row row) {
            return !e.test(row);
        }
    }
}
