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
import com.ash.lake.delta.write.CommitOrder;
import com.ash.lake.delta.write.LakeWriter;
import com.ash.lake.rows.LoadOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.delta.kernel.Operation;
import io.delta.kernel.Table;
import io.delta.kernel.Transaction;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.internal.actions.AddFile;
import io.delta.kernel.internal.actions.SingleAction;
import io.delta.kernel.internal.util.VectorUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The durable Delta commit order ({@link CommitOrder}): each file tagged with the version that added it, a lost race, compaction settling. */
class CommitOrderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private LakeRuntime runtime() {
        return LakeRuntime.builder().options(LakeOptions.builder().home(tmp).tempDir(tmp.resolve("tmp")).build()).build();
    }

    private void load(LakeRuntime rt, Path lake, int seed) throws IOException {
        Path in = Files.createDirectories(tmp.resolve("in-" + seed + "-" + System.nanoTime()));
        PvData.byDate(in, PvData.lines(12, 2, 1, 1));
        String cfg = PvData.config(in + "/*", lake.toString(), "delta") + "\n  mode: append\n";
        Path file = Files.writeString(tmp.resolve("load-" + seed + ".yaml"), cfg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(rt, new String[] {"--config", file.toString(), "-q", "--run-id", "20261121T131500.000-1-1"}, new PrintStream(out)))
                .as(out.toString()).isEqualTo(LakeLoad.OK);
    }

    /** Every add action in the log: its version and its tags (by file name). */
    private static Map<String, long[]> adds(Path table) throws IOException {
        Map<String, long[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(table.resolve("_delta_log"))) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches("\\d{20}\\.json")).sorted().toList()) {
                long v = Long.parseLong(f.getFileName().toString().substring(0, 20));
                for (String line : Files.readAllLines(f)) {
                    JsonNode add = JSON.readTree(line).get("add");
                    if (add != null) {
                        JsonNode tag = add.path("tags").get(LoadOrder.COMMIT_TAG);
                        out.put(CommitOrder.name(add.get("path").asText()), new long[] {v, tag == null ? -1 : Long.parseLong(tag.asText())});
                    }
                }
            }
        }
        return out;
    }

    @Test
    void everyFileCarriesTheVersionThatAddedIt() throws Exception {
        LakeRuntime rt = runtime();
        Path lake = tmp.resolve("lake");
        load(rt, lake, 1);
        load(rt, lake, 2);
        Map<String, long[]> adds = adds(lake.resolve("risk/scenario-pv"));
        assertThat(adds).isNotEmpty();
        adds.forEach((name, v) -> assertThat(v[1]).as(name).isEqualTo(v[0]));
    }

    @Test
    void aCommitThatLostItsVersionIsTaggedWithTheVersionItTook() throws Exception {
        LakeRuntime rt = runtime();
        Path lake = tmp.resolve("lake");
        load(rt, lake, 1);
        String path = lake.resolve("risk/scenario-pv").toString();
        Engine engine = LakeWriter.engineFor(rt, path, Map.of());
        Transaction txn = Table.forPath(engine, path).createTransactionBuilder(engine, "test", Operation.WRITE).withMaxRetries(0).build(engine);
        long read = txn.getReadTableVersion();
        load(rt, lake, 2);                                       // another writer takes version read + 1
        Path parquet;
        try (Stream<Path> s = Files.walk(Path.of(path))) {
            parquet = s.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains("_delta_log")).findFirst().orElseThrow();
        }
        Path copy = Files.copy(parquet, parquet.resolveSibling("copy-" + parquet.getFileName()));
        String rel = Path.of(path).relativize(copy).toString();
        Map<String, String> pv = new java.util.HashMap<>();
        pv.put("business_date", rel.substring(rel.indexOf("business_date=") + 14, rel.indexOf("business_date=") + 24));
        var add = SingleAction.createAddFileSingleAction(AddFile.createAddFileRow(null, rel, VectorUtils.stringStringMapValue(pv), Files.size(copy),
                System.currentTimeMillis(), true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        var done = CommitOrder.commit(engine, path, txn, () -> Table.forPath(engine, path).createTransactionBuilder(engine, "test", Operation.WRITE)
                .withMaxRetries(0).build(engine), new ArrayList<>(List.of(add)));
        assertThat(done.version()).isEqualTo(read + 2);
        assertThat(adds(Path.of(path)).get(copy.getFileName().toString())).containsExactly(read + 2, read + 2);
    }

    @Test
    void compactionSettlesATwoFileDateIntoOneCommit() throws Exception {
        LakeRuntime rt = runtime();
        Path lake = tmp.resolve("lake");
        load(rt, lake, 1);
        load(rt, lake, 2);                                       // the same ids under the same run id: two files of one date
        String path = lake.resolve("risk/scenario-pv").toString();
        var store = Stores.of(rt, "delta");
        List<String> first = DeltaUpkeep.compact(rt, store, lake.toString(), "risk", "scenario-pv", path, 128L << 20, false);
        assertThat(first).as("two commits' files are rewritten into one").isNotEmpty();
        Engine engine = LakeWriter.engineFor(rt, path, Map.of());
        var s = Table.forPath(engine, path).getLatestSnapshot(engine);
        for (String d : first) {
            assertThat(DeltaUpkeep.oneCommit(engine, s, d)).as(d).isTrue();
        }
        assertThat(DeltaUpkeep.compact(rt, store, lake.toString(), "risk", "scenario-pv", path, 128L << 20, false))
                .as("a settled date is left").isEmpty();
    }
}
