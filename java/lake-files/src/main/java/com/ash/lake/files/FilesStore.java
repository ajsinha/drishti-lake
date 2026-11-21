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
import com.ash.lake.spi.AsOf;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.DateChange;
import com.ash.lake.spi.Row;
import com.ash.lake.spi.RowScan;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableStore;
import com.ash.lake.spi.TableVersion;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The files store (LK-8): one JSON-lines file per table and business date, {@code <root>/<domain>/<date>/<table>.jsonl}, a line per
 * record ({@code {"domain", "kind", "id", "date", "doc"}}, or a plain document whose {@code id} names it), as the file connector reads
 * it ({@link JsonlDay}). A date is written whole into a file beside it and moved into place, so a reader sees the old day or the new one.
 * Its rows are {@code id}, {@code business_date}, {@code doc} and the table's typed columns (each line's {@code columns}); its log
 * ({@link FilesLog}) keeps every version of a date with the load and batch that wrote it, for undo, a batch's retry and reading an earlier
 * version. A table of day files without a log (written by hand) reads as {@code id}, {@code business_date} and {@code doc}. On every
 * storage a lake lives on: local disk, S3 and memory ({@link Place}), as Delta and Iceberg.
 */
public final class FilesStore implements TableStore {

    public static final String FORMAT = "files";

    /** The columns every table of this store has (first, in this order). */
    public static final Columns BASE = Columns.of(List.of(new Column("id", ColumnType.STRING), new Column("business_date", ColumnType.DATE),
            new Column("doc", ColumnType.STRING)));

    private static final Capabilities CAPABILITIES = Capabilities.all();

    private FilesStore() {
    }

    public static FilesStore create() {
        return new FilesStore();
    }

