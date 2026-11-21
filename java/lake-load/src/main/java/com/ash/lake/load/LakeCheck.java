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

import com.ash.lake.LakeRuntime;
import com.ash.lake.config.ConnectorLake;
import com.ash.lake.config.Lake;
import com.ash.lake.config.LakeTable;
import com.ash.lake.config.Lakes;
import com.ash.lake.config.LakesCli;
import com.ash.lake.delta.NativeEngine;
import com.ash.lake.iceberg.catalog.PathCatalog;
import com.ash.lake.iceberg.io.FileAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.delta.kernel.Snapshot;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The checks of docs/architecture/DATA_ARCHITECTURE.md section 6, run at start and by {@code drishti.py lake check}: every Delta or Iceberg
 * connector names a known lake; every table of a lake file that exists on a local disk matches it (its format, its partition columns,
 * its promoted columns present). A difference is reported, never rewritten. A table on S3 or in memory is reported as not checked from
 * here (the server's connector checks it when it opens the table). A table's partition columns and columns are read through the official
 * libraries: the Delta Kernel's latest snapshot, Apache Iceberg's current metadata (the path catalog's). In {@code lake-load}, which
 * holds both; the lake-file model ({@code lake-config}) reads neither.
 */
public final class LakeCheck {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** One finding: {@code level} error (the lake file and the table disagree, or a connector names no known lake) or note. */
    public record Finding(String level, String lake, String table, String message) {}

    private LakeCheck() {
    }

    /** Checks every lake, and every connector setting map given ({@code name -> (plugin, settings)}). */
    public static List<Finding> check(LakeRuntime runtime, Lakes lakes, Map<String, Map.Entry<String, Map<String, String>>> connectors) {
        List<Finding> out = new ArrayList<>();
        connectors.forEach((name, c) -> {
            if (!ConnectorLake.PLUGINS.contains(c.getKey())) {
                return;
            }
            String lake = c.getValue().get(ConnectorLake.LAKE);
            if (lake == null || lake.isBlank()) {
                return;                                        // the explicit form (root, domain, table.<kind>): no lake file to check
            } else if (!ConnectorLake.overridden(c.getValue()).isEmpty()) {
                out.add(new Finding("note", lake, "", "connector " + name + " names lake " + lake + " and gives " + ConnectorLake.overridden(c.getValue())
                        + " itself: its own values win over the lake file's"));
            } else if (lakes.find(lake.strip()).isEmpty()) {
                out.add(new Finding("error", lake, "", "connector " + name + " names lake " + lake + ", which has no file in " + lakes.folder()));
            }
        });
        for (Lake l : lakes.all().values()) {
            for (LakeTable t : l.tables().values()) {
                out.addAll(table(runtime, l, t));
            }
        }
        return out;
    }

    /** One table against what is on disk. */
    public static List<Finding> table(LakeRuntime runtime, Lake lake, LakeTable t) {
        List<Finding> out = new ArrayList<>();
        String loc = lake.location(t);
        if (lake.inMemory() || lake.root().isEmpty() || loc.matches("^[a-z][a-z0-9+]*://.*") && !loc.startsWith("file://")) {
            out.add(new Finding("note", lake.name(), t.name(), "not checked from here: " + loc + " is not a local folder"));
            return out;
        }
        Path dir = Path.of(loc.replaceFirst("^file://", ""));
        if (!Files.isDirectory(dir)) {
            out.add(new Finding("note", lake.name(), t.name(), "no table yet at " + dir + " (a writer creates it as the lake file says)"));
            return out;
        }
        boolean delta = Files.isDirectory(dir.resolve("_delta_log"));
        boolean iceberg = Files.isDirectory(dir.resolve("metadata"));
        String found = delta ? "delta" : iceberg ? "iceberg" : "none";
        if (!found.equals(t.format())) {
            out.add(new Finding("error", lake.name(), t.name(), "the lake file says " + t.format() + "; " + dir + " is " + (found.equals("none")
                    ? "neither a Delta nor an Iceberg table" : "a " + found + " table")));
            return out;
        }
        try {
            Shape s = delta ? deltaShape(runtime, dir) : icebergShape(runtime, dir);
            List<String> want = t.format().equals("delta") ? t.partitionColumns() : List.of(LakeTable.BUSINESS_DATE);
            if (!s.partitions().equals(want)) {
                out.add(new Finding("error", lake.name(), t.name(), "partitioned by " + s.partitions() + " on disk; the lake file says " + want
                        + " (reported, never rewritten: rebuild the table or correct the lake file)"));
            }
            List<String> missing = t.promote().stream().filter(c -> !s.columns().contains(c)).toList();
            if (!missing.isEmpty()) {
                out.add(new Finding("error", lake.name(), t.name(), "promoted columns missing from the table: " + missing));
            }
        } catch (IOException | RuntimeException e) {
            out.add(new Finding("error", lake.name(), t.name(), "cannot read the table's metadata at " + dir + ": " + e.getMessage()));
        }
        return out;
    }

    /** A table's partition columns and its column names. */
    record Shape(List<String> partitions, Set<String> columns) {}

    /** The Delta table's partition columns and columns, from the Kernel's latest snapshot (commits and checkpoints alike). */
    static Shape deltaShape(LakeRuntime runtime, Path table) throws IOException {
        try (NativeEngine engine = NativeEngine.create(runtime)) {
            Snapshot s = io.delta.kernel.Table.forPath(engine, table.toString()).getLatestSnapshot(engine);
            return new Shape(List.copyOf(s.getPartitionColumnNames()), new HashSet<>(s.getSchema().fieldNames()));
        }
    }

    /** The Iceberg table's current schema and partition spec, from its current metadata (the path catalog's, through iceberg-core). */
    static Shape icebergShape(LakeRuntime runtime, Path table) throws IOException {
        try (FileAccess io = FileAccess.local(runtime)) {
            PathCatalog c = new PathCatalog(table.getParent().toString(), io);
            org.apache.iceberg.Table t = c.load(table.getFileName().toString())
                    .orElseThrow(() -> new IOException("no Iceberg table at " + table)).iceberg();
            Set<String> cols = new HashSet<>();
            t.schema().columns().forEach(f -> cols.add(f.name()));
            List<String> parts = new ArrayList<>();
            t.spec().fields().forEach(f -> parts.add(t.schema().findColumnName(f.sourceId())));
            return new Shape(parts, cols);
        }
    }

    /**
     * {@code check [--dir LAKES] [--connectors DIR]}: the lakes of the folder (the runtime's lake folder by default) and the connectors'
     * lake names checked, as JSON; exit 0 when no error was found, 1 otherwise, 2 for a bad command.
     */
    public static int run(LakeRuntime runtime, String[] args, PrintStream out) {
        try {
            Path dir = null;
            Path conns = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "check" -> { }
                    case "--dir" -> dir = Path.of(args[++i]);
                    case "--connectors" -> conns = Path.of(args[++i]);
                    default -> throw new IllegalArgumentException("usage: LakeCheck check [--dir LAKES] [--connectors DIR]");
                }
            }
            Path d = dir != null ? dir : Lakes.dir(runtime);
            Lakes lakes = Lakes.read(runtime, d);
            List<Finding> f = check(runtime, lakes, conns == null ? Map.of() : LakesCli.connectors(conns));
            long errors = f.stream().filter(x -> x.level().equals("error")).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dir", d.toString());
            m.put("lakes", lakes.all().keySet());
            m.put("findings", f);
            m.put("errors", errors);
            out.println(JSON.writeValueAsString(m));
            return errors == 0 ? 0 : 1;
        } catch (IllegalArgumentException | IOException | java.io.UncheckedIOException e) {
            out.println("{\"error\": " + quoted(e.getMessage()) + "}");
            return 2;
        }
    }

    public static void main(String[] args) {
        System.exit(run(LakeRuntime.fromProcess(), args, System.out));
    }

    private static String quoted(String s) {
        try {
            return JSON.writeValueAsString(String.valueOf(s));
        } catch (IOException e) {
            return "\"unprintable\"";
        }
    }
}
