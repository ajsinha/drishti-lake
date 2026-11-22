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

import com.ash.lake.iceberg.Table;
import com.ash.lake.iceberg.catalog.Catalog;
import com.ash.lake.iceberg.manifest.DataFile;
import com.ash.lake.iceberg.meta.PartitionSpec;
import com.ash.lake.iceberg.meta.SortOrder;
import com.ash.lake.iceberg.meta.Transform;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.iceberg.types.Type;
import com.ash.lake.iceberg.write.Operations;
import com.ash.lake.iceberg.write.ParquetDataWriter;
import com.ash.lake.iceberg.table.IcebergLayout.Row;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link IcebergLayout} written through Apache Iceberg with no Hadoop: the same schema (field ids 1
 * {@code id}, 2 {@code doc}, 3 {@code business_date}, then the promoted columns), the same identity partition on
 * {@code business_date}, sort order, table properties and files of {@code file-rows} rows, so tables move between the
 * engines unchanged. Stateless.
 */
final class NativeLayout {

    private NativeLayout() {}

    /** The layout's schema (as {@link IcebergLayout#schema}). */
    static Schema schema(Map<String, Boolean> promoted) {
        return schema(promoted, Set.of());
    }

    /** The layout's schema with vector columns ({@code list<double>}, optional elements), as {@link IcebergLayout#schema(Map, Set)}. */
    static Schema schema(Map<String, Boolean> promoted, Set<String> vectors) {
        return schema(promoted, vectors, Set.of());
    }

    /** With list attributes ({@code list<string>}, optional elements: promoted lists of simple values). */
    static Schema schema(Map<String, Boolean> promoted, Set<String> vectors, Set<String> lists) {
        List<NestedField> fields = new ArrayList<>();
        fields.add(NestedField.required(1, IcebergLayout.ID, Type.Primitive.STRING));
        fields.add(NestedField.optional(2, IcebergLayout.DOC, Type.Primitive.STRING));
        fields.add(NestedField.required(3, IcebergLayout.DATE, Type.Primitive.DATE));
        int next = 4;
        for (Map.Entry<String, Boolean> e : promoted.entrySet()) {
            if (!vectors.contains(e.getKey())) {
                fields.add(NestedField.optional(next++, IcebergLayout.column(e.getKey()), e.getValue() ? Type.Primitive.DOUBLE : Type.Primitive.STRING));
            }
        }
        for (String v : vectors) {
            int id = next++;
            fields.add(NestedField.optional(id, IcebergLayout.column(v), new Type.ListType(next++, false, Type.Primitive.DOUBLE)));
        }
        for (String l : lists) {
            int id = next++;
            fields.add(NestedField.optional(id, IcebergLayout.column(l), new Type.ListType(next++, false, Type.Primitive.STRING)));
        }
        return new Schema(0, fields);
    }

