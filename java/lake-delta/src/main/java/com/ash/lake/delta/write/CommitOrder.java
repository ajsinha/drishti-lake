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
package com.ash.lake.delta.write;

import com.ash.lake.rows.LoadOrder;
import io.delta.kernel.CommitActions;
import io.delta.kernel.Transaction;
import io.delta.kernel.TransactionCommitResult;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.MapValue;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.ConcurrentWriteException;
import io.delta.kernel.exceptions.MaxCommitRetryLimitReachedException;
import io.delta.kernel.hook.PostCommitHook;
import io.delta.kernel.internal.DeltaLogActionUtils.DeltaAction;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.SingleAction;
import io.delta.kernel.internal.data.GenericRow;
import io.delta.kernel.internal.util.VectorUtils;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The durable commit order of a Delta table ({@link LoadOrder}): every data file the lake's writers add carries, in its add action's tag
 * {@link LoadOrder#COMMIT_TAG}, the version of the commit that adds it, so the newest load is known from the table's current files alone,
 * after a checkpoint and after the log that recorded the commit is expired. A commit is attempted at the version after the one its
 * transaction read with the tags of that version; when another writer took that version, the winning commits are checked as Delta Kernel's
 * own conflict check does (a metadata or protocol change, a file both removed: a {@link ConcurrentWriteException}; else the same actions
 * again at the next version, tagged with it). A file another writer added (delta-rs, Spark, the Python path) is stamped by
 * {@code lake maintain} with the version the log records for it ({@link #stamp}): the same file added again, {@code dataChange} false, its tags
 * with the version.
 */
public final class CommitOrder {

    /** The attempts of one commit before it fails (Kernel's own default is 200 retries). */
    static final int ATTEMPTS = 200;

    private CommitOrder() {}

    /** A commit made: its version and the post-commit hooks Kernel hands (none when the commit was found already in the log). */
    public record Committed(long version, List<PostCommitHook> hooks) {}

    /**
     * Commits {@code actions} through {@code txn} (built with {@code withMaxRetries(0)}), every add action tagged with the version it is
     * committed at. {@code again} builds the next attempt's transaction on the table's latest version (null: a create, never retried).
     *
     * @throws ConcurrentWriteException when the commits that won hold a metadata or protocol change or remove a file these actions remove
     */
    public static Committed commit(Engine engine, String path, Transaction txn, Supplier<Transaction> again, List<Row> actions) {
        Set<String> removes = new HashSet<>();
        Set<String> adds = new HashSet<>();
        for (Row a : actions) {
            String r = actionPath(a, "remove");
            if (r != null) {
                removes.add(name(r));
            }
            String p = actionPath(a, "add");
            if (p != null) {
                adds.add(name(p));
            }
        }
        Transaction t = txn;
        for (int attempt = 1; ; attempt++) {
            long read = t.getReadTableVersion();
            long version = read + 1;
            List<Row> tagged = new ArrayList<>(actions.size());
            for (Row a : actions) {
                tagged.add(actionPath(a, "add") == null ? a : withTags(a, Map.of(LoadOrder.COMMIT_TAG, Long.toString(version))));
            }
            try {
                TransactionCommitResult r = t.commit(engine, CloseableIterable.inMemoryIterable(iterator(tagged)));
                return new Committed(r.getVersion(), r.getPostCommitHooks());
            } catch (MaxCommitRetryLimitReachedException lost) {
                if (again == null || read < 0 || attempt >= ATTEMPTS) {
                    throw new ConcurrentWriteException(path + ": the commit at version " + version + " was taken by another writer", lost);
                }
                t = again.get();
                Long ours = check(engine, path, read + 1, t.getReadTableVersion(), removes, adds);
                if (ours != null) {
                    return new Committed(ours, List.of());       // the commit had landed (its write reported a failure): it stands
                }
            }
        }
    }

    /**
     * The commits {@code from..to} that won against an attempt: the version of one that holds this attempt's own files (it landed),
     * null when none does.
     *
     * @throws ConcurrentWriteException as Kernel's conflict check: a metadata or protocol change, or a file this attempt removes removed
     */
    static Long check(Engine engine, String path, long from, long to, Set<String> removes, Set<String> adds) {
        if (to < from) {
            return null;
        }
        try {
            io.delta.kernel.CommitRange range = io.delta.kernel.TableManager.loadCommitRange(path,
                    io.delta.kernel.CommitRangeBuilder.CommitBoundary.atVersion(from))
                    .withEndBoundary(io.delta.kernel.CommitRangeBuilder.CommitBoundary.atVersion(to)).build(engine);
            try (CloseableIterator<CommitActions> commits = range.getCommitActions(engine,
                    EnumSet.of(DeltaAction.ADD, DeltaAction.REMOVE, DeltaAction.METADATA, DeltaAction.PROTOCOL))) {
                while (commits.hasNext()) {
                    try (CommitActions c = commits.next(); CloseableIterator<ColumnarBatch> batches = c.getActions()) {
                        boolean mine = false;
                        String conflict = null;
                        while (batches.hasNext()) {
                            ColumnarBatch b = batches.next();
                            try (CloseableIterator<Row> rows = b.getRows()) {
                                while (rows.hasNext()) {
                                    Row r = rows.next();
                                    if (conflict == null && (present(r, "metaData") || present(r, "protocol"))) {
                                        conflict = "changed the table's metadata or protocol while this commit was made";
                                    }
                                    String added = actionPath(r, "add");
                                    mine |= added != null && adds.contains(name(added));
                                    String removed = actionPath(r, "remove");
                                    if (conflict == null && removed != null && removes.contains(name(removed))) {
                                        conflict = "removed " + name(removed) + ", which this commit removes too";
                                    }
                                }
                            }
                        }
                        if (!mine && conflict != null) {
                            throw new ConcurrentWriteException(path + ": version " + c.getVersion() + " " + conflict);
                        }
                        if (mine) {
                            return c.getVersion();
                        }
                    }
                }
            }
        } catch (ConcurrentWriteException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(path + ": the commits " + from + ".." + to + " could not be read: " + e.getMessage(), e);
        }
        return null;
    }

    private static boolean present(Row r, String action) {
        int at = r.getSchema().indexOf(action);
        return at >= 0 && !r.isNullAt(at);
    }

    /** The path of a single action row's {@code add} or {@code remove}; null when the row is not one. */
    private static String actionPath(Row single, String action) {
        int at = single.getSchema().indexOf(action);
        if (at < 0 || single.isNullAt(at)) {
            return null;
        }
        Row a = single.getStruct(at);
        return a.getString(a.getSchema().indexOf("path"));
    }

    /** A data file's name (the last part of its path, decoded): unique in a table, the same in an add, a remove and a scan file. */
    public static String name(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        return java.net.URLDecoder.decode(last.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The version an add action (or a scan file's {@code add}) records in {@link LoadOrder#COMMIT_TAG}; -1 when it has none. */
    public static long tagged(Row add) {
        int at = add.getSchema().indexOf("tags");
        if (at < 0 || add.isNullAt(at)) {
            return -1;
        }
        MapValue m = add.getMap(at);
        for (int i = 0; i < m.getSize(); i++) {
            if (!m.getKeys().isNullAt(i) && LoadOrder.COMMIT_TAG.equals(m.getKeys().getString(i)) && !m.getValues().isNullAt(i)) {
                try {
                    return Long.parseLong(m.getValues().getString(i).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** An add action (a single action row) with {@code extra} added to its tags (each replacing a tag of its name). */
    static Row withTags(Row single, Map<String, String> extra) {
        int at = single.getSchema().indexOf("add");
        Row add = single.getStruct(at);
        StructType s = add.getSchema();
        Map<Integer, Object> values = new HashMap<>();
        for (int i = 0; i < s.length(); i++) {
            if (add.isNullAt(i)) {
                continue;
            }
            io.delta.kernel.types.DataType t = s.at(i).getDataType();
            values.put(i, t instanceof io.delta.kernel.types.StringType ? add.getString(i) : t instanceof io.delta.kernel.types.LongType ? (Object) add.getLong(i)
                    : t instanceof io.delta.kernel.types.IntegerType ? (Object) add.getInt(i)
                    : t instanceof io.delta.kernel.types.BooleanType ? (Object) add.getBoolean(i)
                    : t instanceof io.delta.kernel.types.MapType ? add.getMap(i) : t instanceof StructType ? add.getStruct(i)
                    : t instanceof io.delta.kernel.types.ArrayType ? add.getArray(i) : null);
            if (values.get(i) == null) {
                throw new IllegalStateException("an add action's field " + s.at(i).getName() + " of type " + t + " is not carried");
            }
        }
        Map<String, String> tags = new LinkedHashMap<>();
        int ti = s.indexOf("tags");
        if (ti < 0) {
            throw new IllegalStateException("Delta Kernel's add action has no tags field");
        }
        if (!add.isNullAt(ti)) {
            tags.putAll(VectorUtils.toJavaMap(add.getMap(ti)));
        }
        tags.putAll(extra);
        values.put(ti, VectorUtils.stringStringMapValue(tags));
        return SingleAction.createAddFileSingleAction(new GenericRow(s, values));
    }

    /**
     * The stamp of a file another writer added ({@code add}: its add action as the snapshot holds it, a scan file's {@code add}): the same
     * file added again, {@code dataChange} false, with every field kept and {@link LoadOrder#COMMIT_TAG} set to {@code version}.
     */
    public static Row stamp(Row add, long version) {
        AddFile f = new AddFile(add);
        Map<Integer, Object> values = new HashMap<>();
        StructType full = AddFile.FULL_SCHEMA;
        values.put(full.indexOf("path"), f.getPath());
        values.put(full.indexOf("partitionValues"), f.getPartitionValues());
        values.put(full.indexOf("size"), f.getSize());
        values.put(full.indexOf("modificationTime"), f.getModificationTime());
        values.put(full.indexOf("dataChange"), false);
        f.getDeletionVector().ifPresent(dv -> values.put(full.indexOf("deletionVector"), dv.toRow()));
        Map<String, String> tags = new LinkedHashMap<>();
        f.getTags().ifPresent(m -> tags.putAll(VectorUtils.toJavaMap(m)));
        tags.put(LoadOrder.COMMIT_TAG, Long.toString(version));
        values.put(full.indexOf("tags"), VectorUtils.stringStringMapValue(tags));
        f.getBaseRowId().ifPresent(v -> values.put(full.indexOf("baseRowId"), v));
        f.getDefaultRowCommitVersion().ifPresent(v -> values.put(full.indexOf("defaultRowCommitVersion"), v));
        f.getStatsJson().ifPresent(v -> {
            if (v != null) {
                values.put(full.indexOf("stats"), v);
            }
        });
        return SingleAction.createAddFileSingleAction(new GenericRow(full, values));
    }

    /**
     * Stamps the files of the Delta table at {@code path} that another writer added (no {@link LoadOrder#COMMIT_TAG}) with the version the
     * log records for each ({@link #stamp(Row, long)}), in one commit, so their order survives the log's expiry ({@code lake maintain}).
     * A file whose commit the log no longer holds is left as it is. The number of files stamped (on a dry run, that would be); 0 when
     * another writer committed meanwhile (the next run stamps them).
     */
    public static int stamp(Engine engine, String path, boolean dryRun) throws IOException {
        io.delta.kernel.Snapshot s = io.delta.kernel.Table.forPath(engine, path).getLatestSnapshot(engine);
        List<Row> untagged = new ArrayList<>();
        io.delta.kernel.Scan scan = s.getScanBuilder().build();
        try (CloseableIterator<io.delta.kernel.data.FilteredColumnarBatch> it = scan instanceof io.delta.kernel.internal.ScanImpl si
                ? si.getScanFiles(engine, true) : scan.getScanFiles(engine)) {
            while (it.hasNext()) {
                try (CloseableIterator<Row> rows = it.next().getRows()) {
                    while (rows.hasNext()) {
                        Row add = rows.next().getStruct(io.delta.kernel.internal.InternalScanFileUtils.ADD_FILE_ORDINAL);
                        if (tagged(add) < 0) {
                            untagged.add(add);
                        }
                    }
                }
            }
        }
        if (untagged.isEmpty()) {
            return 0;
        }
        Set<String> names = new HashSet<>();
        untagged.forEach(a -> names.add(name(new AddFile(a).getPath())));
        Map<String, Long> versions = addedIn(engine, path, s.getVersion(), names);
        List<Row> stamps = new ArrayList<>();
        for (Row a : untagged) {
            Long v = versions.get(name(new AddFile(a).getPath()));
            if (v != null) {
                stamps.add(stamp(a, v));
            }
        }
        if (dryRun || stamps.isEmpty()) {
            return stamps.size();
        }
        Transaction txn = io.delta.kernel.Table.forPath(engine, path).createTransactionBuilder(engine, ENGINE_INFO, io.delta.kernel.Operation.WRITE)
                .withMaxRetries(0).build(engine);
        if (txn.getReadTableVersion() != s.getVersion()) {
            return 0;                                            // another writer committed meanwhile: a stamp never re-adds a file it removed
        }
        try {
            txn.commit(engine, CloseableIterable.inMemoryIterable(iterator(stamps)));
        } catch (MaxCommitRetryLimitReachedException | ConcurrentWriteException lost) {
            return 0;
        }
        return stamps.size();
    }

    /** The commits' engineInfo of a stamp. */
    private static final String ENGINE_INFO = "Drishti Lake maintain";

    /** The commits a commit-range read takes at a time, newest first. */
    private static final int COMMITS_PER_READ = 64;

    /**
     * The version that added each of {@code wanted} (data file names) as the log records it: the commits up to {@code latest} read newest
     * first through Kernel's commit range (add actions only), until every file is found or the log holds no older commit (its expired
     * part: the commits still there are read); a file not found is left out.
     */
    public static Map<String, Long> addedIn(Engine engine, String path, long latest, Set<String> wanted) {
        Map<String, Long> out = new HashMap<>();
        for (long end = latest; end >= 0 && out.size() < wanted.size(); end -= COMMITS_PER_READ) {
            long start = Math.max(0, end - COMMITS_PER_READ + 1);
            while (true) {
                try {
                    read(engine, path, start, end, wanted, out);
                    break;
                } catch (io.delta.kernel.exceptions.KernelException | IllegalArgumentException gone) {
                    if (start >= end) {
                        return out;                              // the log holds no older commit (expired)
                    }
                    start = (start + end + 1) / 2;               // part of the range expired: the newer half
                    end = Math.max(end, start);
                }
            }
            if (start > Math.max(0, end - COMMITS_PER_READ + 1)) {
                return out;                                      // the commits before start are gone
            }
        }
        return out;
    }

    private static void read(Engine engine, String path, long start, long end, Set<String> wanted, Map<String, Long> out) {
        io.delta.kernel.CommitRange range = io.delta.kernel.TableManager.loadCommitRange(path,
                io.delta.kernel.CommitRangeBuilder.CommitBoundary.atVersion(start))
                .withEndBoundary(io.delta.kernel.CommitRangeBuilder.CommitBoundary.atVersion(end)).build(engine);
        try (CloseableIterator<CommitActions> commits = range.getCommitActions(engine, EnumSet.of(DeltaAction.ADD))) {
            while (commits.hasNext()) {
                try (CommitActions c = commits.next(); CloseableIterator<ColumnarBatch> batches = c.getActions()) {
                    while (batches.hasNext()) {
                        try (CloseableIterator<Row> rows = batches.next().getRows()) {
                            while (rows.hasNext()) {
                                String added = actionPath(rows.next(), "add");
                                if (added != null && wanted.contains(name(added))) {
                                    out.merge(name(added), c.getVersion(), Math::max);
                                }
                            }
                        }
                    }
                }
            }
        } catch (io.delta.kernel.exceptions.KernelException | IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static <T> CloseableIterator<T> iterator(List<T> list) {
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
}
