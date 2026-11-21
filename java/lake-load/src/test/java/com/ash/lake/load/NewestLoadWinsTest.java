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
import com.ash.lake.read.LakeAddress;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.AsOf;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Two loads of one date with ids in common: every read sees the newest load's copy, whatever the length of the load ids. A load id of
 * 32 characters or more has no usable statistics on Delta (a writer may cut it), so the order cannot come from the statistics alone:
 * a 7-digit process id and a tenth run in one process make {@code <time>-<pid>-<n>.<file>} 32 characters long.
 */
class NewestLoadWinsTest {

    private static final LocalDate D = LocalDate.parse(PvData.DATES[0]);
    /** 31 characters as a load id ({@code .1} added), 32, and 34. */
    private static final String SHORT = "20261121T131500.123-1234567-9";
    private static final String LONG = "20261121T131501.000-1234567-10";
    private static final String LONGER = "20261121T131502.000-1234567-1000";

    @TempDir
    Path tmp;

    @ParameterizedTest(name = "{0}: {1} then {2}")
    @CsvSource({"delta,SHORT,LONG", "delta,LONG,LONGER", "delta,SHORT,SHORT2", "iceberg,SHORT,LONG", "files,SHORT,LONG"})
    void theNewestLoadsCopyIsRead(String format, String first, String second) throws IOException {
        LakeRuntime rt = LakeRuntime.builder().options(LakeOptions.builder().home(tmp).tempDir(tmp.resolve("tmp")).build()).build();
        Path in1 = Files.createDirectories(tmp.resolve("in1"));
        Path in2 = Files.createDirectories(tmp.resolve("in2"));
        PvData.byDate(in1, PvData.lines(30, 3, 1, 6));
        PvData.byDate(in2, PvData.lines(10, 3, 1, 7));                  // ids P-1000000..9 again, other values
        Path both = tmp.resolve("both");
        Path newest = tmp.resolve("newest");
        load(rt, in1, both, format, "overwrite-dates", id(first));
        load(rt, in2, both, format, "append", id(second));
        load(rt, in2, newest, format, "overwrite-dates", id(second));
        var store = Stores.of(rt, format);
        try (var a = store.open(rt, LakeAddress.of(both.toString(), "risk")); var b = store.open(rt, LakeAddress.of(newest.toString(), "risk"))) {
            TableSnapshot sa = a.table("scenario-pv").snapshot(AsOf.LATEST).orElseThrow();
            TableSnapshot sb = b.table("scenario-pv").snapshot(AsOf.LATEST).orElseThrow();
            var ia = sa.ids(D);
            var ib = sb.ids(D);
            assertThat(ia.size()).isEqualTo(30);
            for (int i = 0; i < ib.size(); i++) {
                String id = ib.id(i);
                assertThat(sa.document(D, ia.file(id), id)).as(id).isEqualTo(sb.document(D, ib.file(id), id));
            }
        }
    }

    private static String id(String name) {
        return switch (name) {
            case "SHORT" -> SHORT;
            case "SHORT2" -> "20261121T131503.000-1234567-8";
            case "LONG" -> LONG;
            default -> LONGER;
        };
    }

    private void load(LakeRuntime rt, Path in, Path lake, String format, String mode, String run) throws IOException {
        Path cfg = Files.writeString(tmp.resolve("load-" + lake.getFileName() + "-" + in.getFileName() + ".yaml"),
                PvData.config(in + "/*", lake.toString(), format, "  mode: " + mode));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(rt, new String[] {"--config", cfg.toString(), "--run-id", run, "-q"}, new PrintStream(out)))
                .as(out.toString()).isEqualTo(LakeLoad.OK);
    }
}
