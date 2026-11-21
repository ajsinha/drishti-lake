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

import com.ash.lake.LakeRuntime;
import com.ash.lake.config.Lake;
import com.ash.lake.config.Lakes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lake check reads a table's shape through the official libraries (step L7): the Delta Kernel's snapshot, Iceberg's current metadata.
 * A lake file that disagrees with the table on disk is reported (partition columns, missing promoted columns); a connector naming no
 * known lake is an error.
 */
class LakeCheckTest {

    @TempDir
    Path tmp;

    private static final LakeRuntime RT = LakeRuntime.fromProcess();

    private Path load(String format) throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in-" + format));
        PvData.write(in.resolve("pv.2026-10-27.jsonl"), PvData.lines(20, 3, 1, 3));
        Path lake = tmp.resolve("lake-" + format);
        Path cfg = Files.writeString(tmp.resolve(format + ".yaml"), PvData.config(in + "/*", lake.toString(), format));
        assertThat(LakeLoad.run(RT, new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8))).isZero();
        return lake;
    }

    @Test
    void aDeltaTableDisagreeingWithItsLakeFileIsReported() throws Exception {
        Path root = load("delta");
        Lake l = Lakes.parse("lake: main\nroot: '" + root + "'\ntables:\n  risk.scenario-pv:\n    kind: scenario-pv\n"
                + "    partition-by: [DESK]\n    promote: [LEGAL_ENTITY, PORTFOLIO]\n", "main", k -> null);
        List<LakeCheck.Finding> f = LakeCheck.check(RT, Lakes.of(l), Map.of("x", Map.entry("delta", Map.of("lake", "nowhere"))));
        assertThat(f).anyMatch(x -> x.level().equals("error") && x.message().contains("partitioned by [business_date] on disk"));
        assertThat(f).anyMatch(x -> x.message().contains("promoted columns missing from the table: [PORTFOLIO]"));
        assertThat(f).anyMatch(x -> x.message().contains("names lake nowhere"));
    }

    @Test
    void anIcebergTableIsReadThroughItsCurrentMetadata() throws Exception {
        Path root = load("iceberg");
        Lake l = Lakes.parse("lake: ice\nformat: iceberg\nroot: '" + root + "'\ntables:\n  risk.scenario-pv:\n    kind: scenario-pv\n"
                + "    promote: [LEGAL_ENTITY, PORTFOLIO]\n", "ice", k -> null);
        List<LakeCheck.Finding> f = LakeCheck.check(RT, Lakes.of(l), Map.of());
        assertThat(f).noneMatch(x -> x.message().contains("partitioned by"));
        assertThat(f).anyMatch(x -> x.message().contains("promoted columns missing from the table: [PORTFOLIO]"));
        assertThat(f).noneMatch(x -> x.message().contains("cannot read"));
    }

    @Test
    void theCommandLineSaysTheFindingsAsJson() throws Exception {
        Path root = load("delta");
        Path lakes = Files.createDirectories(tmp.resolve("lakes"));
        Files.writeString(lakes.resolve("main.yaml"), "lake: main\nroot: '" + root + "'\ntables:\n  risk.scenario-pv:\n    kind: scenario-pv\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = LakeCheck.run(RT, new String[] {"check", "--dir", lakes.toString()}, new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"errors\" : 0");
        assertThat(code).isZero();
    }
}