    @Override
    public com.ash.lake.read.LakeReader open(com.ash.lake.LakeRuntime runtime, com.ash.lake.read.LakeAddress address) {
        return new FilesLakeReader(runtime, address);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    /** The domain's folder of a table: {@code <root>/<domain>}, on the storage of its root. */
    static Place domainDir(LakeRuntime runtime, TableRef t) {
        return Place.root(runtime, t.root()).resolve(t.domain());
    }

    /** The table's day files by date, oldest first. */
    static SortedMap<LocalDate, Place> days(LakeRuntime runtime, TableRef t) throws IOException {
        return days(domainDir(runtime, t), t.table());
    }

    static SortedMap<LocalDate, Place> days(Place base, String table) throws IOException {
        SortedMap<LocalDate, Place> out = new TreeMap<>();
        for (String d : base.directories()) {
            LocalDate date = date(d);
            Place f = base.resolve(d).resolve(table + ".jsonl");
            if (date != null && f.isFile()) {
                out.put(date, f);
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
        return !days(runtime, table).isEmpty();
    }

    @Override
    public Columns columns(LakeRuntime runtime, TableRef table) throws IOException {
        if (!exists(runtime, table)) {
            throw new IOException("no files table at " + domainDir(runtime, table) + "/<date>/" + table.table() + ".jsonl");
        }
        return columnsOf(runtime, table);
    }

    static Columns columnsOf(LakeRuntime runtime, TableRef table) throws IOException {
        Columns c = new FilesLog(domainDir(runtime, table), table.table()).columns();
        return c == null ? BASE : c;
    }

    @Override
    public SortedMap<LocalDate, Long> dates(LakeRuntime runtime, TableRef table) throws IOException {
        SortedMap<LocalDate, Long> out = new TreeMap<>();
        for (Map.Entry<LocalDate, Place> e : days(runtime, table).entrySet()) {
            out.put(e.getKey(), e.getValue().countLines());
        }
        return out;
    }

    /** The log's commits, oldest first: a commit's number and when it was committed (a table without a log: none). */
    @Override
    public List<TableVersion> versions(LakeRuntime runtime, TableRef table) throws IOException {
        List<TableVersion> out = new ArrayList<>();
        for (FilesLog.Commit c : new FilesLog(domainDir(runtime, table), table.table()).commits()) {
            out.add(new TableVersion(c.version(), java.time.Instant.ofEpochMilli(c.at())));
        }
        return out;
    }

    /**
     * The table's days as of {@code asOf}, oldest first, each read by {@link #readDay}: the day files (the newest version), or each date's
     * content at the commit the log names (a date whose newest commit then removed it is absent).
     */
    private static SortedMap<LocalDate, Object> daysAsOf(LakeRuntime runtime, TableRef table, AsOf asOf) throws IOException {
        SortedMap<LocalDate, Object> out = new TreeMap<>();
        if (asOf.latest()) {
            out.putAll(days(runtime, table));
            return out;
        }
        FilesLog log = new FilesLog(domainDir(runtime, table), table.table());
        List<FilesLog.Commit> commits = log.commits();
        List<TableVersion> versions = new ArrayList<>();
        commits.forEach(c -> versions.add(new TableVersion(c.version(), java.time.Instant.ofEpochMilli(c.at()))));
        TableVersion at = asOf.resolve(versions);
        if (at == null) {
            throw new IOException(asOf.missing(domainDir(runtime, table) + "/" + table.table()));
        }
        java.util.Set<LocalDate> dates = new java.util.TreeSet<>();
        commits.forEach(c -> dates.add(c.date()));
        for (LocalDate d : dates) {
            FilesLog.Commit c = log.at(commits, d, at.version());
            if (c != null && c.content() != null) {
                out.put(d, log.lines(c));
            }
        }
        return out;
    }

    /** A line of a day, read in order. */
    private interface LineSink {
        void line(String line) throws IOException;
    }

    /** Hands each line of a day to {@code sink}: its day file, read as a stream, or the lines a commit held. */
    @SuppressWarnings("unchecked")
    private static void readDay(Object day, LineSink sink) throws IOException {
        if (day instanceof Place p) {
            try (java.io.BufferedReader r = p.reader()) {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    sink.line(line);
                }
            }
            return;
        }
        for (String line : (List<String>) day) {
            sink.line(line);
        }
    }

    /**
     * The rows of the request, of the version it names: each line's id, its day and its document. A line that cannot be read (not a JSON
     * object, no id) is skipped, as the file connector skips it.
     */
    @Override
    public RowScan scan(LakeRuntime runtime, TableRef table, ScanRequest request) throws IOException {
        Columns all = columnsOf(runtime, table);
        Columns out = request.columns().isEmpty() ? all : project(all, request.columns());
        int[] picks = new int[out.size()];
        for (int i = 0; i < picks.length; i++) {
            picks[i] = all.indexOf(out.get(i).name());
        }
        List<Row> rows = new ArrayList<>();
        Map<LocalDate, com.fasterxml.jackson.databind.JsonNode> stats = statsAsOf(runtime, table, request.asOf());
        long[] count = new long[3];                              // files read, files skipped, rows read
        for (Map.Entry<LocalDate, Object> e : daysAsOf(runtime, table, request.asOf()).entrySet()) {
            LocalDate date = e.getKey();
            com.fasterxml.jackson.databind.JsonNode st = stats.get(date);
            // a date is one file: pruned by its date and by its commit's statistics (FilesStats), as the Parquet stores prune files
            if (!request.wants(date) || !com.ash.lake.io.read.Pruning.mayHold(request.where(), date, Map.of(), c -> bounds(st, c))) {
                count[1]++;
                continue;
            }
            count[0]++;
            readDay(e.getValue(), line -> {
                if (line.isBlank()) {
                    return;
                }
                count[2]++;
                JsonlDay.Line l;
                try {
                    l = JsonlDay.line(line.getBytes(StandardCharsets.UTF_8), "id", JsonlFormat.DEFAULT);
                } catch (IOException bad) {
                    return;
                }
                if (l.id() == null) {
                    return;
                }
                Row whole = new Row(all, values(all, l.id(), date, new String(l.doc(), StandardCharsets.UTF_8), line));
                if (!request.where().test(whole)) {
                    return;
                }
                Object[] v = new Object[picks.length];
                for (int i = 0; i < picks.length; i++) {
                    v[i] = whole.get(picks[i]);
                }
                rows.add(new Row(out, v));
            });
        }
        return RowScan.of(out, rows, new com.ash.lake.spi.ScanStats(count[0], count[1], count[2], rows.size()));
    }

    /** Each date's commit statistics at the request's version (none for a table without a log, or a commit before statistics). */
    private static Map<LocalDate, com.fasterxml.jackson.databind.JsonNode> statsAsOf(LakeRuntime runtime, TableRef table, AsOf asOf) throws IOException {
        FilesLog log = new FilesLog(domainDir(runtime, table), table.table());
        List<FilesLog.Commit> commits = log.commits();
        long version = Long.MAX_VALUE;
        if (!asOf.latest() && !commits.isEmpty()) {
            List<TableVersion> versions = new ArrayList<>();
            commits.forEach(c -> versions.add(new TableVersion(c.version(), java.time.Instant.ofEpochMilli(c.at()))));
            TableVersion at = asOf.resolve(versions);
            version = at == null ? -1 : at.version();
        }
        Map<LocalDate, com.fasterxml.jackson.databind.JsonNode> out = new java.util.HashMap<>();
        for (FilesLog.Commit c : commits) {
            if (c.version() <= version) {
                if (c.stats() == null) {
                    out.remove(c.date());
                } else {
                    out.put(c.date(), c.stats());
                }
            }
        }
        return out;
    }

    /** A text column's bounds in a date's commit statistics (FilesStats: min, max, nulls); null when not stated. */
    private static com.ash.lake.read.Bounds bounds(com.fasterxml.jackson.databind.JsonNode stats, String column) {
        com.fasterxml.jackson.databind.JsonNode b = stats == null ? null : stats.path("columns").get(column);
        if (b == null) {
            return null;
        }
        return new com.ash.lake.read.Bounds(b.hasNonNull("min") ? b.get("min").asText() : null, b.hasNonNull("max") ? b.get("max").asText() : null,
                b.hasNonNull("nulls") ? b.get("nulls").asLong() : null);
    }

    /** A line's values: its id, date and document, then the table's typed columns from its {@code columns} (missing: null). */
    static Object[] values(Columns all, String id, LocalDate date, String doc, String line) throws IOException {
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
                    case DOUBLE_LIST, FLOAT_LIST -> { List<Double> l = new ArrayList<>(); n.forEach(x -> l.add(x.isNull() ? null : x.asDouble())); yield l; }
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
    public TableWriter writer(WriteSpec spec) throws IOException {
        return new FilesWriter(spec, Place.root(spec.runtime(), spec.root(), spec.storage()));
    }

    @Override
    public List<DateChange> deleteDates(LakeRuntime runtime, TableRef table, Set<LocalDate> dates, boolean dryRun) throws IOException {
        Set<String> wanted = new java.util.HashSet<>();
        dates.forEach(d -> wanted.add(d.toString()));
        List<DateChange> out = new ArrayList<>();
        for (Map<String, Object> line : lines(dropDates(runtime, table.root(), table.domain(), table.table(), wanted::contains, dryRun))) {
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
        FilesLog log = new FilesLog(domainDir(runtime, table), table.table());
        List<DateChange> out = new ArrayList<>();
        if (!log.exists()) {
            return out;
        }
        List<FilesLog.Commit> commits = log.commits();
        for (Map.Entry<LocalDate, Place> e : days(runtime, table).entrySet()) {
            List<String> keep = new ArrayList<>();
            long removed = 0;
            for (String line : e.getValue().lines()) {
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

    /** The tables of the lake at {@code root} that have a log, {@code <domain>/<table>}, sorted. */
    public static List<String> tables(LakeRuntime runtime, String root) throws IOException {
        Place r = Place.root(runtime, root);
        List<String> out = new ArrayList<>();
        for (String d : r.directories()) {
            Place logs = r.resolve(d).resolve("_log");
            for (String t : logs.directories()) {
                if (logs.resolve(t).resolve("columns.json").isFile()) {
                    out.add(d + "/" + t);
                }
            }
        }
        return out;
    }

    /** The business dates of the table {@code domain/table} of the lake at {@code root} (its day files), oldest first. */
    public static List<LocalDate> dayDates(LakeRuntime runtime, String root, String domain, String table) throws IOException {
        return new ArrayList<>(days(domain.isEmpty() ? Place.root(runtime, root) : Place.root(runtime, root).resolve(domain), table).keySet());
    }

    /** The non-blank lines of one day of the table {@code domain/table} of the lake at {@code root} (none when the day is not there). */
    public static List<String> dayLines(LakeRuntime runtime, String root, String domain, String table, LocalDate date) throws IOException {
        Place base = domain.isEmpty() ? Place.root(runtime, root) : Place.root(runtime, root).resolve(domain);
        Place f = base.resolve(date.toString()).resolve(table + ".jsonl");
        return f.isFile() ? f.lines() : List.of();
    }

    /** Commits {@code lines} as the date's next version ({@code op}: {@code undo}, {@code retention}); none: the date removed. */
    static FilesLog.Commit rewrite(FilesLog log, LocalDate date, String op, List<String> loads, List<String> lines) throws IOException {
        Place content = null;
        if (!lines.isEmpty()) {
            content = log.newContent();
            try (java.io.OutputStream out = content.out()) {
                out.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return log.commit(date, op, loads, null, lines.size(), content);
    }

    /**
     * The day {@code date} of the table {@code domain/table} of the lake at {@code root} rewritten with {@code lines} in one commit (the
     * lake's row retention); a table without a log has its day file replaced whole.
     */
    public static void rewriteDay(LakeRuntime runtime, String root, String domain, String table, LocalDate date, List<String> lines, String op)
            throws IOException {
        FilesLog log = new FilesLog(domain.isEmpty() ? Place.root(runtime, root) : Place.root(runtime, root).resolve(domain), table);
        if (log.exists()) {
            rewrite(log, date, op, List.of(), lines);
            return;
        }
        Place f = log.day(date);
        if (lines.isEmpty()) {
            f.delete();
            return;
        }
        f.put((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whole dates removed from the table {@code domain/table} of the lake at {@code root} (the lake's delete): {@code held} (every date
     * held before) and {@code dates} (a line per date {@code wanted} accepts: {@code date}, {@code rows}, {@code dropped}). Each day file is
     * removed whole, so a reader sees the whole day or none of it; nothing on a dry run.
     */
    public static Map<String, Object> dropDates(LakeRuntime runtime, String root, String domain, String table, Predicate<String> wanted,
            boolean dryRun) throws IOException {
        Place base = domain.isEmpty() ? Place.root(runtime, root) : Place.root(runtime, root).resolve(domain);
        List<String> held = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<LocalDate, Place> gone = new TreeMap<>();
        for (Map.Entry<LocalDate, Place> e : days(base, table).entrySet()) {
            String date = e.getKey().toString();
            held.add(date);
            if (wanted.test(date)) {
                long n = e.getValue().countLines();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", date);
                m.put("rows", n);
                m.put("dropped", n);
                lines.add(m);
                gone.put(e.getKey(), e.getValue());
            }
        }
        if (!dryRun) {
            FilesLog log = new FilesLog(base, table);
            boolean logged = log.exists();
            for (Map.Entry<LocalDate, Place> e : gone.entrySet()) {
                if (logged) {
                    log.commit(e.getKey(), "delete", List.of(), null, 0, null);   // the date's versions kept
                } else {
                    e.getValue().delete();
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("held", held);
        out.put("dates", lines);
        return out;
    }
}
