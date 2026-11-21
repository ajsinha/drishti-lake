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
package com.ash.lake.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.lake.LakeOptions;
import com.ash.lake.LakeRuntime;
import com.ash.lake.delta.write.LakeWriter;
import com.ash.lake.delta.write.TableUpkeep;
import com.ash.lake.memory.store.MemoryStorageProvider;
import com.ash.lake.memory.store.MemoryStores;
import com.ash.lake.spi.RowSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The memory lake behind the product's own seams (step L8 part 2): a table filled from the host's connector through {@link RowSource},
 * the stores held per runtime (two runtimes never share one), and a runtime without memory storage refused by name.
 */
class MemoryRowSourceTest {

    private static LakeRuntime runtime() {
        return LakeRuntime.builder().options(LakeOptions.fromProcess()).storage(new MemoryStorageProvider()).build();
    }

    /** A host with one connector, {@code feed}, holding {@code n} documents of kind {@code quote}. */
    private static RowSource host(int n) {
        return new RowSource() {
            @Override
            public List<Map<String, Object>> documents(String connector, String kind) {
                if (!connector.equals("feed") || !kind.equals("quote")) {
                    throw new IllegalStateException("connector " + connector + " is not running");
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    out.add(Map.of("QUOTE_ID", "Q" + i, "DESK", "D" + (i % 3), "PX", 100.0 + i, "LEGS", List.of(Map.of("LEG", 1))));
                }
                return out;
            }

            @Override
            public List<Map<String, Object>> fetch(String connector, String kind, List<String> ids) {
                return ids.stream().map(id -> Map.<String, Object>of("QUOTE_ID", id, "DESK", "D0", "PX", 1.0)).toList();
            }

            @Override
            public List<Served> memoryOnly() {
                return List.of(new Served("feed", "mem://q/quote", List.of("quote")));
            }
        };
    }

    private static MemoryLake lake(LakeRuntime rt, RowSource host, MemoryLakeSettings.Source source) {
        MemoryLakeSettings.TableSpec t = new MemoryLakeSettings.TableSpec("quote", "q", null, source, "replace", List.of("QUOTE_ID"), null,
                "2026-11-02", List.of("DESK"), null, null, false, null, false, 2, null, null, null);
        MemoryLakeSettings p = new MemoryLakeSettings(Map.of("q", new MemoryLakeSettings.Store(16, 64, "Q-1", true, null, null)), List.of(t),
                new MemoryLakeSettings.Compaction(false, false, null, 0, 1, null, 3, null), null, 1);
        return new MemoryLake(rt, p, new ReplenishSource(host), null, () -> true, null);
    }

    private static int rows(LakeRuntime rt, String path) throws Exception {
        List<TableUpkeep.Staged> s = TableUpkeep.rows(LakeWriter.engineFor(rt, path, Map.of()), path, pv -> true);
        return s == null ? 0 : s.size();
    }

    @Test
    void aTableIsFilledFromTheHostsConnectorAndByIds() throws Exception {
        LakeRuntime rt = runtime();
        try (MemoryLake lake = lake(rt, host(25), new MemoryLakeSettings.Source("feed", "quote", null, null, null, null, null))) {
            assertThat(lake.runNow("quote", "test").outcome()).isEqualTo("ok");
            assertThat(rows(rt, "mem://q/quote")).isEqualTo(25);
        }
        try (MemoryLake lake = lake(rt, host(25), new MemoryLakeSettings.Source("feed", "quote", null, null, null, List.of("A", "B"), null))) {
            assertThat(lake.runNow("quote", "test").outcome()).isEqualTo("ok");
            assertThat(rows(rt, "mem://q/quote")).isEqualTo(2);
        }
    }

    @Test
    void theRetentionGuardReadsTheHostsMemoryOnlyConnectors() {
        Retention guard = new Retention(host(1), k -> k.equals("quote") ? "QTE" : null, store -> "Q-1");
        assertThat(guard.memoryOnly("QTE")).isTrue();
        assertThat(guard.memoryOnlyConnector("feed")).isTrue();
        assertThatThrownBy(() -> guard.require(Retention.Sink.LAKE, List.of("quote"), "This view"))
                .hasMessageContaining("held in memory only (contract Q-1)");
        assertThat(new Retention(RowSource.NONE, null, s -> "").memoryOnly("quote")).isFalse();
    }

    @Test
    void twoRuntimesHoldTheirOwnStores() throws Exception {
        LakeRuntime one = runtime();
        LakeRuntime two = runtime();
        try (MemoryLake lake = lake(one, host(10), new MemoryLakeSettings.Source("feed", "quote", null, null, null, null, null))) {
            assertThat(lake.runNow("quote", "test").outcome()).isEqualTo("ok");
            assertThat(rows(one, "mem://q/quote")).isEqualTo(10);
            assertThat(MemoryStores.of(two).store("q").pool().capBytes()).isZero();
            assertThat(MemoryStores.of(two).store("q").filesIn("mem://q/quote")).isEmpty();
        }
    }

    @Test
    void aRuntimeWithoutMemoryStorageIsRefused() {
        LakeRuntime plain = LakeRuntime.builder().build();
        assertThatThrownBy(() -> MemoryStores.of(plain)).hasMessageContaining("new MemoryStorageProvider()");
    }
}
