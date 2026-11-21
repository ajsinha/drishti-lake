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
import com.ash.tools.files.TempFolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A files table's log, {@code <root>/<domain>/_log/<table>/}, beside its day files (visible names only): {@code columns.json} (the
 * table's columns and types, fixed by its first load; the load columns added later as Delta adds them), and per commit, numbered from 0,
 * {@code <n>.json} (the commit: its date, operation, load ids, batch, rows and content) with {@code <n>.jsonl} (the date's whole content
 * at that version). A commit is the creation of its {@code <n>.json} (create-new: two writers never take one number); the day file
 * {@code <root>/<domain>/<date>/<table>.jsonl} is then published as that content (a link, else a copy, moved into place atomically), so
 * the file connector reads the newest day and the store reads any version (time travel, undo, a batch's retry).
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

    private final Path dir;
    private final Path domainDir;
    private final String table;

    /** One temporary folder per log, inside the log (the same file system as the files it is moved to: the move is atomic). */
    private static final java.util.concurrent.ConcurrentHashMap<Path, TempFolder> PARTS = new java.util.concurrent.ConcurrentHashMap<>();

    private TempFolder parts() {
        TempFolder t = PARTS.computeIfAbsent(dir.toAbsolutePath().normalize(), d -> TempFolder.of(d, "part-", "lake-temp-folder.txt"));
        if (t.made() && !Files.isDirectory(t.root())) {
            t.cleanUp();                                    // the log was removed (an undo, a dropped table) and is written again: made anew
        }
        return t;
    }

    FilesLog(Path domainDir, String table) {
        this.domainDir = domainDir;
        this.table = table;
        this.dir = domainDir.resolve("_log").resolve(table);
    }

    /** Whether the table has a log (a table written before the log, or by hand, has day files only). */
    boolean exists() {
        return Files.isRegularFile(dir.resolve("columns.json"));
    }

    Path dir() {
        return dir;
    }

    /** The day file of {@code date}. */
    Path day(LocalDate date) {
        return domainDir.resolve(date.toString()).resolve(table + ".jsonl");
    }

    /** The table's columns, or null without a log. */
    Columns columns() throws IOException {
        if (!exists()) {
            return null;
        }
        List<Column> out = new ArrayList<>();
        for (JsonNode c : JSON.readTree(dir.resolve("columns.json").toFile()).path("columns")) {
            out.add(new Column(c.path("name").asText(), ColumnType.valueOf(c.path("type").asText().toUpperCase(Locale.ROOT))));
        }
        return Columns.of(out);
    }

    /** Writes the table's columns (a new table, or the load columns added), moved into place whole. */
    /** The table's id ({@code columns.json}'s {@code id}, written at creation and by the first commit after), or null (F2). */
    String id() throws IOException {
        if (!exists()) {
            return null;
        }
        JsonNode id = JSON.readTree(dir.resolve("columns.json").toFile()).get("id");
        return id == null || id.isNull() ? null : id.asText();
    }

    void columns(Columns cols) throws IOException {
        Path tmp = columnsFile(cols);
        locked(() -> Files.move(tmp, dir.resolve("columns.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));
    }

    /** {@code columns.json} for {@code cols} written beside it (its id kept, or a new one), to be moved into place. */
    private Path columnsFile(Columns cols) throws IOException {
        Files.createDirectories(dir);
        String id = id();
        ObjectNode o = JSON.createObjectNode().put("id", id == null ? java.util.UUID.randomUUID().toString() : id);
        ArrayNode a = o.putArray("columns");
        cols.all().forEach(c -> a.addObject().put("name", c.name()).put("type", c.type().name().toLowerCase(Locale.ROOT)));
        Path tmp = parts().createFile("columns-", ".part");             // one per writer: two writers never share a temporary file
        JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), o);
        return tmp;
    }

    List<Commit> commits() throws IOException {
        List<Commit> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().matches("\\d+\\.json")).toList()) {
                Commit c = Commit.of(JSON.readTree(p.toFile()));
                out.add(c.at() > 0 ? c : new Commit(c.version(), c.date(), c.op(), c.loads(), c.batch(), c.rows(), c.content(),
                        Files.getLastModifiedTime(p).toMillis(), c.stats()));
            }
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
        return Files.readAllLines(dir.resolve(c.content()), java.nio.charset.StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
    }

    /** A commit that does not check the date's version it was built on (a replace, a delete, an undo's rewrite). */
    static final long ANY = Long.MIN_VALUE;

    /** The newest commit of {@code date}, or null. */
    Commit latest(LocalDate date) throws IOException {
        return at(commits(), date, Long.MAX_VALUE);
    }

    /** The content file of a commit, or null for a commit that removed the date. */
    Path content(Commit c) {
        return c == null || c.content() == null ? null : dir.resolve(c.content());
    }

    /**
     * Commits {@code content} (a file written beside the log, moved in; null: the date removed) as the date's next version and publishes the
     * day file. Returns the commit.
     */
    Commit commit(LocalDate date, String op, List<String> loads, String batch, long rows, Path content) throws IOException {
        return commit(date, op, loads, batch, rows, content, ANY);
    }

    /**
     * As {@link #commit(LocalDate, String, List, String, long, Path)}, when {@code date}'s newest version is still {@code base} (-1: the
     * date had none): null when another writer committed the date since (an append built on older content: the caller builds it again).
     * Writers of one table, in this process or another, commit one at a time ({@link #locked}): the number taken, the content and the
     * commit written and the day file published together, so a day file never goes back to an older version.
     */
    Commit commit(LocalDate date, String op, List<String> loads, String batch, long rows, Path content, long base) throws IOException {
        return locked(() -> {
            List<Commit> all = commits();
            if (base != ANY) {
                Commit now = at(all, date, Long.MAX_VALUE);
                if ((now == null ? -1 : now.version()) != base) {
                    return null;
                }
            }
            long n = all.stream().mapToLong(Commit::version).max().orElse(-1) + 1;
            String name = content == null ? null : n + ".jsonl";
            if (content != null) {
                Files.move(content, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Columns cols = columns();
            if (cols != null && id() == null) {
                Files.move(columnsFile(cols), dir.resolve("columns.json"), StandardCopyOption.REPLACE_EXISTING,   // a table written
                        StandardCopyOption.ATOMIC_MOVE);                // before ids: its next commit gives it one (F2), under the lock held
            }
            JsonNode stats = name == null || cols == null ? null : FilesStats.of(dir.resolve(name), cols);
            Commit c = new Commit(n, date, op, List.copyOf(loads), batch, rows, name, System.currentTimeMillis(), stats);
            Files.write(dir.resolve(n + ".json"), JSON.writeValueAsBytes(c.json()), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            publish(c);
            return c;
        });
    }

    private interface LogWork<T> {
        T run() throws IOException;
    }

    /**
     * {@code work} holding the table's commit lock ({@code commit.lock} in its log folder: an operating-system file lock, released by the
     * system if the process dies; a second thread of this process waits for the first).
     */
    private <T> T locked(LogWork<T> work) throws IOException {
        Files.createDirectories(dir);
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.resolve("commit.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = null;
            long wait = 1;
            while (lock == null) {
                try {
                    lock = ch.tryLock();
                } catch (java.nio.channels.OverlappingFileLockException heldHere) {
                    lock = null;                                     // another thread of this process holds it
                }
                if (lock == null) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("waiting for the commit lock of " + table);
                    }
                    wait = Math.min(50, wait * 2);
                }
            }
            try {
                return work.run();
            } finally {
                lock.release();
            }
        }
    }

    /** The day file made the commit's content (or removed). */
    private void publish(Commit c) throws IOException {
        Path day = day(c.date());
        if (c.content() == null) {
            Files.deleteIfExists(day);
            return;
        }
        Files.createDirectories(day.getParent());
        Path tmp = day.resolveSibling(day.getFileName() + ".publish");
        Files.deleteIfExists(tmp);
        try {
            Files.createLink(tmp, dir.resolve(c.content()));
        } catch (IOException | UnsupportedOperationException noLinks) {
            Files.copy(dir.resolve(c.content()), tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, day, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** A new content file beside the log, to be filled and committed. */
    Path newContent() throws IOException {
        Files.createDirectories(dir);
        return parts().createFile("content-", ".part");
    }
}
