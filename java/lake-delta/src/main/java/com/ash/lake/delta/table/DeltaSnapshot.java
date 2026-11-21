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

import com.ash.lake.io.read.Problems;
import com.ash.lake.read.Bounds;
import com.ash.lake.read.ColumnAsk;
import com.ash.lake.read.ColumnBlock;
import com.ash.lake.read.DataFile;
import com.ash.lake.read.DayFiles;
import com.ash.lake.read.DocumentSink;
import com.ash.lake.read.FileFormat;
import com.ash.lake.read.IdMap;
import com.ash.lake.read.RowSink;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.read.VectorRequest;
import com.ash.lake.read.VectorSink;
import com.ash.lake.rows.VectorReads;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.Expr;
import io.delta.kernel.Scan;
import io.delta.kernel.data.Row;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.types.StructType;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * One version of a Delta table behind the read API: the table layer's {@link DeltaTable.Layout} (the Kernel's rows stay in it), every
 * read the table layer's own, called as the host's adapter called it; a read's failure is recorded in the lake's problems and its next
 * good read clears it.
 */
final class DeltaSnapshot implements TableSnapshot {

    /** The columns every layout table has that are not a document's paths. */
    static final Set<String> BASE = Set.of("doc", "business_date", "_load_id", "_source_file");

    private final DeltaLakeReader lake;
    private final String name;
    private final DeltaTable table;
    private final DeltaTable.Layout layout;
    private final Columns columns;
    private final Map<String, String> promoted;
    private final Set<String> vectors;
    private final Map<String, String> logicalOf;
    private final Map<LocalDate, DayFiles> days = new ConcurrentHashMap<>();

    DeltaSnapshot(DeltaLakeReader lake, String name, DeltaTable table, DeltaTable.Layout layout) {
        this.lake = lake;
        this.name = name;
        this.table = table;
        this.layout = layout;
        this.columns = DeltaRows.columns(layout.schema());
        Map<String, String> p = new LinkedHashMap<>();
        Set<String> v = new LinkedHashSet<>();
        for (var c : columns.all()) {
            if (c.type().vector()) {
                v.add(c.name());
            } else if (!BASE.contains(c.name()) && !c.name().equals(table.idColumn())) {
                p.put(c.name().replace("__", "."), c.name());
            }
        }
        this.promoted = Collections.unmodifiableMap(p);
        this.vectors = Collections.unmodifiableSet(v);
        Map<String, String> back = new HashMap<>();
        layout.physicalNames().forEach((logical, phys) -> back.put(phys, logical));
        this.logicalOf = back;
    }

    private <T> T read(LocalDate date, Problems.Read<T> body) {
        return lake.problems.read(name, date, layout.version(), body);
    }

    @Override
    public long version() {
        return layout.version();
    }

    @Override
    public Instant committed() {
        return Instant.ofEpochMilli(layout.timestamp());
    }

    @Override
    public long sequence() {
        return layout.version();
    }

    @Override
    public Columns columns() {
        return columns;
    }

    @Override
    public String idColumn() {
        return table.idColumn();
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
        return layout.deletionVectors();
    }

    @Override
    public NavigableSet<LocalDate> dates() {
        NavigableSet<LocalDate> out = new TreeSet<>(layout.files().keySet());
        out.remove(LocalDate.MIN);
        return Collections.unmodifiableNavigableSet(out);
    }

    private List<Row> files(LocalDate date) {
        return layout.files().getOrDefault(date, List.of());
    }

    @Override
    public Optional<DayFiles> day(LocalDate date) {
        List<Row> rows = layout.files().get(date);
        if (rows == null) {
            return Optional.empty();
        }
        return Optional.of(days.computeIfAbsent(date, d -> read(d, () -> {
            List<DataFile> out = new ArrayList<>(rows.size());
            for (Row r : rows) {
                out.add(dataFile(r));
            }
            return DayFiles.of(d, out);
        })));
    }

