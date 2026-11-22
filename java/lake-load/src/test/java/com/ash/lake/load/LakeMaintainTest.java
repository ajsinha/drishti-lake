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

import com.ash.lake.LakeOptions;
import com.ash.lake.LakeRuntime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link LakeMaintain} on every store: compaction to one file a date, vacuum of what older versions held, the uncommitted-file rule. */
class LakeMaintainTest {

    @TempDir
    Path tmp;

    private LakeRuntime runtime() {
        return LakeRuntime.builder().options(LakeOptions.builder().home(tmp).tempDir(tmp.resolve("tmp")).build()).build();
    }

    private void load(LakeRuntime rt, Path lake, String format, int seed, String mode) throws IOException {
        Path in = Files.createDirectories(tmp.resolve("in-" + seed));
        PvData.byDate(in, PvData.lines(12, 2, 1, seed));
        String cfg = PvData.config(in + "/*", lake.toString(), format) + (mode == null ? "" : "\n  mode: " + mode + "\n");
        Path file = Files.writeString(tmp.resolve("load-" + format + seed + ".yaml"), cfg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(rt, new String[] {"--config", file.toString(), "-q"}, new PrintStream(out))).as(out.toString()).isEqualTo(LakeLoad.OK);
    }

    private static long dataFiles(Path lake) throws IOException {
        try (Stream<Path> s = Files.walk(lake)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return Files.isRegularFile(p) && !n.startsWith(".") && (n.endsWith(".parquet") || n.endsWith(".jsonl") && !n.equals("scenario-pv.jsonl"));
            }).count();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"delta", "iceberg", "files"})
    @SuppressWarnings("unchecked")
    void compactsAndVacuumsAlike(String format) throws Exception {
        LakeRuntime rt = runtime();
        Path lake = tmp.resolve("lake");
        load(rt, lake, format, 1, "append");
        load(rt, lake, format, 2, "append");
        load(rt, lake, format, 3, "append");
        long before = dataFiles(lake);
        var steps = new LakeMaintain.Steps(0, null, null, 0.5, false, 0, new com.ash.lake.common.RowRetention(null, null, null), true, 128, true, 0L);
        Map<String, Object> dry = LakeMaintain.maintain(rt, lake.toString(), format, "risk/scenario-pv", steps, Instant.now(), true);
        assertThat(dataFiles(lake)).as("a dry run changes nothing").isEqualTo(before);
        if (!format.equals("files")) {
            assertThat((List<String>) ((Map<String, Object>) dry.get("compact")).get("dates")).as(dry.toString()).isNotEmpty();
        }
        // a file no commit names, young: kept by vacuum whatever the hours (a load still running may own it)
        Path stray = Files.list(format.equals("files") ? lake.resolve("risk/_log/scenario-pv") : lake.resolve("risk/scenario-pv"))
                .filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("_")).findFirst()
                .orElse(lake.resolve(format.equals("files") ? "risk/_log/scenario-pv" : "risk/scenario-pv"));
        Path young = Files.writeString(stray.resolve(format.equals("files") ? "young.jsonl" : "young.parquet"), "x");
        Path old = Files.writeString(stray.resolve(format.equals("files") ? "old.jsonl" : "old.parquet"), "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(100 * 3600)));
        Map<String, Object> done = LakeMaintain.maintain(rt, lake.toString(), format, "risk/scenario-pv", steps, Instant.now(), false);
        assertThat(((Map<String, Object>) done.get("after")).get("rows")).isEqualTo(((Map<String, Object>) done.get("before")).get("rows"));
        assertThat(young).as("an uncommitted young file is kept").exists();
        if (!format.equals("iceberg")) {                      // Iceberg's orphans are path-checked by its own walk (a stray name outside data/)
            assertThat(old).as("an uncommitted file older than 72 hours goes").doesNotExist();
        }
        Files.delete(young);
        Files.deleteIfExists(old);
        long after = dataFiles(lake);
        assertThat(after).as(done.toString()).isLessThanOrEqualTo(before);
        // the three appends repeat the same ids: the rows readers see in one file, each older load's copies in a file of its own (the
        // files store: its one day file), so the newest copy is still the one read; a second compaction has nothing to do
        try (var reader = Stores.of(rt, format).open(rt, com.ash.lake.read.LakeAddress.of(lake.toString(), "risk"))) {
            var s = reader.table("scenario-pv").snapshot(com.ash.lake.spi.AsOf.LATEST).orElseThrow();
            for (var d : s.dates()) {
                assertThat(s.day(d).orElseThrow().files()).as(format + " " + d).hasSize(format.equals("files") ? 1 : 3);
                var ids = s.ids(d);
                for (int i = 1; i < ids.size(); i++) {
                    assertThat(ids.id(i - 1)).as("one id once, in order").isLessThan(ids.id(i));
                }
                assertThat(ids.size()).isEqualTo(12);
            }
        }
        Map<String, Object> again = LakeMaintain.maintain(rt, lake.toString(), format, "risk/scenario-pv", steps, Instant.now(), false);
        if (!format.equals("files")) {
            assertThat((List<String>) ((Map<String, Object>) again.get("compact")).get("dates")).as(again.toString()).isEmpty();
        }
    }

    @Test
    void keepDaysKeepsTheAsOfDayAndThatManyBusinessDaysBeforeIt() {
        assertThat(LakeMaintain.keepFrom(java.time.LocalDate.parse("2026-10-29"), 1)).isEqualTo(java.time.LocalDate.parse("2026-10-28"));
        assertThat(LakeMaintain.keepFrom(java.time.LocalDate.parse("2026-11-23"), 2)).isEqualTo(java.time.LocalDate.parse("2026-11-19"));
        assertThat(LakeMaintain.keepFrom(java.time.LocalDate.parse("2026-11-21"), 1)).isEqualTo(java.time.LocalDate.parse("2026-11-20"));
        assertThat(LakeMaintain.keepFrom(java.time.LocalDate.parse("2026-11-02"), 5)).isEqualTo(java.time.LocalDate.parse("2026-10-26"));
        assertThat(LakeMaintain.keepFrom(java.time.LocalDate.parse("2026-10-29"), 0)).isEqualTo(java.time.LocalDate.parse("2026-10-29"));
    }
}
