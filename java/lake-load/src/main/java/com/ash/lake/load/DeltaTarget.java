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
import com.ash.lake.iceberg.io.FileAccess;
import com.ash.lake.iceberg.manifest.DataFile;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.iceberg.types.Type;
import com.ash.lake.iceberg.types.Values;
import com.ash.lake.iceberg.write.ParquetDataWriter;
import com.ash.lake.io.LocalPaths;
import com.ash.lake.io.S3Settings;
import com.ash.lake.load.TargetRules.ParquetOut;
import com.ash.lake.load.TargetRules.Source;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import io.delta.kernel.DataWriteContext;
import io.delta.kernel.Operation;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.Transaction;
import io.delta.kernel.TransactionBuilder;
import io.delta.kernel.TransactionCommitResult;
import io.delta.kernel.data.Row;
import io.delta.kernel.internal.SnapshotImpl;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.SingleAction;
import io.delta.kernel.internal.util.ColumnMapping;
import io.delta.kernel.internal.util.FileNames;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Literal;
import io.delta.kernel.hook.PostCommitHook;
import io.delta.kernel.statistics.DataFileStatistics;
import io.delta.kernel.types.ArrayType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Delta table in the layout {@code drishti.py lake build} writes and the Delta connector reads ({@code id}, {@code doc},
 * {@code business_date}, the partition columns, the promoted columns, {@code list<double>} vector columns; partitioned by
 * {@code business_date} then the partition columns). The Parquet files are written by the loader's threads (Drishti's own writer, no
 * Hadoop) into the folders Delta Kernel names, and each date is committed through Kernel as ONE version that adds its files and removes
 * the date's earlier ones, as {@link LakeWriter} does. A new table is created by its first date's commit, with {@code target.properties}.
 * A table laid out by more than the id ({@code sort-by}) says so in {@code lake.layout.sort-by}, as an Iceberg table does, so lake maintain
 * keeps its order. An existing table keeps its partitioning; a column the load sends that the table lacks (a promoted or vector column
 * first sent by a later load, the two load columns {@code _load_id} and {@code _source_file} of a table made before them) is added,
 * nullable, at the end in one metadata commit ({@link #withColumns}), as the Iceberg and files stores add it; the files are not
 * rewritten, their rows read it as null. A column the load lacks is null.
 */
final class DeltaTarget implements TableWriter {

    static final String ENGINE_INFO = "Drishti Lake parallel loader";   // the commits' engineInfo (lake undo's too)
    /** The writer's row groups and pages: as {@link LakeWriter} (lake.parquet.row-group-kb, 256; lake.parquet.page-kb, 64), the runtime's. */
    private final long rowGroup;
    private final long page;

    private final String path;
    private final WriteSpec spec;
    private final Engine engine;
    private final FileAccess io;
    private final Table table;
    private List<String> partitionColumns;
    private StructType schema;
    private Schema fileSchema;
    private Source[] sources;
    private Map<String, String> options;
    private final List<String> leftOut = new ArrayList<>();
    private Snapshot before;
    /** The table when the load began (null: none): a replace of a date changed since is lost ({@link TableWriter.LostReplace}). */
    private final Snapshot base;
    private Transaction txn;
    private Row state;
    private final Map<List<String>, DataWriteContext> contexts = new HashMap<>();

    DeltaTarget(WriteSpec spec) throws IOException {
        this.spec = spec;
        this.path = spec.path();
        Map<String, String> s3 = spec.storage();
        this.rowGroup = spec.runtime().options().rowGroupBytes();
        this.page = spec.runtime().options().pageBytes();
        this.engine = LakeWriter.engineFor(spec.runtime(), path, s3);
        this.io = new FileAccess(spec.runtime(), S3Settings.from(spec.runtime(), s3), com.ash.lake.iceberg.io.Relocation.none(), null);
        this.table = Table.forPath(engine, path);
        this.base = snapshot();                  // the load's base: the table as the load found it (TableWriter)
    }

    @Override
    public void open(LoadColumns c) throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add("business_date");
        parts.addAll(spec.partition());
        if (LocalPaths.isLocal(path)) {
            Files.createDirectories(Path.of(LocalPaths.toLocal(path, LocalPaths.WINDOWS)));
        }
        before = snapshot();
        StructType wanted = new StructType().add("id", StringType.STRING, true).add("doc", StringType.STRING, true).add("business_date", DateType.DATE, true);
        for (String p : spec.partition()) {
            wanted = wanted.add(p, StringType.STRING, true);
        }
        for (int i = 0; i < c.promoted().size(); i++) {
            wanted = wanted.add(c.promoted().get(i), c.list()[i] ? new ArrayType(StringType.STRING, true)
                    : c.numeric()[i] ? DoubleType.DOUBLE : StringType.STRING, true);
        }
        for (String v : spec.vectors()) {
            wanted = wanted.add(v, new ArrayType(spec.layout().float32Vectors() ? FloatType.FLOAT : DoubleType.DOUBLE, true), true);
        }
        if (spec.loadColumns()) {
            wanted = wanted.add(RowPlan.LOAD_ID, StringType.STRING, true).add(RowPlan.SOURCE_FILE, StringType.STRING, true);
        }
        if (before == null) {
            schema = wanted;
            partitionColumns = parts;
        } else {
            if (!before.getPartitionColumnNames().equals(parts)) {
                throw new IllegalStateException(path + " is partitioned by " + before.getPartitionColumnNames() + ", this load by " + parts
                        + " (give the table's partition-by, or write a new table)");
            }
            com.ash.lake.spi.TableProperties.refuseReplace(((io.delta.kernel.internal.SnapshotImpl) before).getMetadata().getConfiguration(), spec);
            before = withColumns(before, wanted);
            schema = before.getSchema();
            partitionColumns = before.getPartitionColumnNames();
            for (StructField f : wanted.fields()) {
                if (schema.indexOf(f.getName()) < 0) {
                    leftOut.add(f.getName());
                }
            }
        }
        List<NestedField> fields = new ArrayList<>();
        int id = 1;
        List<String> vectors = new ArrayList<>();
        for (StructField f : schema.fields()) {
            if (partitionColumns.contains(f.getName())) {
                continue;                                          // a partition column lives in the folder names, not in the files
            }
            Type t = icebergType(f, id + 1000);
            if (t instanceof Type.ListType l && !(l.element() instanceof Type.Primitive p && p.id() == Type.Id.STRING)) {
                vectors.add(f.getName());                            // a vector's encoding; a list attribute (array<string>) is plain
            }
            fields.add(NestedField.optional(id++, f.getName(), t));
        }
        fileSchema = new Schema(0, fields);
        sources = TargetRules.sources(fileSchema, spec);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("write.parquet.row-group-size-bytes", String.valueOf(rowGroup));
        base.put("write.parquet.page-size-bytes", String.valueOf(page));
        base.put("write.metadata.metrics.default", "full");             // Delta statistics: exact minimum and maximum
        base.put("write.metadata.metrics.column.doc", "none");
        options = TargetRules.writerOptions(base, spec.layout(), "snappy", vectors);
    }

    /**
     * The table with the columns of {@code wanted} it lacks added, each nullable, at the end (schema evolution, as the Iceberg and files
     * stores add them: a promoted or vector column first sent by a later load, the load columns of a table made before them): one commit
     * of a {@code metaData} action whose schema is the table's grown (what Delta's merge-schema writes; Kernel itself evolves a schema only
     * under column mapping). No file is rewritten: the rows written before read the new column as null. Written with put-if-absent through
     * the engine; when another writer took the version, read again. A table under column mapping or in-commit timestamps (written by
     * another engine) is not grown here: a data column it lacks refuses the load, naming it (never written and dropped); the load
     * columns are left out, reported.
     */
    private Snapshot withColumns(Snapshot snap, StructType wanted) throws IOException {
        for (int attempt = 0; ; attempt++) {
            StructType s = snap.getSchema();
            List<StructField> add = new ArrayList<>();
            for (StructField f : wanted.fields()) {
                if (s.indexOf(f.getName()) < 0) {
                    add.add(f);
                }
            }
            if (add.isEmpty()) {
                return snap;
            }
            SnapshotImpl si = (SnapshotImpl) snap;
            Metadata meta = si.getMetadata();
            Map<String, String> conf = meta.getConfiguration();
            if (ColumnMapping.isColumnMappingModeEnabled(ColumnMapping.getColumnMappingMode(conf))
                    || "true".equalsIgnoreCase(conf.get("delta.enableInCommitTimestamps"))) {
                List<String> data = new ArrayList<>();
                add.forEach(f -> {
                    if (!f.getName().equals(RowPlan.LOAD_ID) && !f.getName().equals(RowPlan.SOURCE_FILE)) {
                        data.add(f.getName());
                    }
                });
                if (!data.isEmpty()) {
                    throw new IllegalStateException(path + " has no column " + String.join(", ", data) + " and is a table under column mapping "
                            + "or in-commit timestamps, which the loader does not widen: add the column with the engine that wrote the table, "
                            + "or leave it out of promote and vectors; nothing was written");
                }
                return snap;                                            // the load columns: left out by open(), the report lists them
            }
            StructType grown = s;
            for (StructField f : add) {
                grown = grown.add(f.getName(), f.getDataType(), true);
            }
            Row action = SingleAction.createMetadataSingleAction(meta.withNewSchema(grown).toRow());
            String file = FileNames.deltaFile(si.getLogPath(), si.getVersion() + 1);
            try {
                engine.getJsonHandler().writeJsonFileAtomically(file, Utils.singletonCloseableIterator(action), false);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                if (attempt >= 10) {
                    throw new IOException(path + ": the columns " + add.stream().map(StructField::getName).toList() + " could not be added "
                            + "(the table changed ten times meanwhile)", e);
                }
            }
            snap = table.getLatestSnapshot(engine);
        }
    }

    private static Type icebergType(StructField f, int elementId) {
        DataType t = f.getDataType();
        if (t instanceof StringType) {
            return Type.Primitive.STRING;
        }
        if (t instanceof DoubleType) {
            return Type.Primitive.DOUBLE;
        }
        if (t instanceof LongType) {
            return Type.Primitive.LONG;
        }
        if (t instanceof DateType) {
            return Type.Primitive.DATE;
        }
        if (t instanceof ArrayType a && (a.getElementType() instanceof DoubleType || a.getElementType() instanceof FloatType)) {
            return new Type.ListType(elementId, false, a.getElementType() instanceof FloatType ? Type.Primitive.FLOAT : Type.Primitive.DOUBLE);
        }
        if (t instanceof ArrayType a && a.getElementType() instanceof StringType) {
            return new Type.ListType(elementId, false, Type.Primitive.STRING);           // a list attribute (DATA_LAKE_GUIDE.md section 2.4)
        }
        throw new IllegalStateException("the table's column " + f.getName() + " is " + t + ": the loader writes string, double, long, date, "
                + "list<double> and list<string> columns");
    }

    private Snapshot snapshot() {
        try {
            return table.getLatestSnapshot(engine);
        } catch (TableNotFoundException e) {
            return null;
        }
    }

    @Override
    public void begin(LocalDate date) throws IOException {
        TransactionBuilder tb;
        if (before == null) {
            tb = table.createTransactionBuilder(engine, ENGINE_INFO, Operation.CREATE_TABLE).withSchema(engine, schema)
                    .withPartitionColumns(engine, partitionColumns);
            Map<String, String> props = new LinkedHashMap<>(spec.properties());
            if (!spec.sortedById()) {                                 // as the Iceberg store: lake maintain keeps such a table's order
                props.putIfAbsent(com.ash.lake.PersistedNames.key("layout.sort-by"), String.join(",", spec.sortBy()));
            }
            if (!props.isEmpty()) {
                tb = tb.withTableProperties(engine, props);
            }
        } else {
            tb = table.createTransactionBuilder(engine, ENGINE_INFO, Operation.WRITE);
        }
        if (spec.appends() && spec.batchId() != null) {   // the batch's id in the commit: Delta's guard against a second commit
            tb = tb.withTransactionId(engine, appId(date), 1);
        }
        txn = tb.build(engine);
        if (!spec.appends()) {
            before = lostOrBase(date);
        }
        state = txn.getTransactionState(engine);
        synchronized (contexts) {
            contexts.clear();
        }
    }

    private DataWriteContext context(LocalDate date, String[] values) {
        List<String> k = new ArrayList<>(List.of(date.toString()));
        k.addAll(java.util.Arrays.asList(values));
        synchronized (contexts) {
            return contexts.computeIfAbsent(k, x -> Transaction.getWriteContext(engine, state, literals(date, values)));
        }
    }

    private Map<String, Literal> literals(LocalDate date, String[] values) {
        Map<String, Literal> out = new HashMap<>();
        out.put("business_date", Literal.ofDate((int) date.toEpochDay()));
        for (int i = 1; i < partitionColumns.size(); i++) {
            String v = i - 1 < values.length ? values[i - 1] : null;
            out.put(partitionColumns.get(i), v == null || v.isEmpty() ? Literal.ofNull(StringType.STRING) : Literal.ofString(v));   // empty is null, as in Spark
        }
        return out;
    }

    @Override
    public FileOut newFile(LocalDate date, String[] partitionValues, String name) throws IOException {
        String dir = LakeWriter.targetDirectory(context(date, partitionValues)).replaceAll("/+$", "");
        String file = dir + "/" + name;
        ParquetDataWriter w = new ParquetDataWriter(io, file, fileSchema, options, 0, new Object[0], null);
        return new ParquetOut(w, fileSchema, sources, date, false, file, partitionValues, x -> {
            try {
                return x.finish();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /**
     * A replace's view of the table: the snapshot the transaction reads (its removals are that snapshot's files of the date; Delta's
     * own conflict check covers what is committed after it), once the date's live files there are the ones the load's base had, else
     * {@link TableWriter.LostReplace}: another writer changed the date in between.
     */
    private Snapshot lostOrBase(LocalDate date) throws IOException {
        long read = txn.getReadTableVersion();
        Snapshot at = read < 0 ? null : before != null && before.getVersion() == read ? before : table.getSnapshotAsOfVersion(engine, read);
        if (at != null && (base == null || base.getVersion() != at.getVersion()) && !dayFiles(base, date).equals(dayFiles(at, date))) {
            throw new TableWriter.LostReplace(spec.domain() + "/" + spec.table(), date);
        }
        return at;
    }

    /** The live files of {@code date} (this load's slice of it) in {@code s}, with their deletion vectors; empty for no table. */
    private java.util.Set<String> dayFiles(Snapshot s, LocalDate date) throws IOException {
        if (s == null) {
            return java.util.Set.of();
        }
        String day = date.toString();
        Map<String, String> slice = spec.slice();
        return LakeWriter.liveFiles(engine, s, pv -> day.equals(pv.get("business_date")) && TargetRules.inSlice(pv, slice));
    }

    @Override
    public long commit(LocalDate date, List<WrittenFile> files) throws IOException {
        List<Row> actions = new ArrayList<>();
        if (before != null && !spec.appends()) {   // replace: the date's earlier files go in the same commit; append keeps them
            String day = date.toString();
            Map<String, String> slice = spec.slice();      // a shared table: only this load's slice of the date
            java.util.Set<List<String>> parts = spec.partitionsOnly() ? TableWriter.partitionsOf(files) : null;   // overwrite-partitions: those only
            actions.addAll(LakeWriter.removals(engine, before, pv -> day.equals(pv.get("business_date")) && TargetRules.inSlice(pv, slice)
                    && (parts == null || parts.contains(partitionOf(pv)))));
        }
        Map<List<String>, List<DataFileStatus>> byPartition = new LinkedHashMap<>();
        for (WrittenFile f : files) {
            byPartition.computeIfAbsent(java.util.Arrays.asList(f.partition()), k -> new ArrayList<>()).add(status((DataFile) f.handle()));
        }
        for (Map.Entry<List<String>, List<DataFileStatus>> e : byPartition.entrySet()) {
            String[] values = e.getKey().toArray(new String[0]);
            try (CloseableIterator<Row> adds = Transaction.generateAppendActions(engine, state, iterator(e.getValue()), context(date, values))) {
                while (adds.hasNext()) {
                    actions.add(adds.next());
                }
            }
        }
        TransactionCommitResult result;
        try {
            result = txn.commit(engine, CloseableIterable.inMemoryIterable(iterator(actions)));
        } catch (io.delta.kernel.exceptions.ConcurrentWriteException e) {
            if (spec.appends()) {
                throw e;
            }
            throw new TableWriter.LostReplace(spec.domain() + "/" + spec.table(), date);           // committed by another writer after this transaction read the table
        }
        for (PostCommitHook hook : result.getPostCommitHooks()) {
            try {
                hook.threadSafeInvoke(engine);                     // a checkpoint every so many versions, as lake build's writer
            } catch (IOException | RuntimeException e) {
                // the commit stands; the next write or lake maintain checkpoints
            }
        }
        before = snapshot();
        return result.getVersion();
    }

    /** A file's partition values after the date, in the table's order, an empty value as null (TableWriter.partitionsOf). */
    private List<String> partitionOf(Map<String, String> pv) {
        List<String> out = new ArrayList<>(partitionColumns.size() - 1);
        for (int i = 1; i < partitionColumns.size(); i++) {
            String v = pv.get(partitionColumns.get(i));
            out.add(v == null || v.isEmpty() ? null : v);
        }
        return out;
    }

    @Override
    public boolean appended(LocalDate date) {
        return spec.appends() && spec.batchId() != null && before != null
                && before.getLatestTransactionVersion(engine, appId(date)).isPresent();
    }

    /** The application id of a batch's append of a date ({@code drishti-batch:<batch>:<date>}), version 1 once committed. */
    private String appId(LocalDate date) {
        return "drishti-batch:" + spec.batchId() + ":" + date;
    }

    /** A written file as Kernel takes it: path, size, and statistics (row count, null counts, minimum and maximum of the scalar columns). */
    private DataFileStatus status(DataFile f) {
        Map<Column, Literal> min = new HashMap<>();
        Map<Column, Literal> max = new HashMap<>();
        Map<Column, Long> nulls = new HashMap<>();
        for (NestedField c : fileSchema.columns()) {
            if (!(c.type() instanceof Type.Primitive p) || c.name().equals("doc")) {
                continue;
            }
            Column col = new Column(c.name());
            Long n = f.nullValueCounts().get(c.id());
            if (n != null) {
                nulls.put(col, n);
            }
            byte[] lo = f.lowerBounds().get(c.id());
            byte[] hi = f.upperBounds().get(c.id());
            if (lo != null && hi != null) {
                min.put(col, literal(p, Values.fromBytes(p, lo)));
                max.put(col, literal(p, Values.fromBytes(p, hi)));
            }
        }
        DataFileStatistics stats = new DataFileStatistics(f.recordCount(), min, max, nulls, Optional.empty());
        return new DataFileStatus(f.path(), f.fileSizeInBytes(), System.currentTimeMillis(), Optional.of(stats));
    }

    private static Literal literal(Type.Primitive p, Object v) {
        return switch (p.id()) {
            case DOUBLE -> Literal.ofDouble(((Number) v).doubleValue());
            case LONG -> Literal.ofLong(((Number) v).longValue());
            case DATE -> Literal.ofDate(((Number) v).intValue());
            default -> Literal.ofString(v.toString());
        };
    }

    private static <T> CloseableIterator<T> iterator(List<T> list) {
        Iterator<T> it = list.iterator();
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
            public void close() {}
        };
    }

    @Override
    public Map<String, String> columnTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (StructField f : schema.fields()) {
            DataType t = f.getDataType();
            out.put(f.getName(), t instanceof ArrayType a ? (a.getElementType() instanceof StringType ? "list" : "vector") : t instanceof DoubleType ? "double" : t instanceof DateType ? "date"
                    : t instanceof LongType ? "long" : "string");
        }
        return out;
    }

    @Override
    public List<String> leftOut() {
        return leftOut;
    }

    @Override
    public void close() {
        io.close();
    }
}
