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

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a scan reads: the business dates (empty: every date), the columns in order (empty: every column of the table) and a filter.
 */
public record ScanRequest(Set<LocalDate> dates, List<String> columns, Expr where) {

    public ScanRequest {
        dates = Set.copyOf(dates);
        columns = List.copyOf(columns);
        Objects.requireNonNull(where, "where");
    }

    /** Every date, every column, every row. */
    public static ScanRequest all() {
        return new ScanRequest(Set.of(), List.of(), Expr.TRUE);
    }

    public ScanRequest dates(LocalDate... d) {
        return new ScanRequest(Set.of(d), columns, where);
    }

    public ScanRequest columns(String... c) {
        return new ScanRequest(dates, List.of(c), where);
    }

    public ScanRequest where(Expr e) {
        return new ScanRequest(dates, columns, e);
    }

    /** Whether a row of {@code date} is wanted by the dates. */
    public boolean wants(LocalDate date) {
        return dates.isEmpty() || (date != null && dates.contains(date));
    }
}
