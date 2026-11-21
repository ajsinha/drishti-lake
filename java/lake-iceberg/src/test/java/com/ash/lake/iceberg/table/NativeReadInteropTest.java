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

import com.ash.lake.tck.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.lake.iceberg.Table;
import com.ash.lake.iceberg.catalog.Catalog;
import com.ash.lake.iceberg.catalog.Catalogs;
import com.ash.lake.iceberg.expr.Expr;
import com.ash.lake.iceberg.meta.Snapshot;
import com.ash.lake.iceberg.read.TaskReader;
import com.ash.lake.iceberg.scan.FileTask;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Interop, read: every snapshot of every pyiceberg-written fixture table (v1 and v2, every type, every transform,
 * partition and schema evolution, a file without field ids read through the name mapping) read by drishti-iceberg
 * equals what pyiceberg read from it, row for row.
 */
class NativeReadInteropTest {

    static Path root;
    static ExecutorService pool;

    @BeforeAll
    static void copy() {
        root = Fixtures.copy();
        pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterAll
    static void close() {
        pool.close();
    }

    static List<String> tables() {
        List<String> out = new ArrayList<>();
        Fixtures.expected(Path.of(TestRoots.module(), "src", "test", "resources", "iceberg-fixtures")).fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** Every live row of a snapshot as JSON (decimals as text, as the fixtures record them), sorted. */
    static List<String> rows(Table t, Snapshot s, ExecutorService pool) {
        Schema schema = t.metadata().schemaFor(s);
        List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
        List<String> out = new ArrayList<>();
        for (FileTask task : t.plan(s, Expr.TRUE, pool)) {
            try (TaskReader r = t.rows(task, schema, ids, Expr.TRUE, false)) {
                while (r.hasNext()) {
                    Object[] v = r.next();
                    var n = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    for (int i = 0; i < schema.columns().size(); i++) {         // the shape rule's rendering (RA-S8), one for every store
                        NestedField c = schema.columns().get(i);
                        JsonNode x = com.ash.lake.io.read.ShapedDocument.value(IcebergForeign.field(c.name(), c.type()), v[i],
                                new com.ash.lake.io.read.ShapedDocument.Options(true, false));
                        if (x != null) {
                            n.set(c.name(), x);
                        }
                    }
                    out.add(Fixtures.canonical(n));
                }
            }
        }
        out.sort(null);
        return out;
    }

    static List<String> expectedRows(JsonNode snapshot) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : snapshot.path("rows")) {
            out.add(Fixtures.canonical(r));
        }
        out.sort(null);
        return out;
    }

    @ParameterizedTest
    @MethodSource("tables")
    void everySnapshotReadsAsPyicebergReadIt(String name) throws Exception {
        JsonNode want = Fixtures.expected(root).get(name);
        try (Catalog c = Catalogs.open(com.ash.lake.LakeRuntime.fromProcess(), Fixtures.settings(root))) {
            Table t = c.load(name).orElseThrow();
            assertThat(t.metadata().formatVersion()).isEqualTo(want.path("format-version").asInt());
            for (JsonNode snap : want.path("snapshots")) {
                Snapshot s = t.metadata().snapshot(snap.path("id").asLong()).orElseThrow();
                assertThat(rows(t, s, pool)).as(name + " snapshot " + s.snapshotId()).containsExactlyElementsOf(expectedRows(snap));
            }
        }
    }

}
