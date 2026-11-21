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
import com.ash.lake.read.LakeAddress;
import com.ash.lake.read.LakeHealth;
import com.ash.lake.read.LakeReader;
import com.ash.lake.read.ReadProblems;
import com.ash.lake.read.TableIdentity;
import com.ash.lake.read.TableReader;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.AsOf;
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The read API over a files lake (READ_API.md section 4): a table's log ({@code <root>/<domain>/_log/<table>/}) gives its versions,
 * each date's content file at a version, the rows and the text columns' bounds of each commit (F1) and the table's id (F2).
 */
final class FilesLakeReader implements LakeReader {

    final String root;
    final String domain;
    final Path domainDir;
    final Map<String, String> names;
    final Problems problems;
    private final Map<String, Table> tables = new ConcurrentHashMap<>();
    private final Map<String, com.ash.lake.io.read.ForeignTableReader> foreign = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    FilesLakeReader(LakeAddress address) {
        this.root = address.root();
        this.domain = address.domain();
        this.domainDir = FilesStore.local(address.root()).resolve(address.domain());
        this.names = address.tableNames();
        this.problems = new Problems(domainDir.toString(), Problems::describe);
    }

    /** The table's own name under the domain (a {@code table.<name>} setting names another). */
    String stored(String name) {
        return names.getOrDefault(name, name);
    }

    @Override
    public String format() {
        return "files";
    }

    @Override
    public List<String> tables() throws IOException {
        List<String> out = new ArrayList<>();
        Path logs = domainDir.resolve("_log");
        if (Files.isDirectory(logs)) {
            try (Stream<Path> s = Files.list(logs)) {
                s.filter(p -> Files.isRegularFile(p.resolve("columns.json"))).forEach(p -> out.add(p.getFileName().toString()));
            }
        }
        if (!names.isEmpty()) {
            List<String> named = new ArrayList<>();
            names.forEach((name, table) -> {
                if (out.contains(table)) {
                    named.add(name);
                }
            });
            out.clear();
            out.addAll(named);
        }
        out.sort(null);
        return out;
    }

    @Override
    public List<String> newTables(Set<String> known) throws IOException {
        List<String> out = new ArrayList<>();
        for (String t : tables()) {
            if (!known.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    public TableReader table(String name) {
        return tables.computeIfAbsent(name, n -> new Table(n));
    }

    /**
     * A folder of JSON Lines other systems wrote: {@code <domain>/<table>/}, or (a {@code namespace.table}) {@code <levels…>/<table>/}
     * under the lake's root (local disk).
     */
    @Override
    public TableReader foreign(String name, com.ash.lake.read.TableShape shape) {
        Path base = shape.namespace().isEmpty() ? domainDir : FilesStore.local(root).resolve(String.join("/", shape.namespace()));
        Path dir = base.resolve(shape.tableName());
        return foreign.computeIfAbsent(name + "\u001f" + shape, k -> new com.ash.lake.io.read.ForeignTableReader(name, shape,
                new FilesForeign(dir), pool, problems));
    }

    @Override
    public LakeHealth health(Instant now) {
        return new LakeHealth(Files.isDirectory(domainDir), false, domainDir.toString(), "files", List.of());
    }

    @Override
    public ReadProblems problems() {
        return problems;
    }

    @Override
    public void refresh() {
        tables.values().forEach(Table::forget);
        foreign.values().forEach(com.ash.lake.io.read.ForeignTableReader::forget);
    }

    @Override
    public void purge() {
        refresh();
    }

    @Override
    public void purge(LocalDate date) {
        refresh();
    }

    @Override
    public void close() {
        refresh();                                               // the snapshots' indexes hold their files open
        pool.close();
    }

    /** One table: its snapshots per version (the newest four). */
    final class Table implements TableReader {
        private final String name;
        private final java.util.LinkedHashMap<Long, FilesSnapshot> kept = new java.util.LinkedHashMap<>();

        Table(String name) {
            this.name = name;
        }

        synchronized void forget() {
            kept.values().forEach(FilesSnapshot::close);
            kept.clear();
        }

        FilesLog log() {
            return new FilesLog(domainDir, stored(name));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TableIdentity identity() throws IOException {
            FilesLog log = log();
            String id = log.id();
            String where = domainDir.resolve(stored(name)).toAbsolutePath().toString();
            if (id != null) {
                return new TableIdentity("files", id, where);
            }
            String host;
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                host = "localhost";
            }
            return new TableIdentity("location", host + ":" + where, where);
        }

        @Override
        public Optional<TableSnapshot> snapshot(AsOf asOf) throws IOException {
            return problems.read(name, null, -1, () -> {
                FilesLog log = log();
                TableRef ref = new TableRef(root, domain, stored(name));
                List<FilesLog.Commit> commits = log.commits();
                TreeMap<LocalDate, FilesSnapshot.Day> days = new TreeMap<>();
                long version;
                Instant committed;
                if (commits.isEmpty()) {                         // day files without a log: one version, 0
                    if (!log.exists() && FilesStore.days(ref).isEmpty()) {
                        return Optional.<TableSnapshot>empty();
                    }
                    if (!asOf.latest()) {
                        throw new IOException(asOf.missing(domainDir + "/" + stored(name)));
                    }
                    FilesStore.days(ref).forEach((d, p) -> days.put(d, new FilesSnapshot.Day(p, -1, null)));
                    version = 0;
                    committed = Instant.EPOCH;
                } else {
                    List<TableVersion> versions = new ArrayList<>();
                    commits.forEach(c -> versions.add(new TableVersion(c.version(), Instant.ofEpochMilli(c.at()))));
                    TableVersion at = asOf.latest() ? versions.get(versions.size() - 1) : asOf.resolve(versions);
                    if (at == null && asOf.time() != null) {
                        return Optional.<TableSnapshot>empty();          // a time before the first commit: no table then
                    }
                    if (at == null) {
                        throw new IOException(asOf.missing(domainDir + "/" + stored(name)));
                    }
                    version = at.version();
                    committed = at.committed();
                    for (FilesLog.Commit c : commits) {
                        if (c.version() <= version) {
                            days.put(c.date(), c.content() == null ? null : new FilesSnapshot.Day(log.content(c), c.rows(), c.stats()));
                        }
                    }
                    days.values().removeIf(java.util.Objects::isNull);
                }
                synchronized (this) {
                    FilesSnapshot s = kept.get(version);
                    if (s == null) {
                        s = new FilesSnapshot(FilesLakeReader.this, name, version, committed, FilesStore.columnsOf(ref), days);
                        kept.put(version, s);
                        while (kept.size() > 4) {
                            kept.remove(kept.keySet().iterator().next()).close();
                        }
                    }
                    if (asOf.latest()) {
                        problems.version(name, version);
                    }
                    return Optional.<TableSnapshot>of(s);
                }
            });
        }
    }
}
