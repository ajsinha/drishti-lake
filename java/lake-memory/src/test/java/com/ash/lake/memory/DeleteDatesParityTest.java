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

import com.ash.lake.load.LakeLoad;
import com.ash.lake.load.LakeRetention;
import com.ash.lake.load.TableDump;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage parity of {@code lake delete} (docs/guides/DATA_LAKE_GUIDE.md section 5.8, {@code LakeRetention --dates}): the same rows over
 * three business dates in a Delta table, an Iceberg table and a files store; deleting one date (and a range) leaves the same rows in each,
 * a dry run changes nothing, a date the table does not hold is not matched, and delta-rs reads the Delta result.
 */
class DeleteDatesParityTest {

    private static final List<String> DATES = List.of("2026-11-13", "2026-11-16", "2026-11-17");

    @TempDir
    Path dir;

    /** Two trades a date: T<date index><n>. */
    private static List<String[]> trades() {
        List<String[]> out = new ArrayList<>();
        for (int d = 0; d < DATES.size(); d++) {
            for (int n = 0; n < 2; n++) {
                out.add(new String[] {"T" + d + n, DATES.get(d)});
            }
        }
        return out;
    }

    private static String doc(String[] t) {
        return "{\"TRADE_ID\":\"" + t[0] + "\",\"BOOK\":\"B\"}";
    }

    private Path load(String format) throws Exception {
        Path in = Files.createDirectories(dir.resolve("in-" + format));
        for (String d : DATES) {
            Files.write(in.resolve("trades." + d + ".jsonl"), trades().stream().filter(t -> t[1].equals(d)).map(DeleteDatesParityTest::doc).toList(),
                    StandardCharsets.UTF_8);
        }
        Path lake = dir.resolve("lake-" + format);
        Path cfg = dir.resolve("load-" + format + ".yaml");
        Files.writeString(cfg, "source:\n  files: '" + in + "'\n  id: TRADE_ID\n  promote: [BOOK]\ntarget:\n  format: " + format + "\n  lake: '" + lake
                + "'\n  domain: risk\n  kind: trades\n");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(MemoryRuntime.RT, new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(said, true, StandardCharsets.UTF_8)))
                .as(said.toString(StandardCharsets.UTF_8)).isZero();
        return lake;
    }

    private Path files() throws Exception {
        Path root = dir.resolve("lake-files");
        for (String d : DATES) {
            Path day = Files.createDirectories(root.resolve("risk").resolve(d));
            List<String> lines = new ArrayList<>();
            for (String[] t : trades()) {
                if (t[1].equals(d)) {
                    lines.add("{\"kind\":\"trades\",\"id\":\"" + t[0] + "\",\"date\":\"" + d + "\",\"doc\":{\"TRADE_ID\":\"" + t[0] + "\"},\"columns\":{\"BOOK\":\"B\"}}");
                }
            }
            Files.write(day.resolve("trades.jsonl"), lines, StandardCharsets.UTF_8);
        }
        return root;
    }

    private TreeSet<String> ids(String format, Path root) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        if (format.equals("files")) {
            for (String d : DATES) {
                Path f = root.resolve("risk").resolve(d).resolve("trades.jsonl");
                if (Files.exists(f)) {
                    Files.readAllLines(f).forEach(l -> out.add(l.replaceAll("^\\{\"kind\":\"trades\",\"id\":\"([^\"]+)\".*", "$1")));
                }
            }
            return out;
        }
        Map<String, Map<String, Object>> dump = format.equals("delta") ? TableDump.delta(root.resolve("risk/trades").toString(), Map.of())
                : TableDump.iceberg(root.toString(), "risk", "trades", Map.of());
        dump.keySet().forEach(k -> out.add(k.substring(k.indexOf('|') + 1)));
        return out;
    }

    private Path root(String format) throws Exception {
        return format.equals("files") ? files() : load(format);
    }

    @Test
    void deletingADateLeavesTheSameRowsInEveryStore() throws Exception {
        for (String format : List.of("delta", "iceberg", "files")) {
            Path root = root(format);
            TreeSet<String> all = ids(format, root);
            assertThat(all).as(format).hasSize(6);
            Map<String, Object> dry = LakeRetention.dropDates(MemoryRuntime.RT, root.toString(), format, "risk/trades", LakeRetention.dates("2026-11-16"), true);
            assertThat(dry.get("held")).as(format).isEqualTo(DATES);
            assertThat(dry.get("dropped")).as(format).isEqualTo(2L);
            assertThat(ids(format, root)).as(format + ": a dry run changes nothing").isEqualTo(all);
            Map<String, Object> missing = LakeRetention.dropDates(MemoryRuntime.RT, root.toString(), format, "risk/trades", LakeRetention.dates("2026-11-15"), true);
            assertThat((List<?>) missing.get("dates")).as(format + ": a date not held is not matched").isEmpty();
            Map<String, Object> done = LakeRetention.dropDates(MemoryRuntime.RT, root.toString(), format, "risk/trades", LakeRetention.dates("2026-11-16"), false);
            assertThat(((List<?>) done.get("dates"))).as(format).hasSize(1);
            assertThat(ids(format, root)).as(format).containsExactly("T00", "T01", "T20", "T21");
            Map<String, Object> after = LakeRetention.dropDates(MemoryRuntime.RT, root.toString(), format, "risk/trades", LakeRetention.dates("2026-11-01..2026-11-30"), true);
            assertThat(after.get("held")).as(format + ": the date is gone").isEqualTo(List.of("2026-11-13", "2026-11-17"));
            LakeRetention.dropDates(MemoryRuntime.RT, root.toString(), format, "risk/trades", LakeRetention.dates("2026-11-14..2026-11-30"), false);
            assertThat(ids(format, root)).as(format + ": a range").containsExactly("T00", "T01");
        }
        deltaRsReads(dir.resolve("lake-delta/risk/trades"), "T00", "T01");
    }

    @Test
    void aBadDateIsRefusedNotMatchedAsNothing() {
        assertThatThrownBy(() -> LakeRetention.dates("2026-11-31")).isInstanceOf(java.time.format.DateTimeParseException.class);
        assertThatThrownBy(() -> LakeRetention.dates("2026-11-20..2026-11-10")).hasMessageContaining("ends before it starts");
        Predicate<String> p = LakeRetention.dates("2026-11-13,2026-11-16..2026-11-17");
        assertThat(DATES.stream().filter(p).toList()).isEqualTo(DATES);
        assertThat(p.test("2026-11-14")).isFalse();
    }

    @Test
    void theCommandLineSaysWhatItDeleted() throws Exception {
        Path root = load("delta");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        int code = LakeRetention.run(MemoryRuntime.RT, new String[] {"--lake", root.toString(), "--format", "delta", "--table", "risk/trades", "--dates", "2026-11-17", "--json"},
                new PrintStream(said, true, StandardCharsets.UTF_8));
        String out = said.toString(StandardCharsets.UTF_8);
        assertThat(code).as(out).isZero();
        assertThat(out).contains("\"dropped\":2").contains("\"version\":");
        assertThat(ids("delta", root)).containsExactly("T00", "T01", "T10", "T11");
    }

    /** delta-rs reads what was committed (com.ash.lake.tck.DeltaRs: uv with the pinned deltalake; never skipped). */
    private static void deltaRsReads(Path table, String... expected) throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        com.ash.lake.tck.DeltaRs.read(table, "id").path("rows").forEach(r -> ids.add(r.path("id").asText()));
        java.util.Collections.sort(ids);
        assertThat(String.join(",", ids)).as("delta-rs read").isEqualTo(String.join(",", expected));
    }
}
