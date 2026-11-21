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

import com.ash.lake.rows.PromotedValues;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.LoadRow;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.TableWriter.LostReplace;
import com.ash.lake.spi.WriteSpec;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The loader's writer into the files store: each writer thread's rows go to a part file in the table's log folder, one line per row
 * ({@code {"domain", "kind", "id", "date", "doc", "columns"}}, {@code columns} holding the table's typed columns: partition values,
 * promoted values, vectors and the load columns); the commit joins the parts in the loader's order (after the day's present lines, for an
 * append) into the date's next version ({@link FilesLog}) and publishes the day file. A new table's columns are this load's, as Delta
 * makes them; an existing table keeps its columns (a column the load brings is left out and reported) and gains the load columns.
 */
final class FilesWriter implements TableWriter {

    private final WriteSpec spec;
    private final FilesLog log;
    private final List<String> leftOut = new ArrayList<>();
    private Columns columns;
    private boolean columnsChanged;
    /** Per column after the first three: 'p' partition, 'c' promoted, 'v' vector, 'l' load id, 's' source file, 0 none; and its index. */
    private char[] kind;
    private int[] index;

    /** The log's newest version when the load began (-1: none): a replace of a date committed since is lost (TableWriter). */
    private final long base;

    FilesWriter(WriteSpec spec, Place root) throws IOException {
        this.spec = spec;
        this.log = new FilesLog(root.resolve(spec.domain()), spec.table());
        this.base = log.head();
    }

    static final String LOAD_ID = "_load_id";
    static final String SOURCE_FILE = "_source_file";

    @Override
    public void open(LoadColumns c) throws IOException {
        List<Column> wanted = new ArrayList<>(FilesStore.BASE.all());
        spec.partition().forEach(p -> wanted.add(new Column(p, ColumnType.STRING)));
        for (int i = 0; i < c.promoted().size(); i++) {
            wanted.add(new Column(c.promoted().get(i), c.list()[i] ? ColumnType.STRING_LIST : c.numeric()[i] ? ColumnType.DOUBLE : ColumnType.STRING));
        }
        ColumnType vector = spec.layout().float32Vectors() ? ColumnType.FLOAT_LIST : ColumnType.DOUBLE_LIST;
        spec.vectors().forEach(v -> wanted.add(new Column(v, vector)));
        if (spec.loadColumns()) {
            wanted.add(new Column(LOAD_ID, ColumnType.STRING));
            wanted.add(new Column(SOURCE_FILE, ColumnType.STRING));
        }
        Columns before = log.columns();
        if (before == null) {
            columns = Columns.of(wanted);
            columnsChanged = true;
        } else {
            List<Column> cols = new ArrayList<>(before.all());
            if (spec.loadColumns()) {
                for (String l : List.of(LOAD_ID, SOURCE_FILE)) {
                    if (before.indexOf(l) < 0) {
                        cols.add(new Column(l, ColumnType.STRING));       // a table made before the load columns gains them
                        columnsChanged = true;
                    }
                }
            }
            columns = Columns.of(cols);
            wanted.stream().map(Column::name).filter(n -> columns.indexOf(n) < 0).forEach(leftOut::add);
        }
        kind = new char[columns.size()];
        index = new int[columns.size()];
        for (int i = 3; i < columns.size(); i++) {
            String n = columns.get(i).name();
            kind[i] = n.equals(LOAD_ID) ? 'l' : n.equals(SOURCE_FILE) ? 's' : 0;
            find(n, spec.partition(), 'p', i);
            find(n, spec.vectors(), 'v', i);
            find(n, spec.promoted(), 'c', i);
        }
    }

    private void find(String name, List<String> in, char k, int i) {
        int at = in.indexOf(name);
        if (kind[i] == 0 && at >= 0) {
            kind[i] = k;
            index[i] = at;
        }
    }

    @Override
    public void begin(LocalDate date) {
    }

