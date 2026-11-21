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

import bench.ReadBench;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The bench's Iceberg side over the table layer's own classes, called as Drishti's Iceberg adapter calls them (same package). bench.py
 * maps the packages to the host's for the reference run.
 */
public final class IcebergReads implements ReadBench.Reads {
    static final com.ash.lake.LakeRuntime RT = com.ash.lake.LakeRuntime.fromProcess();   // lake-only

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private LakeTables lake;
    private KindTable table;
    private KindTable.Layout layout;
    private KindTable.Day day;
    private KindTable.IdMap ids;

    @Override
    public void open(String root, String domain, String kind, LocalDate date) {
        lake = LakeTables.open(RT, Map.of("root", root, "domain", domain));
        table = lake.table(kind, pool);
        layout = table.layout(table.current().orElseThrow());
        day = layout.days().get(date);
    }

    @Override
    public int ids() {
        ids = table.ids(layout, day);
        return ids.size();
    }

    @Override
    public String[] idList() {
        return ids.ids().toArray(new String[0]);
    }

    @Override
    public int fileOf(String id) {
        return ids.file(id);
    }

    @Override
    public void vectors(String column, List<String> texts, int decoders, ReadBench.Sink sink) {
        com.ash.lake.rows.VectorSink to = sink::row;
        IcebergVectors.vectors(table, layout, day, IcebergLayout.column(column), texts.stream().map(IcebergLayout::column).toList(),
                com.ash.lake.rows.VectorReads.parallel(to, new com.ash.lake.rows.VectorReads.Options(decoders, 64L << 20)));
    }

    @Override
    public int columns(Map<String, Boolean> paths) {
        Map<String, String> cols = new LinkedHashMap<>();
        paths.keySet().forEach(p -> cols.put(p, IcebergLayout.column(p)));
        return table.columns(layout, day, cols).ids().length;
    }

    @Override
    public String document(int file, String id) {
        return table.doc(layout, day, file, id).orElse(null);
    }

    @Override
    public int vectorsOf(int file, Set<String> ids, List<String> columns) {
        int found = 0;
        List<String> physical = columns.stream().map(IcebergLayout::column).toList();
        for (String id : ids) {                      // today's Iceberg: one read per id (Delta reads the page in one)
            found += IcebergVectors.vectorsOf(table, layout, day, file, id, physical).isEmpty() ? 0 : 1;
        }
        return found;
    }

    @Override
    public void close() throws Exception {
        lake.close();
        pool.close();
    }
}
