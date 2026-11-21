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
package com.ash.lake.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.lake.LakeRuntime;
import com.ash.lake.delta.table.LakeStore;
import com.ash.lake.delta.table.NewTables;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Delta lake opener, native path only (step L7, LK-9): a domain's tables listed and opened through the runtime's native engine,
 * a kind read from a folder of another name, new tables found, and the Hadoop engine refused naming why.
 */
class LakeStoreTest {

    @TempDir
    Path tmp;

    private static final LakeRuntime RT = LakeRuntime.fromProcess();

    @Test
    void aDomainsTablesAreListedAndReadByTheNativeEngine() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in"));
        PvData.write(in.resolve("pv.2026-10-27.jsonl"), PvData.lines(10, 2, 1, 5));
        Path lake = tmp.resolve("lake");
        Path cfg = Files.writeString(tmp.resolve("load.yaml"), PvData.config(in + "/*", lake.toString(), "delta"));
        assertThat(LakeLoad.run(RT, new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8))).isZero();

        try (LakeStore store = LakeStore.of(RT, lake.toString(), "risk", Map.of())) {
            assertThat(store.tables()).containsExactly("scenario-pv");
            assertThat(store.reachable()).isTrue();
            assertThat(store.engineName()).isEqualTo("native");
            var snapshot = io.delta.kernel.Table.forPath(store.engine(), store.table("scenario-pv")).getLatestSnapshot(store.engine());
            assertThat(snapshot.getPartitionColumnNames()).containsExactly("business_date");
            List<String> added = new ArrayList<>();
            assertThat(NewTables.find(store, Set.of(), added::add)).isTrue();
            assertThat(added).containsExactly("scenario-pv");
            assertThat(NewTables.find(store, Set.of("scenario-pv"), added::add)).isFalse();
        }
        try (LakeStore named = LakeStore.of(RT, lake.toString(), "risk", Map.of("table.pv", "scenario-pv"))) {
            assertThat(named.tables()).containsExactly("pv");
            assertThat(named.table("pv")).endsWith("/risk/scenario-pv");
        }
        assertThatThrownBy(() -> LakeStore.of(RT, lake.toString(), "risk", Map.of("engine", "hadoop")))
                .hasMessageContaining("native engine only");
        assertThatThrownBy(() -> LakeStore.of(RT, lake.toString(), "../up", Map.of())).hasMessageContaining("escapes");
    }

    @Test
    void aSchemeIsReadOnlyWhereItsProviderIsRegistered() {
        LakeRuntime with = LakeRuntime.builder().storage(new TwoRuntimesTest.Scheme("memx")).build();
        try (LakeStore store = LakeStore.of(with, "memx://feeds", "news", Map.of())) {
            assertThat(store.table("news")).isEqualTo("memx://feeds/news/news");
            assertThat(store.reachable()).isFalse();
        }
        assertThatThrownBy(() -> LakeStore.of(LakeRuntime.builder().build(), "memx://feeds", "news", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
