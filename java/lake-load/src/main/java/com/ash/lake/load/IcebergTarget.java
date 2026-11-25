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

import com.ash.lake.iceberg.Table;
import com.ash.lake.iceberg.catalog.Catalog;
import com.ash.lake.iceberg.catalog.Catalogs;
import com.ash.lake.iceberg.manifest.DataFile;
import com.ash.lake.iceberg.meta.PartitionSpec;
import com.ash.lake.iceberg.meta.Snapshot;
import com.ash.lake.iceberg.meta.SortOrder;
import com.ash.lake.iceberg.meta.Transform;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.iceberg.types.Type;
import com.ash.lake.iceberg.write.Operations;
import com.ash.lake.iceberg.write.ParquetDataWriter;
import com.ash.lake.load.TargetRules.ParquetOut;
import com.ash.lake.load.TargetRules.Source;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Iceberg table in the Iceberg connector's layout ({@code IcebergLayout} of drishti-plugin-iceberg, as {@code lake build --format
 * iceberg} and the Iceberg loader write it): field ids 1 {@code id} (required), 2 {@code doc}, 3 {@code business_date} (required), then the
 * partition and promoted columns ({@code double} or {@code string}) and the vector columns ({@code list<double>}, optional elements); an
 * identity partition on {@code business_date}; the sort order by id; zstd; the layout's table properties. Through drishti-iceberg (the
 * native engine, no Hadoop): a path-based table under {@code <lake>/<domain>/<table>} on disk or S3, or a REST catalog
 * ({@code target.iceberg}). Each date is ONE commit: an overwrite that replaces the date's partition with the new files.
 *
 * <p>Sorted by other columns than the id ({@code sort-by}), the files say so: they carry no sort order id, and the table gets the property
 * {@code lake.layout.sort-by}, which lake maintain reads so that it does not re-sort such a day by id.
 */
final class IcebergTarget implements TableWriter {

    /** The table property naming a layout sorted by more than the id (lake maintain leaves such days in their order). */
    static final String SORT_BY = "layout.sort-by";   // a persisted name (com.ash.lake.PersistedNames)
    private static final int DEFAULT_FILE_ROWS = 250_000;
    private static final long DEFAULT_ROW_GROUP = 1024L * 1024;

    private final WriteSpec spec;
    private final Catalog catalog;
    private final String name;
    /** The table's snapshot when the load began ({@code -1}: none): a replace of a date changed since is lost. */
    private final long base;
    private Table table;
    private Schema schema;
    private Source[] sources;
    private Map<String, String> options;
    private int dateIndex;
    /** Per field of the table's spec: the index of its partition-by field in the load's partition values, -1 for the date. */
    private int[] partIndex;
    private Integer sortOrderId;
    private final List<String> leftOut = new ArrayList<>();

    IcebergTarget(WriteSpec spec) throws IOException {
        this.spec = spec;
        Map<String, String> settings = new HashMap<>(spec.storage());
        settings.putAll(spec.formatOptions());
        settings.putIfAbsent("root", spec.root());
        settings.put("domain", spec.domain());
        this.catalog = Catalogs.open(spec.runtime(), settings);
        this.name = spec.table();
        this.base = catalog.load(name).flatMap(Table::currentSnapshot).map(Snapshot::snapshotId).orElse(-1L);   // the load's base (TableWriter)
    }

