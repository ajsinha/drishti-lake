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

import com.ash.lake.delta.write.LakeManifest;
import com.ash.lake.delta.write.LakeWriter;
import com.ash.lake.delta.write.TableUpkeep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.delta.kernel.Operation;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Undo a load by its id (docs/guides/DATA_LAKE_GUIDE.md section 5.5): every row a load wrote carries its {@code _load_id}; undoing
 * it removes exactly those rows, one commit per table and date, in every table of the lake that has them (an archive's tables together).
 * A load that replaced a date is undone by restoring the date as it was before it (the rows of the version before the load's commit), with
 * the rows later loads appended kept. Every store alike: Delta here, Iceberg and the files store through their stores.
 *
 * <pre>LakeUndo --lake ROOT --load-id ID [--table domain/table ...] [--dry-run] [--json]</pre>
 * Exit codes: 0 undone (or nothing to undo, said so), 1 failed.
 */
public final class LakeUndo {

    static final ObjectMapper JSON = new ObjectMapper();

    /** What undoing did to one table and date: the load's rows removed, rows restored from before a replace, the version committed. */
    public record Done(String table, String date, long removed, long restored, boolean replaced, long version) {}

    private LakeUndo() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** Runs as the command line does, with the process's settings (no host). */
    public static int run(String[] args, PrintStream out) {
        return run(com.ash.lake.LakeRuntime.fromProcess(), args, out);
    }

    public static int run(com.ash.lake.LakeRuntime runtime, String[] args, PrintStream out) {
        String root = null;
        String id = null;
        List<String> only = new ArrayList<>();
        boolean dry = false;
        boolean json = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--lake" -> root = args[++i];
                case "--load-id" -> id = args[++i];
                case "--table" -> only.add(args[++i]);
                case "--dry-run" -> dry = true;
                case "--json" -> json = true;
                default -> {
                    out.println("usage: LakeUndo --lake ROOT --load-id ID [--table domain/table ...] [--dry-run] [--json]");
                    return 1;
                }
            }
        }
        if (root == null || id == null) {
            out.println("lakeundo: give --lake ROOT and --load-id ID (a load's id: its summary's loads, or a row's _load_id)");
            return 1;
        }
        try {
            List<Done> done = undo(runtime, root, id, only, dry);
            if (json) {
                out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(done));
            } else if (done.isEmpty()) {
                out.println("lakeundo: no row of load " + id + " in " + root);
            } else {
                for (Done d : done) {
                    out.println((dry ? "would undo " : "undone ") + d.table() + " " + d.date() + ": " + d.removed() + " rows of load " + id + " removed"
                            + (d.replaced() ? ", " + d.restored() + " rows of the date before it restored" : "") + (dry ? "" : " (version " + d.version() + ")"));
                }
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            out.println("lakeundo: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Undoes load {@code id} in every table of the lake at {@code root} (Delta, Iceberg and the files store alike): a line per table and date
     * ({@code table}, {@code format}, {@code date}, {@code removed}, {@code restored}, {@code replaced}). {@code only}: these
     * {@code domain/table}s; empty: every one.
     */
    public static List<Map<String, Object>> undoAll(com.ash.lake.LakeRuntime runtime, String root, String id, List<String> only, boolean dryRun) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tagged t : undoTagged(runtime, root, id, only, dryRun)) {
            Done d = t.done();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", d.table());
            m.put("format", t.format());
            m.put("date", d.date());
            m.put("removed", d.removed());
            m.put("restored", d.restored());
            m.put("replaced", d.replaced());
            out.add(m);
        }
        return out;
    }

    /** Undoes load {@code id} in the tables under {@code root}, every store ({@code only}: these {@code domain/table}s; empty: every one). */
    public static List<Done> undo(com.ash.lake.LakeRuntime runtime, String root, String id, List<String> only, boolean dryRun) throws IOException {
        return undoTagged(runtime, root, id, only, dryRun).stream().map(Tagged::done).toList();
    }

    /** One table and date undone, with its store's format. */
    private record Tagged(String format, Done done) {}

    private static List<Tagged> undoTagged(com.ash.lake.LakeRuntime runtime, String root, String id, List<String> only, boolean dryRun) throws IOException {
        List<Tagged> out = new ArrayList<>();
        for (String name : deltaTables(runtime, root)) {
            if (!only.isEmpty() && !only.contains(name)) {
                continue;
            }
            undoTable(runtime, LakeRetention.tablePath(root, name), name, id, dryRun).forEach(d -> out.add(new Tagged("delta", d)));
        }
        List<String> iceberg;
        try {
            iceberg = com.ash.lake.iceberg.table.IcebergMaintenance.tables(runtime, root);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(root + ": " + e.getMessage(), e);
        }
        for (String name : iceberg) {                                   // the Iceberg tables, through the store
            undoByStore(runtime, root, name, "iceberg", id, only, dryRun, out);
        }
        for (String name : filesTables(runtime, root)) {                // the files store's tables, through the store
            undoByStore(runtime, root, name, com.ash.lake.files.FilesStore.FORMAT, id, only, dryRun, out);
        }
        return out;
    }

    private static void undoByStore(com.ash.lake.LakeRuntime runtime, String root, String name, String format, String id, List<String> only,
            boolean dryRun, List<Tagged> out) throws IOException {
        if (!only.isEmpty() && !only.contains(name)) {
            return;
        }
        int slash = name.indexOf('/');
        String at = runtime.supports(root) || !com.ash.lake.io.LocalPaths.isLocal(root) ? root : Path.of(root).toAbsolutePath().toString();
        var table = new com.ash.lake.spi.TableRef(at, name.substring(0, slash), name.substring(slash + 1));
        for (com.ash.lake.spi.DateChange c : Stores.of(runtime, format).undo(runtime, table, id, dryRun)) {
            out.add(new Tagged(format, new Done(name, c.date().toString(), c.removed(), c.restored(), c.restored() > 0, c.version())));
        }
    }

    /** The files store's tables under the lake root ({@code <domain>/_log/<table>/columns.json}), on any storage. */
    static List<String> filesTables(com.ash.lake.LakeRuntime runtime, String root) throws IOException {
        return com.ash.lake.files.FilesStore.tables(runtime, root);
    }

    /**
     * The Delta tables under the lake root, {@code <domain>/<table>} with a {@code _delta_log}: on the local disk, on S3 (the runtime's S3
     * settings) or a storage registered with the runtime.
     */
    static List<String> deltaTables(com.ash.lake.LakeRuntime runtime, String root) throws IOException {
        List<String> out = new ArrayList<>();
        if (!runtime.supports(root) && com.ash.lake.io.LocalPaths.scheme(root).equals("file")) {      // a local folder
            Path base = Path.of(root);
            if (Files.isDirectory(base)) {
                try (Stream<Path> domains = Files.list(base)) {
                    for (Path d : domains.filter(Files::isDirectory).sorted().toList()) {
                        try (Stream<Path> tables = Files.list(d)) {
                            tables.filter(t -> Files.isDirectory(t.resolve("_delta_log"))).sorted()
                                    .forEach(t -> out.add(d.getFileName() + "/" + t.getFileName()));
                        }
                    }
                }
            }
            return out;
        }
        try (com.ash.lake.iceberg.io.FileAccess io = com.ash.lake.iceberg.io.FileAccess.local(runtime)) {
            com.ash.lake.spi.Storage st = io.storage(root);          // S3, or a registered storage (mem://)
            if (!st.isDirectory(root)) {
                return out;
            }
            for (String d : st.directories(root)) {
                String domain = LakeRetention.tablePath(root, d);
                for (String t : st.directories(domain)) {
                    if (st.isDirectory(LakeRetention.tablePath(domain, t) + "/_delta_log")) {
                        out.add(d + "/" + t);
                    }
                }
            }
        }
        return out;
    }

    /** One table: each date whose files may hold the load's rows is rewritten without them (restored from before when it replaced). */
    static List<Done> undoTable(com.ash.lake.LakeRuntime runtime, String table, String name, String id, boolean dryRun) throws IOException {
        Engine engine = LakeWriter.engineFor(runtime, table, Map.of());
        Snapshot s;
        try {
            s = Table.forPath(engine, table).getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            return List.of();
        }
        if (s.getSchema().indexOf(RowPlan.LOAD_ID) < 0) {
            return List.of();                                   // a table from before load ids: no load can be found in it
        }
        List<Done> out = new ArrayList<>();
        for (String date : candidateDates(engine, s, id)) {
            List<TableUpkeep.Staged> now = TableUpkeep.rows(engine, s, pv -> date.equals(pv.get("business_date")), true);
            if (now == null) {
                throw new IOException(name + ": a column the undo cannot carry");
            }
            List<TableUpkeep.Staged> kept = new ArrayList<>();
            long removed = 0;
            for (TableUpkeep.Staged r : now) {
                if (id.equals(JSON.readTree(r.line()).path("columns").path(RowPlan.LOAD_ID).asText(null))) {
                    removed++;
                } else {
                    kept.add(r);
                }
            }
            if (removed == 0) {
                continue;
            }
            long replacedAt = replacedAt(engine, table, s.getVersion(), date, id);
            long restored = 0;
            if (replacedAt > 0) {
                Snapshot before = Table.forPath(engine, table).getSnapshotAsOfVersion(engine, replacedAt - 1);
                List<TableUpkeep.Staged> old = TableUpkeep.rows(engine, before, pv -> date.equals(pv.get("business_date")), true);
                if (old != null) {
                    // a replace of one slice of a shared table (LoadConfig.withSlice) left the other slices' rows: those are in kept already
                    java.util.Set<String> still = new java.util.HashSet<>();
                    kept.forEach(k -> still.add(k.line()));
                    List<TableUpkeep.Staged> all = new ArrayList<>();
                    for (TableUpkeep.Staged o : old) {
                        if (!still.contains(o.line())) {
                            all.add(o);
                        }
                    }
                    restored = all.size();
                    all.addAll(kept);
                    kept = all;
                }
            }
            long version = dryRun ? -1 : rewrite(runtime, engine, table, s, date, kept);
            out.add(new Done(name, date, removed, restored, replacedAt > 0, version));
        }
        return out;
    }

    /**
     * The dates of a Delta table at {@code table} whose current files may still hold load {@code id}'s rows (the data inbox's duplicate check:
     * a file sent again is loaded again when a later load replaced its date). Null when it cannot be said (not a Delta table, unreadable):
     * the caller keeps its own answer.
     */
    public static java.util.Set<String> datesHolding(com.ash.lake.LakeRuntime runtime, String table, String id) {
        try {
            Engine engine = LakeWriter.engineFor(runtime, table, Map.of());
            Snapshot s = Table.forPath(engine, table).getLatestSnapshot(engine);
            if (s.getSchema().indexOf(RowPlan.LOAD_ID) < 0) {
                return null;
            }
            return new java.util.TreeSet<>(candidateDates(engine, s, id));
        } catch (TableNotFoundException e) {
            boolean local = !table.contains("://") || table.startsWith("file:");
            Path p = local ? Path.of(table.replaceFirst("^file:(//)?", "")) : null;
            // a local folder that is gone: nothing of the load is there; anything else (an Iceberg table, a store): not known here
            return p != null && !Files.exists(p) ? java.util.Set.of() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The dates whose files' {@code _load_id} statistics may hold {@code id} (a file without them may too). */
    static List<String> candidateDates(Engine engine, Snapshot s, String id) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        if (s.getSchema().indexOf(RowPlan.LOAD_ID) < 0) {
            return List.of();                                         // a table made before the load columns: no load to undo
        }
        try (CloseableIterator<FilteredColumnarBatch> it = s.getScanBuilder().build().getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row f = rows.next();
                        String date = InternalScanFileUtils.getPartitionValues(f).get("business_date");
                        if (date == null || out.contains(date)) {
                            continue;
                        }
                        String[] b = bounds(f);
                        boolean maybe = b[0] == null || b[1] == null ? holds(engine, f, id) : b[0].compareTo(id) <= 0 && b[1].compareTo(id) >= 0;
                        if (maybe) {
                            out.add(date);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Whether a file without {@code _load_id} statistics holds the load: its {@code _load_id} column alone is read (a file written before
     * the column, as every file of a lake built before the load columns, has none: only its footer is read, it holds nothing).
     */
    private static boolean holds(Engine engine, Row scanFile, String id) throws IOException {
        io.delta.kernel.types.StructType one = new io.delta.kernel.types.StructType().add(RowPlan.LOAD_ID, io.delta.kernel.types.StringType.STRING, true);
        try (CloseableIterator<io.delta.kernel.engine.FileReadResult> it = engine.getParquetHandler().readParquetFiles(
                io.delta.kernel.internal.util.Utils.singletonCloseableIterator(InternalScanFileUtils.getAddFileStatus(scanFile)), one, java.util.Optional.empty())) {
            while (it.hasNext()) {
                io.delta.kernel.data.ColumnarBatch b = it.next().getData();
                io.delta.kernel.data.ColumnVector v = b.getColumnVector(0);
                for (int r = 0; r < b.getSize(); r++) {
                    if (!v.isNullAt(r) && id.equals(v.getString(r))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A scan file's {@code _load_id} minimum and maximum from its add action's statistics (null when not stated). */
    private static String[] bounds(Row scanFile) {
        try {
            Row add = scanFile.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL);
            int at = add.getSchema().indexOf("stats");
            if (at < 0 || add.isNullAt(at)) {
                return new String[2];
            }
            JsonNode st = JSON.readTree(add.getString(at));
            return new String[] {st.path("minValues").path(RowPlan.LOAD_ID).asText(null), st.path("maxValues").path(RowPlan.LOAD_ID).asText(null)};
        } catch (IOException | RuntimeException e) {
            return new String[2];
        }
    }

    /** How many commits the newest-first walk of {@link #replacedAt} reads at a time. */
    static final int COMMITS_PER_READ = 64;

    /**
     * The version of the commit that wrote the load's rows of {@code date} when that commit also removed the date's earlier files (a replace),
     * else 0: the table's commits read newest first through the Delta Kernel's commit range (its add and remove actions), down to the
     * oldest commit the log still holds.
     */
    static long replacedAt(Engine engine, String table, long latest, String date, String id) throws IOException {
        for (long end = latest; end >= 0; end -= COMMITS_PER_READ) {
            long start = Math.max(0, end - COMMITS_PER_READ + 1);
            List<long[]> found = new ArrayList<>();               // per commit, newest first: {version, adds the load's rows, removes the date}
            try {
                io.delta.kernel.CommitRange range = io.delta.kernel.TableManager.loadCommitRange(table,
                        io.delta.kernel.CommitRangeBuilder.CommitBoundary.atVersion(start))
                        .withEndBoundary(io.delta.kernel.CommitRangeBuilder.CommitBoundary.atVersion(end)).build(engine);
                try (CloseableIterator<io.delta.kernel.CommitActions> commits = range.getCommitActions(engine,
                        java.util.Set.of(io.delta.kernel.internal.DeltaLogActionUtils.DeltaAction.ADD,
                                io.delta.kernel.internal.DeltaLogActionUtils.DeltaAction.REMOVE))) {
                    while (commits.hasNext()) {
                        try (io.delta.kernel.CommitActions c = commits.next()) {
                            found.add(commit(c, date, id));
                        } catch (Exception e) {
                            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
                        }
                    }
                }
            } catch (io.delta.kernel.exceptions.KernelException | IllegalArgumentException e) {
                return 0;                                         // the log no longer holds these commits (retention): no replace found
            }
            found.sort((a, b) -> Long.compare(b[0], a[0]));
            for (long[] c : found) {
                if (c[1] == 1) {
                    return c[2] == 1 ? c[0] : 0;
                }
            }
        }
        return 0;
    }

    /** One commit's {version, whether an add holds the load's rows of the date (its _load_id bounds), whether a remove is of the date}. */
    private static long[] commit(io.delta.kernel.CommitActions c, String date, String id) throws IOException {
        boolean adds = false;
        boolean removes = false;
        try (CloseableIterator<io.delta.kernel.data.ColumnarBatch> batches = c.getActions()) {
            while (batches.hasNext()) {
                io.delta.kernel.data.ColumnarBatch b = batches.next();
                int addAt = b.getSchema().indexOf("add");
                int removeAt = b.getSchema().indexOf("remove");
                try (CloseableIterator<Row> rows = b.getRows()) {
                    while (rows.hasNext()) {
                        Row r = rows.next();
                        if (addAt >= 0 && !r.isNullAt(addAt)) {
                            Row add = r.getStruct(addAt);
                            if (date.equals(partition(add, "business_date"))) {
                                int st = add.getSchema().indexOf("stats");
                                String stats = st < 0 || add.isNullAt(st) ? "" : add.getString(st);
                                JsonNode n = stats.isEmpty() ? null : JSON.readTree(stats);
                                String lo = n == null ? null : n.path("minValues").path(RowPlan.LOAD_ID).asText(null);
                                String hi = n == null ? null : n.path("maxValues").path(RowPlan.LOAD_ID).asText(null);
                                adds |= lo != null && hi != null && lo.compareTo(id) <= 0 && hi.compareTo(id) >= 0;
                            }
                        }
                        if (removeAt >= 0 && !r.isNullAt(removeAt) && date.equals(partition(r.getStruct(removeAt), "business_date"))) {
                            removes = true;
                        }
                    }
                }
            }
        }
        return new long[] {c.getVersion(), adds ? 1 : 0, removes ? 1 : 0};
    }

    /** One partition value of an add or remove action (null when the action has none of that name). */
    private static String partition(Row action, String column) {
        int at = action.getSchema().indexOf("partitionValues");
        if (at < 0 || action.isNullAt(at)) {
            return null;
        }
        io.delta.kernel.data.MapValue m = action.getMap(at);
        io.delta.kernel.data.ColumnVector keys = m.getKeys();
        io.delta.kernel.data.ColumnVector values = m.getValues();
        for (int i = 0; i < m.getSize(); i++) {
            if (column.equals(keys.getString(i))) {
                return values.isNullAt(i) ? null : values.getString(i);
            }
        }
        return null;
    }

    /** The date rewritten with {@code rows} (one commit: its files replaced); with none, the date's files removed. Returns the version. */
    static long rewrite(com.ash.lake.LakeRuntime runtime, Engine engine, String table, Snapshot s, String date, List<TableUpkeep.Staged> rows) throws IOException {
        List<String> partitions = s.getPartitionColumnNames();
        if (rows.isEmpty()) {
            return removeDates(engine, table, s, date::equals);
        }
        Path dir = com.ash.lake.common.TempFiles.of(runtime).createDir("lake-undo-");
        try {
            Map<Map<String, String>, BufferedWriter> files = new LinkedHashMap<>();
            Map<Map<String, String>, Path> paths = new LinkedHashMap<>();
            Map<Map<String, String>, Long> counts = new LinkedHashMap<>();
            for (TableUpkeep.Staged r : rows) {
                Map<String, String> pv = r.partitionValues();
                BufferedWriter w = files.get(pv);
                if (w == null) {
                    Path p = dir.resolve("part-" + files.size() + ".rows");
                    w = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
                    files.put(pv, w);
                    paths.put(pv, p);
                }
                w.write(r.line());
                w.write('\n');
                counts.merge(pv, 1L, Long::sum);
            }
            for (BufferedWriter w : files.values()) {
                w.close();
            }
            List<LakeManifest.Column> columns = TableUpkeep.columns(engine, table);
            List<String> promoted = new ArrayList<>();
            for (LakeManifest.Column c : columns) {
                if (!List.of("id", "doc").contains(c.name()) && !partitions.contains(c.name())) {
                    promoted.add(c.name());
                }
            }
            List<LakeManifest.Part> parts = new ArrayList<>();
            paths.forEach((pv, p) -> parts.add(new LakeManifest.Part(p, counts.get(pv), pv)));
            LakeManifest m = new LakeManifest(table, partitions, columns, promoted, "overwrite-dates", 50_000,
                    List.of("id", "business_date", RowPlan.LOAD_ID), parts);
            return LakeWriter.forTable(runtime, table, Map.of()).write(m, n -> { }).version();
        } finally {
            try (Stream<Path> s2 = Files.walk(dir)) {
                s2.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * Every file of the business dates {@code dates} accepts removed from the Delta table, one commit (a date left empty, a date deleted:
     * {@code lake delete}); the files stay on disk for time travel until the table's vacuum. Returns the version committed.
     */
    static long removeDates(Engine engine, String table, Snapshot s, java.util.function.Predicate<String> dates) throws IOException {
        List<Row> removes = LakeWriter.removals(engine, s, pv -> pv.get("business_date") != null && dates.test(pv.get("business_date")));
        var txn = Table.forPath(engine, table).createTransactionBuilder(engine, DeltaTarget.ENGINE_INFO, Operation.WRITE).build(engine);
        return txn.commit(engine, CloseableIterable.inMemoryIterable(toIterator(removes))).getVersion();
    }

    private static <T> CloseableIterator<T> toIterator(List<T> list) {
        java.util.Iterator<T> it = list.iterator();
        return new CloseableIterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public T next() {
                return it.next();
            }

            @Override
            public void close() {
            }
        };
    }

    /** The undo's lines as JSON (for the server's Admin answer). */
    static ArrayNode json(List<Done> done) {
        ArrayNode a = JSON.createArrayNode();
        for (Done d : done) {
            ObjectNode n = a.addObject();
            n.put("table", d.table()).put("date", d.date()).put("removed", d.removed()).put("restored", d.restored()).put("replaced", d.replaced())
                    .put("version", d.version());
        }
        return a;
    }
}