    /** One add action as a data file: logical names for its partition values and statistics. */
    private DataFile dataFile(Row r) {
        var fs = InternalScanFileUtils.getAddFileStatus(r);
        Long n = DeltaTable.numRecords(r);
        Map<String, String> partition = new LinkedHashMap<>();
        InternalScanFileUtils.getPartitionValues(r).forEach((k, v) -> {
            String logical = logicalOf.getOrDefault(k, k);
            if (!logical.equals(lake.dateColumn)) {
                partition.put(logical, v);
            }
        });
        Map<String, Bounds> bounds = new LinkedHashMap<>();
        for (var c : columns.all()) {
            if (c.type() == ColumnType.STRING && !c.name().equals(lake.docColumn) && !partition.containsKey(c.name())) {
                String[] b = DeltaTable.textBounds(r, layout.phys(c.name()));
                if (b[0] != null || b[1] != null || b[2] != null) {
                    bounds.put(c.name(), new Bounds(b[0], b[1], b[2] == null ? null : Long.valueOf(b[2])));
                }
            }
        }
        return new DataFile(fs.getPath(), fs.getSize(), FileFormat.PARQUET, n == null ? -1 : n,
                InternalScanFileUtils.getDeletionVectorDescriptorFromRow(r) != null, partition, bounds);
    }

    @Override
    public IdMap ids(LocalDate date) {
        return read(date, () -> {
            DeltaTable.IdMap m = table.ids(layout, date);
            return m instanceof DeltaTable.HeapIdMap h ? IdMap.of(h.idsInOrder(), h.files()) : IdMap.of(m.ids(), files(m));
        });
    }

    private static int[] files(DeltaTable.IdMap m) {
        int[] out = new int[m.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = m.file(m.id(i));
        }
        return out;
    }

    @Override
    public Optional<String> document(LocalDate date, int file, String id) {
        return read(date, () -> table.doc(layout, date, file, id));
    }

    @Override
    public void documents(LocalDate date, DocumentSink sink) {
        read(date, () -> {
            table.forEachDoc(layout, date, sink::document);
            return null;
        });
    }

    @Override
    public ColumnBlock columns(LocalDate date, List<ColumnAsk> asks) {
        Map<String, String> pathToColumn = new LinkedHashMap<>();
        Map<String, Boolean> numeric = new HashMap<>();
        for (ColumnAsk a : asks) {
            pathToColumn.put(a.path(), a.column());
            numeric.put(a.path(), a.numeric());
        }
        return read(date, () -> {
            com.ash.lake.rows.ColumnSet c = table.columns(layout, date, pathToColumn, numeric);
            return new ColumnBlock(c.ids(), c.numbers(), c.texts());
        });
    }

    @Override
    public void vectors(VectorRequest request, VectorSink sink) {
        LocalDate date = request.date();
        read(date, () -> {
            List<Row> all = files(date);
            Set<Row> kept = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Optional<DayFiles> day = day(date);
            for (int i = 0; i < all.size(); i++) {
                if (day.isEmpty() || request.keep().test(day.get().files().get(i))) {
                    kept.add(all.get(i));
                }
            }
            com.ash.lake.rows.VectorSink to = sink::row;
            table.vectors(layout, date, request.column(), request.texts(), kept::contains,
                    VectorReads.parallel(to, new VectorReads.Options(request.decoders(), request.bufferBytes())));
            return null;
        });
    }

    @Override
    public Map<String, Map<String, double[]>> vectorsOf(LocalDate date, int file, Set<String> ids, List<String> columns) {
        return read(date, () -> table.vectorsOf(layout, date, file, ids, columns));
    }

    @Override
    public void scanFile(LocalDate date, int file, List<String> wanted, Expr where, RowSink sink) {
        read(date, () -> {
            List<Row> all = files(date);
            if (file < 0 || file >= all.size()) {
                return null;
            }
            Scan scan = layout.snapshot().getScanBuilder().build();
            Row state = scan.getScanState(table.engine());
            StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
            int[] pick = new int[wanted.size()];
            for (int i = 0; i < pick.length; i++) {
                pick[i] = columns.indexOf(wanted.get(i));
                if (pick[i] < 0) {
                    throw new IllegalArgumentException("no column " + wanted.get(i) + " in " + name + " (it has " + columns.names() + ")");
                }
            }
            int idAt = columns.indexOf(table.idColumn());
            Map<String, Integer> shadow = table.shadow(layout, date);
            Object[] out = new Object[pick.length];
            DeltaRows.read(table.engine(), state, physical, all.get(file), columns, Set.copyOf(layout.snapshot().getPartitionColumnNames()), v -> {
                if (idAt >= 0 && v[idAt] != null && !DeltaTable.seen(shadow, v[idAt].toString(), file)) {
                    return;                                         // an older load's copy of an id a newer load wrote
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