    @Override
    public void open(LoadColumns c) throws IOException {
        Map<String, Type> want = new LinkedHashMap<>();
        for (String p : spec.partition()) {
            want.put(p, Type.Primitive.STRING);
        }
        for (int i = 0; i < c.promoted().size(); i++) {
            // a number column when every value seen is a number; a column never seen with a value is text (as the Iceberg loader types it)
            // a list attribute (every value a list of simple values) is list<string>, its ids assigned when the table is made or grown
            want.put(c.promoted().get(i), c.list()[i] ? new Type.ListType(0, false, Type.Primitive.STRING)
                    : c.numeric()[i] && c.seen()[i] ? Type.Primitive.DOUBLE : Type.Primitive.STRING);
        }
        if (spec.loadColumns()) {
            want.put(RowPlan.LOAD_ID, Type.Primitive.STRING);           // every row: the load that wrote it, and its file
            want.put(RowPlan.SOURCE_FILE, Type.Primitive.STRING);
        }
        List<String> vectors = spec.vectors();
        table = catalog.load(name).orElse(null);
        if (table == null) {
            List<NestedField> fields = new ArrayList<>();
            fields.add(NestedField.required(1, "id", Type.Primitive.STRING));
            fields.add(NestedField.optional(2, "doc", Type.Primitive.STRING));
            fields.add(NestedField.required(3, "business_date", Type.Primitive.DATE));
            int next = 4;
            for (Map.Entry<String, Type> e : want.entrySet()) {
                int id = next++;
                fields.add(NestedField.optional(id, e.getKey(), e.getValue() instanceof Type.ListType
                        ? new Type.ListType(next++, false, Type.Primitive.STRING) : e.getValue()));
            }
            for (String v : vectors) {
                int id = next++;
                fields.add(NestedField.optional(id, v, new Type.ListType(next++, false,
                        this.spec.layout().float32Vectors() ? Type.Primitive.FLOAT : Type.Primitive.DOUBLE)));
            }
            // the business date, then an identity field per partition-by field in the order given (docs/GUIDE.md, the read API)
            List<PartitionSpec.Field> parts = new ArrayList<>(List.of(new PartitionSpec.Field(3, 1000, "business_date", Transform.Identity.INSTANCE)));
            for (String p : this.spec.partition()) {
                NestedField src = fields.stream().filter(f -> f.name().equals(p)).findFirst().orElseThrow();
                parts.add(new PartitionSpec.Field(src.id(), 1000 + parts.size(), p, Transform.Identity.INSTANCE));
            }
            PartitionSpec spec = new PartitionSpec(0, parts);
            SortOrder order = new SortOrder(1, List.of(new SortOrder.Field(1, Transform.Identity.INSTANCE, true, true)));
            try {
                table = catalog.create(name, new Schema(0, fields), spec, order, properties());
            } catch (Catalog.TableExists e) {
                table = catalog.load(name).orElseThrow(() -> e);        // another writer created it
                com.ash.lake.spi.TableProperties.refuseReplace(table.metadata().properties(), this.spec);
            }
        } else {
            com.ash.lake.spi.TableProperties.refuseReplace(table.metadata().properties(), this.spec);
        }
        Map<String, Type> missing = new LinkedHashMap<>();
        want.forEach((col, type) -> {
            if (table.metadata().schema().field(col) == null) {
                missing.put(col, type);
            }
        });
        for (String v : vectors) {
            if (table.metadata().schema().field(v) == null) {
                missing.put(v, new Type.ListType(0, false, Type.Primitive.DOUBLE));     // ids assigned by the commit
            }
        }
        if (!missing.isEmpty()) {
            Operations.addColumns(table, missing);
            table.refresh();
        }
        if (!spec.sortedById() && !String.join(",", spec.sortBy()).equals(com.ash.lake.PersistedNames.get(table.metadata().properties(), SORT_BY, ""))) {
            Operations.setProperties(table, Map.of(com.ash.lake.PersistedNames.key(SORT_BY), String.join(",", spec.sortBy())));
            table.refresh();
        }
        schema = table.metadata().schema();
        sources = TargetRules.sources(schema, spec);
        dateIndex = dateIndex(table);
        partIndex = partIndex(table, spec.partition());
        sortOrderId = spec.sortedById() ? table.metadata().sortOrder().orderId() : 0;
        List<String> listCols = new ArrayList<>();
        for (NestedField f : schema.columns()) {
            if (f.type() instanceof Type.ListType) {
                listCols.add(f.name());
            }
        }
        options = TargetRules.writerOptions(table.metadata().properties(), spec.layout(),
                table.metadata().property("write.parquet.compression-codec", "zstd"), listCols);
    }

