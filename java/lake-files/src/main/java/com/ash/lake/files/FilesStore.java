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
 * Its rows are {@code id}, {@code business_date} and {@code doc}; what a plain file cannot do is declared in {@link #capabilities()}.
 * Local folders only.
 */
public final class FilesStore implements TableStore {

    public static final String FORMAT = "files";

    /** The columns every table of this store has. */
    public static final Columns COLUMNS = Columns.of(List.of(new Column("id", ColumnType.STRING), new Column("business_date", ColumnType.DATE),
            new Column("doc", ColumnType.STRING)));

    private static final Capabilities CAPABILITIES = Capabilities.allBut(
            Capability.APPEND_BATCH_ONCE, "a day file records no batch: an append could not be told from its retry (append without a batch id)",
            Capability.UNDO, "a day file records neither the load that wrote it nor the day before it: a load cannot be taken out",
            Capability.TIME_TRAVEL, "a day file is replaced whole: no earlier version is kept",
            Capability.SCHEMA_EVOLUTION, "a record is its document: there are no columns to add",
            Capability.PROMOTED_COLUMNS, "a record is its document: promoted fields are read from it, not kept beside it",
            Capability.TYPED_LIST_COLUMNS, "a record is its document: lists and vectors stay inside it");

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
        return COLUMNS;
    }

    @Override
    public SortedMap<LocalDate, Long> dates(LakeRuntime runtime, TableRef table) throws IOException {
        SortedMap<LocalDate, Long> out = new TreeMap<>();
        for (Map.Entry<LocalDate, Path> e : days(table).entrySet()) {
            out.put(e.getKey(), lines(e.getValue()));
        }
        return out;
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
        Columns out = request.columns().isEmpty() ? COLUMNS : project(request.columns());
        int[] picks = new int[out.size()];
        for (int i = 0; i < picks.length; i++) {
            picks[i] = COLUMNS.indexOf(out.get(i).name());
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
                    Row whole = new Row(COLUMNS, new Object[] {l.id(), e.getKey(), new String(l.doc(), StandardCharsets.UTF_8)});
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

    private static Columns project(List<String> names) {
        List<Column> cols = new ArrayList<>();
        for (String n : names) {
            int i = COLUMNS.indexOf(n);
            if (i < 0) {
                throw new IllegalArgumentException("no column " + n + " in a files table (it has " + COLUMNS.names() + ")");
            }
            cols.add(COLUMNS.get(i));
        }
        return Columns.of(cols);
    }

    @Override
    public TableWriter writer(WriteSpec spec) {
        if (spec.appends() && spec.batchId() != null) {
            CAPABILITIES.require(FORMAT, Capability.APPEND_BATCH_ONCE);
        }
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

    @Override
    public List<DateChange> undo(LakeRuntime runtime, TableRef table, String loadId, boolean dryRun) {
        CAPABILITIES.require(FORMAT, Capability.UNDO);
        return List.of();
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
            for (Path f : gone) {
                Files.delete(f);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("held", held);
        out.put("dates", lines);
        return out;
    }
}
