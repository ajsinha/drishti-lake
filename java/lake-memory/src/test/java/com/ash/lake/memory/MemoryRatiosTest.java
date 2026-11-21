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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.lake.delta.write.LakeManifest;
import com.ash.lake.delta.write.LakeWriter;
import com.ash.lake.memory.store.MemoryStorage;
import com.ash.lake.memory.store.MemoryStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The memory a table costs: synthetic tables (10,000
 * rows each, seeded) written as the disk lake writes them (Snappy) and as the in-memory lake does (sorted by key, zstd), against
 * their decoded JSON. Prints one line a table; asserts only that memory is smaller than the decoded rows and not larger than the disk.
 */
class MemoryRatiosTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    @AfterEach
    void drop() {
        MemoryRuntime.STORES.remove("ratios");
    }

    /** {@code n} synthetic documents of a table: a key, a few repeated words, numbers, a date and a nested leg list (seeded, so stable). */
    private static List<ObjectNode> synthetic(String table, String key, int n) {
        java.util.Random r = new java.util.Random(table.hashCode());
        String[] words = {"RATES", "FX", "CREDIT", "EQUITY", "COMMODITY"};
        List<ObjectNode> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ObjectNode d = JSON.createObjectNode().put(key, table.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", "") + "-" + (100_000 + i));
            d.put("DESK", "DESK-" + words[r.nextInt(words.length)]).put("BOOK", "BOOK-" + r.nextInt(40)).put("CCY", r.nextBoolean() ? "USD" : "EUR");
            d.put("AMOUNT", Math.round(r.nextGaussian() * 1e6) / 100.0).put("QUANTITY", r.nextInt(10_000) - 5_000).put("TRADE_DATE", "2026-10-0" + (1 + r.nextInt(9)));
            d.put("TEXT", words[r.nextInt(words.length)].toLowerCase(java.util.Locale.ROOT) + " headline " + r.nextInt(500));
            var legs = d.putArray("LEGS");
            for (int l = 0; l < 2; l++) {
                legs.addObject().put("LEG", l + 1).put("RATE", Math.round(r.nextDouble() * 1e4) / 1e6).put("NOTIONAL", 1e6 * (1 + r.nextInt(20)));
            }
            out.add(d);
        }
        return out;
    }

    private static String staged(List<ObjectNode> docs, String key) {
        List<ObjectNode> sorted = new ArrayList<>(docs);
        sorted.sort(Comparator.comparing(d -> d.path(key).asText()));
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (ObjectNode d : sorted) {
            JsonNode k = d.get(key);
            ObjectNode line = JSON.createObjectNode().put("id", k == null ? "row-" + i : k.asText()).put("doc", d.toString());
            line.putObject("columns");
            b.append(line).append('\n');
            i++;
        }
        return b.toString();
    }

    private static LakeManifest manifest(String table, String rows, int n) {
        return new LakeManifest(table, List.of("business_date"),
                List.of(new LakeManifest.Column("id", "string"), new LakeManifest.Column("doc", "string"), new LakeManifest.Column("business_date", "date")),
                List.of(), "replace", 5_000, List.of("id", "business_date"),
                List.of(LakeManifest.Part.inMemory(() -> new StringReader(rows), n, Map.of("business_date", "2026-10-02"))));
    }

    private static long parquetBytes(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains("_delta_log")).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    @Test
    void memoryPerTableAgainstDiskAndDecoded() throws Exception {
        MemoryStorage store = MemoryRuntime.STORES.define("ratios", 256L << 20, 64 * 1024, "");
        String[][] tables = {{"news-and-markets/news", "NEWS_ID"}, {"news-and-markets/quote", "QUOTE_ID"}, {"trades/trade", "TRADE_ID"},
            {"core/counterparty", "COUNTERPARTY_ID"}, {"market-risk/desk-pnl", "DESK_PNL_ID"}};
        System.out.println("MEMORY RATIOS  table | rows | decoded JSON | disk Parquet (snappy) | memory Parquet (zstd, sorted) | held in 64 KB chunks | decoded/memory");
        for (String[] t : tables) {
            List<ObjectNode> docs = synthetic(t[0], t[1], 10_000);
            String rows = staged(docs, t[1]);
            long decoded = docs.stream().mapToLong(d -> d.toString().getBytes(StandardCharsets.UTF_8).length).sum();
            Path disk = tmp.resolve(t[0].replace('/', '_'));
            LakeWriter.forTable(MemoryRuntime.RT, disk.toString(), Map.of()).write(manifest(disk.toString(), rows, docs.size()), n -> { });
            String mem = "mem://ratios/" + t[0].replace('/', '_');
            LakeWriter.forTable(MemoryRuntime.RT, mem, Map.of()).write(manifest(mem, rows, docs.size()), n -> { });
            long diskBytes = parquetBytes(disk);
            long memFile = store.filesIn(mem).stream().filter(f -> f.path().endsWith(".parquet") && !f.path().contains("_delta_log"))
                    .mapToLong(com.ash.lake.spi.FileEntry::size).sum();
            long held = store.bytesUnder(mem);
            System.out.printf(java.util.Locale.ROOT, "MEMORY RATIOS  %s | %,d | %,d | %,d | %,d | %,d | %.1fx%n", t[0], docs.size(), decoded, diskBytes, memFile,
                    held, (double) decoded / memFile);
            assertTrue(memFile < decoded, t[0]);
            assertTrue(memFile <= diskBytes * 1.05, t[0] + " zstd no larger than snappy");
        }
    }
}
