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
package com.ash.lake.load;

import com.ash.lake.LakeRuntime;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.DateChange;
import com.ash.lake.spi.Row;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** What the built-in stores share: a scan's projection and filter, and their date operations through the lake's delete and undo. */
final class StoreScans {

    private final Columns table;
    private final ScanRequest request;
    private final Columns out;
    private final int[] picks;
    private final int date;
    private final List<Row> rows = new ArrayList<>();

    StoreScans(Columns table, ScanRequest request) {
        this.table = table;
        this.request = request;
        if (request.columns().isEmpty()) {
            out = table;
        } else {
            List<Column> cols = new ArrayList<>();
            for (String c : request.columns()) {
                int i = table.indexOf(c);
                if (i < 0) {
                    throw new IllegalArgumentException("no column " + c + " in the table (it has " + table.names() + ")");
                }
                cols.add(table.get(i));
            }
            out = Columns.of(cols);
        }
        picks = new int[out.size()];
        for (int i = 0; i < picks.length; i++) {
            picks[i] = table.indexOf(out.get(i).name());
        }
        date = table.indexOf("business_date");
    }

    /** Whether a file of {@code d} can hold wanted rows (a file's date known before reading it). */
    boolean wants(LocalDate d) {
        return request.wants(d);
    }

    /** One row of the table's columns: kept when its date and the filter want it. */
    void add(Object[] full) {
        if (date >= 0 && !request.dates().isEmpty() && !request.wants((LocalDate) full[date])) {
            return;
        }
        Row whole = new Row(table, full);
        if (!request.where().test(whole)) {
            return;
        }
        if (out == table) {
            rows.add(whole);
            return;
        }
        Object[] v = new Object[picks.length];
        for (int i = 0; i < picks.length; i++) {
            v[i] = full[picks[i]];
        }
        rows.add(new Row(out, v));
    }

    Columns columns() {
        return out;
    }

    List<Row> rows() {
        return rows;
    }

    /** The table's dates and their rows, through the lake's delete on a dry run (which reads them as it would remove them). */
    static SortedMap<LocalDate, Long> dates(LakeRuntime runtime, String format, TableRef t) throws IOException {
        SortedMap<LocalDate, Long> out = new TreeMap<>();
        for (Map<String, Object> line : lines(drop(runtime, format, t, d -> true, true))) {
            out.put(LocalDate.parse((String) line.get("date")), ((Number) line.get("rows")).longValue());
        }
        return out;
    }

    /** Whole dates removed through the lake's delete. */
    static List<DateChange> delete(LakeRuntime runtime, String format, TableRef t, Set<LocalDate> dates, boolean dryRun) throws IOException {
        Set<String> wanted = new java.util.HashSet<>();
        dates.forEach(d -> wanted.add(d.toString()));
        Map<String, Object> got = drop(runtime, format, t, wanted::contains, dryRun);
        long version = !dryRun && got.get("version") instanceof Number n ? n.longValue() : -1;
        List<DateChange> out = new ArrayList<>();
        for (Map<String, Object> line : lines(got)) {
            out.add(new DateChange(LocalDate.parse((String) line.get("date")), Math.max(0, ((Number) line.get("dropped")).longValue()), 0, version));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> got) {
        return (List<Map<String, Object>>) got.get("dates");
    }

    /** The lake's delete, its checked exceptions as an {@link IOException}. */
    private static Map<String, Object> drop(LakeRuntime runtime, String format, TableRef t, java.util.function.Predicate<String> wanted, boolean dryRun)
            throws IOException {
        try {
            return LakeRetention.dropDates(runtime, t.root(), format, t.name(), wanted, dryRun);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(t.location() + ": " + e.getMessage(), e);
        }
    }
}
