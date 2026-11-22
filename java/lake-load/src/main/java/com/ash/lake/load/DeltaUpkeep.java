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

import com.ash.lake.delta.write.LakeWriter;
import com.ash.lake.delta.write.TableUpkeep;
import com.ash.lake.spi.FileEntry;
import com.ash.lake.spi.Storage;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.CheckpointAlreadyExistsException;
import io.delta.kernel.internal.DeltaLogActionUtils.DeltaAction;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.TableImpl;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@code lake maintain}'s steps on a Delta table ({@link LakeMaintain}), through Delta Kernel alone (no delta-rs, no Spark):
 *
 * <ul>
 *   <li><b>compact</b>: each business date held in two or more files under the target size is rewritten into the writer's files, one
 *       commit a date (the date's files removed, the same rows added: what {@code OPTIMIZE} does). A date another writer changed while it
 *       was read is read again; a lost race fails as any concurrent replace does.</li>
 *   <li><b>checkpoint</b>: a checkpoint of the latest version ({@code Table.checkpoint}), so a reader replays a short log.</li>
 *   <li><b>vacuum</b>: the data files no version of the last {@code hours} references are deleted ({@code VACUUM}): the versions kept are
 *       every commit since the cut and the version current at it, so time travel reaches back {@code hours}. A file never committed (a load
 *       still running, or one that died) goes only when older than {@link #UNCOMMITTED_MIN_AGE_HOURS} too; a file a commit of that window
 *       removed is known by its log entry. Only data files ({@code *.parquet}, not under a {@code _} or {@code .} folder) are candidates,
 *       and every file of the latest version must be found in the listing first, else nothing is deleted.</li>
 * </ul>
 */
final class DeltaUpkeep {

    /** As the files store's and Iceberg's orphans: longer than the longest load. */
    static final long UNCOMMITTED_MIN_AGE_HOURS = 72;

    private DeltaUpkeep() {}

    /** The data files of each business date of snapshot {@code s}: their sizes. */
    static SortedMap<String, List<Long>> filesPerDate(Engine engine, Snapshot s) throws IOException {
        SortedMap<String, List<Long>> out = new TreeMap<>();
        try (CloseableIterator<FilteredColumnarBatch> it = s.getScanBuilder().build().getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        String d = InternalScanFileUtils.getPartitionValues(r).get("business_date");
                        out.computeIfAbsent(String.valueOf(d), k -> new ArrayList<>()).add(InternalScanFileUtils.getAddFileStatus(r).getSize());
                    }
                }
            }
        }
        return out;
    }

    /** The dates compaction rewrites: two or more files, two or more of them under {@code targetBytes}. */
    static List<String> toCompact(SortedMap<String, List<Long>> files, long targetBytes) {
        List<String> out = new ArrayList<>();
        files.forEach((d, sizes) -> {
            if (sizes.size() > 1 && sizes.stream().filter(b -> b < targetBytes).count() > 1) {
                out.add(d);
            }
        });
        return out;
    }

    /** Compacts the table at {@code path}: the dates rewritten (on a dry run, the dates that would be). */
    static List<String> compact(com.ash.lake.LakeRuntime runtime, String path, long targetBytes, boolean dryRun) throws IOException {
        Engine engine = LakeWriter.engineFor(runtime, path, Map.of());
        Snapshot s = Table.forPath(engine, path).getLatestSnapshot(engine);
        List<String> dates = toCompact(filesPerDate(engine, s), targetBytes);
        if (dryRun) {
            return dates;
        }
        List<String> done = new ArrayList<>();
        for (String date : dates) {
            for (int attempt = 0; ; attempt++) {
                s = Table.forPath(engine, path).getLatestSnapshot(engine);
                List<Long> files = filesPerDate(engine, s).get(date);
                if (files == null || toCompact(new TreeMap<>(Map.of(date, files)), targetBytes).isEmpty()) {
                    break;                                       // another writer replaced or removed the date meanwhile: nothing to do
                }
                List<TableUpkeep.Staged> rows = TableUpkeep.rows(engine, s, pv -> date.equals(pv.get("business_date")), true);
                if (rows == null) {
                    throw new IOException(path + ": a column of a type the lake's writer does not write (a table written by another "
                            + "engine?): compaction leaves its files as they are");
                }
                long read = s.getVersion();
                if (Table.forPath(engine, path).getLatestSnapshot(engine).getVersion() != read) {
                    if (attempt < 3) {
                        continue;                                // a commit landed while the date was read: read it again
                    }
                    throw new IOException(path + " " + date + ": other writers committed while compaction read the date, 3 times; run it again");
                }
                LakeUndo.rewrite(runtime, engine, path, s, date, rows);
                done.add(date);
                break;
            }
        }
        return done;
    }

    /** Writes a checkpoint of the latest version: the version (one that has one already is left as it is). */
    static long checkpoint(com.ash.lake.LakeRuntime runtime, String path) throws IOException {
        Engine engine = LakeWriter.engineFor(runtime, path, Map.of());
        Table t = Table.forPath(engine, path);
        long v = t.getLatestSnapshot(engine).getVersion();
        try {
            t.checkpoint(engine, v);
        } catch (CheckpointAlreadyExistsException already) {
            // the writer's own hook made it
        }
        return v;
    }

    /** Vacuum (class comment): {@code hours}, {@code files}, {@code bytes}, {@code first}; nothing deleted on a dry run. */
    static Map<String, Object> vacuum(com.ash.lake.LakeRuntime runtime, String path, long hours, long nowMs, boolean dryRun) throws IOException {
        if (hours < 0) {
            throw new IllegalArgumentException("--vacuum-hours must be 0 or more: " + hours);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", hours);
        Engine engine = LakeWriter.engineFor(runtime, path, Map.of());
        TableImpl table = (TableImpl) Table.forPath(engine, path);
        try (com.ash.lake.io.StorageRoutes routes = new com.ash.lake.io.StorageRoutes(runtime, new com.ash.lake.io.LocalStorage(),
                com.ash.lake.io.S3Settings.from(runtime, Map.of()), "lake maintain")) {
            return vacuum(engine, table, routes.storage(path), path, hours, nowMs, dryRun, out);
        }
    }

    private static Map<String, Object> vacuum(Engine engine, TableImpl table, Storage storage, String path, long hours, long nowMs, boolean dryRun,
            Map<String, Object> out) throws IOException {
        String root = storage.qualify(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        List<FileEntry> listed = new ArrayList<>();
        walk(storage, root, root, listed);                                    // listed before the log is read: a later file is younger
        Snapshot latest = table.getLatestSnapshot(engine);
        long cut = nowMs - hours * 3_600_000L;
        long young = nowMs - UNCOMMITTED_MIN_AGE_HOURS * 3_600_000L;
        Long from = versionAt(table, engine, cut);
        List<String> gone = new ArrayList<>();
        long bytes = 0;
        if (from != null) {
            Set<String> referenced = active(engine, table.getSnapshotAsOfVersion(engine, from), table.getPath(engine));
            Set<String> removedLately = new HashSet<>();
            Long since = versionAt(table, engine, Math.min(cut, young));
            long first = since == null ? earliest(table, engine) : since;
            if (first < latest.getVersion()) {
                changes(table, engine, first + 1, latest.getVersion(), from, referenced, removedLately);
            }
            Set<String> current = active(engine, latest, table.getPath(engine));
            Set<String> seen = new HashSet<>();
            List<FileEntry> drop = new ArrayList<>();
            for (FileEntry f : listed) {
                String rel = f.path().substring(root.length() + 1);
                seen.add(rel);
                if (referenced.contains(rel) || current.contains(rel) || f.modified() >= cut) {
                    continue;
                }
                if (f.modified() < young || removedLately.contains(rel)) {
                    drop.add(f);
                }
            }
            if (!seen.containsAll(current)) {
                Set<String> missing = new HashSet<>(current);
                missing.removeAll(seen);
                throw new IOException(path + ": vacuum could not match " + missing.size() + " file(s) of the latest version to the table's "
                        + "folder (first " + missing.iterator().next() + "); nothing was deleted");
            }
            for (FileEntry f : drop) {
                gone.add(f.path());
                bytes += f.size();
                if (!dryRun) {
                    storage.delete(f.path());
                    String crc = f.path().substring(0, f.path().lastIndexOf('/') + 1) + "." + f.path().substring(f.path().lastIndexOf('/') + 1) + ".crc";
                    try {
                        storage.delete(crc);                        // a local file system's checksum beside it, if any
                    } catch (IOException none) {
                        // none there
                    }
                }
            }
        }
        out.put("files", gone.size());
        out.put("bytes", bytes);
        out.put("first", gone.stream().limit(5).toList());
        return out;
    }

    /** The version current at {@code ms}; null when the table's first commit came after it. */
    private static Long versionAt(TableImpl table, Engine engine, long ms) {
        try {
            return table.getVersionBeforeOrAtTimestamp(engine, ms);
        } catch (RuntimeException before) {
            return null;
        }
    }

    private static long earliest(TableImpl table, Engine engine) {
        try {
            return table.getVersionAtOrAfterTimestamp(engine, 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The data files of a snapshot, relative to the table's folder and decoded (as they are named on the storage). */
    private static Set<String> active(Engine engine, Snapshot s, String tableRoot) throws IOException {
        Set<String> out = new HashSet<>();
        try (CloseableIterator<FilteredColumnarBatch> it = s.getScanBuilder().build().getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(rows.next());
                        out.add(relative(fs.getPath(), tableRoot));
                    }
                }
            }
        }
        return out;
    }

    /** {@code p} relative to the table's folder: a log's relative path decoded, an absolute one with the folder taken off. */
    static String relative(String p, String tableRoot) {
        String path = p;
        if (!path.contains(":/") && !path.startsWith("/")) {
            try {
                return new URI(path).getPath();                          // a log entry: relative and URI-encoded
            } catch (URISyntaxException e) {
                return path;
            }
        }
        String r = tableRoot.endsWith("/") ? tableRoot.substring(0, tableRoot.length() - 1) : tableRoot;
        for (String prefix : List.of(r, r.replaceFirst("^file:///", "file:/"), r.replaceFirst("^file:/+", "/"))) {
            if (path.startsWith(prefix + "/")) {
                return path.substring(prefix.length() + 1);
            }
        }
        String plain = path.replaceFirst("^file:/+", "/");
        String rp = r.replaceFirst("^file:/+", "/");
        return plain.startsWith(rp + "/") ? plain.substring(rp.length() + 1) : path;
    }

    /** The adds of commits {@code from+1..to} after {@code keepFrom} into {@code referenced}; every remove of them into {@code removed}. */
    private static void changes(TableImpl table, Engine engine, long from, long to, long keepFrom, Set<String> referenced, Set<String> removed)
            throws IOException {
        try (CloseableIterator<ColumnarBatch> it = table.getChanges(engine, from, to, EnumSet.of(DeltaAction.ADD, DeltaAction.REMOVE))) {
            while (it.hasNext()) {
                ColumnarBatch b = it.next();
                int version = b.getSchema().indexOf("version");
                int add = b.getSchema().indexOf("add");
                int remove = b.getSchema().indexOf("remove");
                try (CloseableIterator<Row> rows = b.getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        long v = r.getLong(version);
                        if (!r.isNullAt(add) && v > keepFrom) {
                            referenced.add(relative(pathOf(r.getStruct(add)), ""));
                        }
                        if (!r.isNullAt(remove)) {
                            removed.add(relative(pathOf(r.getStruct(remove)), ""));
                        }
                    }
                }
            }
        }
    }

    private static String pathOf(Row action) {
        return action.getString(action.getSchema().indexOf("path"));
    }

    /** The data files under {@code dir}: {@code *.parquet}, no folder or file whose name starts with {@code _} or {@code .}. */
    private static void walk(Storage storage, String root, String dir, List<FileEntry> out) throws IOException {
        for (FileEntry f : storage.files(dir)) {
            String name = f.path().substring(f.path().lastIndexOf('/') + 1);
            if (name.endsWith(".parquet") && !name.startsWith("_") && !name.startsWith(".")) {
                out.add(f);
            }
        }
        for (String d : storage.directories(dir)) {
            String name = d.replaceAll("/+$", "");
            name = name.substring(name.lastIndexOf('/') + 1);
            if (name.startsWith("_") || name.startsWith(".")) {
                continue;                                                    // _delta_log, hidden folders
            }
            walk(storage, root, (dir.endsWith("/") ? dir : dir + "/") + name, out);
        }
    }
}
