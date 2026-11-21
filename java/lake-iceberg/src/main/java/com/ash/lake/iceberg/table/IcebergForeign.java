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

import com.ash.lake.io.read.ForeignSource;
import com.ash.lake.io.read.ShapeField;
import com.ash.lake.iceberg.Table;
import com.ash.lake.iceberg.catalog.Catalog;
import com.ash.lake.iceberg.expr.Expr;
import com.ash.lake.iceberg.meta.PartitionSpec;
import com.ash.lake.iceberg.meta.Snapshot;
import com.ash.lake.iceberg.meta.Transform;
import com.ash.lake.iceberg.read.TaskReader;
import com.ash.lake.iceberg.scan.FileTask;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.iceberg.types.Type;
import com.ash.lake.iceberg.types.Values;
import com.ash.lake.read.DataFile;
import com.ash.lake.read.FileFormat;
import com.ash.lake.read.TableIdentity;
import com.ash.lake.spi.AsOf;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * A table other engines wrote (Spark, Flink, Trino, pyiceberg), in its own layout, as the shape rule reads it (READ_API.md, RA-S8): the
 * product's Iceberg planning (partitions, bounds and delete files), its reader for the columns asked (deletes applied, row groups pruned
 * by an id's values).
 */
final class IcebergForeign implements ForeignSource {

    private final Catalog catalog;
    private final String table;
    private final ExecutorService pool;
    private volatile Table loaded;

    IcebergForeign(Catalog catalog, String table, ExecutorService pool) {
        this.catalog = catalog;
        this.table = table;
        this.pool = pool;
    }

    private Optional<Table> table() throws IOException {
        Table t = loaded;
        if (t == null) {
            Optional<Table> o = catalog.load(table);
            if (o.isEmpty()) {
                return o;
            }
            loaded = o.get();
            return o;
        }
        t.refresh();
        return Optional.of(t);
    }

    @Override
    public TableIdentity identity() throws IOException {
        Optional<Table> t = table();
        if (t.isPresent() && t.get().metadata().uuid() != null) {
            return new TableIdentity("iceberg", t.get().metadata().uuid(), t.get().metadata().location());
        }
        return new TableIdentity("location", catalog.describe() + "/" + table, catalog.describe() + "/" + table);
    }

    @Override
    public Optional<ForeignSource.Version> version(AsOf asOf) throws IOException {
        Optional<Table> o = table();
        if (o.isEmpty()) {
            return Optional.empty();
        }
        Table t = o.get();
        Optional<Snapshot> s;
        if (asOf.latest()) {
            s = t.currentSnapshot();
        } else if (asOf.version() != null) {
            s = t.metadata().snapshot(asOf.version());
            if (s.isEmpty()) {
                throw new IOException(asOf.missing(table));
            }
        } else {
            s = t.metadata().snapshotAsOf(asOf.time().toEpochMilli());      // empty: a time before the first snapshot
        }
        if (s.isEmpty()) {
            return Optional.empty();                  // created, nothing written yet
        }
        return Optional.of(new Version(t, s.get()));
    }

    static ShapeField field(String name, Type t) {
        return switch (t) {
            case Type.Primitive p -> new ShapeField(name, switch (p.id()) {
                case BOOLEAN -> ShapeField.Kind.BOOLEAN;
                case INT -> ShapeField.Kind.INT;
                case LONG -> ShapeField.Kind.LONG;
                case FLOAT -> ShapeField.Kind.FLOAT;
                case DOUBLE -> ShapeField.Kind.DOUBLE;
                case DECIMAL -> ShapeField.Kind.DECIMAL;
                case DATE -> ShapeField.Kind.DATE;
                case TIME -> ShapeField.Kind.TIME;
                case TIMESTAMP -> ShapeField.Kind.TIMESTAMP;
                case TIMESTAMPTZ -> ShapeField.Kind.TIMESTAMPTZ;
                case UUID -> ShapeField.Kind.UUID;
                case FIXED, BINARY -> ShapeField.Kind.BINARY;
                default -> ShapeField.Kind.STRING;
            }, p.scale(), List.of());
            case Type.Struct s -> new ShapeField(name, ShapeField.Kind.STRUCT, 0, s.fields().stream().map(f -> field(f.name(), f.type())).toList());
            case Type.ListType l -> new ShapeField(name, ShapeField.Kind.LIST, 0, List.of(field("element", l.element())));
            case Type.MapType m -> new ShapeField(name, ShapeField.Kind.MAP, 0, List.of(field("key", m.key()), field("value", m.value())));
        };
    }

