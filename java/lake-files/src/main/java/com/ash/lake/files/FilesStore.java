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

import com.ash.lake.LakeRuntime;
import com.ash.lake.spi.Capabilities;
import com.ash.lake.spi.Capability;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.DateChange;
import com.ash.lake.spi.Row;
import com.ash.lake.spi.RowScan;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableStore;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The files store (LK-8): one JSON-lines file per table and business date, {@code <root>/<domain>/<date>/<table>.jsonl}, a line per
 * record ({@code {"domain", "kind", "id", "date", "doc"}}, or a plain document whose {@code id} names it), as the file connector reads
 * it ({@link JsonlDay}). A date is written whole into a file beside it and moved into place, so a reader sees the old day or the new one.
 * Its rows are {@code id}, {@code business_date}, {@code doc} and the table's typed columns (each line's {@code columns}); its log
 * ({@link FilesLog}) keeps every version of a date with the load and batch that wrote it, for undo, a batch's retry and reading an earlier
 * version. A table of day files without a log (written by hand) reads as {@code id}, {@code business_date} and {@code doc}. Local folders
 * only.
 */
public final class FilesStore implements TableStore {

    public static final String FORMAT = "files";

    /** The columns every table of this store has (first, in this order). */
    public static final Columns BASE = Columns.of(List.of(new Column("id", ColumnType.STRING), new Column("business_date", ColumnType.DATE),
            new Column("doc", ColumnType.STRING)));

    private static final Capabilities CAPABILITIES = Capabilities.allBut(
            Capability.TIME_TRAVEL, "no operation of the store reads an earlier version yet (the log keeps every version)");

    private FilesStore() {
    }

    public static FilesStore create() {
        return new FilesStore();
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    /** The domain's folder of a table: {@code <root>/<domain>}, a local folder. */
    static Path domainDir(TableRef t) {
        return local(t.root()).resolve(t.domain());
    }

    static Path local(String root) {
        String r = root.startsWith("file://") ? root.substring("file://".length()) : root;
        if (r.contains("://")) {
            throw new UnsupportedOperationException("the files store reads and writes local folders only, not " + root);
        }
        return Path.of(r);
    }

    /** The table's day files by date, oldest first. */
    static SortedMap<LocalDate, Path> days(TableRef t) throws IOException {
        SortedMap<LocalDate, Path> out = new TreeMap<>();
        Path base = domainDir(t);
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (Stream<Path> s = Files.list(base)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                LocalDate date = date(d.getFileName().toString());
                Path f = d.resolve(t.table() + ".jsonl");
                if (date != null && Files.isRegularFile(f)) {
                    out.put(date, f);
                }
            }
        }
        return out;
    }