    /**
     * The layout's table properties (as the Iceberg loader creates a table), with the sort columns when they are more than the id, then
     * the load's {@code target.properties} (their Iceberg names: TableProperties), which win.
     */
    private Map<String, String> properties() {
        long rowGroup = spec.layout().rowGroupBytes() > 0 ? spec.layout().rowGroupBytes() : DEFAULT_ROW_GROUP;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("format-version", "2");
        p.put("write.parquet.row-group-size-bytes", String.valueOf(rowGroup));
        p.put("write.parquet.compression-codec", "zstd");
        p.put("write.parquet.dict-size-bytes", String.valueOf(256 * 1024));
        p.put("write.parquet.row-group-check-min-record-count", "10");
        p.put("write.metadata.metrics.column.doc", "none");
        p.put("write.metadata.metrics.column.id", "full");
        p.put("write.metadata.delete-after-commit.enabled", "true");
        p.put("write.metadata.previous-versions-max", "50");
        p.put("commit.retry.num-retries", "10");
        p.put(com.ash.lake.PersistedNames.key("layout.file-rows"), String.valueOf(DEFAULT_FILE_ROWS));
        if (!spec.sortedById()) {
            p.put(com.ash.lake.PersistedNames.key(SORT_BY), String.join(",", spec.sortBy()));
        }
        p.putAll(spec.properties());
        p.put("format-version", "2");                           // the layout's: sequence numbers are the loader's versions
        return p;
    }

    private static int dateIndex(Table t) {
        return com.ash.lake.iceberg.table.IcebergLayout.dateIndex(t.metadata().spec(), t.metadata().schema(), t.name());
    }

