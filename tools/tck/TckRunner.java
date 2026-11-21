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

import com.ash.lake.delta.NativeEngine;
import com.ash.lake.iceberg.Table;
import com.ash.lake.iceberg.catalog.Catalog;
import com.ash.lake.iceberg.catalog.Catalogs;
import com.ash.lake.iceberg.expr.Expr;
import com.ash.lake.iceberg.read.TaskReader;
import com.ash.lake.iceberg.scan.FileTask;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.load.LakeLoad;
import com.ash.lake.load.LakeRetention;
import com.ash.lake.load.LakeUndo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.data.ArrayValue;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.ArrayType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The parity kit's runner (design section 4): one job a line in, one result a line out. A job is
 * {@code {"case", "steps": [{"op": "load"|"undo"|"retention", "args": [...]}], "dumps": [{"name", "format", "path"} | {"name", "format",
 * "root", "domain", "table"}]}}; a result is {@code {"case", "steps": [{"op", "exit", "summary"}], "tables": {name: {"date|id": row}}}}
 * with every value plain (numbers as doubles, dates as text, vectors as lists), so two runs compare as text.
 *
 * <p>The same source runs over this repository's classes and, with its imports mapped back to the host's packages by
 * {@code tools/split/make_reference.py}, over the host's jars at the freeze commit: the split's reference (the bridge). It uses only
 * the loader's command-line entry points and the stores' readers, the surface both sides share.
 *
 * <pre>java -cp CLASSPATH tools/tck/TckRunner.java JOBS.jsonl RESULTS.jsonl</pre>
 */
public final class TckRunner {

