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
package com.ash.lake.delta.table;

import bench.ReadBench;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bench's Delta side over the table layer's own classes, called as Drishti's Delta adapter calls them (same package). bench.py maps
 * the packages to the host's for the reference run (Drishti's jars at the freeze); over this build it is the "before" of the read API.
 */
public final class DeltaReads implements ReadBench.Reads {
    static final com.ash.lake.LakeRuntime RT = com.ash.lake.LakeRuntime.fromProcess();   // lake-only

    private LakeStore lake;
    private DeltaTable table;
    private DeltaTable.Layout layout;
    private DeltaTable.IdMap ids;
    private LocalDate date;

    @Override
    public void open(String root, String domain, String kind, LocalDate date) {
        lake = LakeStore.of(RT, root, domain, Map.of());
        table = new DeltaTable(lake.engine(), lake.table(kind), "id", "doc", "business_date");
        layout = table.layout((java.time.Instant) null).orElseThrow();
        this.date = date;
    }

    @Override
    public int ids() {
        ids = table.ids(layout, date);
        return ids.size();
    }

    @Override
    public String[] idList() {
        return ids.ids();
    }

    @Override
    public int fileOf(String id) {
        return ids.file(id);
    }

    @Override
    public void vectors(String column, List<String> texts, int decoders, ReadBench.Sink sink) {
        com.ash.lake.rows.VectorSink to = sink::row;
        table.vectors(layout, date, column, texts,
                com.ash.lake.rows.VectorReads.parallel(to, new com.ash.lake.rows.VectorReads.Options(decoders, 64L << 20)));
    }

    @Override
    public int columns(Map<String, Boolean> paths) {
        Map<String, String> pathToColumn = new LinkedHashMap<>();
        paths.keySet().forEach(p -> pathToColumn.put(p, p));
        return table.columns(layout, date, pathToColumn, paths).ids().length;
    }

    @Override
    public String document(int file, String id) {
        return table.doc(layout, date, file, id).orElse(null);
    }

    @Override
    public int vectorsOf(int file, Set<String> ids, List<String> columns) {
        return table.vectorsOf(layout, date, file, ids, columns).size();
    }

    @Override
    public void close() {
        lake.close();
    }
}
