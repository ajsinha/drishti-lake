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

import com.ash.lake.iceberg.scan.FileTask;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.io.read.Problems;
import com.ash.lake.read.ColumnAsk;
import com.ash.lake.read.ColumnBlock;
import com.ash.lake.read.DataFile;
import com.ash.lake.read.DayFiles;
import com.ash.lake.read.DocumentSink;
import com.ash.lake.read.IdMap;
import com.ash.lake.read.RowSink;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.read.VectorRequest;
import com.ash.lake.read.VectorSink;
import com.ash.lake.rows.VectorReads;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.Expr;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One snapshot of an Iceberg table behind the read API: the table layer's {@link KindTable.Layout} (its file tasks stay in it), every read
 * the table layer's own (deletes applied by its reader); a read's failure is recorded in the lake's problems.
 */
final class IcebergSnapshot implements TableSnapshot {

    static final Set<String> BASE = Set.of(IcebergLayout.DOC, IcebergLayout.DATE, "_load_id", "_source_file");

    private final IcebergLakeReader lake;
    private final String name;
    private final NativeKindTable table;
    private final KindTable.Layout layout;
    private final Schema schema;
    private final Columns columns;
    private final Map<String, String> promoted;
    private final Set<String> vectors;
    private final long sequence;
    private final Map<LocalDate, DayFiles> days = new ConcurrentHashMap<>();

    IcebergSnapshot(IcebergLakeReader lake, String name, NativeKindTable table, KindTable.Layout layout, Schema schema) {
        this.lake = lake;
        this.name = name;
        this.table = table;
        this.layout = layout;
        this.schema = schema;
        this.columns = IcebergColumns.columns(schema);
        Map<String, String> p = new LinkedHashMap<>();
        Set<String> v = new LinkedHashSet<>();
        for (var c : columns.all()) {
            if (c.type() == ColumnType.DOUBLE_LIST) {
                v.add(c.name());
            } else if (!BASE.contains(c.name()) && !c.name().equals(IcebergLayout.ID)) {
                p.put(c.name().replace("__", "."), c.name());
            }
        }
        this.promoted = Collections.unmodifiableMap(p);
        this.vectors = Collections.unmodifiableSet(v);
        this.sequence = table.sequenceNumber(layout.snapshotId());
    }

    private <T> T read(LocalDate date, Problems.Read<T> body) {
        return lake.problems.read(name, date, layout.snapshotId(), body);
    }

    private KindTable.Day dayOf(LocalDate date) {
        KindTable.Day d = layout.days().get(date);
        if (d == null) {
            return new KindTable.Day(date, List.of(), 0, false, 0);
        }
        return d;
    }

    @Override
    public long version() {
        return layout.snapshotId();
    }

    @Override
    public Instant committed() {
        return Instant.ofEpochMilli(layout.timestamp());
    }

    @Override
    public long sequence() {
        return sequence;
    }

    @Override
    public Columns columns() {
        return columns;
    }

    @Override
    public String idColumn() {
        return IcebergLayout.ID;
    }

    @Override
    public Map<String, String> promoted() {
        return promoted;
    }

    @Override
    public Set<String> vectorColumns() {
        return vectors;
    }

    @Override
    public boolean deletes() {
        return layout.deletes();
    }

    @Override
    public NavigableSet<LocalDate> dates() {
        NavigableSet<LocalDate> out = new TreeSet<>(layout.days().keySet());
        out.remove(LocalDate.MIN);
        return Collections.unmodifiableNavigableSet(out);
    }

    @Override
    public Optional<DayFiles> day(LocalDate date) {
        KindTable.Day d = layout.days().get(date);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(days.computeIfAbsent(date, x -> read(x, () -> {
            List<DataFile> out = new ArrayList<>();
            for (FileTask t : NativeKindTable.tasks(d)) {
                out.add(dataFile(t));
            }
            return DayFiles.of(x, out);
        })));
    }

    /** A manifest entry as a data file (IcebergColumns.dataFile: one rule with the store's scan). */
    private DataFile dataFile(FileTask t) {
        return IcebergColumns.dataFile(schema, columns, t);
    }

    @Override
    public IdMap ids(LocalDate date) {
        return read(date, () -> {
            KindTable.IdMap m = table.ids(layout, dayOf(date));
            if (m instanceof KindTable.Sorted s) {
                return IdMap.of(s.idArray(), s.files());
            }
            String[] ids = m.ids().toArray(new String[0]);
            int[] files = new int[ids.length];
            for (int i = 0; i < ids.length; i++) {
                files[i] = m.file(ids[i]);
            }
            return IdMap.of(ids, files);
        });
    }