    /** One snapshot. */
    private final class Version implements ForeignSource.Version {
        private final Table t;
        private final Snapshot s;
        private final Schema schema;
        private final List<ShapeField> columns = new ArrayList<>();

        Version(Table t, Snapshot s) {
            this.t = t;
            this.s = s;
            this.schema = t.metadata().schemaFor(s);
            schema.columns().forEach(f -> columns.add(field(f.name(), f.type())));
        }

        @Override
        public long version() {
            return s.snapshotId();
        }

        @Override
        public Instant committed() {
            return Instant.ofEpochMilli(s.timestampMs());
        }

        @Override
        public long sequence() {
            return s.sequenceNumber();
        }

        @Override
        public List<ShapeField> columns() {
            return columns;
        }

        @Override
        public List<String> identifierColumns() {
            List<String> out = new ArrayList<>();
            for (int id : schema.identifierFieldIds()) {
                NestedField f = schema.field(id);
                if (f != null && schema.parentOf(id) == -1) {
                    out.add(f.name());
                }
            }
            return out;
        }

        @Override
        public List<String> dayPartitions() {
            List<String> out = new ArrayList<>();
            for (PartitionSpec.Field f : t.metadata().spec().fields()) {
                NestedField src = schema.field(f.sourceId());
                if (src != null && schema.parentOf(src.id()) == -1 && day(f, src)) {
                    out.add(src.name());
                }
            }
            return out;
        }

        private boolean day(PartitionSpec.Field f, NestedField src) {
            Type.Id id = src.type().id();
            return f.transform() instanceof Transform.Identity && id == Type.Id.DATE
                    || f.transform() instanceof Transform.Time tt && tt.unit() == Transform.TimeUnit.DAY
                            && (id == Type.Id.DATE || id == Type.Id.TIMESTAMP || id == Type.Id.TIMESTAMPTZ);
        }

        @Override
        public List<ForeignSource.File> files() {
            List<ForeignSource.File> out = new ArrayList<>();
            for (FileTask task : t.plan(s, Expr.TRUE, pool)) {
                var f = task.file();
                Map<String, Integer> days = new LinkedHashMap<>();
                Map<String, String> partition = new LinkedHashMap<>();
                PartitionSpec spec = task.spec();
                for (int i = 0; i < spec.fields().size(); i++) {
                    PartitionSpec.Field pf = spec.fields().get(i);
                    Object v = f.partition() != null && i < f.partition().length ? f.partition()[i] : null;
                    NestedField src = schema.field(pf.sourceId());
                    partition.put(pf.name(), v == null ? null : String.valueOf(v));
                    if (v != null && src != null && day(pf, src)) {
                        days.put(src.name(), ((Number) v).intValue());
                    }
                }
                partition.values().removeIf(java.util.Objects::isNull);
                Map<String, Object[]> bounds = new LinkedHashMap<>();
                for (NestedField c : schema.columns()) {
                    if (c.type() instanceof Type.Primitive p && f.lowerBounds() != null && f.upperBounds() != null) {
                        byte[] lo = f.lowerBounds().get(c.id());
                        byte[] hi = f.upperBounds().get(c.id());
                        if (lo != null && hi != null) {
                            bounds.put(c.name(), new Object[] {Values.fromBytes(p, lo), Values.fromBytes(p, hi)});
                        }
                    }
                }
                DataFile api = new DataFile(f.path(), f.fileSizeInBytes(), FileFormat.PARQUET, f.recordCount(), task.hasDeletes(), partition, Map.of());
                out.add(new ForeignSource.File(api, days, bounds, task));
            }
            return out;
        }

        @Override
        public void read(ForeignSource.File file, int[] cols, Map<Integer, Object> equal, ForeignSource.Rows sink) {
            List<Integer> ids = new ArrayList<>();
            for (int c : cols) {
                ids.add(schema.columns().get(c).id());
            }
            List<Expr> preds = new ArrayList<>();
            equal.forEach((c, v) -> {
                NestedField f = schema.columns().get(c);
                Type.Id id = f.type().id();
                if (id == Type.Id.STRING || id == Type.Id.INT || id == Type.Id.LONG || id == Type.Id.DATE) {
                    preds.add(Expr.eq(f.id(), v));
                }
            });
            Expr filter = preds.isEmpty() ? Expr.TRUE : Expr.and(preds.toArray(new Expr[0]));
            try (TaskReader r = t.rows((FileTask) file.handle(), schema, ids, filter, filter != Expr.TRUE)) {
                while (r.hasNext()) {
                    sink.row(r.next());
                }
            }
        }
    }
}