    /** The runtime the dumps read with: the process's, as a command line (the reference bridge drops it). */
    static final com.ash.lake.LakeRuntime RT = com.ash.lake.LakeRuntime.fromProcess();   // lake-only

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(JSON.writeValueAsString(run(JSON.readTree(line))));
            }
        }
        Files.write(Path.of(args[1]), out, StandardCharsets.UTF_8);
        System.exit(0);
    }

    static ObjectNode run(JsonNode job) {
        ObjectNode r = JSON.createObjectNode().put("case", job.path("case").asText());
        ArrayNode steps = r.putArray("steps");
        try {
            for (JsonNode step : job.path("steps")) {
                List<String> a = new ArrayList<>();
                step.path("args").forEach(x -> a.add(x.asText()));
                String op = step.path("op").asText();
                ByteArrayOutputStream said = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(said, true, StandardCharsets.UTF_8);
                Path summary = null;
                if (op.equals("load")) {
                    summary = Files.createTempFile("tck-summary", ".json");
                    a.addAll(List.of("--summary", summary.toString(), "-q"));
                }
                String[] argv = a.toArray(new String[0]);
                int code = switch (op) {
                    case "load" -> LakeLoad.run(argv, ps);
                    case "undo" -> LakeUndo.run(argv, ps);
                    case "retention" -> LakeRetention.run(argv, ps);
                    default -> throw new IllegalArgumentException("unknown op " + op);
                };
                ObjectNode s = steps.addObject().put("op", op).put("exit", code);
                String text = said.toString(StandardCharsets.UTF_8).lines()
                        .filter(l -> !l.matches("\\d{4}-\\d{2}-\\d{2}T\\S+ lake-build .*"))   // the run log's line: a time and a run id
                        .collect(java.util.stream.Collectors.joining("\n")).strip();
                if (!text.isEmpty() && (code != 0 || !op.equals("load"))) {
                    s.put("said", text);                    // a refusal's reason, an upkeep's answer (paths become ${work})
                }
                if (summary != null) {
                    if (Files.size(summary) > 0) {
                        s.set("summary", stable(JSON.readTree(summary.toFile())));
                    }
                    Files.deleteIfExists(summary);
                }
            }
            ObjectNode tables = r.putObject("tables");
            for (JsonNode d : job.path("dumps")) {
                Map<String, Map<String, Object>> rows = d.path("format").asText().equals("delta") ? delta(d.path("path").asText())
                        : iceberg(d.path("root").asText(), d.path("domain").asText(), d.path("table").asText());
                tables.set(d.path("name").asText(), JSON.valueToTree(rows));
            }
            r.put("status", "ok");
        } catch (Throwable e) {
            r.put("status", "error").put("error", e.getClass().getSimpleName());
        }
        return r;
    }

    /** The parts of a load summary that do not change from run to run (no times, ids, paths or sizes). */
    static JsonNode stable(JsonNode s) {
        ObjectNode o = JSON.createObjectNode();
        for (String k : List.of("status", "documents", "accepted", "rejected", "rows")) {
            if (s.has(k)) {
                o.set(k, s.get(k));
            }
        }
        if (s.path("commit").has("commits")) {
            o.put("commits", s.path("commit").path("commits").asInt());
        }
        if (s.path("rejects").has("byReason")) {
            o.set("rejectReasons", s.path("rejects").path("byReason"));
        }
        return o;
    }

    static Map<String, Map<String, Object>> delta(String table) throws Exception {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        if (!Files.isDirectory(Path.of(table, "_delta_log"))) {
            return out;
        }
        Engine engine = NativeEngine.create(RT);
        Snapshot s = io.delta.kernel.Table.forPath(engine, table).getLatestSnapshot(engine);
        Scan scan = s.getScanBuilder().build();
        Row state = scan.getScanState(engine);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> fileRows = files.next().getRows()) {
                    while (fileRows.hasNext()) {
                        Row file = fileRows.next();
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(file);
                        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler().readParquetFiles(
                                Utils.singletonCloseableIterator(fs), physical, Optional.empty());
                             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, state, file,
                                     raw.map(FileReadResult::getData))) {
                            while (data.hasNext()) {
                                FilteredColumnarBatch b = data.next();
                                StructType schema = b.getData().getSchema();
                                Optional<ColumnVector> sel = b.getSelectionVector();
                                for (int r = 0; r < b.getData().getSize(); r++) {
                                    if (sel.isPresent() && (sel.get().isNullAt(r) || !sel.get().getBoolean(r))) {
                                        continue;
                                    }
                                    Map<String, Object> row = new TreeMap<>();
                                    for (int c = 0; c < schema.length(); c++) {
                                        row.put(schema.at(c).getName(), value(b.getData().getColumnVector(c), r));
                                    }
                                    put(out, row);
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Object value(ColumnVector v, int r) {
        if (v.isNullAt(r)) {
            return null;
        }
        DataType t = v.getDataType();
        if (t instanceof DoubleType) {
            return v.getDouble(r);
        }
        if (t instanceof FloatType) {
            return (double) v.getFloat(r);
        }
        if (t instanceof LongType || t instanceof TimestampType) {
            return (double) v.getLong(r);
        }
        if (t instanceof IntegerType) {
            return (double) v.getInt(r);
        }
        if (t instanceof DateType) {
            return LocalDate.ofEpochDay(v.getInt(r)).toString();
        }
        if (t instanceof ArrayType) {
            ArrayValue a = v.getArray(r);
            ColumnVector e = a.getElements();
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < a.getSize(); i++) {
                out.add(value(e, i));
            }
            return out;
        }
        if (t instanceof io.delta.kernel.types.BooleanType) {
            return v.getBoolean(r);
        }
        return v.getString(r);
    }

    static Map<String, Map<String, Object>> iceberg(String root, String domain, String table) throws Exception {
        Map<String, String> s = new HashMap<>();
        s.put("root", root);
        s.put("domain", domain);
        Map<String, Map<String, Object>> out = new TreeMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Catalog c = Catalogs.open(RT, s)) {
            Optional<Table> found = c.load(table);
            if (found.isEmpty() || found.get().currentSnapshot().isEmpty()) {
                return out;
            }
            Table t = found.get();
            var schema = t.metadata().schema();
            List<Integer> ids = schema.columns().stream().map(NestedField::id).toList();
            for (FileTask task : t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, pool)) {
                try (TaskReader rows = t.rows(task, schema, ids, Expr.TRUE, false)) {
                    while (rows.hasNext()) {
                        Object[] v = rows.next();
                        Map<String, Object> row = new TreeMap<>();
                        for (int i = 0; i < ids.size(); i++) {
                            row.put(schema.columns().get(i).name(), plain(v[i]));
                        }
                        put(out, row);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    private static Object plain(Object v) {
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(plain(x)));
            return out;
        }
        if (v instanceof CharSequence cs) {
            return cs.toString();
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return v;
    }

    /** Keyed {@code date|id}; rows that share a key are numbered {@code #1, #2} in the order of their text, never of reading. */
    private static void put(Map<String, Map<String, Object>> out, Map<String, Object> row) {
        row.remove("_load_id");                       // the load columns differ from run to run by design
        row.remove("_source_file");
        String key = row.get("business_date") + "|" + row.get("id");
        if (!out.containsKey(key) && !out.containsKey(key + "#1")) {
            out.put(key, row);
            return;
        }
        List<Map<String, Object>> same = new ArrayList<>();
        if (out.containsKey(key)) {
            same.add(out.remove(key));
        }
        for (int n = 1; out.containsKey(key + "#" + n); n++) {
            same.add(out.remove(key + "#" + n));
        }
        same.add(row);
        same.sort(java.util.Comparator.comparing(Object::toString));
        for (int n = 0; n < same.size(); n++) {
            out.put(key + "#" + (n + 1), same.get(n));
        }
    }
}
