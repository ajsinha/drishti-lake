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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
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
    record Commit(long version, LocalDate date, String op, List<String> loads, String batch, long rows, String content, long at) {

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
            return o;
        }

        static Commit of(JsonNode o) {
            List<String> loads = new ArrayList<>();
            o.path("loads").forEach(n -> loads.add(n.asText()));
            return new Commit(o.path("version").asLong(), LocalDate.parse(o.path("date").asText()), o.path("op").asText(), List.copyOf(loads),
                    o.hasNonNull("batch") ? o.get("batch").asText() : null, o.path("rows").asLong(), o.hasNonNull("content") ? o.get("content").asText() : null,
                    o.path("at").asLong(0));
        }
    }

    private final Path dir;
    private final Path domainDir;
    private final String table;

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
    void columns(Columns cols) throws IOException {
        Files.createDirectories(dir);
        ObjectNode o = JSON.createObjectNode();
        ArrayNode a = o.putArray("columns");
        cols.all().forEach(c -> a.addObject().put("name", c.name()).put("type", c.type().name().toLowerCase(Locale.ROOT)));
        Path tmp = dir.resolve("columns.json.tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), o);
        Files.move(tmp, dir.resolve("columns.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Every commit, oldest first. */
    List<Commit> commits() throws IOException {
        List<Commit> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().matches("\\d+\\.json")).toList()) {
                Commit c = Commit.of(JSON.readTree(p.toFile()));
                out.add(c.at() > 0 ? c : new Commit(c.version(), c.date(), c.op(), c.loads(), c.batch(), c.rows(), c.content(),
                        Files.getLastModifiedTime(p).toMillis()));
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

    /**
     * Commits {@code content} (a file written beside the log, moved in; null: the date removed) as the date's next version and publishes the
     * day file. Returns the commit.
     */
    Commit commit(LocalDate date, String op, List<String> loads, String batch, long rows, Path content) throws IOException {
        Files.createDirectories(dir);
        long n = commits().stream().mapToLong(Commit::version).max().orElse(-1) + 1;
        while (true) {
            String name = content == null ? null : n + ".jsonl";
            if (content != null) {
                Files.move(content, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Commit c = new Commit(n, date, op, List.copyOf(loads), batch, rows, name, System.currentTimeMillis());
            try {
                Files.write(dir.resolve(n + ".json"), JSON.writeValueAsBytes(c.json()), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException taken) {                // another writer took the number: the content moves on with us
                if (content != null) {
                    Path back = dir.resolve(name + ".moving");
                    Files.move(dir.resolve(name), back, StandardCopyOption.ATOMIC_MOVE);
                    content = back;
                }
                n++;
                continue;
            }
            publish(c);
            return c;
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
        return Files.createTempFile(dir, "content-", ".part");
    }
}
