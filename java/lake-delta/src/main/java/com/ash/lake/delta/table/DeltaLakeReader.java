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
package com.ash.lake.delta.table;

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

/**
 * The read API over a Delta lake (READ_API.md section 4): one domain of a lake through the runtime's native engine, the Kernel's types
 * kept inside ({@link DeltaSnapshot}). Opened by the Delta store's {@code open}.
 */
public final class DeltaLakeReader implements LakeReader {

    final LakeRuntime runtime;
    final LakeStore lake;
    final Problems problems;
    final String root;
    final String idColumn;
    final String docColumn;
    final String dateColumn;
    private final Map<String, DeltaTableReader> tables = new ConcurrentHashMap<>();

    private DeltaLakeReader(LakeRuntime runtime, LakeStore lake, LakeAddress address) {
        this.runtime = runtime;
        this.lake = lake;
        this.root = address.root();
        this.idColumn = address.settings().getOrDefault("id-column", "id");
        this.docColumn = address.settings().getOrDefault("doc-column", "doc");
        this.dateColumn = address.settings().getOrDefault("date-column", "business_date");
        this.problems = new Problems(lake.describe(), DeltaLakeReader::reason);
    }

    /** The lake of {@code address}, read by the runtime's native engine. */
    public static LakeReader open(LakeRuntime runtime, LakeAddress address) {
        Map<String, String> settings = new java.util.LinkedHashMap<>(address.settings());
        address.tableNames().forEach((k, v) -> settings.put("table." + k, v));
        return new DeltaLakeReader(runtime, LakeStore.of(runtime, address.root(), address.domain(), settings), address);
    }

    /** The words of a failed read: what is missing or why it cannot be decoded. */
    static String reason(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof io.delta.kernel.exceptions.TableNotFoundException) {
                return "the table is not there (moved or removed): " + t.getMessage();
            }
        }
        return Problems.describe(e);
    }

    @Override
    public String format() {
        return "delta";
    }

    @Override
    public List<String> tables() throws IOException {
        return lake.tables();
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
        return tables.computeIfAbsent(name, n -> new DeltaTableReader(this, n));
    }

    @Override
    public LakeHealth health(Instant now) {
        List<String> notes = new ArrayList<>();
        com.ash.tools.net.TlsMaterial tls = lake.tls();
        if (tls != null && !tls.expiryNote(now).isEmpty()) {
            notes.add(tls.expiryNote(now));
        }
        String proxy = lake.proxy().note();
        if (!proxy.isEmpty()) {
            notes.add(proxy);
        }
        return new LakeHealth(lake.reachable(), root.startsWith("mem://"), lake.describe(), lake.engineName(), notes);
    }

    @Override
    public ReadProblems problems() {
        return problems;
    }

    @Override
    public void refresh() {
        tables.values().forEach(DeltaTableReader::forget);
    }

    @Override
    public void purge() {
        tables.values().forEach(DeltaTableReader::forget);
        com.ash.lake.delta.NativeEngine.purgeFooterCache(runtime);
    }

    @Override
    public void purge(LocalDate date) {
        purge();
    }

    @Override
    public void close() {
        lake.close();
    }
}
