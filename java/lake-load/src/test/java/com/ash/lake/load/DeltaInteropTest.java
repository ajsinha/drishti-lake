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
import com.ash.lake.delta.write.LakeCli;
import com.ash.lake.spi.Row;
import com.ash.lake.spi.RowScan;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import com.ash.lake.tck.DeltaRs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Delta interop with delta-rs, both ways (owner rule: 100% Delta compliant, no workaround; never skipped): partition values of every
 * special-character class (space, %, :, /, \, ., .., control characters, =, #, ?, quotes, non-ASCII, a long value, the Hive null's own
 * name, an empty string, a null) and a date partition.
 * <ol>
 * <li>Our lake writer writes; delta-rs ({@code DeltaTable} and {@code pyarrow.dataset}) reads every row and value; each folder is the
 * Hive-escaped value exactly as Spark names it; each {@code add.path} decodes to a file on disk.</li>
 * <li>Our loader writes ids and texts of the same values; delta-rs reads them.</li>
 * <li>delta-rs writes; the Delta store (the Kernel, through the {@code TableStore} SPI) reads every row and value.</li>
 * </ol>
 * An empty partition string reads back as null, as Spark and delta-rs read it.
 */
class DeltaInteropTest {

    static final List<String> VALUES = Arrays.asList("Money market", " lead", "trail ", "100%", "%2F", "%", "a:b", "x/y", "/lead", "trail/",
            "a//b", "../escape", ".", "..", "a.b", "back\\slash", "..\\up", "tab\there", "new\nline", "ctl\u0001", "del\u007f", "k=v", "a#b",
            "a?b", "a*b", "q\"q", "it's", "{c}", "[b]", "a^b", "a|b", "<a>", "a&b", "a+b", "a~b", "a@b", "a!b", "a$b", "a,b", "a;b",
            "Z\u00fcrich", "\u6771\u4eac", "\ud83d\ude00\ud83d\ude80", "x".repeat(200), "__HIVE_DEFAULT_PARTITION__", "", null);
    static final List<String> DATES = List.of("2026-11-05", "2026-11-06");
    private static final String NULL = "__HIVE_DEFAULT_PARTITION__";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private LakeRuntime runtime() {
        return LakeRuntime.builder().options(LakeOptions.builder().home(tmp).tempDir(tmp.resolve("tmp")).build()).build();
    }

    /** Spark's {@code ExternalCatalogUtils.escapePathName}: the Hive folder name of a partition value. */
    static String sparkEscape(String v) {
        StringBuilder b = new StringBuilder();
        v.codePoints().forEach(c -> {
            if (c < 32 || c == 0x7f || "\"#%'*/:=?\\{[]^".indexOf(c) >= 0) {
                b.append('%').append(String.format("%02X", c));
            } else {
                b.appendCodePoint(c);
            }
        });
        return b.toString();
    }

    /** id -> [business_date, PRODUCT_GROUP] as every reader must see them (an empty string is null). */
    static Map<String, List<String>> expected() {
        Map<String, List<String>> out = new TreeMap<>();
        for (int d = 0; d < DATES.size(); d++) {
            for (int i = 0; i < VALUES.size(); i++) {
                String v = VALUES.get(i);
                out.put(String.format("%d-%02d", d, i), Arrays.asList(DATES.get(d), v == null || v.isEmpty() ? null : v));
            }
        }
        return out;
    }

    private static Map<String, List<String>> rows(JsonNode rows) {
        Map<String, List<String>> out = new TreeMap<>();
        rows.forEach(r -> out.put(r.path("id").asText(), Arrays.asList(r.path("business_date").asText(),
                r.path("PRODUCT_GROUP").isNull() ? null : r.path("PRODUCT_GROUP").asText())));
        return out;
    }

    private static Map<String, List<String>> store(LakeRuntime rt, TableRef t) throws IOException {
        Map<String, List<String>> out = new TreeMap<>();
        try (RowScan s = Stores.of(rt, "delta").scan(rt, t, ScanRequest.all().columns("id", "business_date", "PRODUCT_GROUP"))) {
            while (s.hasNext()) {
                Row r = s.next();
                Object v = r.get(2);
                out.put((String) r.get(0), Arrays.asList(r.get(1).toString(), v == null ? null : v.toString()));
            }
        }
        return out;
    }

