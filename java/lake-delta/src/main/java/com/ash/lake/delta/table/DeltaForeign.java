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

import com.ash.lake.io.read.ForeignSource;
import com.ash.lake.io.read.ShapeField;
import com.ash.lake.read.DataFile;
import com.ash.lake.read.FileFormat;
import com.ash.lake.read.TableIdentity;
import com.ash.lake.spi.AsOf;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ArrayValue;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.MapValue;
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
import io.delta.kernel.types.ByteType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DecimalType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.MapType;
import io.delta.kernel.types.ShortType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampNTZType;
import io.delta.kernel.types.TimestampType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Delta table other engines wrote (Spark, delta-rs), in its own layout, as the shape rule reads it (READ_API.md, RA-S8): through the
 * Kernel, partition values and the add actions' statistics as each file's days and bounds, deletion vectors applied.
 */
final class DeltaForeign implements ForeignSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Engine engine;
    private final String path;

    DeltaForeign(Engine engine, String path) {
        this.engine = engine;
        this.path = path;
    }

    private Optional<Snapshot> latest() {
        try {
            return Optional.of(Table.forPath(engine, path).getLatestSnapshot(engine));
        } catch (TableNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public String missing() {
        return "no Delta table at " + path + " (no _delta_log with a commit there)";
    }

    @Override
    public TableIdentity identity() {
        Optional<Snapshot> s = latest();
        return s.isPresent() ? new TableIdentity("delta", DeltaTable.metadataId(s.get()), path) : new TableIdentity("location", path, path);
    }

    @Override
    public Optional<ForeignSource.Version> version(AsOf asOf) throws IOException {
        Optional<Snapshot> latest = latest();
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Snapshot s = latest.get();
        if (!asOf.latest()) {
            Table table = Table.forPath(engine, path);
            try {
                if (asOf.version() != null) {
                    s = asOf.version() == s.getVersion() ? s : table.getSnapshotAsOfVersion(engine, asOf.version());
                } else {
                    long ms = asOf.time().toEpochMilli();
                    s = ms >= s.getTimestamp(engine) ? s : table.getSnapshotAsOfTimestamp(engine, ms);
                }
            } catch (io.delta.kernel.exceptions.KernelException | IllegalArgumentException e) {
                if (asOf.time() != null && DeltaTableReader.beforeFirstCommit(e)) {
                    return Optional.empty();
                }
                throw new IOException(asOf.missing(path), e);
            }
        }
        return Optional.of(new Version(s));
    }

    static ShapeField field(String name, DataType t) {
        if (t instanceof StructType st) {
            List<ShapeField> children = new ArrayList<>();
            for (StructField f : st.fields()) {
                children.add(field(f.getName(), f.getDataType()));
            }
            return new ShapeField(name, ShapeField.Kind.STRUCT, 0, children);
        }
        if (t instanceof ArrayType a) {
            return new ShapeField(name, ShapeField.Kind.LIST, 0, List.of(field("element", a.getElementType())));
        }
        if (t instanceof MapType m) {
            return new ShapeField(name, ShapeField.Kind.MAP, 0, List.of(field("key", m.getKeyType()), field("value", m.getValueType())));
        }
        if (t instanceof DecimalType d) {
            return new ShapeField(name, ShapeField.Kind.DECIMAL, d.getScale(), List.of());
        }
        ShapeField.Kind k = t instanceof BooleanType ? ShapeField.Kind.BOOLEAN
                : t instanceof IntegerType || t instanceof ShortType || t instanceof ByteType ? ShapeField.Kind.INT
                : t instanceof LongType ? ShapeField.Kind.LONG
                : t instanceof FloatType ? ShapeField.Kind.FLOAT
                : t instanceof DoubleType ? ShapeField.Kind.DOUBLE
                : t instanceof DateType ? ShapeField.Kind.DATE
                : t instanceof TimestampType ? ShapeField.Kind.TIMESTAMPTZ
                : t instanceof TimestampNTZType ? ShapeField.Kind.TIMESTAMP
                : t instanceof BinaryType ? ShapeField.Kind.BINARY
                : ShapeField.Kind.STRING;
        return ShapeField.of(name, k);
    }

    /** A Kernel value in the shape rule's form ({@link ShapeField}). */
    static Object value(ColumnVector v, int r) {
        if (v.isNullAt(r)) {
            return null;
        }
        DataType t = v.getDataType();
        if (t instanceof StructType st) {
            Object[] out = new Object[st.length()];
            for (int i = 0; i < out.length; i++) {
                out[i] = value(v.getChild(i), r);
            }
            return out;
        }
        if (t instanceof ArrayType) {
            ArrayValue a = v.getArray(r);
            ColumnVector e = a.getElements();
            List<Object> out = new ArrayList<>(a.getSize());
            for (int i = 0; i < a.getSize(); i++) {
                out.add(value(e, i));
            }
            return out;
        }
        if (t instanceof MapType) {
            MapValue m = v.getMap(r);
            Map<Object, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < m.getSize(); i++) {
                out.put(value(m.getKeys(), i), value(m.getValues(), i));
            }
            return out;
        }
        if (t instanceof BooleanType) {
            return v.getBoolean(r);
        }
        if (t instanceof IntegerType || t instanceof DateType) {
            return v.getInt(r);
        }
        if (t instanceof ShortType) {
            return (int) v.getShort(r);
        }
        if (t instanceof ByteType) {
            return (int) v.getByte(r);
        }
        if (t instanceof LongType || t instanceof TimestampType || t instanceof TimestampNTZType) {
            return v.getLong(r);
        }
        if (t instanceof FloatType) {
            return v.getFloat(r);
        }
        if (t instanceof DoubleType) {
            return v.getDouble(r);
        }
        if (t instanceof DecimalType) {
            return v.getDecimal(r);
        }
        if (t instanceof BinaryType) {
            return v.getBinary(r);
        }
        return v.getString(r);
    }

    /** A partition value or a statistic (text, as the log holds it) in the shape rule's form; null when it cannot be one. */
    static Object parse(ShapeField f, String text) {
        if (text == null) {
            return null;
        }
        try {
            return switch (f.kind()) {
                case STRING -> text.length() < 32 ? text : null;            // a writer may cut a longer value: not a bound
                case INT -> Integer.parseInt(text);
                case LONG -> Long.parseLong(text);
                case DOUBLE -> Double.parseDouble(text);
                case DATE -> (int) LocalDate.parse(text).toEpochDay();
                case TIMESTAMPTZ -> micros(OffsetDateTime.parse(text.replace(' ', 'T')).toInstant());
                case TIMESTAMP -> micros(LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC));
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long micros(Instant i) {
        return Math.multiplyExact(i.getEpochSecond(), 1_000_000L) + i.getNano() / 1000;
    }

    /** One version of the table. */
    private final class Version implements ForeignSource.Version {
        private final Snapshot s;
        private final StructType schema;
        private final Map<String, String> physical;
        private final List<ShapeField> columns = new ArrayList<>();
        private final Map<String, Row> scanStates = new ConcurrentHashMap<>();

        Version(Snapshot s) {
            this.s = s;
            this.schema = s.getSchema();
            this.physical = DeltaTable.physicalNames(s);
            for (StructField f : schema.fields()) {
                columns.add(field(f.getName(), f.getDataType()));
            }
        }

        private String phys(String logical) {
            return physical.getOrDefault(logical, logical);
        }

        @Override
        public long version() {
            return s.getVersion();
        }

        @Override
        public Instant committed() {
            return Instant.ofEpochMilli(s.getTimestamp(engine));
        }

        @Override
        public long sequence() {
            return s.getVersion();
        }

        @Override
        public List<ShapeField> columns() {
            return columns;
        }

        @Override
        public List<String> dayPartitions() {
            List<String> out = new ArrayList<>();
            for (String p : s.getPartitionColumnNames()) {
                int at = schema.indexOf(p);
                if (at >= 0 && schema.at(at).getDataType() instanceof DateType) {
                    out.add(p);
                }
            }
            return out;
        }

        @Override
        public List<ForeignSource.File> files() throws IOException {
            List<String> partitions = s.getPartitionColumnNames();
            Scan scan = s.getScanBuilder().build();
            List<ForeignSource.File> out = new ArrayList<>();
            CloseableIterator<FilteredColumnarBatch> scanFiles = scan instanceof io.delta.kernel.internal.ScanImpl si ? si.getScanFiles(engine, true)
                    : scan.getScanFiles(engine);
            try (CloseableIterator<FilteredColumnarBatch> it = scanFiles) {
                while (it.hasNext()) {
                    try (CloseableIterator<Row> rows = it.next().getRows()) {
                        while (rows.hasNext()) {
                            out.add(file(rows.next(), partitions));
                        }
                    }
                }
            }
            return out;
        }

        private ForeignSource.File file(Row r, List<String> partitions) {
            FileStatus fs = InternalScanFileUtils.getAddFileStatus(r);
            Map<String, String> values = InternalScanFileUtils.getPartitionValues(r);
            Map<String, String> partition = new LinkedHashMap<>();
            Map<String, Integer> days = new LinkedHashMap<>();
            Map<String, Object[]> bounds = new LinkedHashMap<>();
            for (String p : partitions) {
                int at = schema.indexOf(p);
                String v = values.get(phys(p));
                if (v == null || at < 0) {
                    continue;
                }
                partition.put(p, v);
                Object x = parse(columns.get(at), v);
                if (x != null) {
                    bounds.put(p, new Object[] {x, x});
                    if (columns.get(at).kind() == ShapeField.Kind.DATE) {
                        days.put(p, (Integer) x);
                    }
                }
            }
            stats(r, bounds);
            Long n = DeltaTable.numRecords(r);
            boolean dv = InternalScanFileUtils.getDeletionVectorDescriptorFromRow(r) != null;
            DataFile api = new DataFile(fs.getPath(), fs.getSize(), FileFormat.PARQUET, n == null ? -1 : n, dv, partition, Map.of());
            return new ForeignSource.File(api, days, bounds, r);
        }

        /** The add action's minValues and maxValues of the top-level columns. */
        private void stats(Row r, Map<String, Object[]> bounds) {
            try {
                Row add = r.getStruct(InternalScanFileUtils.ADD_FILE_ORDINAL);
                int at = add.getSchema().indexOf("stats");
                if (at < 0 || add.isNullAt(at)) {
                    return;
                }
                JsonNode stats = JSON.readTree(add.getString(at));
                JsonNode min = stats.path("minValues");
                JsonNode max = stats.path("maxValues");
                for (ShapeField f : columns) {
                    JsonNode lo = min.get(phys(f.name()));
                    JsonNode hi = max.get(phys(f.name()));
                    if (lo != null && hi != null && lo.isValueNode() && hi.isValueNode()) {
                        Object a = parse(f, lo.asText());
                        Object b = parse(f, hi.asText());
                        if (a != null && b != null) {
                            bounds.putIfAbsent(f.name(), new Object[] {a, b});
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // no statistics to go by: the file is read
            }
        }

        @Override
        public void read(ForeignSource.File file, int[] cols, Map<Integer, Object> equal, ForeignSource.Rows sink) throws IOException {
            StringBuilder key = new StringBuilder();
            StructType read = new StructType();
            for (int c : cols) {
                read = read.add(schema.at(c));
                key.append(c).append(',');
            }
            StructType readSchema = read;
            Row scanState = scanStates.computeIfAbsent(key.toString(), k -> s.getScanBuilder().withReadSchema(readSchema).build().getScanState(engine));
            Row fileRow = (Row) file.handle();
            StructType physicalSchema = ScanStateRow.getPhysicalDataReadSchema(scanState);
            FileStatus fs = InternalScanFileUtils.getAddFileStatus(fileRow);
            Object[] out = new Object[cols.length];
            try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                    .readParquetFiles(Utils.singletonCloseableIterator(fs), physicalSchema, Optional.empty());
                 CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, scanState, fileRow,
                         raw.map(FileReadResult::getData))) {
                while (data.hasNext()) {
                    FilteredColumnarBatch b = data.next();
                    ColumnarBatch batch = b.getData();
                    ColumnVector[] vs = new ColumnVector[cols.length];
                    for (int i = 0; i < cols.length; i++) {
                        int at = batch.getSchema().indexOf(schema.at(cols[i]).getName());
                        vs[i] = batch.getColumnVector(at < 0 ? i : at);
                    }
                    Optional<ColumnVector> selected = b.getSelectionVector();
                    for (int r = 0; r < batch.getSize(); r++) {
                        if (selected.isPresent() && (selected.get().isNullAt(r) || !selected.get().getBoolean(r))) {
                            continue;
                        }
                        for (int i = 0; i < cols.length; i++) {
                            out[i] = value(vs[i], r);
                        }
                        sink.row(out);
                    }
                }
            }
        }
    }
}
