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

import com.ash.lake.rows.VectorReads;
import com.ash.lake.rows.VectorSink;
import com.ash.lake.iceberg.table.KindTable.Day;
import com.ash.lake.iceberg.table.KindTable.Layout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * What a day of an Iceberg table gives in one pass, on top of {@link KindTable#scan} (so both engines read it alike): the rows of a
 * vector column ({@code layout.<kind>.vectors}, a {@code list<double>} such as a trade's 509-value P&amp;L vector, VAR_GUIDE.md section
 * 29.3) with some text columns, one entity's vectors put back into its document in place of their {@code {"$vector": "PNL"}} markers,
 * and every document of the day file by file. The same answers as the Delta connector's: a null element is NaN (never 0), a null
 * vector has length -1, a number in a text column keeps its digits ({@code QUANTITY} 1500.0 reads "1500"). Stateless.
 */
final class IcebergVectors {

    private IcebergVectors() {
    }

    /**
     * Streams the day's rows with the vector {@code column} and the {@code textColumns} (physical names) to {@code sink}, file by file
     * in file order: one buffer a file, reused for every row, so a date of millions of vectors is never held here; several files decode
     * at once when {@code sink} came from {@link VectorReads#parallel}.
     */
    static void vectors(KindTable t, Layout l, Day day, String column, List<String> textColumns, VectorSink sink) {
        vectors(t, l, day, column, textColumns, f -> true, sink);
    }

    /** As above, only the files {@code keep} accepts by index (a VaR shard's read skips those holding none of its keys). */
    static void vectors(KindTable t, Layout l, Day day, String column, List<String> textColumns, java.util.function.IntPredicate keep,
            VectorSink sink) {
        List<String> cols = new ArrayList<>(List.of(IcebergLayout.ID, column));
        cols.addAll(textColumns);
        int[] files = java.util.stream.IntStream.range(0, day.files().size()).filter(keep).toArray();
        Map<String, Integer> shadow = t.shadow(l, day);
        List<int[]> pieces = pieces(t, l, day, files, sink);
        int[] order = java.util.stream.IntStream.range(0, pieces.size()).toArray();
        // piece by piece in file order; several pieces decode at once when the reader asked for it (VectorReads, VAR_GUIDE.md 27a.6)
        VectorReads.inFileOrder(order, sink, (i, to) -> {
            int[] piece = pieces.get(i);
            int f = piece[0];
            double[][] buf = {new double[512]};
            String[] texts = new String[textColumns.size()];
            t.scanGroups(l, day, f, piece[1], piece[2], cols, r -> {
                for (int c = 0; c < texts.length; c++) {
                    texts[c] = text(r[c + 2]);
                }
                String id = r[0] == null ? "" : r[0].toString();
                if (KindTable.seen(shadow, id, f)) {                    // not an older load's copy of an id a newer load wrote
                    to.row(id, buf[0], copy(r[1], buf), texts);
                }
            });
        });
    }

    /**
     * The files cut into pieces {file, from group, to group} for {@code sink}'s decoding threads, as Delta's read cuts them (READ_API.md
     * defect I2): whole files on one thread; else each file's row groups in runs of about {@link VectorReads#partBytes} uncompressed
     * bytes (at least one group), so a date of a few large files still decodes on every thread. A file whose footer cannot be read is
     * one piece: its read says what is wrong.
     */
    static List<int[]> pieces(KindTable t, Layout l, Day day, int[] files, VectorSink sink) {
        List<int[]> out = new ArrayList<>();
        boolean cut = VectorReads.threads(sink) > 1;
        long part = cut ? VectorReads.partBytes(sink) : Long.MAX_VALUE;
        for (int f : files) {
            long[] groups = cut ? t.rowGroups(l, day, f) : new long[0];
            if (groups.length <= 1 || Arrays.stream(groups).sum() <= part) {
                out.add(new int[] {f, 0, Integer.MAX_VALUE});
                continue;
            }
            int from = 0;
            long bytes = 0;
            for (int g = 0; g < groups.length; g++) {
                if (g > from && bytes + groups[g] > part) {
                    out.add(new int[] {f, from, g});
                    from = g;
                    bytes = 0;
                }
                bytes += groups[g];
            }
            out.add(new int[] {f, from, groups.length});
        }
        return out;
    }

    /** One entity's vector columns in one file of the day (its document's markers are put back with them); empty when none. */
    static Map<String, double[]> vectorsOf(KindTable t, Layout l, Day day, int file, String id, List<String> columns) {
        if (file < 0 || file >= day.files().size() || columns.isEmpty()) {
            return Map.of();
        }
        List<String> cols = new ArrayList<>(List.of(IcebergLayout.ID));
        cols.addAll(columns);
        Map<String, double[]> out = new LinkedHashMap<>();
        t.scan(l, day, file, cols, id, r -> {
            if (!out.isEmpty() || !id.equals(String.valueOf(r[0]))) {
                return;                                          // the row group's other rows
            }
            for (int c = 0; c < columns.size(); c++) {
                double[][] one = {new double[0]};
                int len = copy(r[c + 1], one);
                if (len >= 0) {
                    out.put(columns.get(c), Arrays.copyOf(one[0], len));
                }
            }
        });
        return out;
    }

    /** Every document of the day, file by file in file order (deletes applied): id and JSON text. */
    static void documents(KindTable t, Layout l, Day day, BiConsumer<String, String> sink) {
        Map<String, Integer> shadow = t.shadow(l, day);
        for (int f = 0; f < day.files().size(); f++) {
            int file = f;
            t.scan(l, day, f, List.of(IcebergLayout.ID, IcebergLayout.DOC), null, r -> {
                if (r[0] != null && r[1] != null && KindTable.seen(shadow, r[0].toString(), file)) {
                    sink.accept(r[0].toString(), r[1].toString());
                }
            });
        }
    }

    /**
     * A list cell's values into {@code buf[0]} (grown when short): doubles, or floats widened exactly; a null element is NaN. Returns the
     * length, or -1 for a null cell (an entity without a vector: counted by the caller, never taken for zeros).
     */
    static int copy(Object cell, double[][] buf) {
        if (cell == null) {
            return -1;
        }
        if (!(cell instanceof List<?> list)) {
            throw new IllegalStateException("a vector column holds " + cell.getClass().getSimpleName() + ", not a list of numbers");
        }
        int len = list.size();
        if (buf[0].length < len) {
            buf[0] = new double[Math.max(len, buf[0].length * 2)];
        }
        double[] b = buf[0];
        for (int i = 0; i < len; i++) {
            Object v = list.get(i);
            b[i] = v == null ? Double.NaN : ((Number) v).doubleValue();
        }
        return len;
    }

    /** A cell as text, as the Delta connector gives it: a number keeps its digits (1500, never 1500.0). */
    static String text(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Double d) {
            return com.ash.lake.rows.IdText.of(d);
        }
        if (v instanceof Float f) {
            return com.ash.lake.rows.IdText.of(f);
        }
        if (v instanceof BigDecimal d) {
            return d.stripTrailingZeros().toPlainString();
        }
        if (v instanceof java.util.Collection<?> l) {
            return com.ash.lake.rows.ListValues.json(l);           // a list attribute (TRADE_IDS): its JSON text
        }
        return v.toString();
    }
}
