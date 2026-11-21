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

import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.iceberg.types.Type;
import com.ash.lake.iceberg.write.ParquetDataWriter;
import com.ash.lake.spi.LoadRow;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.TableWriter.WrittenFile;
import com.ash.lake.spi.WriteSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rules the loader's built-in writers share ({@link DeltaTarget}, {@link IcebergTarget}): a shared table's slice, the value a file
 * column takes from a {@link LoadRow}, the Parquet writer's options, and a {@link TableWriter.FileOut} over the Parquet writer.
 */
final class TargetRules {

    private TargetRules() {
    }

    /**
     * The slice a load writes in a shared table (DATA_LAKE_GUIDE.md section 5): the connector's {@code set:} constants whose column is one
     * of {@code columns} (Delta: a partition column, {@code SOURCE_SYSTEM} of {@code trading.trades_shared}), by column. A replace takes out
     * only the date's files of this slice, so separate loads of several source systems into one table never replace each other. Empty: the
     * whole date is the load's.
     */
    static Map<String, String> slice(Map<String, String> set, java.util.Collection<String> columns) {
        Map<String, String> out = new LinkedHashMap<>();
        if (set != null && columns != null) {
            set.forEach((k, v) -> {
                String c = RowPlan.column(k);
                if (v != null && columns.contains(c)) {
                    out.put(c, v);
                }
            });
        }
        return out;
    }