    /**
     * Where each field of the table's spec takes its value from: the load's partition-by fields must be the table's identity fields
     * beside the date, the same and in the same order (no spec evolution), as Delta's partition columns must be the table's.
     */
    private static int[] partIndex(Table t, List<String> partitionBy) {
        PartitionSpec spec = t.metadata().spec();
        int[] out = new int[spec.fields().size()];
        List<String> have = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            PartitionSpec.Field f = spec.fields().get(i);
            NestedField src = t.metadata().schema().field(f.sourceId());
            String col = src == null ? f.name() : src.name();
            out[i] = col.equals("business_date") ? -1 : partitionBy.indexOf(col);
            if (!col.equals("business_date")) {
                have.add(col);
            }
        }
        if (!have.equals(partitionBy)) {
            throw new IllegalStateException(t.name() + " is partitioned by " + have + " beside business_date, this load by " + partitionBy
                    + " (give the table's partition-by, or write a new table)");
        }
        return out;
    }

    private LocalDate dateOf(DataFile f) {
        PartitionSpec spec = table.metadata().spec(f.specId());
        NestedField date = table.metadata().schema().field("business_date");
        for (int i = 0; i < spec.fields().size(); i++) {
            if (date != null && spec.fields().get(i).sourceId() == date.id() && spec.fields().get(i).transform() instanceof Transform.Identity) {
                Object v = f.partition()[i];
                return v == null ? null : LocalDate.ofEpochDay(((Number) v).longValue());
            }
        }
        return null;
    }

    @Override
    public void begin(LocalDate date) {}

    @Override
    public FileOut newFile(LocalDate date, String[] partitionValues, String fileName) throws IOException {
        PartitionSpec spec = table.metadata().spec();
        Object[] partition = new Object[spec.fields().size()];
        StringBuilder dir = new StringBuilder(table.metadata().location().replaceAll("/+$", "")).append("/data/business_date=").append(date);
        for (int i = 0; i < partition.length; i++) {
            if (i == dateIndex) {
                partition[i] = (int) date.toEpochDay();
            } else {
                String v = partitionValues[partIndex[i]];
                partition[i] = v == null || v.isEmpty() ? null : v;                       // empty is null, as Delta's partition values
                dir.append('/').append(spec.fields().get(i).name()).append('=').append(partition[i] == null ? "null"
                        : java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String path = dir + "/" + fileName;
        ParquetDataWriter w = new ParquetDataWriter(table.io(), path, schema, options, spec.specId(), partition, sortOrderId);
        return new ParquetOut(w, schema, sources, date, true, path, partitionValues, x -> {
            try {
                return x.finish();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Override
    public long commit(LocalDate date, List<WrittenFile> files) {
        List<DataFile> add = new ArrayList<>();
        files.forEach(f -> add.add((DataFile) f.handle()));
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        ids.addAll(spec.loadIds());
        Map<String, String> summary = new LinkedHashMap<>(Map.of(com.ash.lake.PersistedNames.key("business-dates"), date.toString(), com.ash.lake.PersistedNames.key("loader"), "parallel",
                com.ash.lake.PersistedNames.key("load-ids"), String.join(",", ids)));    // an undo finds the commit of a load (and whether it replaced the date)
        Snapshot s;
        if (spec.appends()) {                     // append: the date's earlier files stay; the batch (when named) in the snapshot
            if (spec.batchId() != null) {
                summary.put(com.ash.lake.PersistedNames.key(BATCH), batchKey(date));
            }
            s = Operations.append(table, add, summary).orElseThrow(() -> new IllegalStateException("the commit of " + date + " made no snapshot"));
        } else {
            Map<String, String> slice = spec.slice();      // a shared table: only this load's slice of the date
            Map<String, String> where = new LinkedHashMap<>(slice);
            where.put("business_date", date.toString());
            java.util.Set<List<String>> parts = spec.partitionsOnly() ? TableWriter.partitionsOf(files) : null;   // overwrite-partitions: those only
            try {
                s = Operations.replacePartitions(table, f -> date.equals(dateOf(f)) && inSlice(f, slice) && (parts == null || parts.contains(partitionOf(f))),
                        add, summary, new Operations.Since(base, where))
                        .orElseThrow(() -> new IllegalStateException("the commit of " + date + " made no snapshot"));
            } catch (Operations.ValidationFailed lost) {
                throw new TableWriter.LostReplace(spec.domain() + "/" + spec.table(), date);
            }
        }
        committed.put(s.sequenceNumber(), s.snapshotId());
        return s.sequenceNumber();                // the loader's version (TableWriter.commit); the snapshot's id is nativeVersion
    }

    /** This load's commits: sequence number to snapshot id. */
    private final Map<Long, Long> committed = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public long nativeVersion(long version) {
        return committed.getOrDefault(version, version);
    }

    /** A data file's partition values after the date, in the load's partition-by order, an empty value as null (TableWriter.partitionsOf). */
    private List<String> partitionOf(DataFile f) {
        String[] out = new String[spec.partition().size()];
        for (int i = 0; i < partIndex.length; i++) {
            if (partIndex[i] >= 0) {
                Object v = f.partition()[i];
                out[partIndex[i]] = v == null || v.toString().isEmpty() ? null : v.toString();
            }
        }
        return java.util.Arrays.asList(out);
    }

    /**
     * Whether a data file holds only rows of the load's slice (TargetRules.slice; an Iceberg table is partitioned by date alone, so the slice
     * is read from the file's column bounds: lower and upper both the slice's value). A file with no bounds for the column is another's.
     */
    private boolean inSlice(DataFile f, Map<String, String> slice) {
        for (Map.Entry<String, String> e : slice.entrySet()) {
            NestedField c = table.metadata().schema().field(e.getKey());
            if (c == null || !(c.type() instanceof Type.Primitive p)) {
                return false;
            }
            byte[] lo = f.lowerBounds() == null ? null : f.lowerBounds().get(c.id());
            byte[] hi = f.upperBounds() == null ? null : f.upperBounds().get(c.id());
            if (lo == null || hi == null || !e.getValue().equals(String.valueOf(com.ash.lake.iceberg.types.Values.fromBytes(p, lo)))
                    || !e.getValue().equals(String.valueOf(com.ash.lake.iceberg.types.Values.fromBytes(p, hi)))) {
                return false;
            }
        }
        return true;
    }

    /** The snapshot summary key that names an append's batch and date. */
    static final String BATCH = "batch";   // a persisted name (com.ash.lake.PersistedNames)

    @Override
    public boolean appended(LocalDate date) {
        if (!spec.appends() || spec.batchId() == null || table == null) {
            return false;                                  // no batch named: an append is not guarded (its rows carry their _load_id)
        }
        String key = batchKey(date);
        return table.metadata().snapshots().stream().anyMatch(x -> x.summary() != null && key.equals(com.ash.lake.PersistedNames.get(x.summary(), BATCH)));
    }

    private String batchKey(LocalDate date) {
        return spec.batchId() + ":" + date;
    }

    @Override
    public Map<String, String> columnTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (NestedField f : schema.columns()) {
            out.put(f.name(), f.type() instanceof Type.ListType l ? (Type.Primitive.STRING.equals(l.element()) ? "list" : "vector")
                    : f.type().toString().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    @Override
    public List<String> leftOut() {
        return leftOut;
    }

    @Override
    public void close() {
        catalog.close();
    }
}
