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
import com.ash.lake.iceberg.catalog.Catalog;
import com.ash.lake.iceberg.catalog.Catalogs;
import com.ash.lake.iceberg.expr.Expr;
import com.ash.lake.iceberg.meta.Snapshot;
import com.ash.lake.iceberg.read.TaskReader;
import com.ash.lake.iceberg.scan.FileTask;
import com.ash.lake.iceberg.table.IcebergMaintenance;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.iceberg.types.Type;
import com.ash.lake.spi.AsOf;
import com.ash.lake.spi.Capabilities;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.DateChange;
import com.ash.lake.spi.RowScan;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableStore;
import com.ash.lake.spi.TableVersion;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Iceberg store: tables in the lake's layout ({@code <root>/<domain>/<table>}, partitioned by {@code business_date}), written by the
 * loader through {@link IcebergTarget} and read through the product's Iceberg reader. Its types stay inside: the columns and rows are the
 * product's.
 */
public final class IcebergStore implements TableStore {

    public static final String FORMAT = "iceberg";

    private IcebergStore() {
    }

    public static IcebergStore create() {
        return new IcebergStore();
    }

    @Override
    public com.ash.lake.read.LakeReader open(com.ash.lake.LakeRuntime runtime, com.ash.lake.read.LakeAddress address) {
        return com.ash.lake.iceberg.table.IcebergLakeReader.open(runtime, address);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.all();
    }

    private static Catalog catalog(LakeRuntime runtime, TableRef t) {
        Map<String, String> s = new HashMap<>();
        s.put("root", t.root());
        s.put("domain", t.domain());
        return Catalogs.open(runtime, s);
    }

    @Override
    public boolean exists(LakeRuntime runtime, TableRef table) throws IOException {
        try (Catalog c = catalog(runtime, table)) {
            return c.load(table.table()).isPresent();
        }
    }

    @Override
    public Columns columns(LakeRuntime runtime, TableRef table) throws IOException {
        try (Catalog c = catalog(runtime, table)) {
            com.ash.lake.iceberg.Table t = c.load(table.table()).orElseThrow(() -> new IOException("no Iceberg table at " + table.location()));
            return com.ash.lake.iceberg.table.IcebergColumns.columns(t.metadata().schema());
        }
    }

    @Override
    public SortedMap<LocalDate, Long> dates(LakeRuntime runtime, TableRef table) throws IOException {
        return StoreScans.dates(runtime, FORMAT, table);
    }

    /** The snapshots of the table's history (the snapshot log of its main branch), oldest first: a snapshot's id and when it became current. */
    @Override
    public List<TableVersion> versions(LakeRuntime runtime, TableRef table) throws IOException {
        try (Catalog c = catalog(runtime, table)) {
            List<TableVersion> out = new ArrayList<>();
            Optional<com.ash.lake.iceberg.Table> found = c.load(table.table());
            if (found.isPresent()) {
                for (var e : found.get().metadata().snapshotLog()) {
                    out.add(new TableVersion(e.snapshotId(), java.time.Instant.ofEpochMilli(e.timestampMs())));
                }
            }
            return out;
        }
    }

    /** The snapshot {@code asOf} names: the current one, a snapshot by its id, or the one current at a time (null: the table has none yet). */
    private static Snapshot snapshot(com.ash.lake.iceberg.Table t, TableRef table, AsOf asOf) throws IOException {
        if (t.currentSnapshot().isEmpty()) {
            if (asOf.latest()) {
                return null;
            }
            throw new IOException(asOf.missing(table.location()));
        }
        Optional<Snapshot> s = asOf.latest() ? t.currentSnapshot() : asOf.version() != null ? t.metadata().snapshot(asOf.version())
                : t.metadata().snapshotAsOf(asOf.time().toEpochMilli());
        return s.orElseThrow(() -> new IOException(asOf.missing(table.location())));
    }

    @Override
    public RowScan scan(LakeRuntime runtime, TableRef table, ScanRequest request) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Catalog c = catalog(runtime, table)) {
            Optional<com.ash.lake.iceberg.Table> found = c.load(table.table());
            if (found.isEmpty()) {
                return RowScan.of(Columns.of(List.of()), List.of());
            }
            com.ash.lake.iceberg.Table t = found.get();
            Snapshot at = snapshot(t, table, request.asOf());
            Schema schema = at == null ? t.metadata().schema() : t.metadata().schemaFor(at);
            Columns cols = com.ash.lake.iceberg.table.IcebergColumns.columns(schema);
            StoreScans rows = new StoreScans(cols, request);
            if (at == null) {
                return RowScan.of(rows.columns(), List.of());
            }
            List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
            for (FileTask task : t.plan(at, Expr.TRUE, pool)) {
                try (TaskReader r = t.rows(task, schema, ids, Expr.TRUE, false)) {
                    while (r.hasNext()) {
                        Object[] v = r.next();
                        Object[] out = new Object[v.length];
                        for (int i = 0; i < v.length; i++) {
                            out[i] = cols.get(i).type() == ColumnType.DATE && v[i] instanceof Number n ? LocalDate.ofEpochDay(n.longValue()) : plain(v[i]);
                        }
                        rows.add(out);
                    }
                }
            }
            return RowScan.of(rows.columns(), rows.rows());
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(table.location() + ": " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Object plain(Object v) {
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(plain(x)));
            return out;
        }
        if (v instanceof CharSequence cs) {
            return cs.toString();
        }
        if (v instanceof java.nio.ByteBuffer b) {
            byte[] out = new byte[b.remaining()];
            b.duplicate().get(out);
            return out;
        }
        return v;
    }

    @Override
    public TableWriter writer(WriteSpec spec) {
        return new IcebergTarget(spec);
    }

    @Override
    public List<DateChange> deleteDates(LakeRuntime runtime, TableRef table, Set<LocalDate> dates, boolean dryRun) throws IOException {
        return StoreScans.delete(runtime, FORMAT, table, dates, dryRun);
    }

    @Override
    public List<DateChange> undo(LakeRuntime runtime, TableRef table, String loadId, boolean dryRun) throws IOException {
        List<Map<String, Object>> lines;
        try {
            lines = IcebergMaintenance.undoTable(runtime, table.root(), table.domain(), table.table(), loadId, dryRun,
                    com.ash.lake.common.TempFiles.of(runtime).subDir("iceberg-undo"));
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(table.location() + ": " + e.getMessage(), e);
        }
        List<DateChange> out = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            out.add(new DateChange(LocalDate.parse(String.valueOf(l.get("date"))), ((Number) l.get("removed")).longValue(),
                    ((Number) l.get("restored")).longValue(), l.get("version") instanceof Number v ? v.longValue() : -1));
        }
        return out;
    }
}