    @Override
    public Optional<String> document(LocalDate date, int file, String id) {
        return read(date, () -> table.doc(layout, dayOf(date), file, id));
    }

    @Override
    public void documents(LocalDate date, DocumentSink sink) {
        read(date, () -> {
            IcebergVectors.documents(table, layout, dayOf(date), sink::document);
            return null;
        });
    }

    @Override
    public ColumnBlock columns(LocalDate date, List<ColumnAsk> asks) {
        Map<String, String> pathToColumn = new LinkedHashMap<>();
        asks.forEach(a -> pathToColumn.put(a.path(), a.column()));
        return read(date, () -> {
            com.ash.lake.rows.ColumnSet c = table.columns(layout, dayOf(date), pathToColumn);
            return new ColumnBlock(c.ids(), c.numbers(), c.texts());
        });
    }

    @Override
    public void vectors(VectorRequest request, VectorSink sink) {
        LocalDate date = request.date();
        read(date, () -> {
            KindTable.Day d = dayOf(date);
            List<DataFile> files = day(date).map(DayFiles::files).orElse(List.of());
            com.ash.lake.rows.VectorSink to = sink::row;
            IcebergVectors.vectors(table, layout, d, request.column(), request.texts(), f -> request.keep().test(files.get(f)),
                    VectorReads.parallel(to, new VectorReads.Options(request.decoders(), request.bufferBytes())));
            return null;
        });
    }

    @Override
    public Map<String, Map<String, double[]>> vectorsOf(LocalDate date, int file, Set<String> ids, List<String> cols) {
        return read(date, () -> {
            KindTable.Day d = dayOf(date);
            if (file < 0 || file >= d.files().size() || cols.isEmpty() || ids.isEmpty()) {
                return Map.of();
            }
            if (ids.size() == 1) {                        // one id: the reader skips the row groups that cannot hold it
                String id = ids.iterator().next();
                Map<String, double[]> one = IcebergVectors.vectorsOf(table, layout, d, file, id, cols);
                return one.isEmpty() ? Map.<String, Map<String, double[]>>of() : Map.of(id, one);
            }
            List<String> read = new ArrayList<>(List.of(IcebergLayout.ID));
            read.addAll(cols);
            Map<String, Map<String, double[]>> out = new HashMap<>();
            table.scan(layout, d, file, read, null, r -> {            // a page: one decode of the file for every id
                String id = r[0] == null ? null : r[0].toString();
                if (id == null || !ids.contains(id) || out.containsKey(id)) {
                    return;
                }
                Map<String, double[]> vs = new LinkedHashMap<>();
                for (int c = 0; c < cols.size(); c++) {
                    double[][] one = {new double[0]};
                    int len = IcebergVectors.copy(r[c + 1], one);
                    if (len >= 0) {
                        vs.put(cols.get(c), Arrays.copyOf(one[0], len));
                    }
                }
                out.put(id, vs);
            });
            return out;
        });
    }

    @Override
    public void scanFile(LocalDate date, int file, List<String> wanted, Expr where, RowSink sink) {
        read(date, () -> {
            KindTable.Day d = dayOf(date);
            if (file < 0 || file >= d.files().size()) {
                return null;
            }
            List<String> all = columns.names();
            int[] pick = new int[wanted.size()];
            for (int i = 0; i < pick.length; i++) {
                pick[i] = all.indexOf(wanted.get(i));
                if (pick[i] < 0) {
                    throw new IllegalArgumentException("no column " + wanted.get(i) + " in " + name + " (it has " + all + ")");
                }
            }
            int idAt = all.indexOf(IcebergLayout.ID);
            int dateAt = all.indexOf(IcebergLayout.DATE);
            Map<String, Integer> shadow = table.shadow(layout, d);
            Object[] out = new Object[pick.length];
            table.scan(layout, d, file, all, null, v -> {
                if (idAt >= 0 && v[idAt] != null && !KindTable.seen(shadow, v[idAt].toString(), file)) {
                    return;
                }
                if (dateAt >= 0 && v[dateAt] instanceof Number n) {
                    v[dateAt] = LocalDate.ofEpochDay(n.longValue());     // as the other stores give a date
                }
                if (where.test(new com.ash.lake.spi.Row(columns, v))) {
                    for (int i = 0; i < pick.length; i++) {
                        out[i] = v[pick[i]];
                    }
                    sink.row(out);
                }
            });
            return null;
        });
    }
}
