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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.stream.Stream;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LakeMaintain} on an Iceberg lake whose tables a REST catalog holds (AWS Glue's Iceberg endpoint, Polaris, Nessie), against a
 * catalog in the test process ({@link RestCatalogStub}): the catalog given by {@code --set} or by a lake file's {@code catalog:}, its
 * secrets only from the environment; compaction, the manifests and keep-days committed through the catalog, every row kept.
 */
class LakeMaintainCatalogTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TableIdentifier TABLE = TableIdentifier.of("risk", "scenario-pv");

    @TempDir
    Path tmp;

    LakeRuntime rt;
    RestCatalogStub catalog;

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(tmp.resolve("lakes"));
        rt = LakeRuntime.builder().options(LakeOptions.builder().home(tmp).tempDir(tmp.resolve("tmp"))
                .set(LakeOptions.LAKES_DIR, tmp.resolve("lakes").toString()).build()).settings(Map.of("CAT_TOKEN", "t0k")).build();
        Path lake = tmp.resolve("lake");
        for (int seed = 1; seed <= 2; seed++) {                 // two appends of the same ids: two files a date
            Path in = Files.createDirectories(tmp.resolve("in-" + seed));
            PvData.byDate(in, PvData.lines(30, 2, 3, 1));
            Path file = Files.writeString(tmp.resolve("load-" + seed + ".yaml"), PvData.config(in + "/*", lake.toString(), "iceberg") + "\n  mode: append\n");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(LakeLoad.run(rt, new String[] {"--config", file.toString(), "-q"}, new PrintStream(out))).as(out.toString()).isEqualTo(LakeLoad.OK);
        }
        var io = new com.ash.lake.iceberg.io.DrishtiFileIO(rt);
        io.initialize(Map.of());
        catalog = new RestCatalogStub(io, tmp.resolve("warehouse").toString());
        catalog.catalog.adopt(TABLE, latestMetadata(lake.resolve("risk/scenario-pv/metadata")));
    }

    @AfterEach
    void stop() {
        catalog.close();
    }

    /** The table's newest metadata file (the latest {@code last-updated-ms}). */
    private static String latestMetadata(Path dir) throws IOException {
        Path best = null;
        long at = -1;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".metadata.json")).toList()) {
                long t = JSON.readTree(p.toFile()).path("last-updated-ms").asLong();
                if (t > at) {
                    at = t;
                    best = p;
                }
            }
        }
        return best.toAbsolutePath().toString();
    }

    private JsonNode maintain(int exit, String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int got = LakeMaintain.run(rt, args, new PrintStream(out));
        assertThat(got).as(out.toString()).isEqualTo(exit);
        return exit == 0 ? JSON.readTree(out.toString()) : JSON.createObjectNode().put("said", out.toString());
    }

    private SortedMap<LocalDate, Long> dates() throws Exception {
        return LakeMaintain.catalogDates(rt, Map.of("catalog", "rest", "uri", catalog.uri(), "token", "t0k"), "risk", "scenario-pv");
    }

    @Test
    void compactsAndRegroupsThroughTheCatalogGivenBySet() throws Exception {
        SortedMap<LocalDate, Long> before = dates();
        String pointer = catalog.catalog.pointers.get(TABLE);
        JsonNode done = maintain(0, "--format", "iceberg", "--table", "risk/scenario-pv", "--set", "catalog=rest", "--set", "uri=" + catalog.uri(),
                "--set", "token=${CAT_TOKEN}", "--compact", "--checkpoint", "--vacuum-hours", "0", "--json");
        assertThat(done.path("compact").path("dates")).as(done.toString()).isNotEmpty();
        assertThat(done.path("after").path("rows")).isEqualTo(done.path("before").path("rows"));
        assertThat(dates()).as("every row kept").isEqualTo(before);
        assertThat(catalog.catalog.pointers.get(TABLE)).as("committed through the catalog").isNotEqualTo(pointer);
        List<String> unsigned = new ArrayList<>(catalog.calls.stream().filter(c -> !c.endsWith(" Bearer t0k")).toList());
        assertThat(unsigned).as("every call with the token from the environment").isEmpty();
        // a second run has nothing to compact
        JsonNode again = maintain(0, "--format", "iceberg", "--table", "risk/scenario-pv", "--set", "catalog=rest", "--set", "uri=" + catalog.uri(),
                "--set", "token=${CAT_TOKEN}", "--compact", "--json");
        assertThat(again.path("compact").path("dates")).as(again.toString()).isEmpty();
    }

    @Test
    void keepDaysThroughALakeFilesCatalog() throws Exception {
        Files.writeString(tmp.resolve("lakes/glue.yaml"), """
                lake: glue
                format: iceberg
                catalog:
                  type: rest
                  uri: '%s'
                  token: '${CAT_TOKEN}'
                """.formatted(catalog.uri()));
        SortedMap<LocalDate, Long> before = dates();
        assertThat(before).hasSize(3);
        JsonNode done = maintain(0, "--lake", "glue", "--table", "risk/scenario-pv", "--keep-days", "1", "--as-of", before.lastKey().toString(),
                "--force-drop", "--json");
        SortedMap<LocalDate, Long> after = dates();
        assertThat(after).as(done.toString()).containsKey(before.lastKey()).hasSizeLessThan(before.size());
        assertThat(done.path("format").asText()).isEqualTo("iceberg");
    }

    @Test
    void aSecretIsTakenFromTheEnvironmentOnly() throws Exception {
        JsonNode said = maintain(1, "--format", "iceberg", "--table", "risk/scenario-pv", "--set", "catalog=rest", "--set", "uri=" + catalog.uri(),
                "--set", "token=t0k", "--compact");
        assertThat(said.path("said").asText()).contains("--set token: a secret is never given on the command line");
        assertThat(catalog.calls).as("nothing asked of the catalog").isEmpty();
    }

    @Test
    void catalogSettingsAreIcebergs() throws Exception {
        JsonNode said = maintain(1, "--format", "delta", "--table", "risk/scenario-pv", "--set", "catalog=rest", "--set", "uri=" + catalog.uri(),
                "--compact");
        assertThat(said.path("said").asText()).contains("catalog settings (--set) are Iceberg's");
    }
}