    @Test
    void ourWriterWritesDeltaRsReads() throws IOException {
        Path table = tmp.resolve("lake/risk/interop");
        ArrayNode parts = JSON.createArrayNode();
        for (Map.Entry<String, List<String>> e : expected().entrySet()) {
            int i = Integer.parseInt(e.getKey().substring(2));
            ObjectNode line = JSON.createObjectNode().put("id", e.getKey()).put("doc", JSON.createObjectNode().put("V", VALUES.get(i)).toString());
            line.putObject("columns");
            Path f = tmp.resolve("rows-" + e.getKey());
            Files.writeString(f, JSON.writeValueAsString(line) + "\n");
            ObjectNode p = parts.addObject().put("file", f.toString());
            p.putObject("values").put("business_date", e.getValue().get(0)).put("PRODUCT_GROUP", VALUES.get(i));
        }
        ObjectNode m = JSON.createObjectNode().put("table", table.toString()).put("mode", "overwrite-dates").put("batchRows", 100);
        m.putArray("partitionColumns").add("business_date").add("PRODUCT_GROUP");
        ArrayNode cols = m.putArray("columns");
        for (String[] c : new String[][] {{"id", "string"}, {"doc", "string"}, {"business_date", "date"}, {"PRODUCT_GROUP", "string"}}) {
            cols.addObject().put("name", c[0]).put("type", c[1]);
        }
        m.putArray("promoted");
        m.putArray("statsColumns").add("id").add("business_date");
        m.set("parts", parts);
        Path manifest = tmp.resolve("manifest.json");
        JSON.writeValue(manifest.toFile(), m);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(LakeCli.run(new String[] {"lake", "write", "--manifest", manifest.toString()}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8))).as(err.toString(StandardCharsets.UTF_8)).isZero();

        for (String date : DATES) {                                   // the folders: Hive escaping exactly as Spark names them
            Set<String> folders = new TreeSet<>();
            try (Stream<Path> s = Files.list(table.resolve("business_date=" + date))) {
                s.forEach(p -> folders.add(p.getFileName().toString()));
            }
            Set<String> want = new TreeSet<>();
            VALUES.forEach(v -> want.add("PRODUCT_GROUP=" + (v == null || v.isEmpty() ? NULL : sparkEscape(v))));
            assertThat(folders).isEqualTo(want);
        }
        JsonNode read = DeltaRs.read(table, "id", "business_date", "PRODUCT_GROUP");
        assertThat(rows(read.path("rows"))).isEqualTo(expected());
        assertThat(rows(read.path("dataset"))).isEqualTo(expected());
        assertThat(read.path("missing")).isEmpty();
        for (JsonNode add : read.path("adds")) {                      // add.path: a URI that decodes to the file's path on disk
            assertThat(table.resolve(URLDecoder.decode(add.asText().replace("+", "%2B"), StandardCharsets.UTF_8))).as(add.asText()).isRegularFile();
        }
        assertThat(store(runtime(), new TableRef(tmp.resolve("lake").toString(), "risk", "interop"))).isEqualTo(expected());
    }

    @Test
    void ourLoaderWritesDeltaRsReads() throws IOException {
        Path in = Files.createDirectories(tmp.resolve("in"));
        Set<String> ids = new TreeSet<>();
        for (String date : DATES) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < VALUES.size(); i++) {
                String id = i + ":" + (VALUES.get(i) == null ? "" : VALUES.get(i));
                ids.add(date + "|" + id);
                lines.add(JSON.writeValueAsString(JSON.createObjectNode().put("POSITION_ID", id).put("BUSINESS_DATE", date).put("TEXT", VALUES.get(i))));
            }
            Files.write(in.resolve("interop." + date + ".jsonl"), lines, StandardCharsets.UTF_8);
        }
        Path lake = tmp.resolve("lake");
        Path cfg = Files.writeString(tmp.resolve("load.yaml"), "source:\n  files: '" + in + "/*'\n  id: POSITION_ID\n  promote: [TEXT]\n"
                + "target:\n  format: delta\n  path: '" + lake + "/risk/interop'\n  domain: risk\n  kind: interop\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(runtime(), new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(out))).as(out.toString()).isEqualTo(LakeLoad.OK);
        JsonNode read = DeltaRs.read(lake.resolve("risk/interop"), "id", "business_date", "TEXT");
        Set<String> got = new TreeSet<>();
        read.path("rows").forEach(r -> {
            got.add(r.path("business_date").asText() + "|" + r.path("id").asText());
            int i = Integer.parseInt(r.path("id").asText().split(":", 2)[0]);
            assertThat(r.path("TEXT").isNull() ? null : r.path("TEXT").asText()).isEqualTo(VALUES.get(i));
        });
        assertThat(got).isEqualTo(ids);
        assertThat(read.path("missing")).isEmpty();
    }

    @Test
    void deltaRsWritesWeRead() throws IOException {
        Path lake = tmp.resolve("lake");
        Path table = Files.createDirectories(lake.resolve("risk")).resolve("interop");
        ObjectNode spec = JSON.createObjectNode();
        spec.putObject("columns").put("id", "string").put("business_date", "date").put("PRODUCT_GROUP", "string").put("doc", "string");
        ArrayNode rows = spec.putArray("rows");
        for (Map.Entry<String, List<String>> e : expected().entrySet()) {
            String v = VALUES.get(Integer.parseInt(e.getKey().substring(2)));
            rows.addObject().put("id", e.getKey()).put("business_date", e.getValue().get(0)).put("PRODUCT_GROUP", v).put("doc", "{}");
        }
        DeltaRs.write(table, spec, "business_date", "PRODUCT_GROUP");
        assertThat(store(runtime(), new TableRef(lake.toString(), "risk", "interop"))).isEqualTo(expected());
    }
}
