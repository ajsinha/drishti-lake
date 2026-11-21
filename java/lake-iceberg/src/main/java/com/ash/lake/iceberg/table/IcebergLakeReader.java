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

import com.ash.lake.LakeRuntime;
import com.ash.lake.io.read.Problems;
import com.ash.lake.read.LakeAddress;
import com.ash.lake.read.LakeHealth;
import com.ash.lake.read.LakeReader;
import com.ash.lake.read.ReadProblems;
import com.ash.lake.read.TableReader;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The read API over an Iceberg lake (READ_API.md section 4): one domain through the product's own Iceberg reader (catalog, S3 Tables,
 * {@code relocate} as the address's settings say), the table library's objects kept inside ({@link IcebergSnapshot}).
 */
public final class IcebergLakeReader implements LakeReader {

    final LakeTables lake;
    final Problems problems;
    final String root;
    final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, IcebergTableReader> tables = new ConcurrentHashMap<>();
    private final Map<String, com.ash.lake.io.read.ForeignTableReader> foreign = new ConcurrentHashMap<>();
    private final Map<List<String>, com.ash.lake.iceberg.catalog.Catalog> namespaces = new ConcurrentHashMap<>();
    private final com.ash.lake.LakeRuntime runtime;
    private final Map<String, String> settings;

    private IcebergLakeReader(com.ash.lake.LakeRuntime runtime, Map<String, String> settings, LakeTables lake, LakeAddress address) {
        this.runtime = runtime;
        this.settings = settings;
        this.lake = lake;
        this.root = address.root();
        this.problems = new Problems(lake.describe(), IcebergLakeReader::reason);
    }

    /** The lake of {@code address}. */
    public static LakeReader open(LakeRuntime runtime, LakeAddress address) {
        Map<String, String> settings = new java.util.LinkedHashMap<>(address.settings());
        settings.put("root", address.root());
        settings.put("domain", address.domain());
        address.tableNames().forEach((k, v) -> settings.put("table." + k, v));
        return new IcebergLakeReader(runtime, settings, LakeTables.open(runtime, settings), address);
    }

    static String reason(Throwable e) {
        return Problems.describe(e);
    }

    @Override
    public String format() {
        return "iceberg";
    }

    @Override
    public List<String> tables() throws IOException {
        return lake.kinds();
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
        return tables.computeIfAbsent(name, n -> new IcebergTableReader(this, n));
    }

    /**
     * A table other engines wrote, in the reader's domain or (a {@code namespace.table}) under that namespace: a REST catalog's namespace,
     * or the folder {@code <root>/<levels…>}.
     */
    @Override
    public TableReader foreign(String name, com.ash.lake.read.TableShape shape) {
        com.ash.lake.iceberg.catalog.Catalog catalog = shape.namespace().isEmpty() ? lake.catalog()
                : namespaces.computeIfAbsent(shape.namespace(), ns -> {
                    Map<String, String> s = new java.util.LinkedHashMap<>(settings);
                    if ("rest".equalsIgnoreCase(s.getOrDefault("catalog", "")) || com.ash.lake.iceberg.catalog.S3Tables.named(s)) {
                        s.put("namespace", String.join(".", ns));
                    } else {
                        s.put("domain", String.join("/", ns));
                    }
                    return com.ash.lake.iceberg.catalog.Catalogs.open(runtime, s);
                });
        return foreign.computeIfAbsent(name + "\u001f" + shape, k -> new com.ash.lake.io.read.ForeignTableReader(name, shape,
                new IcebergForeign(catalog, shape.tableName(), pool), pool, problems));
    }

    @Override
    public LakeHealth health(Instant now) {
        List<String> notes = new ArrayList<>();
        com.ash.tools.net.TlsMaterial tls = lake.tls();
        if (tls != null && !tls.expiryNote(now).isEmpty()) {
            notes.add(tls.expiryNote(now));
        }
        String proxy = lake.proxy() == null ? "" : lake.proxy().note();
        if (!proxy.isEmpty()) {
            notes.add(proxy);
        }
        return new LakeHealth(lake.reachable(), root.startsWith("mem://"), lake.describe(), "native", notes);
    }

    @Override
    public ReadProblems problems() {
        return problems;
    }

    @Override
    public void refresh() {
        tables.values().forEach(IcebergTableReader::refresh);
        foreign.values().forEach(com.ash.lake.io.read.ForeignTableReader::forget);
    }

    @Override
    public void purge() {
        tables.values().forEach(IcebergTableReader::forget);
        foreign.values().forEach(com.ash.lake.io.read.ForeignTableReader::forget);
    }

    @Override
    public void purge(LocalDate date) {
        purge();
    }

    @Override
    public void close() {
        try {
            for (var c : namespaces.values()) {
                c.close();
            }
            lake.close();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            pool.close();
        }
    }
}
