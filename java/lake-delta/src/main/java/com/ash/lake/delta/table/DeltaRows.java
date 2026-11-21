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
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;

/** Delta's schema and rows in the product's types: one rule for the store's scans and the read API. */
public final class DeltaRows {

    private DeltaRows() {
    }

    /** A Delta schema as the product's columns. */
    public static Columns columns(StructType schema) {
        List<Column> out = new ArrayList<>();
        for (StructField f : schema.fields()) {
            out.add(new Column(f.getName(), type(f.getDataType())));
        }
        return Columns.of(out);
    }

    /** A Delta type as the product's. */
    public static ColumnType type(DataType t) {
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
            return e instanceof StringType ? ColumnType.STRING_LIST : e instanceof DoubleType ? ColumnType.DOUBLE_LIST : e instanceof FloatType ? ColumnType.FLOAT_LIST
                    : ColumnType.OTHER;
        }
        return ColumnType.OTHER;
    }

    /**
     * The rows of one data file. A partition column's empty string is null, as the Delta protocol reads it (and Spark and delta-rs with
     * it): a writer may record a null partition value as {@code ""}.
     */
    public static void read(Engine engine, Row state, StructType physical, Row file, Columns cols, Set<String> partitions,
            java.util.function.Consumer<Object[]> rows)
            throws IOException {
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
                boolean[] partition = new boolean[at.length];
                for (int c = 0; c < at.length; c++) {
                    partition[c] = partitions.contains(schema.at(c).getName());
                }
                Optional<ColumnVector> sel = b.getSelectionVector();
                for (int r = 0; r < b.getData().getSize(); r++) {
                    if (sel.isPresent() && (sel.get().isNullAt(r) || !sel.get().getBoolean(r))) {
                        continue;
                    }
                    Object[] v = new Object[cols.size()];
                    for (int c = 0; c < at.length; c++) {
                        if (at[c] >= 0) {
                            Object x = value(b.getData().getColumnVector(c), r);
                            v[at[c]] = partition[c] && "".equals(x) ? null : x;
                        }
                    }
                    rows.accept(v);
                }
            }
        }
    }

    public static Object value(ColumnVector v, int r) {
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


    /** What a scan's pushdown knows of one file before reading it: its partition values and text columns' statistics, by logical name. */
    public record FileFacts(Map<String, String> partition, Map<String, com.ash.lake.read.Bounds> bounds) {}

    /**
     * The rule of the read API's {@code DataFile} for the scan files of {@code s} (scanned with their statistics): partition values
     * other than {@code business_date} and the text columns' bounds (a value of 32 characters or more not stated), by logical name.
     */
    public static java.util.function.Function<Row, FileFacts> facts(Snapshot s, Columns cols) {
        Map<String, String> phys = DeltaTable.physicalNames(s);
        Map<String, String> logical = new java.util.HashMap<>();
        phys.forEach((l, p) -> logical.put(p, l));
        return r -> {
            Map<String, String> partition = new java.util.LinkedHashMap<>();
            InternalScanFileUtils.getPartitionValues(r).forEach((k, v) -> {
                String name = logical.getOrDefault(k, k);
                if (!name.equals("business_date")) {
                    partition.put(name, v == null || v.isEmpty() ? null : v);
                }
            });
            Map<String, com.ash.lake.read.Bounds> bounds = new java.util.LinkedHashMap<>();
            for (Column c : cols.all()) {
                if (c.type() == ColumnType.STRING && !c.name().equals("doc") && !partition.containsKey(c.name())) {
                    String[] b = DeltaTable.textBounds(r, phys.getOrDefault(c.name(), c.name()));
                    if (b[0] != null || b[1] != null || b[2] != null) {
                        bounds.put(c.name(), new com.ash.lake.read.Bounds(b[0], b[1], b[2] == null ? null : Long.valueOf(b[2])));
                    }
                }
            }
            return new FileFacts(partition, bounds);
        };
    }

    /** The scan files of {@code scan} with their statistics (the add actions' {@code stats}), as the read API's layout reads them. */
    public static io.delta.kernel.utils.CloseableIterator<FilteredColumnarBatch> scanFilesWithStats(Scan scan, Engine engine) {
        return scan instanceof io.delta.kernel.internal.ScanImpl si ? si.getScanFiles(engine, true) : scan.getScanFiles(engine);
    }
}
