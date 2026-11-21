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
package com.ash.lake.files;

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
import com.ash.lake.rows.IdText;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.Expr;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One version of a files table behind the read API: each date is one content file of the log (immutable once committed), read line by
 * line; "the newest load wins" in the one load order (LoadOrder; an append adds lines after the earlier ones). Speed is not held to Parquet's
 * (RA-10): the answers and the calls are the same.
 */
final class FilesSnapshot implements TableSnapshot {

    /** A date's content: its file, the rows its commit states ({@code -1}: none), the commit's statistics (null: none). */
    record Day(Path file, long rows, JsonNode stats) {}

    static final Set<String> BASE = Set.of("doc", "business_date", "_load_id", "_source_file");
    private static final JsonFactory FACTORY = new JsonFactory();

    private final FilesLakeReader lake;
    private final String name;
    private final long version;
    private final Instant committed;
    private final Columns columns;
    private final NavigableMap<LocalDate, Day> days;
    private final Map<String, String> promoted;
    private final Set<String> vectors;
    private final Map<LocalDate, Map<String, Integer>> shadows = new ConcurrentHashMap<>();
    private final Map<LocalDate, DayFiles> dayFiles = new ConcurrentHashMap<>();
    /** Per date, its ids with the byte range of each one's live line (built once, on the first read by id). */
    private final Map<LocalDate, JsonlDay> indexes = new HashMap<>();

    FilesSnapshot(FilesLakeReader lake, String name, long version, Instant committed, Columns columns, NavigableMap<LocalDate, Day> days) {
        this.lake = lake;
        this.name = name;
        this.version = version;
        this.committed = committed;
        this.columns = columns;
        this.days = days;
        Map<String, String> p = new LinkedHashMap<>();
        Set<String> v = new LinkedHashSet<>();
        for (var c : columns.all()) {
            if (c.type() == ColumnType.DOUBLE_LIST) {
                v.add(c.name());
            } else if (!BASE.contains(c.name()) && !c.name().equals("id")) {
                p.put(c.name().replace("__", "."), c.name());
            }
        }
        this.promoted = Collections.unmodifiableMap(p);
        this.vectors = Collections.unmodifiableSet(v);
    }

    @FunctionalInterface
    private interface ByIndex<T> {
        T apply(JsonlDay index) throws IOException;
    }

    /**
     * {@code fn} over the date's id index, built on first use and kept (the date's content is immutable at this version). An index
     * released under the call ({@link #close}) is built again and the call repeated once.
     */
    private <T> T byIndex(LocalDate date, ByIndex<T> fn) throws IOException {
        for (int attempt = 0; ; attempt++) {
            JsonlDay ix;
            synchronized (indexes) {
                ix = indexes.get(date);
                if (ix == null) {
                    ix = JsonlDay.index(days.get(date).file(), date, List.of(), "id");
                    if (!ix.readable() || ix.report().unreadable() > 0) {             // a committed day reads whole or fails, as a scan does
                        ix.close();
                        throw new IOException(days.get(date).file() + ": " + (ix.readable() ? ix.report().unreadableLines() : ix.report().failure()));
                    }
                    indexes.put(date, ix);
                }
            }
            try {
                return fn.apply(ix);
            } catch (JsonlDay.StaleIndexException e) {
                synchronized (indexes) {
                    indexes.remove(date, ix);
                }
                if (attempt > 0) {
                    throw e;
                }
            }
        }
    }

    /** Releases the dates' indexes and the files they hold open (the reader dropped or purged this snapshot); a later read builds them again. */
    void close() {
        synchronized (indexes) {
            indexes.values().forEach(JsonlDay::close);
            indexes.clear();
        }
    }

    private <T> T read(LocalDate date, Problems.Read<T> body) {
        return lake.problems.read(name, date, version, body);
    }

    @FunctionalInterface
    private interface LineFn {
        void line(int index, String line) throws IOException;
    }

