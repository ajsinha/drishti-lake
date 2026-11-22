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
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The loader summary's {@code version} means the same on every store (TableWriter.commit): it grows with every commit of the table (Delta's
 * version, the Iceberg snapshot's sequence number, the files store's log number); {@code nativeVersion} is the store's own id of that commit,
 * the one a read at a version takes (Iceberg: the snapshot id; Delta and files: the version itself).
 */
class LoadVersionTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"delta", "iceberg", "files"})
    void theVersionGrowsByCommitAndTheNativeIdIsTheStoresOwn(String format) throws Exception {
        LakeRuntime rt = LakeRuntime.builder().options(LakeOptions.builder().home(tmp).tempDir(tmp.resolve("tmp")).build()).build();
        Path lake = tmp.resolve("lake");
        JsonNode first = load(rt, lake, format, "a", 20, 1);
        JsonNode second = load(rt, lake, format, "b", 12, 2);
        JsonNode third = load(rt, lake, format, "c", 7, 3);
        long v1 = first.path("version").asLong();
        long v2 = second.path("version").asLong();
        long v3 = third.path("version").asLong();
        assertThat(v2).as(format + ": a later commit has a larger version").isGreaterThan(v1);
        assertThat(v3).isGreaterThan(v2);
        var versions = Stores.of(rt, format).versions(rt, new TableRef(lake.toString(), "risk", "scenario-pv")).stream().map(TableVersion::version).toList();
        assertThat(versions).as(format + ": the native id is a version a read takes")
                .contains(first.path("nativeVersion").asLong(), second.path("nativeVersion").asLong(), third.path("nativeVersion").asLong());
        if (format.equals("iceberg")) {
            assertThat(third.path("nativeVersion").asLong()).as("the snapshot id, not the sequence number").isNotEqualTo(v3);
            assertThat(v3 - v1).as("one sequence number a commit").isEqualTo(2);
        } else {
            assertThat(third.path("nativeVersion").asLong()).isEqualTo(v3);
        }
    }

    /** One load of one date (replaced each time), its summary's commit of that date. */
    private JsonNode load(LakeRuntime rt, Path lake, String format, String name, int n, int seed) throws Exception {
        Path in = Files.createDirectories(tmp.resolve("in-" + name));
        PvData.byDate(in, PvData.lines(n, 2, 1, seed));
        Path summary = tmp.resolve("summary-" + name + ".json");
        Path cfg = Files.writeString(tmp.resolve("load-" + name + ".yaml"), PvData.config(in + "/*", lake.toString(), format, "  mode: replace-date"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(rt, new String[] {"--config", cfg.toString(), "--summary", summary.toString(), "-q"}, new PrintStream(out)))
                .as(out.toString()).isEqualTo(LakeLoad.OK);
        JsonNode dates = new ObjectMapper().readTree(summary.toFile()).path("commit").path("dates");
        return dates.elements().next();
    }
}