    /** The layout's table properties (as {@link IcebergLayout#properties}), with the format version for a new table. */
    static Map<String, String> properties(long rowGroupBytes, int fileRows) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("format-version", "2");
        p.put("write.parquet.row-group-size-bytes", String.valueOf(rowGroupBytes));
        p.put("write.parquet.compression-codec", "zstd");
        p.put("write.parquet.dict-size-bytes", String.valueOf(256 * 1024));
        p.put("write.parquet.row-group-check-min-record-count", "10");
        p.put("write.metadata.metrics.column." + IcebergLayout.DOC, "none");
        p.put("write.metadata.metrics.column." + IcebergLayout.ID, "full");
        p.put("write.metadata.delete-after-commit.enabled", "true");
        p.put("write.metadata.previous-versions-max", "50");
        p.put("commit.retry.num-retries", "10");
        p.put(com.ash.lake.PersistedNames.key(IcebergLayout.FILE_ROWS), String.valueOf(fileRows));
        return p;
    }

    /** The kind's table, created in the layout if it does not exist; promoted paths it lacks are added as columns. */
    static Table open(Catalog catalog, String kind, Map<String, Boolean> promoted, long rowGroupBytes, int fileRows) {
        return open(catalog, kind, promoted, Set.of(), rowGroupBytes, fileRows);
    }

    /** {@link #open(Catalog, String, Map, long, int)} with vector columns ({@code list<double>}), added when missing too. */
    static Table open(Catalog catalog, String kind, Map<String, Boolean> promoted, Set<String> vectors, long rowGroupBytes, int fileRows) {
        return open(catalog, kind, promoted, vectors, Set.of(), rowGroupBytes, fileRows);
    }

    /** With list attributes ({@code list<string>}), added when missing too. */
    static Table open(Catalog catalog, String kind, Map<String, Boolean> promoted, Set<String> vectors, Set<String> lists, long rowGroupBytes,
            int fileRows) {
        try {
            Table t = catalog.load(kind).orElse(null);
            if (t == null) {
                Schema s = schema(promoted, vectors, lists);
                PartitionSpec spec = new PartitionSpec(0, List.of(new PartitionSpec.Field(3, 1000, IcebergLayout.DATE, Transform.Identity.INSTANCE)));
                SortOrder order = new SortOrder(1, List.of(new SortOrder.Field(1, Transform.Identity.INSTANCE, true, true)));
                try {
                    return catalog.create(kind, s, spec, order, properties(rowGroupBytes, fileRows));
                } catch (Catalog.TableExists e) {
                    t = catalog.load(kind).orElseThrow(() -> e);    // a concurrent writer created it
                }
            }
            Map<String, Type> missing = new LinkedHashMap<>();
            for (Map.Entry<String, Boolean> e : promoted.entrySet()) {
                if (!vectors.contains(e.getKey()) && t.metadata().schema().field(IcebergLayout.column(e.getKey())) == null) {
                    missing.put(IcebergLayout.column(e.getKey()), e.getValue() ? Type.Primitive.DOUBLE : Type.Primitive.STRING);
                }
            }
            for (String v : vectors) {
                if (t.metadata().schema().field(IcebergLayout.column(v)) == null) {
                    missing.put(IcebergLayout.column(v), new Type.ListType(0, false, Type.Primitive.DOUBLE));   // ids assigned by the commit
                }
            }
            for (String l : lists) {
                if (t.metadata().schema().field(IcebergLayout.column(l)) == null) {
                    missing.put(IcebergLayout.column(l), new Type.ListType(0, false, Type.Primitive.STRING));
                }
            }
            if (!missing.isEmpty()) {
                Operations.addColumns(t, missing);
            }
            return t;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The table's file-rows (the layout's), or the default. */
    static int fileRows(Table t) {
        return Integer.parseInt(com.ash.lake.PersistedNames.get(t.metadata().properties(), IcebergLayout.FILE_ROWS, String.valueOf(IcebergLayout.DEFAULT_FILE_ROWS)));
    }

    /** The index of the {@code business_date} identity field in the table's current spec. */
    static int dateIndex(PartitionSpec spec, Schema schema) {
        return IcebergLayout.dateIndex(spec, schema, "the table");
    }

    /** The business date of a data or delete file (null for another spec without the date). */
    static LocalDate dateOf(Table t, DataFile f) {
        PartitionSpec spec = t.metadata().spec(f.specId());
        NestedField date = t.metadata().schema().field(IcebergLayout.DATE);
        for (int i = 0; i < spec.fields().size(); i++) {
            if (date != null && spec.fields().get(i).sourceId() == date.id() && spec.fields().get(i).transform() instanceof Transform.Identity) {
                Object v = f.partition()[i];
                return v == null ? null : LocalDate.ofEpochDay(((Number) v).longValue());
            }
        }
        return null;
    }

    /**
     * Writes one business day's rows, which must come sorted by id, into files of {@code fileRows} rows each (each
     * file its own id range). Nothing is committed: {@link #commitDays} makes the files the day's data.
     */
    static List<DataFile> writeDay(Table table, LocalDate date, Iterator<Row> sorted, int fileRows) {
        return writeDay(table, date, sorted, fileRows, table.metadata().sortOrder().orderId());
    }

    /** As above, the files' sort order id given ({@code 0}: unsorted, for rows in another order than the table's sort order). */
    static List<DataFile> writeDay(Table table, LocalDate date, Iterator<Row> sorted, int fileRows, int sortOrder) {
        return writeDay(table, date, sorted, fileRows, sortOrder, false);
    }

    /**
     * As above; with {@code older} the files are files of older copies: their names hold {@link com.ash.lake.rows.LoadOrder#OLDER_NAME}
     * ({@code <date>-older-<n>-<uuid>.parquet}), so readers put them before the commit's other files, and in the order written.
     */
    static List<DataFile> writeDay(Table table, LocalDate date, Iterator<Row> sorted, int fileRows, int sortOrder, boolean older) {
        Schema schema = table.metadata().schema();
        PartitionSpec spec = table.metadata().spec();
        int dateIndex = dateIndex(spec, schema);
        List<NestedField> cols = schema.columns();
        Map<String, String> props = table.metadata().properties();
        String base = table.metadata().location().replaceAll("/+$", "") + "/data/" + IcebergLayout.DATE + "=" + date;
        // one open file per partition tuple (the date, then the partition-by fields' values), each its own run of the sorted rows
        Map<List<Object>, ParquetDataWriter> open = new LinkedHashMap<>();
        Map<List<Object>, long[]> counts = new java.util.HashMap<>();
        List<DataFile> files = new ArrayList<>();
        try {
            while (sorted.hasNext()) {
                Row row = sorted.next();
                Object[] values = new Object[cols.size()];
                for (int i = 0; i < cols.size(); i++) {
                    NestedField f = cols.get(i);
                    values[i] = switch (f.name()) {
                        case IcebergLayout.ID -> row.id();
                        case IcebergLayout.DOC -> row.doc();
                        case IcebergLayout.DATE -> (int) date.toEpochDay();
                        default -> {
                            String path = f.name().replace("__", ".");
                            yield row.columns().containsKey(path) ? value(f.type(), row.columns().get(path)) : null;
                        }
                    };
                }
                Object[] partition = new Object[spec.fields().size()];
                StringBuilder dir = new StringBuilder(base);
                for (int i = 0; i < partition.length; i++) {
                    if (i == dateIndex) {
                        partition[i] = (int) date.toEpochDay();
                    } else {
                        int at = cols.indexOf(schema.field(spec.fields().get(i).sourceId()));
                        Object v = at < 0 ? null : values[at];
                        partition[i] = v == null || v.toString().isEmpty() ? null : v.toString();
                        if (at >= 0) {
                            values[at] = partition[i];                     // empty is null, in the tuple and the column alike
                        }
                        dir.append('/').append(spec.fields().get(i).name()).append('=').append(partition[i] == null ? "null"
                                : java.net.URLEncoder.encode(partition[i].toString(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                List<Object> key = Arrays.asList(partition);
                ParquetDataWriter w = open.get(key);
                if (w == null) {
                    String name = date + (older ? com.ash.lake.rows.LoadOrder.OLDER_NAME : "-") + String.format("%05d", files.size() + open.size())
                            + "-" + UUID.randomUUID() + ".parquet";
                    w = new ParquetDataWriter(table.io(), dir + "/" + name, schema, props, spec.specId(), partition, sortOrder);
                    open.put(key, w);
                }
                w.write(values);
                if (++counts.computeIfAbsent(key, k -> new long[1])[0] % fileRows == 0) {
                    files.add(open.remove(key).finish());
                }
            }
            for (ParquetDataWriter w : open.values()) {
                files.add(w.finish());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** A promoted value as the column's type, as {@link IcebergLayout#value} does for the library. */
    static Object value(Type type, Object v) {
        if (v == null) {
            return null;
        }
        if (type instanceof Type.ListType l && Type.Primitive.STRING.equals(l.element())) {
            List<String> texts = v instanceof SortedRuns.Texts x ? x.values() : com.ash.lake.rows.ListValues.of(v);
            return texts != null ? texts : List.of(String.valueOf(v));      // a list attribute; a single value is a list of one
        }
        if (type.id() == Type.Id.LIST) {
            return IcebergLayout.vector(v);
        }
        if (type.id() == Type.Id.DOUBLE) {
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf(d.longValue());                 // 42.0 promoted into a text column reads "42"
        }
        return String.valueOf(v);
    }

    /** Makes each day's files that day's data, in one commit that replaces the days listed (loading a day again is idempotent). */
    static void commitDays(Table table, Map<LocalDate, List<DataFile>> days) {
        if (days.isEmpty()) {
            return;
        }
        Set<LocalDate> dates = new HashSet<>(days.keySet());
        List<DataFile> files = new ArrayList<>();
        days.values().forEach(files::addAll);
        String label = days.size() == 1 ? days.keySet().iterator().next().toString() : days.size() + " days";
        Operations.replacePartitions(table, f -> dates.contains(dateOf(table, f)), files, Map.of(com.ash.lake.PersistedNames.key("business-dates"), label));
    }
}