    /** Every non-blank line of the date's content, numbered from 0. */
    private void lines(LocalDate date, LineFn fn) throws IOException {
        Day d = days.get(date);
        if (d == null) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(d.file(), StandardCharsets.UTF_8)) {
            int i = 0;
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (!line.isBlank()) {
                    fn.line(i++, line);
                }
            }
        }
    }

    /**
     * The ids written more than once on the date, each with the line readers see: the newest load's copy in the one load order
     * ({@link com.ash.lake.rows.LoadOrder}: the run's time, then the commit, here the line's place, as an append adds lines after the
     * earlier ones).
     */
    private Map<String, Integer> shadow(LocalDate date) throws IOException {
        Map<String, Integer> s = shadows.get(date);
        if (s != null) {
            return s;
        }
        Map<String, Integer> best = new HashMap<>();
        Map<String, String> bestLoad = new HashMap<>();
        Set<String> twice = new java.util.HashSet<>();
        lines(date, (i, line) -> {
            String[] il = idAndLoadOf(line);
            if (il[0] == null) {
                return;
            }
            Integer was = best.get(il[0]);
            if (was != null) {
                twice.add(il[0]);
            }
            if (was == null || com.ash.lake.rows.LoadOrder.compare(il[1], i, bestLoad.get(il[0]), was) >= 0) {
                best.put(il[0], i);
                bestLoad.put(il[0], il[1]);
            }
        });
        best.keySet().retainAll(twice);
        shadows.put(date, Map.copyOf(best));
        return shadows.get(date);
    }

    /** A line's {id, _load_id} (either null when absent). */
    private static String[] idAndLoadOf(String line) throws IOException {
        String[] out = new String[2];
        try (JsonParser p = FACTORY.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return out;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String f = p.currentName();
                JsonToken t = p.nextToken();
                if (f.equals("id") && t.isScalarValue() && t != JsonToken.VALUE_NULL) {
                    out[0] = p.getText();
                } else if (f.equals("columns") && t == JsonToken.START_OBJECT) {
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        String c = p.currentName();
                        JsonToken v = p.nextToken();
                        if (c.equals(FilesWriter.LOAD_ID) && v.isScalarValue() && v != JsonToken.VALUE_NULL) {
                            out[1] = p.getText();
                        } else {
                            p.skipChildren();
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
        return out;
    }

    private static boolean live(Map<String, Integer> shadow, String id, int line) {
        Integer w = shadow.get(id);
        return w == null || w == line;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public Instant committed() {
        return committed;
    }

    @Override
    public long sequence() {
        return version;
    }

    @Override
    public Columns columns() {
        return columns;
    }

    @Override
    public String idColumn() {
        return "id";
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
        return false;                                     // a delete is a new commit of the date: no file carries deleted rows
    }

    @Override
    public NavigableSet<LocalDate> dates() {
        return Collections.unmodifiableNavigableSet(new TreeSet<>(days.keySet()));
    }

    @Override
    public Optional<DayFiles> day(LocalDate date) {
        Day d = days.get(date);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(dayFiles.computeIfAbsent(date, x -> read(x, () -> {
            Map<String, Bounds> bounds = new LinkedHashMap<>();
            long rows = d.rows();
            if (d.stats() != null) {
                rows = d.stats().path("numRecords").asLong(rows);
                d.stats().path("columns").fields().forEachRemaining(e -> {
                    JsonNode b = e.getValue();
                    bounds.put(e.getKey(), new Bounds(b.hasNonNull("min") ? b.get("min").asText() : null,
                            b.hasNonNull("max") ? b.get("max").asText() : null, b.hasNonNull("nulls") ? b.get("nulls").asLong() : null));
                });
            }
            DataFile f = new DataFile(d.file().toAbsolutePath().toString(), Files.size(d.file()), FileFormat.JSONL, rows, false, Map.of(), bounds);
            return DayFiles.of(x, List.of(f));
        })));
    }

    @Override
    public IdMap ids(LocalDate date) {
        return read(date, () -> {
            if (!days.containsKey(date)) {
                return IdMap.of(new String[0], new int[0]);
            }
            String[] sorted = byIndex(date, ix -> ix.ids().clone());       // the caller's copy: the index searches its own
            return IdMap.of(sorted, new int[sorted.length]);
        });
    }

    /** A document by id: a binary search of the date's index and one positioned read of its line (the last copy: the newest load's). */
    @Override
    public Optional<String> document(LocalDate date, int file, String id) {
        return read(date, () -> {
            if (file != 0 || !days.containsKey(date)) {
                return Optional.<String>empty();
            }
            return byIndex(date, ix -> ix.document(id)).map(b -> new String(b, StandardCharsets.UTF_8));
        });
    }

    @Override
    public void documents(LocalDate date, DocumentSink sink) {
        read(date, () -> {
            Map<String, Integer> shadow = shadow(date);
            lines(date, (i, line) -> {
                JsonlDay.Line l = JsonlDay.line(line.getBytes(StandardCharsets.UTF_8), "id", JsonlFormat.DEFAULT);
                if (l.id() != null && live(shadow, l.id(), i)) {
                    sink.document(l.id(), new String(l.doc(), StandardCharsets.UTF_8));
                }
            });
            return null;
        });
    }

    /** A cell as text, as the Parquet stores give it: a number keeps its digits (1500, never 1500.0); a list its JSON text. */
    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return IdText.of(n.asDouble());
        }
        if (n.isArray()) {
            List<Object> items = new ArrayList<>();
            n.forEach(x -> items.add(x.isNull() ? null : x.isTextual() ? x.asText() : x.isNumber() ? (Object) x.asDouble() : x.toString()));
            return com.ash.lake.rows.ListValues.json(items);
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    @Override
    public ColumnBlock columns(LocalDate date, List<ColumnAsk> asks) {
        return read(date, () -> {
            Map<String, Integer> shadow = shadow(date);
            List<String> ids = new ArrayList<>();
            List<List<Object>> values = new ArrayList<>();
            asks.forEach(a -> values.add(new ArrayList<>()));
            lines(date, (i, line) -> {
                JsonNode o = FilesLog.JSON.readTree(line);
                String id = o.hasNonNull("id") ? o.get("id").asText() : null;
                if (id == null || !live(shadow, id, i)) {
                    return;
                }
                ids.add(id);
                JsonNode cols = o.path("columns");
                for (int c = 0; c < asks.size(); c++) {
                    JsonNode n = cols.get(asks.get(c).column());
                    values.get(c).add(asks.get(c).numeric() ? (Object) (n != null && n.isNumber() ? n.asDouble() : Double.NaN) : text(n));
                }
            });
            Map<String, double[]> numbers = new LinkedHashMap<>();
            Map<String, String[]> texts = new LinkedHashMap<>();
            for (int c = 0; c < asks.size(); c++) {
                ColumnAsk a = asks.get(c);
                List<Object> v = values.get(c);
                if (a.numeric()) {
                    double[] out = new double[v.size()];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = (Double) v.get(i);
                    }
                    numbers.put(a.path(), out);
                } else {
                    String[] out = new String[v.size()];
                    Map<String, String> pool = new HashMap<>();
                    for (int i = 0; i < out.length; i++) {
                        String s = (String) v.get(i);
                        out[i] = s == null || pool.size() > 200_000 ? s : pool.computeIfAbsent(s, x -> x);
                    }
                    texts.put(a.path(), out);
                }
            }
            return new ColumnBlock(ids.toArray(new String[0]), numbers, texts);
        });
    }

    /**
     * One line's id, vector and texts, read by a streaming parser straight into {@code buf} (grown when short) and {@code texts}: no
     * tree, no list of boxed numbers. Returns the vector's length, -1 for none; the id in {@code id[0]}.
     */
    private static int vectorLine(String line, String column, List<String> textNames, String[] texts, double[][] buf, String[] id)
            throws IOException {
        Arrays.fill(texts, null);
        id[0] = null;
        int len = -1;
        try (JsonParser p = FACTORY.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return -1;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String f = p.currentName();
                JsonToken t = p.nextToken();
                if (f.equals("id") && t.isScalarValue() && t != JsonToken.VALUE_NULL) {
                    id[0] = p.getText();
                } else if (f.equals("columns") && t == JsonToken.START_OBJECT) {
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        String c = p.currentName();
                        JsonToken v = p.nextToken();
                        int ti = textNames.indexOf(c);
                        if (c.equals(column) && v == JsonToken.START_ARRAY) {
                            len = 0;
                            for (JsonToken e = p.nextToken(); e != JsonToken.END_ARRAY; e = p.nextToken()) {
                                if (len == buf[0].length) {
                                    buf[0] = Arrays.copyOf(buf[0], Math.max(16, len * 2));
                                }
                                buf[0][len++] = e == JsonToken.VALUE_NULL ? Double.NaN : p.getDoubleValue();
                            }
                        } else if (ti >= 0 && v.isScalarValue() && v != JsonToken.VALUE_NULL) {
                            texts[ti] = v.isNumeric() ? IdText.of(p.getDoubleValue()) : p.getText();
                        } else {
                            p.skipChildren();
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
        return len;
    }

    @Override
    public void vectors(VectorRequest request, VectorSink sink) {
        LocalDate date = request.date();
        read(date, () -> {
            Optional<DayFiles> day = day(date);
            if (day.isEmpty() || !request.keep().test(day.get().files().get(0))) {
                return null;
            }
            Map<String, Integer> shadow = shadow(date);
            double[][] buf = {new double[512]};
            String[] texts = new String[request.texts().size()];
            String[] id = new String[1];
            lines(date, (i, line) -> {
                int len = vectorLine(line, request.column(), request.texts(), texts, buf, id);
                if (id[0] != null && live(shadow, id[0], i)) {
                    sink.row(id[0], buf[0], len, texts);
                }
            });
            return null;
        });
    }

    @Override
    public Map<String, Map<String, double[]>> vectorsOf(LocalDate date, int file, Set<String> ids, List<String> cols) {
        return read(date, () -> {
            Map<String, Map<String, double[]>> out = new HashMap<>();
            if (file != 0 || ids.isEmpty() || cols.isEmpty() || !days.containsKey(date)) {
                return out;
            }
            double[][] buf = {new double[512]};
            String[] id = new String[1];
            byIndex(date, ix -> {
                for (String want : ids) {
                    Optional<byte[]> line = ix.lineOf(want);              // the live line alone: O(log n), not a pass over the date
                    if (line.isEmpty()) {
                        continue;
                    }
                    String text = new String(line.get(), StandardCharsets.UTF_8);
                    Map<String, double[]> vs = new LinkedHashMap<>();
                    for (String c : cols) {
                        int len = vectorLine(text, c, List.of(), new String[0], buf, id);
                        if (len >= 0) {
                            vs.put(c, Arrays.copyOf(buf[0], len));
                        }
                    }
                    out.put(want, vs);
                }
                return null;
            });
            return out;
        });
    }

    @Override
    public void scanFile(LocalDate date, int file, List<String> wanted, Expr where, RowSink sink) {
        read(date, () -> {
            if (file != 0) {
                return null;
            }
            int[] pick = new int[wanted.size()];
            for (int i = 0; i < pick.length; i++) {
                pick[i] = columns.indexOf(wanted.get(i));
                if (pick[i] < 0) {
                    throw new IllegalArgumentException("no column " + wanted.get(i) + " in " + name + " (it has " + columns.names() + ")");
                }
            }
            Map<String, Integer> shadow = shadow(date);
            Object[] out = new Object[pick.length];
            lines(date, (i, line) -> {
                JsonlDay.Line l = JsonlDay.line(line.getBytes(StandardCharsets.UTF_8), "id", JsonlFormat.DEFAULT);
                if (l.id() == null || !live(shadow, l.id(), i)) {
                    return;
                }
                Object[] v = FilesStore.values(columns, l.id(), date, new String(l.doc(), StandardCharsets.UTF_8), line);
                if (where.test(new com.ash.lake.spi.Row(columns, v))) {
                    for (int k = 0; k < pick.length; k++) {
                        out[k] = v[pick[k]];
                    }
                    sink.row(out);
                }
            });
            return null;
        });
    }
}
