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

import com.ash.lake.LakeOptions;
import com.ash.lake.LakeRuntime;
import com.ash.lake.common.FileDates;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loaders' today is the calendar date in the business zone ({@code lake.business.zone}), never the JVM's default zone. At any
 * instant {@code Pacific/Kiritimati} (UTC+14) is one or two days ahead of {@code Etc/GMT+12} (UTC-12), so the day after today in the
 * one is today (or yesterday) in the other: a load for it is refused in the western zone and accepted in the eastern, whatever zone
 * the JVM runs in.
 */
class BusinessZoneTest {

    private static final ZoneId EAST = ZoneId.of("Pacific/Kiritimati");
    private static final ZoneId WEST = ZoneId.of("Etc/GMT+12");

    @TempDir
    Path tmp;

    private static LakeRuntime runtime(Path home, ZoneId zone) {
        return LakeRuntime.builder().options(LakeOptions.builder().home(home).tempDir(home.resolve("tmp"))
                .set(LakeOptions.BUSINESS_ZONE, zone.getId()).build()).build();
    }

    private int load(LakeRuntime rt, Path file, Path lake, String... more) throws Exception {
        Path cfg = Files.writeString(Files.createTempFile(tmp, "load", ".yaml"), "source: {files: '" + file + "', id: POSITION_ID}\n"
                + "target: {format: delta, path: '" + lake + "/risk/scenario-pv', domain: risk, kind: scenario-pv, mode: replace}\n");
        List<String> args = new java.util.ArrayList<>(List.of("--config", cfg.toString(), "-q"));
        args.addAll(List.of(more));
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        int code = LakeLoad.run(rt, args.toArray(new String[0]), new PrintStream(said, true, StandardCharsets.UTF_8));
        last = said.toString(StandardCharsets.UTF_8);
        return code;
    }

    private String last;

    @Test
    void theDayAfterTodayInTheWestIsRefusedThereAndLoadsInTheEast() throws Exception {
        LocalDate day = LocalDate.now(WEST).plusDays(1);              // never after today in Kiritimati
        Path dir = Files.createDirectories(tmp.resolve("in"));
        Path named = Files.write(dir.resolve("scenario-pv." + day + ".jsonl"), List.of("{\"POSITION_ID\":\"P1\",\"PV\":1}"));
        Path plain = Files.write(dir.resolve("scenario-pv.jsonl"), List.of("{\"POSITION_ID\":\"P2\",\"PV\":2}"));

        // the name's date and a date given (--as-of): refused in the west's business zone, nothing written
        assertThat(load(runtime(tmp.resolve("w"), WEST), named, tmp.resolve("lake-w"))).as(last).isNotZero();
        assertThat(last).contains("in the future");
        assertThat(load(runtime(tmp.resolve("w"), WEST), plain, tmp.resolve("lake-w"), "--as-of", day.toString())).as(last).isNotZero();
        assertThat(last).contains("business date " + day).contains("in the future");
        assertThat(Files.exists(tmp.resolve("lake-w/risk/scenario-pv/_delta_log"))).isFalse();

        // the same file and date are today (or earlier) in the east: loaded under that date
        assertThat(load(runtime(tmp.resolve("e"), EAST), named, tmp.resolve("lake-e"))).as(last).isZero();
        assertThat(load(runtime(tmp.resolve("e"), EAST), plain, tmp.resolve("lake-e2"), "--as-of", day.toString())).as(last).isZero();
        assertThat(TableDump.delta(tmp.resolve("lake-e/risk/scenario-pv").toString(), java.util.Map.of()).values())
                .extracting(r -> String.valueOf(r.get("business_date"))).containsExactly(day.toString());
    }

    @Test
    void theZoneIsTheRuntimesAndAWrongOneIsRefusedWithItsKey() {
        assertThat(LakeOptions.builder().build().businessZone()).isEqualTo(ZoneId.of(LakeOptions.DEFAULT_BUSINESS_ZONE));
        assertThat(LakeOptions.builder().set(LakeOptions.BUSINESS_ZONE, "Asia/Kolkata").build().businessZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThatThrownBy(() -> LakeOptions.builder().set(LakeOptions.BUSINESS_ZONE, "Mars/Olympus").build().businessZone())
                .hasMessageContaining("lake.business.zone").hasMessageContaining("Mars/Olympus");
        // a name's date is judged against the zone's today
        LocalDate day = LocalDate.now(WEST).plusDays(1);
        assertThat(FileDates.parse("pv." + day + ".jsonl", FileDates.today(WEST)).error()).contains("in the future");
        assertThat(FileDates.parse("pv." + day + ".jsonl", FileDates.today(EAST)).date()).isEqualTo(day);
    }
}