    private static LocalDate date(String name) {
        try {
            return name.matches("\\d{4}-\\d{2}-\\d{2}") ? LocalDate.parse(name) : null;
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    @Override
    public boolean exists(LakeRuntime runtime, TableRef table) throws IOException {
        return !days(table).isEmpty();
    }

    @Override
    public Columns columns(LakeRuntime runtime, TableRef table) throws IOException {
        if (!exists(runtime, table)) {
            throw new IOException("no files table at " + domainDir(table) + "/<date>/" + table.table() + ".jsonl");
        }
        return columnsOf(table);
    }

    private static Columns columnsOf(TableRef table) throws IOException {
        Columns c = new FilesLog(domainDir(table), table.table()).columns();
        return c == null ? BASE : c;
    }

    @Override
    public SortedMap<LocalDate, Long> dates(LakeRuntime runtime, TableRef table) throws IOException {
        SortedMap<LocalDate, Long> out = new TreeMap<>();
        for (Map.Entry<LocalDate, Path> e : days(table).entrySet()) {
            out.put(e.getKey(), lines(e.getValue()));
        }
        return out;
    }

    /** The rows of a day file. */
    static long count(Path f) throws IOException {
        return lines(f);
    }

    private static long lines(Path f) throws IOException {
        try (Stream<String> ls = Files.lines(f, StandardCharsets.UTF_8)) {
            return ls.filter(l -> !l.isBlank()).count();
        }
    }

    /**
     * The rows of the request: each line's id, its day and its document. A line that cannot be read (not a JSON object, no id) is
     * skipped, as the file connector skips it.
     */
    @Override
    public RowScan scan(LakeRuntime runtime, TableRef table, ScanRequest request) throws IOException {
        Columns all = columnsOf(table);
        Columns out = request.columns().isEmpty() ? all : project(all, request.columns());
        int[] picks = new int[out.size()];
        for (int i = 0; i < picks.length; i++) {
            picks[i] = all.indexOf(out.get(i).name());
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, Path> e : days(table).entrySet()) {
            if (!request.wants(e.getKey())) {
                continue;
            }
            try (InputStream in = Files.newInputStream(e.getValue());
                 BufferedReader r = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonlDay.Line l;
                    try {
                        l = JsonlDay.line(line.getBytes(StandardCharsets.UTF_8), "id", JsonlFormat.DEFAULT);
                    } catch (IOException bad) {
                        continue;
                    }
                    if (l.id() == null) {
                        continue;
                    }
                    Row whole = new Row(all, values(all, l.id(), e.getKey(), new String(l.doc(), StandardCharsets.UTF_8), line));
                    if (!request.where().test(whole)) {
                        continue;
                    }
                    Object[] v = new Object[picks.length];
                    for (int i = 0; i < picks.length; i++) {
                        v[i] = whole.get(picks[i]);
                    }
                    rows.add(new Row(out, v));
                }
            }
        }
        return RowScan.of(out, rows);
    }

    /** A line's values: its id, date and document, then the table's typed columns from its {@code columns} (missing: null). */
    private static Object[] values(Columns all, String id, LocalDate date, String doc, String line) throws IOException {
        Object[] v = new Object[all.size()];
        v[0] = id;
        v[1] = date;
        v[2] = doc;
        if (all.size() > 3 && line.contains("\"columns\"")) {
            com.fasterxml.jackson.databind.JsonNode cols = FilesLog.JSON.readTree(line).path("columns");
            for (int i = 3; i < v.length; i++) {
                com.fasterxml.jackson.databind.JsonNode n = cols.get(all.get(i).name());
                v[i] = n == null || n.isNull() ? null : switch (all.get(i).type()) {
                    case DOUBLE -> n.asDouble();
                    case STRING_LIST -> { List<String> l = new ArrayList<>(); n.forEach(x -> l.add(x.asText())); yield l; }
                    case DOUBLE_LIST -> { List<Double> l = new ArrayList<>(); n.forEach(x -> l.add(x.isNull() ? null : x.asDouble())); yield l; }
                    default -> n.isTextual() ? n.asText() : n.toString();
                };
            }
        }
        return v;
    }

    private static Columns project(Columns all, List<String> names) {
        List<Column> cols = new ArrayList<>();
        for (String n : names) {
            int i = all.indexOf(n);
            if (i < 0) {
                throw new IllegalArgumentException("no column " + n + " in a files table (it has " + all.names() + ")");
            }
            cols.add(all.get(i));
        }
        return Columns.of(cols);
    }

    @Override
    public TableWriter writer(WriteSpec spec) {
        if (!spec.slice().isEmpty()) {
            throw new UnsupportedOperationException("the files store holds one day file per table: a shared table's slice " + spec.slice()
                    + " cannot be replaced alone");
        }
        return new FilesWriter(spec, local(spec.root()));
    }

    @Override
    public List<DateChange> deleteDates(LakeRuntime runtime, TableRef table, Set<LocalDate> dates, boolean dryRun) throws IOException {
        Set<String> wanted = new java.util.HashSet<>();
        dates.forEach(d -> wanted.add(d.toString()));
        List<DateChange> out = new ArrayList<>();
        for (Map<String, Object> line : lines(dropDates(table.root(), table.domain(), table.table(), wanted::contains, dryRun))) {
            out.add(new DateChange(LocalDate.parse((String) line.get("date")), ((Number) line.get("dropped")).longValue(), 0, -1));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> got) {
        return (List<Map<String, Object>>) got.get("dates");
    }

    /**
     * Undoes load {@code loadId} as the Delta store does: each date whose rows hold the load loses them, and a date the load replaced gets
     * back the rows it had before (the version before the load's commit), with the rows later loads appended kept. One commit per date.
     */
    @Override
    public List<DateChange> undo(LakeRuntime runtime, TableRef table, String loadId, boolean dryRun) throws IOException {
        FilesLog log = new FilesLog(domainDir(table), table.table());
        List<DateChange> out = new ArrayList<>();
        if (!log.exists()) {
            return out;
        }
        List<FilesLog.Commit> commits = log.commits();
        for (Map.Entry<LocalDate, Path> e : days(table).entrySet()) {
            List<String> keep = new ArrayList<>();
            long removed = 0;
            for (String line : java.nio.file.Files.readAllLines(e.getValue(), StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                if (loadId.equals(FilesLog.JSON.readTree(line).path("columns").path(FilesWriter.LOAD_ID).asText(null))) {
                    removed++;
                } else {
                    keep.add(line);
                }
            }
            if (removed == 0) {
                continue;
            }
            List<String> restored = List.of();
            FilesLog.Commit wrote = null;
            for (FilesLog.Commit c : commits) {
                if (c.date().equals(e.getKey()) && c.op().equals("replace") && c.loads().stream().anyMatch(l -> l.equals(loadId))) {
                    wrote = c;
                }
            }
            if (wrote != null) {
                restored = log.lines(log.at(commits, e.getKey(), wrote.version() - 1));
            }
            List<String> content = new ArrayList<>(restored);
            content.addAll(keep);
            long version = -1;
            if (!dryRun) {
                version = rewrite(log, e.getKey(), "undo", List.of(loadId), content).version();
            }
            out.add(new DateChange(e.getKey(), removed, restored.size(), version));
        }
        return out;
    }

    /** Commits {@code lines} as the date's next version ({@code op}: {@code undo}, {@code retention}); none: the date removed. */
    static FilesLog.Commit rewrite(FilesLog log, LocalDate date, String op, List<String> loads, List<String> lines) throws IOException {
        Path content = null;
        if (!lines.isEmpty()) {
            content = log.newContent();
            java.nio.file.Files.write(content, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return log.commit(date, op, loads, null, lines.size(), content);
    }

    /**
     * The day {@code date} of the table {@code domain/table} of the lake at {@code root} rewritten with {@code lines} in one commit (the
     * lake's row retention); a table without a log has its day file replaced atomically.
     */
    public static void rewriteDay(String root, String domain, String table, LocalDate date, List<String> lines, String op) throws IOException {
        FilesLog log = new FilesLog(domain.isEmpty() ? local(root) : local(root).resolve(domain), table);
        if (log.exists()) {
            rewrite(log, date, op, List.of(), lines);
            return;
        }
        Path f = log.day(date);
        if (lines.isEmpty()) {
            java.nio.file.Files.deleteIfExists(f);
            return;
        }
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        java.nio.file.Files.write(tmp, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Whole dates removed from the table {@code domain/table} of the lake at {@code root} (the lake's delete): {@code held} (every date
     * held before) and {@code dates} (a line per date {@code wanted} accepts: {@code date}, {@code rows}, {@code dropped}). Each day file is
     * deleted whole, so a reader sees the whole day or none of it; nothing on a dry run.
     */
    public static Map<String, Object> dropDates(String root, String domain, String table, Predicate<String> wanted, boolean dryRun) throws IOException {
        Path base = domain.isEmpty() ? local(root) : local(root).resolve(domain);
        List<String> held = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Path> gone = new ArrayList<>();
        if (Files.isDirectory(base)) {
            try (Stream<Path> days = Files.list(base)) {
                for (Path d : days.filter(Files::isDirectory).sorted().toList()) {
                    Path f = d.resolve(table + ".jsonl");
                    if (!Files.isRegularFile(f)) {
                        continue;
                    }
                    String date = d.getFileName().toString();
                    held.add(date);
                    if (wanted.test(date)) {
                        long n = lines(f);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", date);
                        m.put("rows", n);
                        m.put("dropped", n);
                        lines.add(m);
                        gone.add(f);
                    }
                }
            }
        }
        if (!dryRun) {
            FilesLog log = new FilesLog(base, table);
            for (Path f : gone) {
                if (log.exists()) {
                    log.commit(LocalDate.parse(f.getParent().getFileName().toString()), "delete", List.of(), null, 0, null);   // the date's versions kept
                } else {
                    Files.delete(f);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("held", held);
        out.put("dates", lines);
        return out;
    }
}
