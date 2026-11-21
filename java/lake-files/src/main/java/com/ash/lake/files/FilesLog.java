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

import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.FileEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A files table's log, {@code <root>/<domain>/_log/<table>/}, beside its day files (visible names only), on the storage the lake lives on
 * (local disk, S3, memory: {@link Place}): {@code columns.json} (the table's columns and types, fixed by its first load; the load columns
 * added later as Delta adds them), and per commit, numbered from 0, {@code <n>.json} (the commit: its date, operation, load ids, batch,
 * rows and the name of its content file, {@code <uuid>.jsonl}: the date's whole content at that version). A commit is the exclusive
 * creation of its {@code <n>.json} (two writers never take one number; the loser reads the log again and checks what it was built on, as
 * Delta's log does), so the log needs no lock on any storage. The day file {@code <root>/<domain>/<date>/<table>.jsonl} is then published
 * as the date's newest content, whole (Storage#publish), so the file connector reads the newest day and the store reads any version (time
 * travel, undo, a batch's retry).
 */
final class FilesLog {

    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One commit: the version, the date, {@code replace|append|delete|undo|retention}, the load ids, the batch, the rows, the content file
     * and when it was committed (epoch milliseconds; 0 when the commit does not say, then the time of its file).
     */
    record Commit(long version, LocalDate date, String op, List<String> loads, String batch, long rows, String content, long at,
            JsonNode stats) {

        Commit(long version, LocalDate date, String op, List<String> loads, String batch, long rows, String content, long at) {
            this(version, date, op, loads, batch, rows, content, at, null);
        }

        ObjectNode json() {
            ObjectNode o = JSON.createObjectNode().put("version", version).put("date", date.toString()).put("op", op).put("at", at);
            ArrayNode l = o.putArray("loads");
            loads.forEach(l::add);
            if (batch != null) {
                o.put("batch", batch);
            }
            o.put("rows", rows);
            if (content != null) {
                o.put("content", content);
            }
            if (stats != null) {
                o.set("stats", stats);
            }
            return o;
        }

        static Commit of(JsonNode o) {
            List<String> loads = new ArrayList<>();
            o.path("loads").forEach(n -> loads.add(n.asText()));
            return new Commit(o.path("version").asLong(), LocalDate.parse(o.path("date").asText()), o.path("op").asText(), List.copyOf(loads),
                    o.hasNonNull("batch") ? o.get("batch").asText() : null, o.path("rows").asLong(), o.hasNonNull("content") ? o.get("content").asText() : null,
                    o.path("at").asLong(0), o.get("stats"));
        }
    }

    private final Place dir;
    private final Place domainDir;
    private final String table;

    FilesLog(Place domainDir, String table) {
        this.domainDir = domainDir;
        this.table = table;
        this.dir = domainDir.resolve("_log").resolve(table);
    }

    /** Whether the table has a log (a table written before the log, or by hand, has day files only). */
    boolean exists() throws IOException {
        return dir.resolve("columns.json").isFile();
    }

    Place dir() {
        return dir;
    }

    /** The day file of {@code date}. */
    Place day(LocalDate date) {
        return domainDir.resolve(date.toString()).resolve(table + ".jsonl");
    }

    /** The table's columns, or null without a log. */
    Columns columns() throws IOException {
        JsonNode o = columnsFile();
        if (o == null) {
            return null;
        }
        List<Column> out = new ArrayList<>();
        for (JsonNode c : o.path("columns")) {
            out.add(new Column(c.path("name").asText(), ColumnType.valueOf(c.path("type").asText().toUpperCase(Locale.ROOT))));
        }
        return Columns.of(out);
    }

    private JsonNode columnsFile() throws IOException {
        Place f = dir.resolve("columns.json");
        try {
            return JSON.readTree(f.readAll());
        } catch (java.io.FileNotFoundException | java.nio.file.NoSuchFileException none) {
            return null;
        }
    }

    /** The table's id ({@code columns.json}'s {@code id}, written at creation and by the first commit after), or null (F2). */
    String id() throws IOException {
        JsonNode o = columnsFile();
        JsonNode id = o == null ? null : o.get("id");
        return id == null || id.isNull() ? null : id.asText();
    }

    /** Writes the table's columns (a new table, or the load columns added), whole: its id kept, or a new one. */
    void columns(Columns cols) throws IOException {
        String id = id();
        ObjectNode o = JSON.createObjectNode().put("id", id == null ? java.util.UUID.randomUUID().toString() : id);
        ArrayNode a = o.putArray("columns");
        cols.all().forEach(c -> a.addObject().put("name", c.name()).put("type", c.type().name().toLowerCase(Locale.ROOT)));
        dir.resolve("columns.json").put(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(o));
    }

    List<Commit> commits() throws IOException {
        List<Commit> out = new ArrayList<>();
        for (FileEntry e : dir.storage().files(dir.path())) {
            String name = e.path().substring(e.path().lastIndexOf('/') + 1);
            if (!name.matches("\\d+\\.json")) {
                continue;
            }
            Commit c;
            try {
                c = Commit.of(JSON.readTree(dir.resolve(name).readAll()));
            } catch (java.io.FileNotFoundException | java.nio.file.NoSuchFileException gone) {
                continue;                                         // removed while listing (a dropped table)
            }
            out.add(c.at() > 0 ? c : new Commit(c.version(), c.date(), c.op(), c.loads(), c.batch(), c.rows(), c.content(), e.modified(), c.stats()));
        }
        out.sort(java.util.Comparator.comparingLong(Commit::version));
        return out;
    }

    /** The newest commit of {@code date} at or before {@code version}, or null. */
    Commit at(List<Commit> commits, LocalDate date, long version) {
        Commit out = null;
        for (Commit c : commits) {
            if (c.version() <= version && c.date().equals(date)) {
                out = c;
            }
        }
        return out;
    }

    /** The content of a commit (its lines), empty for a commit that removed the date. */
    List<String> lines(Commit c) throws IOException {
        if (c == null || c.content() == null) {
            return List.of();
        }
        return dir.resolve(c.content()).lines();
    }

    /** A commit that does not check the date's version it was built on (a replace, a delete, an undo's rewrite). */
    static final long ANY = Long.MIN_VALUE;

    /** The newest commit of {@code date}, or null. */
    Commit latest(LocalDate date) throws IOException {
        return at(commits(), date, Long.MAX_VALUE);
    }

    /** The content file of a commit, or null for a commit that removed the date. */
    Place content(Commit c) {
        return c == null || c.content() == null ? null : dir.resolve(c.content());
    }

    /**
     * Commits {@code content} (a file of this log from {@link #newContent}, filled; null: the date removed) as the date's next version and
     * publishes the day file. Returns the commit.
     */
    Commit commit(LocalDate date, String op, List<String> loads, String batch, long rows, Place content) throws IOException {
        return commit(date, op, loads, batch, rows, content, ANY);
    }

    /**
     * As {@link #commit(LocalDate, String, List, String, long, Place)}, when {@code date}'s newest version is still {@code base} (-1: the
     * date had none): null when another writer committed the date since (an append built on older content: the caller builds it again).
     */
    Commit commit(LocalDate date, String op, List<String> loads, String batch, long rows, Place content, long base) throws IOException {
        return commit(date, op, loads, batch, rows, content, now -> base == ANY || (now == null ? -1 : now.version()) == base);
    }

    /**
     * As {@link #commit(LocalDate, String, List, String, long, Place)}, when no commit of {@code date} came after the log's version
     * {@code since} (-1: the log had none): null when one did (a replace that lost to another writer).
     */
    Commit commitSince(LocalDate date, String op, List<String> loads, String batch, long rows, Place content, long since) throws IOException {
        return commit(date, op, loads, batch, rows, content, now -> now == null || now.version() <= since);
    }

    /** The log's newest version, -1 when it has none. */
    long head() throws IOException {
        return commits().stream().mapToLong(Commit::version).max().orElse(-1);
    }

    /**
     * Takes the log's next number by creating {@code <n>.json} exclusively, while what the date's newest commit is still passes
     * {@code builtOn}; a number another writer took first makes it read the log again (and check again). The content and its statistics
     * are written before; the day file is published after.
     */
    private Commit commit(LocalDate date, String op, List<String> loads, String batch, long rows, Place content,
            java.util.function.Predicate<Commit> builtOn) throws IOException {
        Columns cols = columns();
        if (cols != null && id() == null) {
            columns(cols);                                   // a table written before ids: its next commit gives it one (F2)
        }
        JsonNode stats = content == null || cols == null ? null : FilesStats.of(content, cols);
        while (true) {
            List<Commit> all = commits();
            if (!builtOn.test(at(all, date, Long.MAX_VALUE))) {
                return null;
            }
            long n = all.stream().mapToLong(Commit::version).max().orElse(-1) + 1;
            Commit c = new Commit(n, date, op, List.copyOf(loads), batch, rows, content == null ? null : content.name(), System.currentTimeMillis(),
                    stats);
            if (dir.resolve(n + ".json").createIfAbsent(JSON.writeValueAsBytes(c.json()))) {
                publish(date);
                return c;
            }
        }
    }

    /**
     * The day file made the date's newest content (or removed), again until what it was made from is still the newest: writers that
     * commit at once each publish after their commit, so the last to look leaves the newest version, and a day file never stays older.
     */
    private void publish(LocalDate date) throws IOException {
        Place day = day(date);
        for (int round = 0; round < 100; round++) {
            Commit newest = latest(date);
            if (newest == null) {
                return;
            }
            if (newest.content() == null) {
                day.delete();
            } else {
                day.publish(dir.resolve(newest.content()));
            }
            Commit after = latest(date);
            if (after == null || after.version() == newest.version()) {
                return;
            }
        }
        throw new IOException("the day file of " + date + " of " + table + " was moved by other writers 100 times while it was published");
    }

    /** A new content file of this log ({@code <uuid>.jsonl}), to be filled and committed (removed by the caller when it is not). */
    Place newContent() {
        return dir.resolve(java.util.UUID.randomUUID() + ".jsonl");
    }
}
