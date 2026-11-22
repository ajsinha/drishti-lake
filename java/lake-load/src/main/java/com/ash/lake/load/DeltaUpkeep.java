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
 *       commit a date (the date's files removed, the same rows added: what {@code OPTIMIZE} does). The rows readers see (each id's newest
 *       load's copy) are written in the table's order (by id, or by its {@code lake.layout.sort-by} fields then the id) into one file, and
 *       every older copy an append left into one more (by id, the oldest load first; its newest load is older than the first file's), so
 *       readers still read the newest copy and an undo of the newer load still finds the older one. A date already in as few files is
 *       left (a second run has nothing to do). A date another writer changed while it was read is read again; a lost race fails as any concurrent replace
 *       does.</li>
 *   <li><b>relayout</b>: a table laid out by id (the loader's sort, {@code lake.layout.sort-by} absent or {@code id}) keeps that layout:
 *       each business date whose files drifted from it (two files of one partition sharing an id range, as an append makes; a file over
 *       the table's {@code lake.layout.file-rows}, 250,000; more files than its rows need; a file without id statistics) is rewritten as
 *       readers see it (the newest load's copy of each id), sorted by id and cut into files of {@code file-rows} rows, each file its own id
 *       range, one commit a date ({@link #relayout}). A table laid out by more than the id is left in its order.</li>
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

    /**
     * Whether the date's files were all added by one commit (one {@code lake.commit} version on every file): what compaction leaves. Files
     * of two commits (two loads, even of one run id or in as few files as compaction would make) are rewritten into one commit, so their
     * order is held by the files themselves.
     */
    static boolean oneCommit(Engine engine, Snapshot s, String date) throws IOException {
        Set<Long> versions = new HashSet<>();
        try (CloseableIterator<FilteredColumnarBatch> it = s.getScanBuilder().build().getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        if (date.equals(String.valueOf(InternalScanFileUtils.getPartitionValues(r).get("business_date")))) {
                            long v = com.ash.lake.delta.write.CommitOrder.tagged(r.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL));
                            if (v < 0) {
                                return false;
                            }
                            versions.add(v);
                        }
                    }
                }
            }
        }
        return versions.size() <= 1;
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
    static List<String> compact(com.ash.lake.LakeRuntime runtime, com.ash.lake.spi.TableStore store, String root, String domain, String kind,
            String path, long targetBytes, boolean dryRun) throws IOException {
        Engine engine = LakeWriter.engineFor(runtime, path, Map.of());
        Snapshot s = Table.forPath(engine, path).getLatestSnapshot(engine);
        List<String> dates = toCompact(filesPerDate(engine, s), targetBytes);
        List<String> done = new ArrayList<>();
        for (String date : dates) {
            for (int attempt = 0; ; attempt++) {
                s = Table.forPath(engine, path).getLatestSnapshot(engine);
                List<Long> files = filesPerDate(engine, s).get(date);
                if (files == null || toCompact(new TreeMap<>(Map.of(date, files)), targetBytes).isEmpty()) {
                    break;                                       // another writer replaced or removed the date meanwhile: nothing to do
                }
                List<List<TableUpkeep.Staged>> groups = compacted(engine, path, s, date);
                if (groups != null && !groups.isEmpty() && groups.size() >= files.size() && oneCommit(engine, s, date)) {
                    break;                                       // already as compaction leaves it: one commit's files (the rows readers see, the older copies)
                }
                if (dryRun && groups != null && !groups.isEmpty()) {
                    done.add(date);
                    break;
                }
                if (groups == null) {
                    throw new IOException(path + ": a column of a type the lake's writer does not write (a table written by another "
                            + "engine?): compaction leaves its files as they are");
                }
                long read = s.getVersion();
                if (groups.isEmpty() || Table.forPath(engine, path).getLatestSnapshot(engine).getVersion() != read) {
                    if (attempt < 3) {
                        continue;                                // a commit landed while the date was read: read it again
                    }
                    throw new IOException(path + " " + date + ": other writers committed while compaction read the date, 3 times; run it again");
                }
                LakeUndo.rewriteGroups(runtime, engine, path, s, date, groups, Integer.MAX_VALUE, true);
                done.add(date);
                break;
            }
        }
        return done;
    }

    /** The rows a laid-out file holds at most when the table does not say ({@code lake.layout.file-rows}): as the Iceberg store's. */
    static final int DEFAULT_FILE_ROWS = 250_000;

    /** The table's {@code lake.layout.sort-by} when it is laid out by more than the id, else null (laid out by id). */
    static String sortedByMore(Map<String, String> conf) {
        String by = com.ash.lake.PersistedNames.get(conf, "layout.sort-by", "").trim();
        return by.isEmpty() || by.equals("id") ? null : by;
    }

    /** The table's rows a file ({@code lake.layout.file-rows}), else {@link #DEFAULT_FILE_ROWS}. */
    static int fileRows(Map<String, String> conf) {
        String v = com.ash.lake.PersistedNames.get(conf, "layout.file-rows", "");
        try {
            return v.isBlank() ? DEFAULT_FILE_ROWS : Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_FILE_ROWS;
        }
    }

    /**
     * Whether a date's files drifted from the id layout (class comment): files grouped by their partition values past the date, each group
     * sorted by its smallest id must hold disjoint id ranges, no file over {@code fileRows}, no more files than {@code ceil(rows/fileRows)}.
     */
    static boolean drifted(com.ash.lake.read.DayFiles day, int fileRows) {
        Map<Map<String, String>, List<com.ash.lake.read.DataFile>> groups = new LinkedHashMap<>();
        for (com.ash.lake.read.DataFile f : day.files()) {
            Map<String, String> pv = new java.util.TreeMap<>(f.partition() == null ? Map.of() : f.partition());
            pv.remove("business_date");
            groups.computeIfAbsent(pv, k -> new ArrayList<>()).add(f);
        }
        for (List<com.ash.lake.read.DataFile> g : groups.values()) {
            long rows = 0;
            for (com.ash.lake.read.DataFile f : g) {
                if (f.rows() > fileRows || f.deletes()) {
                    return true;
                }
                rows += Math.max(0, f.rows());
            }
            if (g.size() > Math.max(1, (rows + fileRows - 1) / fileRows)) {
                return true;
            }
            if (g.size() < 2) {
                continue;
            }
            List<com.ash.lake.read.Bounds> ranges = new ArrayList<>();
            for (com.ash.lake.read.DataFile f : g) {
                com.ash.lake.read.Bounds b = f.bounds() == null ? null : f.bounds().get("id");
                if (b == null || b.min() == null || b.max() == null) {
                    return true;
                }
                ranges.add(b);
            }
            ranges.sort(java.util.Comparator.comparing(com.ash.lake.read.Bounds::min));
            for (int i = 1; i < ranges.size(); i++) {
                if (ranges.get(i).min().compareTo(ranges.get(i - 1).max()) <= 0) {
                    return true;                                 // two files share an id range: an append out of order
                }
            }
        }
        return false;
    }

    /**
     * Relayout (class comment) of the Delta table {@code domain/kind} at {@code path} in the lake {@code root}, read through the read API
     * of {@code store} (the dates' files and their bounds, each id's winning copy): the dates rewritten (on a dry run: that would be).
     */
    static List<String> relayout(com.ash.lake.LakeRuntime runtime, com.ash.lake.spi.TableStore store, String root, String domain, String kind,
            String path, Integer rowsAFile, java.util.function.Predicate<java.time.LocalDate> wanted, boolean force, boolean dryRun)
            throws IOException {
        Engine engine = LakeWriter.engineFor(runtime, path, Map.of());
        Snapshot s = Table.forPath(engine, path).getLatestSnapshot(engine);
        Map<String, String> conf = ((io.delta.kernel.internal.SnapshotImpl) s).getMetadata().getConfiguration();
        List<String> done = new ArrayList<>();
        if (sortedByMore(conf) != null) {
            return done;                                         // laid out by more than the id: left in its order
        }
        int fileRows = rowsAFile != null && rowsAFile > 0 ? rowsAFile : fileRows(conf);
        try (com.ash.lake.read.LakeReader lake = store.open(runtime, com.ash.lake.read.LakeAddress.of(root, domain))) {
            com.ash.lake.read.TableSnapshot snap = lake.table(kind).snapshot(com.ash.lake.spi.AsOf.LATEST).orElse(null);
            if (snap == null) {
                return done;
            }
            if (snap.version() != s.getVersion()) {
                throw new IOException(path + ": another writer committed while relayout read the table; run it again");
            }
            List<java.time.LocalDate> dates = new ArrayList<>();
            for (java.time.LocalDate d : snap.dates()) {
                if (!java.time.LocalDate.MIN.equals(d) && wanted.test(d) && (force || snap.day(d).map(day -> drifted(day, fileRows)).orElse(false))) {
                    dates.add(d);
                }
            }
            // each date is one commit of its own: the next date expects the version relayout itself just committed, so only another
            // writer's commit between two dates (or while a date is read) stops it
            long expected = snap.version();
            for (java.time.LocalDate d : dates) {
                if (!dryRun) {
                    expected = relayDate(runtime, engine, path, snap, expected, d, fileRows);
                }
                done.add(d.toString());
            }
        }
        return done;
    }

    /**
     * One date rewritten as readers see it: each id's winning copy (the read API's id map of {@code snap}), sorted by id, files of
     * {@code fileRows}; returns the version committed. The table must be at {@code expected}: {@code snap}'s version, or the version an
     * earlier date of the same relayout committed (that commit touched no other date, so {@code snap}'s files and ids of this date are
     * still the table's). Any other version is another writer's commit: nothing is written.
     */
    private static long relayDate(com.ash.lake.LakeRuntime runtime, Engine engine, String path, com.ash.lake.read.TableSnapshot snap,
            long expected, java.time.LocalDate date, int fileRows) throws IOException {
        Snapshot s = Table.forPath(engine, path).getLatestSnapshot(engine);
        if (s.getVersion() != expected) {
            throw new IOException(path + " " + date + ": another writer committed while relayout read the date; run it again");
        }
        List<com.ash.lake.read.DataFile> files = snap.day(date).orElseThrow().files();
        com.ash.lake.read.IdMap ids = snap.ids(date);
        Map<String, String> winner = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            int f = ids.file(ids.id(i));
            if (f >= 0 && f < files.size()) {
                winner.put(ids.id(i), files.get(f).path());
            }
        }
        String day = date.toString();
        Map<String, TableUpkeep.Staged> kept = new java.util.TreeMap<>();
        boolean simple = TableUpkeep.rows(engine, s, pv -> day.equals(pv.get("business_date")), true, (file, row) -> {
            if (row.id() != null && file.equals(winner.get(row.id()))) {
                kept.put(row.id(), row);                         // the last copy in the winning file (a file holds one, as written)
            }
        });
        if (!simple) {
            throw new IOException(path + ": a column of a type the lake's writer does not write (a table written by another engine?): "
                    + "relayout leaves its files as they are");
        }
        if (kept.size() != winner.size()) {
            throw new IOException(path + " " + day + ": " + (winner.size() - kept.size()) + " id(s) of the date were not found in their "
                    + "files; nothing was rewritten");
        }
        return LakeUndo.rewriteSorted(runtime, engine, path, s, day, new ArrayList<>(kept.values()), fileRows);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * A date's rows as compaction writes them (class comment): first the rows readers see, in the table's order, then, when an append left
     * any, every older copy in one group by id and in the readers' order (the oldest first). Null when the table has a column the lines
     * cannot carry. Which copy readers see is the readers' own order ({@link TableUpkeep#ranked}): the commit, never a value in the data.
     */
    static List<List<TableUpkeep.Staged>> compacted(Engine engine, String path, Snapshot s, String date) throws IOException {
        Map<String, String> conf = ((io.delta.kernel.internal.SnapshotImpl) s).getMetadata().getConfiguration();
        List<TableUpkeep.Ranked> rows = TableUpkeep.ranked(engine, path, s, pv -> date.equals(pv.get("business_date")), 1, null, null);
        return rows == null ? null : TableUpkeep.generations(rows, order(sortedByMore(conf)));
    }

    /** The table's row order: by its sort-by fields (the staged row's columns: numbers by value, else text; absent first) then the id. */
    static java.util.Comparator<TableUpkeep.Staged> order(String sortBy) {
        java.util.Comparator<TableUpkeep.Staged> byId = java.util.Comparator.comparing(r -> r.id() == null ? "" : r.id());
        if (sortBy == null) {
            return byId;
        }
        Map<TableUpkeep.Staged, com.fasterxml.jackson.databind.JsonNode> cols = new java.util.IdentityHashMap<>();
        java.util.function.Function<TableUpkeep.Staged, com.fasterxml.jackson.databind.JsonNode> columns = r -> cols.computeIfAbsent(r, x -> {
            try {
                return JSON.readTree(x.line()).path("columns");
            } catch (IOException e) {
                return JSON.createObjectNode();
            }
        });
        java.util.Comparator<TableUpkeep.Staged> c = (a, b) -> 0;
        for (String f : sortBy.split(",")) {
            String name = f.trim();
            if (name.isEmpty() || name.equals("id")) {
                continue;
            }
            c = c.thenComparing((a, b) -> {
                com.fasterxml.jackson.databind.JsonNode x = columns.apply(a).path(name);
                com.fasterxml.jackson.databind.JsonNode y = columns.apply(b).path(name);
                boolean xa = x.isMissingNode() || x.isNull();
                boolean ya = y.isMissingNode() || y.isNull();
                if (xa || ya) {
                    return Boolean.compare(!xa, !ya);
                }
                return x.isNumber() && y.isNumber() ? Double.compare(x.asDouble(), y.asDouble()) : x.asText().compareTo(y.asText());
            });
        }
        return c.thenComparing(byId);
    }

    /** Writes a checkpoint of the latest version: the version (one that has one already is left as it is). */
    /**
     * The files another writer added (delta-rs, Spark, the Python path) stamped with the version of the commit that added each, read from
     * the log while it holds it ({@link com.ash.lake.delta.write.CommitOrder#stamp}): run before compaction and the checkpoint, after
     * which the log's expiry may drop those commits. The number of files stamped.
     */
    static int stamp(com.ash.lake.LakeRuntime runtime, String path, boolean dryRun) throws IOException {
        return com.ash.lake.delta.write.CommitOrder.stamp(LakeWriter.engineFor(runtime, path, Map.of()), path, dryRun);
    }

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
