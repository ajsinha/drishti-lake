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

import com.ash.lake.spi.Expr;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableStore;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The loader's drop guard ({@code guard.max-drop-share}, {@code --max-drop-share}, {@code --force-drop}; LOADER.md "Guards"): before a
 * replace writes anything, each date it replaces is compared with what the table holds for it now (this load's slice of a shared table):
 * a date that would lose more than the share of its rows refuses the whole load, nothing written, every such date named, unless
 * {@code force-drop}. An append never drops a row; a date the table does not hold yet drops none; off when no share is set. The same on
 * every store: the rows are counted through the store's scan ({@link TableStore#scan}).
 */
final class DropGuard {

    private DropGuard() {}

    static void check(LoadConfig cfg, SortedMap<LocalDate, Long> loading) throws IOException {
        LoadConfig.Guard g = cfg.guard();
        if (g.maxDropShare() == null || g.forceDrop() || cfg.target().appends() || loading.isEmpty()) {
            return;
        }
        String[] dt = cfg.domainAndTable();
        TableRef ref = new TableRef(cfg.lakeRoot(), dt[0], dt[1]);
        TableStore store = Stores.of(cfg.runtime(), cfg.target().format());
        if (!store.exists(cfg.runtime(), ref)) {
            return;
        }
        SortedMap<LocalDate, Long> held = held(cfg, store, ref, loading.keySet());
        List<String> refused = new ArrayList<>();
        loading.forEach((date, rows) -> {
            long before = held.getOrDefault(date, 0L);
            long dropping = Math.max(0, before - rows);
            if (before > 0 && dropping > g.maxDropShare() * before) {
                refused.add(String.format(Locale.ROOT, "%s holds %,d rows, the load brings %,d (%.0f%% fewer)", date, before, rows,
                        dropping * 100.0 / before));
            }
        });
        if (!refused.isEmpty()) {
            throw new Loader.LoadException(dt[0] + "/" + dt[1] + ": " + String.join("; ", refused) + ": more than guard.max-drop-share "
                    + g.maxDropShare() + " of a date's rows would go; nothing was written. Check the input, or pass --force-drop");
        }
    }

    /** The rows the table holds now on {@code dates} (this load's slice of them). */
    private static SortedMap<LocalDate, Long> held(LoadConfig cfg, TableStore store, TableRef ref, java.util.Set<LocalDate> dates) throws IOException {
        Map<String, String> slice = cfg.source().slice();
        Expr where = Expr.TRUE;
        if (!slice.isEmpty()) {
            List<Expr> each = new ArrayList<>();
            slice.forEach((c, v) -> each.add(Expr.eq(c, v)));
            where = Expr.and(each.toArray(new Expr[0]));
        }
        SortedMap<LocalDate, Long> out = new TreeMap<>();
        try (var rows = store.scan(cfg.runtime(), ref, ScanRequest.all().dates(dates.toArray(new LocalDate[0])).columns("business_date").where(where))) {
            rows.forEachRemaining(r -> {
                Object d = r.get(0);
                LocalDate day = d instanceof LocalDate l ? l : d instanceof Number n ? LocalDate.ofEpochDay(n.longValue()) : LocalDate.parse(String.valueOf(d));
                out.merge(day, 1L, Long::sum);
            });
        }
        return out;
    }
}