    /** Whether a file with these partition values (by column) is in the slice. */
    static boolean inSlice(Map<String, String> values, Map<String, String> slice) {
        for (Map.Entry<String, String> e : slice.entrySet()) {
            if (!e.getValue().equals(values.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ the shared column rules

    /** Where a file column's value comes from: the id, the document, the date, a promoted value, a partition value or a vector. */
    record Source(char kind, int index, Type type) {}

    /**
     * The value of {@code src} for a row in the writer's internal form. A promoted value into a text column is lake build's text for it
     * ({@code icebergRules}: the Iceberg loader's, where a whole number reads {@code 42}); into a number column, a number (text that
     * reads as one, else null).
     */
    static Object value(LoadRow r, Source src, int epochDay, boolean icebergRules) {
        return switch (src.kind()) {
            case 'i' -> r.id();
            case 'd' -> r.doc();
            case 't' -> Integer.valueOf(epochDay);
            case 'p' -> {
                String v = r.partition(src.index());
                if (v == null) {
                    yield null;
                }
                yield v.getBytes(StandardCharsets.UTF_8);
            }
            case 'v' -> r.vector(src.index());
            case 'c' -> promoted(r, src.index(), src.type(), icebergRules);
            case 'l' -> r.loadId();
            case 's' -> r.sourceFile();
            default -> null;
        };
    }


    private static Object promoted(LoadRow r, int i, Type type, boolean icebergRules) {
        byte tag = r.tag(i);
        if (tag == LoadRow.NULL) {
            return null;
        }
        if (type.id() == Type.Id.DOUBLE) {
            if (tag == LoadRow.NUM) {
                return r.num(i);
            }
            try {
                return Double.parseDouble(new String(r.text(i), StandardCharsets.UTF_8).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (type.id() == Type.Id.LIST) {
            return listValue(r, i, tag, (Type.ListType) type);
        }
        if (tag == LoadRow.NUM) {
            if (icebergRules) {
                double d = r.num(i);
                return (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d))
                        .getBytes(StandardCharsets.UTF_8);
            }
            return r.numText(i);
        }
        return r.text(i);
    }

    /**
     * A promoted value into an {@code array<string>} column: a list of simple values as its texts ({@link ListValues}: a number in its
     * plain text), a single value as a list of one. A list of numbers into a vector column ({@code list<double>}) the load keeps as a
     * promoted field: none, as before.
     */
    private static Object listValue(LoadRow r, int i, byte tag, Type.ListType type) {
        if (!(type.element() instanceof Type.Primitive p) || p.id() != Type.Id.STRING) {
            return null;
        }
        if (tag == LoadRow.NUM) {
            return List.of(r.numText(i));
        }
        String text = new String(r.text(i), StandardCharsets.UTF_8);
        if (tag == LoadRow.STR) {
            return List.of(r.text(i));
        }
        List<String> values = com.ash.lake.rows.ListValues.of(text);
        if (values == null) {
            return List.of(r.text(i));                                  // an object or a nested list: its JSON text, the one element
        }
        List<byte[]> out = new ArrayList<>(values.size());
        for (String v : values) {
            out.add(v.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    /** The Parquet writer's options for a load: the codec, row groups, and the vector columns' encoding. */
    static Map<String, String> writerOptions(Map<String, String> base, WriteSpec.FileLayout layout, String defaultCodec, List<String> vectorColumns) {
        Map<String, String> p = new LinkedHashMap<>(base);
        String codec = layout.compression().equals("auto") ? defaultCodec : layout.compression();
        p.put("write.parquet.compression-codec", codec.toLowerCase(Locale.ROOT));
        if (layout.rowGroupBytes() > 0) {
            p.put("write.parquet.row-group-size-bytes", String.valueOf(layout.rowGroupBytes()));
        }
        List<String> paths = new ArrayList<>();
        for (String v : vectorColumns) {
            paths.add(v + ".list.element");
            if (!layout.vectorCompression().equals(codec)) {
                p.put("lake.parquet.codec." + v + ".list.element", layout.vectorCompression());
            }
        }
        if (!paths.isEmpty()) {
            if (layout.byteStreamSplit()) {
                p.put("lake.parquet.byte-stream-split", String.join(",", paths));
            }
            p.put("lake.parquet.no-dictionary", String.join(",", paths));
            p.put("lake.parquet.no-statistics", String.join(",", paths));
        }
        return p;
    }

    /** A {@link TableWriter.FileOut} over a {@link ParquetDataWriter} whose columns come from {@code sources}. */
    static final class ParquetOut implements TableWriter.FileOut {
        private final ParquetDataWriter w;
        private final Source[] sources;
        private final int epochDay;
        private final boolean icebergRules;
        private final String path;
        private final String[] partition;
        private final Object[] row;
        private long rows;
        private byte[] firstKey;
        private byte[] lastKey;
        private final java.util.function.Function<ParquetDataWriter, Object> describe;

        ParquetOut(ParquetDataWriter w, Schema schema, Source[] sources, LocalDate date, boolean icebergRules, String path, String[] partition,
                java.util.function.Function<ParquetDataWriter, Object> describe) {
            this.w = w;
            this.sources = sources;
            this.epochDay = (int) date.toEpochDay();
            this.icebergRules = icebergRules;
            this.path = path;
            this.partition = partition;
            this.row = new Object[schema.columns().size()];
            this.describe = describe;
        }

        @Override
        public void write(LoadRow r) throws IOException {
            for (int i = 0; i < sources.length; i++) {
                row[i] = sources[i] == null ? null : value(r, sources[i], epochDay, icebergRules);
            }
            w.write(row);
            byte[] k = r.key();
            if (firstKey == null) {
                firstKey = k;
            }
            lastKey = k;
            rows++;
        }

        @Override
        public long length() {
            return w.length();
        }

        @Override
        public WrittenFile finish() throws IOException {
            Object handle = describe.apply(w);
            long bytes = handle instanceof com.ash.lake.iceberg.manifest.DataFile f ? f.fileSizeInBytes() : -1;
            return new WrittenFile(path, rows, bytes, handle, partition, RowPlan.components(firstKey, 0, firstKey.length),
                    RowPlan.components(lastKey, 0, lastKey.length));
        }
    }

    /** The file-column sources for {@code schema}'s columns by name. */
    static Source[] sources(Schema schema, WriteSpec spec) {
        List<NestedField> cols = schema.columns();
        Source[] out = new Source[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            NestedField f = cols.get(i);
            out[i] = source(f.name(), f.type(), spec);
        }
        return out;
    }

    private static Source source(String name, Type type, WriteSpec spec) {
        switch (name) {
            case "id":
                return new Source('i', 0, type);
            case "doc":
                return new Source('d', 0, type);
            case "business_date":
                return new Source('t', 0, type);
            case RowPlan.LOAD_ID:
                return new Source('l', 0, type);
            case RowPlan.SOURCE_FILE:
                return new Source('s', 0, type);
            default:
                break;
        }
        for (int k = 0; k < spec.vectors().size(); k++) {
            if (spec.vectors().get(k).equals(name) && type.id() == Type.Id.LIST) {
                return new Source('v', k, type);
            }
        }
        for (int j = 0; j < spec.partition().size(); j++) {
            if (spec.partition().get(j).equals(name)) {
                return new Source('p', j, type);
            }
        }
        for (int c = 0; c < spec.promoted().size(); c++) {
            if (spec.promoted().get(c).equals(name)) {
                return new Source('c', c, type);
            }
        }
        return null;                                                    // a column of the table the load does not have: null
    }
}
