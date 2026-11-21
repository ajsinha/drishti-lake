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
import com.ash.lake.delta.write.LakeWriter;
import com.ash.lake.spi.Capabilities;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.DateChange;
import com.ash.lake.spi.RowScan;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableStore;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ArrayValue;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.ArrayType;
import io.delta.kernel.types.BinaryType;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampNTZType;
import io.delta.kernel.types.TimestampType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

/**
 * The Delta store: tables in the lake's layout ({@code <root>/<domain>/<table>}, partitioned by {@code business_date}), written by the
 * loader through {@link DeltaTarget} and read through Delta Kernel on the runtime's native engine. Kernel's types stay inside: the
 * columns and rows are the product's.
 */
public final class DeltaStore implements TableStore {

    public static final String FORMAT = "delta";

    private DeltaStore() {
    }

    public static DeltaStore create() {
        return new DeltaStore();
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.all();
    }

    private static Optional<Snapshot> snapshot(LakeRuntime runtime, TableRef t, Engine[] engine) {
        engine[0] = LakeWriter.engineFor(runtime, t.location(), Map.of());
        try {
            return Optional.of(Table.forPath(engine[0], t.location()).getLatestSnapshot(engine[0]));
        } catch (TableNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean exists(LakeRuntime runtime, TableRef table) {
        return snapshot(runtime, table, new Engine[1]).isPresent();
    }

    @Override
    public Columns columns(LakeRuntime runtime, TableRef table) throws IOException {
        Snapshot s = snapshot(runtime, table, new Engine[1]).orElseThrow(() -> new IOException("no Delta table at " + table.location()));
        return columns(s.getSchema());
    }

    static Columns columns(StructType schema) {
        List<Column> out = new ArrayList<>();
        for (StructField f : schema.fields()) {
            out.add(new Column(f.getName(), type(f.getDataType())));
        }
        return Columns.of(out);
    }

    static ColumnType type(DataType t) {
        if (t instanceof StringType) {
            return ColumnType.STRING;
        }
        if (t instanceof DoubleType || t instanceof FloatType) {
            return ColumnType.DOUBLE;
        }
        if (t instanceof LongType) {
            return ColumnType.LONG;
        }
        if (t instanceof IntegerType) {
            return ColumnType.INT;
        }
        if (t instanceof BooleanType) {
            return ColumnType.BOOLEAN;
        }
        if (t instanceof DateType) {
            return ColumnType.DATE;
        }
        if (t instanceof TimestampType || t instanceof TimestampNTZType) {
            return ColumnType.TIMESTAMP;
        }
        if (t instanceof BinaryType) {
            return ColumnType.BINARY;
        }
        if (t instanceof ArrayType a) {
            DataType e = a.getElementType();
            return e instanceof StringType ? ColumnType.STRING_LIST : e instanceof DoubleType || e instanceof FloatType ? ColumnType.DOUBLE_LIST
                    : ColumnType.OTHER;
        }
        return ColumnType.OTHER;
    }

    @Override
    public SortedMap<LocalDate, Long> dates(LakeRuntime runtime, TableRef table) throws IOException {
        return exists(runtime, table) ? StoreScans.dates(runtime, FORMAT, table) : new java.util.TreeMap<>();
    }

    @Override
    public RowScan scan(LakeRuntime runtime, TableRef table, ScanRequest request) throws IOException {
        Engine[] e = new Engine[1];
        Optional<Snapshot> found = snapshot(runtime, table, e);
        if (found.isEmpty()) {
            return RowScan.of(Columns.of(List.of()), List.of());
        }
        Engine engine = e[0];
        Snapshot s = found.get();
        Columns cols = columns(s.getSchema());
        StoreScans rows = new StoreScans(cols, request);
        Scan scan = s.getScanBuilder().build();
        Row state = scan.getScanState(engine);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> fileRows = files.next().getRows()) {
                    while (fileRows.hasNext()) {
                        Row file = fileRows.next();
                        String day = InternalScanFileUtils.getPartitionValues(file).get("business_date");
                        if (day != null && !rows.wants(LocalDate.parse(day))) {
                            continue;                                   // a file of a date the scan does not want
                        }
                        read(engine, state, physical, file, cols, rows);
                    }
                }
            }
        }
        return RowScan.of(rows.columns(), rows.rows());
    }

    private static void read(Engine engine, Row state, StructType physical, Row file, Columns cols, StoreScans rows) throws IOException {
        FileStatus fs = InternalScanFileUtils.getAddFileStatus(file);
        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler().readParquetFiles(Utils.singletonCloseableIterator(fs), physical,
                Optional.empty());
             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, state, file, raw.map(FileReadResult::getData))) {
            while (data.hasNext()) {
                FilteredColumnarBatch b = data.next();
                StructType schema = b.getData().getSchema();
                int[] at = new int[schema.length()];
                for (int c = 0; c < at.length; c++) {
                    at[c] = cols.indexOf(schema.at(c).getName());
                }
                Optional<ColumnVector> sel = b.getSelectionVector();
                for (int r = 0; r < b.getData().getSize(); r++) {
                    if (sel.isPresent() && (sel.get().isNullAt(r) || !sel.get().getBoolean(r))) {
                        continue;
                    }
                    Object[] v = new Object[cols.size()];
                    for (int c = 0; c < at.length; c++) {
                        if (at[c] >= 0) {
                            v[at[c]] = value(b.getData().getColumnVector(c), r);
                        }
                    }
                    rows.add(v);
                }
            }
        }
    }

    private static Object value(ColumnVector v, int r) {
        if (v.isNullAt(r)) {
            return null;
        }
        DataType t = v.getDataType();
        if (t instanceof DoubleType) {
            return v.getDouble(r);
        }
        if (t instanceof FloatType) {
            return (double) v.getFloat(r);
        }
        if (t instanceof LongType || t instanceof TimestampType || t instanceof TimestampNTZType) {
            return v.getLong(r);
        }
        if (t instanceof IntegerType) {
            return v.getInt(r);
        }
        if (t instanceof DateType) {
            return LocalDate.ofEpochDay(v.getInt(r));
        }
        if (t instanceof BooleanType) {
            return v.getBoolean(r);
        }
        if (t instanceof BinaryType) {
            return v.getBinary(r);
        }
        if (t instanceof ArrayType) {
            ArrayValue a = v.getArray(r);
            ColumnVector e = a.getElements();
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < a.getSize(); i++) {
                out.add(value(e, i));
            }
            return out;
        }
        return v.getString(r);
    }

    @Override
    public TableWriter writer(WriteSpec spec) throws IOException {
        return new DeltaTarget(spec);
    }

    @Override
    public List<DateChange> deleteDates(LakeRuntime runtime, TableRef table, Set<LocalDate> dates, boolean dryRun) throws IOException {
        return StoreScans.delete(runtime, FORMAT, table, dates, dryRun);
    }

    @Override
    public List<DateChange> undo(LakeRuntime runtime, TableRef table, String loadId, boolean dryRun) throws IOException {
        List<DateChange> out = new ArrayList<>();
        for (LakeUndo.Done d : LakeUndo.undoTable(runtime, table.location(), table.name(), loadId, dryRun)) {
            out.add(new DateChange(LocalDate.parse(d.date()), d.removed(), d.restored(), dryRun ? -1 : d.version()));
        }
        return out;
    }
}
