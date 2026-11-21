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
package com.ash.lake.memory;

import com.ash.lake.spi.FileEntry;
import com.ash.lake.memory.store.MemoryStorage;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.utils.CloseableIterator;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.util.FileNames;

/**
 * Old versions of a Delta table in a memory store expired the moment they are past {@code keep-versions}: a version still readable holds its files; one past it costs memory for nothing. The caller holds the table's write
 * lock: a file written but not committed yet would otherwise look unreferenced.
 */
final class MemoryUpkeep {


    private MemoryUpkeep() {}

    /** The data files of a version, as qualified paths of {@code store}. */
    static Set<String> filesOf(Engine engine, MemoryStorage store, Snapshot s) throws IOException {
        Set<String> out = new HashSet<>();
        try (CloseableIterator<FilteredColumnarBatch> files = s.getScanBuilder().build().getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> rows = files.next().getRows()) {
                    while (rows.hasNext()) {
                        out.add(store.qualify(InternalScanFileUtils.getAddFileStatus(rows.next()).getPath()));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Frees what versions past {@code keepVersions} held: data files no kept version references (and files of writes that never
     * committed), and the log before the newest checkpoint at or below the oldest kept version. Returns the bytes freed (chunks).
     */
    public static long expire(Engine engine, MemoryStorage store, String table, int keepVersions) throws IOException {
        Table t = Table.forPath(engine, table);
        Snapshot latest = t.getLatestSnapshot(engine);
        long v = latest.getVersion();
        long oldest = Math.max(0, v - Math.max(1, keepVersions) + 1);
        Set<String> live = new HashSet<>(filesOf(engine, store, latest));
        for (long x = oldest; x < v; x++) {
            live.addAll(filesOf(engine, store, t.getSnapshotAsOfVersion(engine, x)));
        }
        String root = store.qualify(table);
        String log = root + "/_delta_log/";
        long freed = 0;
        for (FileEntry f : store.filesIn(table)) {
            if (f.path().startsWith(log) || live.contains(f.path())) {
                continue;
            }
            freed += chunked(store, f.size());
            store.delete(f.path());
        }
        // the log before the checkpoint the oldest kept version reads from (the Kernel's own log segment and file names)
        long checkpoint = ((SnapshotImpl) t.getSnapshotAsOfVersion(engine, oldest)).getLogSegment().getCheckpointVersionOpt().orElse(-1L);
        if (checkpoint > 0) {
            for (FileEntry f : store.filesIn(root + "/_delta_log")) {
                String name = f.path().substring(log.length());
                if ((FileNames.isCommitFile(name) || FileNames.isCheckpointFile(name)) && FileNames.getFileVersion(new io.delta.kernel.internal.fs.Path(f.path())) < checkpoint) {
                    freed += chunked(store, f.size());
                    store.delete(f.path());
                }
            }
        }
        return freed;
    }

    private static long chunked(MemoryStorage store, long length) {
        int c = store.pool().chunkBytes();
        return (length + c - 1) / c * c;
    }
}