    @Override
    public FileOut newFile(LocalDate date, String[] partitionValues, String name) throws IOException {
        Path part = com.ash.lake.common.TempFiles.of(spec.runtime()).createFile("files-part-", ".jsonl");   // the writer's own, local
        byte[] head = ("{\"domain\":" + quote(spec.domain()) + ",\"kind\":" + quote(spec.table()) + ",\"id\":").getBytes(StandardCharsets.UTF_8);
        byte[] dated = (",\"date\":\"" + date + "\",\"doc\":").getBytes(StandardCharsets.UTF_8);
        byte[] cols = ",\"columns\":".getBytes(StandardCharsets.UTF_8);
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
        return new FileOut() {
            private long rows;
            private long bytes;

            @Override
            public void write(LoadRow row) throws IOException {
                byte[] id = quote(new String(row.id(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                byte[] doc = row.doc();
                byte[] values = FilesLog.JSON.writeValueAsBytes(values(row));
                out.write(head);
                out.write(id);
                out.write(dated);
                out.write(doc);
                out.write(cols);
                out.write(values);
                out.write('}');
                out.write('\n');
                bytes += head.length + id.length + dated.length + doc.length + cols.length + values.length + 2;
                rows++;
            }

            @Override
            public long length() {
                return bytes;
            }

            @Override
            public WrittenFile finish() throws IOException {
                out.close();
                return new WrittenFile(part.toString(), rows, bytes, part, partitionValues, List.of(), List.of());
            }
        };
    }

    /** The row's typed columns (nulls left out: a reader reads a missing column as null). */
    private Map<String, Object> values(LoadRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 3; i < columns.size(); i++) {
            Column c = columns.get(i);
            int k = index[i];
            Object v = switch (kind[i]) {
                case 'p' -> row.partition(k) == null || row.partition(k).isEmpty() ? null : row.partition(k);   // empty is null, as Delta's
                case 'v' -> vector(row.vector(k), c.type() == ColumnType.FLOAT_LIST);
                case 'l' -> text(row.loadId());
                case 's' -> text(row.sourceFile());
                case 'c' -> c.type() == ColumnType.DOUBLE ? PromotedValues.number(row, k)
                        : c.type() == ColumnType.STRING_LIST ? PromotedValues.list(row, k) : PromotedValues.text(row, k);
                default -> null;
            };
            if (v != null) {
                out.put(c.name(), v);
            }
        }
        return out;
    }

    /** A vector's elements (NaN: null); a float32 column's rounded to float as Parquet's {@code float} holds them, widened exactly. */
    private static List<Double> vector(double[] v, boolean float32) {
        if (v == null) {
            return null;
        }
        List<Double> out = new ArrayList<>(v.length);
        for (double d : v) {
            out.add(Double.isNaN(d) ? null : float32 ? (double) (float) d : d);
        }
        return out;
    }

    private static String text(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(s)) + "\"";
    }

    @Override
    public long commit(LocalDate date, List<WrittenFile> files) throws IOException {
        if (columnsChanged) {
            log.columns(columns);
            columnsChanged = false;
        }
        boolean append = spec.appends();
        Map<String, String> slice = spec.slice();
        if (files.isEmpty() && (append || !log.day(date).isFile())) {
            return -1;                                             // nothing to add, or nothing to remove
        }
        FilesLog.Commit c;
        while (true) {                                             // an append is built on the date's newest version, again if another writer moved it
            FilesLog.Commit latest = append || !slice.isEmpty() ? log.latest(date) : null;
            Place before = latest != null ? log.content(latest)                   // a day file without a log (written before it): its lines
                    : (append || !slice.isEmpty()) && log.day(date).isFile() ? log.day(date) : null;
            Place content = null;
            long rows = 0;
            if (!files.isEmpty() || before != null && !slice.isEmpty()) {
                content = log.newContent();
                try (OutputStream out = new BufferedOutputStream(content.out(), 1 << 16)) {
                    if (before != null && append) {
                        rows += before.countLines();
                        before.copyTo(out);
                    } else if (before != null) {
                        rows += otherSlices(before, slice, out);  // a shared table: the other loads' slices of the date stay
                    }
                    for (WrittenFile f : files) {
                        Files.copy((Path) f.handle(), out);
                        rows += f.rows();
                    }
                }
                if (rows == 0) {
                    content.delete();
                    content = null;                                // nothing left of the date
                }
            }
            String op = append ? "append" : "replace";
            c = append ? log.commit(date, op, spec.loadIds(), spec.batchId(), rows, content, latest == null ? -1 : latest.version())
                    : log.commitSince(date, op, spec.loadIds(), null, rows, content, base);
            if (c != null) {
                break;
            }
            if (content != null) {
                content.delete();
            }
            if (!append) {
                for (WrittenFile f : files) {
                    Files.deleteIfExists((Path) f.handle());
                }
                throw new LostReplace(spec.domain() + "/" + spec.table(), date);
            }
        }
        for (WrittenFile f : files) {
            Files.deleteIfExists((Path) f.handle());
        }
        return c.version();
    }

    /** Copies the lines of {@code day} outside the slice (a line whose columns hold every value of the slice is in it); their count. */
    private static long otherSlices(Place day, Map<String, String> slice, OutputStream out) throws IOException {
        long n = 0;
        try (java.io.BufferedReader r = day.reader()) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.isBlank() || inSlice(FilesLog.JSON.readTree(line).path("columns"), slice)) {
                    continue;
                }
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                n++;
            }
        }
        return n;
    }

    private static boolean inSlice(com.fasterxml.jackson.databind.JsonNode columns, Map<String, String> slice) {
        for (Map.Entry<String, String> e : slice.entrySet()) {
            com.fasterxml.jackson.databind.JsonNode v = columns.get(e.getKey());
            if (v == null || v.isNull() || !e.getValue().equals(v.asText())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean appended(LocalDate date) throws IOException {
        String batch = spec.batchId();
        return batch != null && log.commits().stream().anyMatch(c -> c.op().equals("append") && batch.equals(c.batch()) && c.date().equals(date));
    }

    @Override
    public Map<String, String> columnTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Column c : columns.all()) {
            out.put(c.name(), switch (c.type()) {
                case DOUBLE -> "double";
                case DATE -> "date";
                case STRING_LIST -> "list";
                case DOUBLE_LIST, FLOAT_LIST -> "vector";
                default -> "string";
            });
        }
        return out;
    }

    @Override
    public List<String> leftOut() {
        return leftOut;
    }

    @Override
    public void close() {
    }
}
