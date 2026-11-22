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
    static final com.ash.lake.memory.store.MemoryStorageProvider MEMORY = new com.ash.lake.memory.store.MemoryStorageProvider();   // lake-only
    /** The runtime every step and dump uses: the process's options, and memory storage ({@code mem://}) for the cases' memory row. */   // lake-only
    static final com.ash.lake.LakeRuntime RT = com.ash.lake.LakeRuntime.builder().options(com.ash.lake.LakeOptions.fromProcess()).storage(MEMORY).build();   // lake-only

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public static void main(String[] args) throws Exception {
        String s3 = System.getProperty("tck.s3", "");   // lake-only: the matrix's S3 row: S3Mock in this JVM (lake-tck), its folder
        if (!s3.isEmpty()) startS3(Path.of(s3));   // lake-only
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(JSON.writeValueAsString(run(JSON.readTree(line))));
            }
        }
        Files.write(Path.of(args[1]), out, StandardCharsets.UTF_8);
        System.exit(0);
    }

    /**   // lake-only
     * Starts the S3 simulator (bucket {@code tck}) and points the runtime's S3 options at it ({@code lake.s3.*}, read when asked), with   // lake-only
     * its keys in the AWS default chain: every step and dump reaches it only through the runtime, as a host's would.   // lake-only
     */   // lake-only
    static void startS3(Path dir) throws Exception {   // lake-only
        Object server = Class.forName("com.ash.lake.tck.s3.S3MockServer").getMethod("start", Path.class, String[].class)   // lake-only
                .invoke(null, dir, new String[] {"tck"});   // lake-only
        Object e = server.getClass().getMethod("endpoint").invoke(server);   // lake-only
        System.setProperty("lake.s3.endpoint", (String) e.getClass().getMethod("endpoint").invoke(e));   // lake-only
        System.setProperty("lake.s3.region", "us-east-1");   // lake-only
        System.setProperty("lake.s3.path-style", "true");   // lake-only
        System.setProperty("aws.accessKeyId", (String) e.getClass().getMethod("accessKey").invoke(e));   // lake-only
        System.setProperty("aws.secretAccessKey", (String) e.getClass().getMethod("secretKey").invoke(e));   // lake-only
    }   // lake-only

    /** One step: its exit code, what it said when it refused (or an upkeep's answer), a load's stable summary. */
    static ObjectNode step(JsonNode step) throws Exception {
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
        int code;
        if (step.path("loader").asText("").equals("python")) code = python(op, argv, ps); else   // the matrix's Python row (LK-7)   // lake-only
        code = switch (op) {
            case "load" -> LakeLoad.run(RT, argv, ps);
            case "undo" -> LakeUndo.run(RT, argv, ps);
            case "retention" -> LakeRetention.run(RT, argv, ps);
            case "maintain" -> com.ash.lake.load.LakeMaintain.run(RT, argv, ps);   // lake-only
            case "store" -> store(argv, ps);   // lake-only
            default -> throw new IllegalArgumentException("unknown op " + op);
        };
        ObjectNode s = JSON.createObjectNode().put("op", op).put("exit", code);
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
        return s;
    }

    static ObjectNode run(JsonNode job) {
        ObjectNode r = JSON.createObjectNode().put("case", job.path("case").asText());
        ArrayNode steps = r.putArray("steps");
        LABELS.clear();   // lake-only: a case's labels and held readers are its own
        HELD.clear();   // lake-only
        String mem = job.path("memory").asText("");   // lake-only: the case's memory store (the matrix's mem:// row)
        if (!mem.isEmpty()) MEMORY.stores().define(mem, 512L << 20, 1 << 20, "");   // lake-only
        try {
            for (JsonNode step : job.path("steps")) {
                String group = step.path("op").asText();
                if (group.equals("settled")) {   // lake-only: writers at once (a load racing maintain); each that failed run again alone until it holds
                    steps.add(settled(step));   // lake-only
                    continue;   // lake-only
                }   // lake-only
                if (group.equals("parallel") || group.equals("race")) {   // writers at once: each started together, listed in order
                    ArrayNode at = steps.addObject().put("op", group).putArray("steps");
                    List<JsonNode> each = new ArrayList<>();
                    step.path("steps").forEach(each::add);
                    ExecutorService pool = Executors.newFixedThreadPool(each.size());
                    try {
                        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                        List<java.util.concurrent.Future<ObjectNode>> done = new ArrayList<>();
                        for (JsonNode e : each) {
                            done.add(pool.submit(() -> { go.await(); return step(e); }));
                        }
                        go.countDown();
                        List<ObjectNode> each2 = new ArrayList<>();
                        for (var f : done) {
                            each2.add(f.get());
                        }
                        if (group.equals("race")) {                     // which writer wins is not known: the outcomes in the order of their text
                            each2.sort(java.util.Comparator.comparing(ObjectNode::toString));
                        }
                        each2.forEach(at::add);
                    } finally {
                        pool.shutdownNow();
                    }
                    continue;
                }
                steps.add(step(step));
            }
            ObjectNode tables = r.putObject("tables");
            for (JsonNode d : job.path("dumps")) {
                if (d.path("format").asText().equals("files")) { tables.set(d.path("name").asText(), JSON.valueToTree(files(d))); continue; }   // lake-only
                Map<String, Map<String, Object>> rows = d.path("format").asText().equals("delta") ? delta(d.path("path").asText())
                        : iceberg(d.path("root").asText(), d.path("domain").asText(), d.path("table").asText());
                tables.set(d.path("name").asText(), JSON.valueToTree(rows));
            }
            r.put("status", "ok");
        } catch (Throwable e) {
            r.put("status", "error").put("error", e.getClass().getSimpleName());
        } finally {
            if (!mem.isEmpty()) MEMORY.stores().remove(mem);   // lake-only
        }
        return r;
    }

    /**   // lake-only
     * A load by the Python path ({@code drishti_lake}): the command {@code -Dtck.python} names (a JSON list: uv, the built wheel, its   // lake-only
     * pins; tools/tck/tck.py), with the step's arguments; what it prints is what it said, its exit code the step's.   // lake-only
     */   // lake-only
    static int python(String op, String[] argv, PrintStream ps) throws Exception {   // lake-only
        List<String> cmd = new ArrayList<>();   // lake-only
        JSON.readTree(System.getProperty("tck.python")).forEach(x -> cmd.add(x.asText()));   // lake-only
        cmd.add(op);   // lake-only
        cmd.addAll(List.of(argv));   // lake-only
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT);   // lake-only
        pb.environment().put("LAKE_CLASSPATH", java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))   // lake-only
                .filter(e -> !e.contains("logback")).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));   // the product's class path for its Java tools, without the runner's logging   // lake-only
        pb.environment().put("LAKE_JAVA", ProcessHandle.current().info().command().orElse("java"));   // lake-only
        Process p = pb.start();   // lake-only
        byte[] said = p.getInputStream().readAllBytes();   // lake-only
        int code = p.waitFor();   // lake-only
        ps.print(new String(said, StandardCharsets.UTF_8));   // lake-only
        return code;   // lake-only
    }   // lake-only

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
        if (!deltaTable(table)) {
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

    /** Whether a Delta table is there (a folder with its log, or a memory table's log). */
    static boolean deltaTable(String table) throws Exception {
        if (table.startsWith("mem://")) return RT.storage(table).isDirectory(table + "/_delta_log");   // lake-only
        if (table.startsWith("s3://")) try (var io = com.ash.lake.iceberg.io.FileAccess.local(RT)) { return io.storage(table).isDirectory(table + "/_delta_log"); }   // lake-only
        return Files.isDirectory(Path.of(table, "_delta_log"));
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

    /**   // lake-only
     * Writers started together whose interleaving is not known (a load racing lake maintain): each that failed (a commit lost to the   // lake-only
     * other) is run again alone, up to five times; the result is only each writer's final exit, in the order listed, so two runs   // lake-only
     * compare. What the writers left is checked by the steps after it (every row there once, the documents the same).   // lake-only
     */   // lake-only
    static ObjectNode settled(JsonNode step) throws Exception {   // lake-only
        ObjectNode out = JSON.createObjectNode().put("op", "settled");   // lake-only
        ArrayNode at = out.putArray("steps");   // lake-only
        List<JsonNode> each = new ArrayList<>();   // lake-only
        step.path("steps").forEach(each::add);   // lake-only
        ExecutorService pool = Executors.newFixedThreadPool(each.size());   // lake-only
        try {   // lake-only
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);   // lake-only
            List<java.util.concurrent.Future<ObjectNode>> done = new ArrayList<>();   // lake-only
            for (JsonNode e : each) {   // lake-only
                done.add(pool.submit(() -> { go.await(); return step(e); }));   // lake-only
            }   // lake-only
            go.countDown();   // lake-only
            for (int i = 0; i < each.size(); i++) {   // lake-only
                ObjectNode r = done.get(i).get();   // lake-only
                for (int n = 0; n < 5 && r.path("exit").asInt(0) != 0; n++) {   // lake-only
                    r = step(each.get(i));   // lake-only
                }   // lake-only
                at.addObject().put("op", each.get(i).path("op").asText()).put("exit", r.path("exit").asInt(0));   // lake-only
            }   // lake-only
        } finally {   // lake-only
            pool.shutdownNow();   // lake-only
        }   // lake-only
        return out;   // lake-only
    }   // lake-only

    /** What a digest verb saw under a label: the first time kept, later compared ({@code same as <label>=true}). */   // lake-only
    static final Map<String, String> LABELS = new java.util.concurrent.ConcurrentHashMap<>();   // lake-only

    static String labelled(String text, String label) {   // lake-only
        if (label == null || label.isEmpty()) {   // lake-only
            return text;   // lake-only
        }   // lake-only
        String was = LABELS.putIfAbsent(label, text);   // lake-only
        return text + "\n" + (was == null ? "kept as " + label : "same as " + label + "=" + was.equals(text));   // lake-only
    }   // lake-only

    /** Holds a table's snapshot open by name (a reader that started before maintain): {@code read-held} reads it later. */   // lake-only
    static final Map<String, com.ash.lake.read.TableSnapshot> HELD = new java.util.concurrent.ConcurrentHashMap<>();   // lake-only

    /**   // lake-only
     * Every row the table holds now (each copy, deleted ones left out by the store), by business date: its count and a digest of the rows   // lake-only
     * (the document, every column, vectors included; the load columns, which differ from run to run, left out), as the dumps read them.   // lake-only
     */   // lake-only
    static String rawDigest(String format, String root, String domain, String table, String from) throws java.io.IOException {   // lake-only
        Map<String, Map<String, Object>> rows;   // lake-only
        try {   // lake-only
            if (format.equals("files")) {   // lake-only
                ObjectNode d = JSON.createObjectNode().put("root", root).put("domain", domain).put("table", table);   // lake-only
                rows = files(d);   // lake-only
            } else if (format.equals("delta")) {   // lake-only
                rows = delta(root + "/" + domain + "/" + table);   // lake-only
            } else {   // lake-only
                rows = iceberg(root, domain, table);   // lake-only
            }   // lake-only
        } catch (java.io.IOException | RuntimeException e) {   // lake-only
            throw e;   // lake-only
        } catch (Exception e) {   // lake-only
            throw new java.io.IOException(e);   // lake-only
        }   // lake-only
        Map<String, List<String>> byDate = new TreeMap<>();   // lake-only
        for (Map<String, Object> row : rows.values()) {   // lake-only
            Map<String, Object> r = new TreeMap<>(row);   // lake-only
            Object d = r.get("business_date");   // lake-only
            String date = d instanceof Number n ? LocalDate.ofEpochDay(n.longValue()).toString() : String.valueOf(d);   // lake-only
            if (from != null && date.compareTo(from) < 0) {   // lake-only: only the dates from that one on
                continue;   // lake-only
            }   // lake-only
            r.put("business_date", date);   // lake-only
            byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(JSON.writeValueAsString(r));   // lake-only
        }   // lake-only
        StringBuilder out = new StringBuilder("dates " + byDate.size());   // lake-only
        byDate.forEach((d, l) -> out.append("\n").append(d).append(": rows=").append(l.size()).append(" digest=").append(ReadVerbs.digestOf(l)));   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    /** A store operation through the TableStore SPI: {@code <op> <format> <root> <domain> <table> [arg]} (a capability case's refusal). */   // lake-only
    static int store(String[] a, PrintStream out) {   // lake-only
        var store = com.ash.lake.load.Stores.of(RT, a[1]);   // lake-only
        var t = new com.ash.lake.spi.TableRef(a[2], a[3], a[4]);   // lake-only
        try {   // lake-only
            Object done = switch (a[0]) {   // lake-only
                case "undo" -> store.undo(RT, t, a[5], false);   // lake-only
                case "delete" -> store.deleteDates(RT, t, java.util.Set.of(LocalDate.parse(a[5])), false);   // lake-only
                case "dates" -> store.dates(RT, t);   // lake-only
                case "versions" -> store.versions(RT, t).size() + " versions";   // lake-only
                case "scan-at" -> scanAt(store, t, a[5], a.length > 6 && a[6].equals("time"));   // lake-only
                case "raw-digest" -> labelled(rawDigest(a[1], a[2], a[3], a[4], a.length > 6 ? a[6] : null), a.length > 5 ? a[5] : null);   // lake-only: every row, by date
                case "hold" -> {   // lake-only: a reader's snapshot taken now, read later by read-held
                    var lake = store.open(RT, com.ash.lake.read.LakeAddress.of(a[2], a[3]));   // lake-only: open while the case runs
                    HELD.put(a[5], lake.table(a[4]).snapshot(com.ash.lake.spi.AsOf.LATEST).orElseThrow());   // lake-only
                    yield "held " + a[5];   // lake-only
                }   // lake-only
                case "read-held" -> labelled(ReadVerbs.visible(HELD.get(a[5]), null), a.length > 6 ? a[6] : null);   // lake-only: the held reader, after what ran since
                case "read-visible" -> {   // lake-only: what readers see now, by date
                    try (var lake = store.open(RT, com.ash.lake.read.LakeAddress.of(a[2], a[3]))) {   // lake-only
                        yield labelled(ReadVerbs.visible(lake.table(a[4]).snapshot(com.ash.lake.spi.AsOf.LATEST).orElseThrow(), a.length > 6 ? a[6] : null),   // lake-only
                                a.length > 5 ? a[5] : null);   // lake-only
                    }   // lake-only
                }   // lake-only
                case "scan-stats" -> scanStats(store, t, LocalDate.parse(a[5]), a[6], a[7]);   // lake-only: pushdown (READ_API.md 2.7)
                case "delete-row" -> DeleteRow.run(a);   // lake-only: a row deleted as other writers do (READ_API.md 6)
                default -> a[0].startsWith("read-") ? ReadVerbs.run(store, a) : null;   // lake-only: the read API (READ_API.md section 6)
            };   // lake-only
            if (done == null) throw new IllegalArgumentException("unknown store op " + a[0]);   // lake-only
            out.println(done);   // lake-only
            return 0;   // lake-only
        } catch (UnsupportedOperationException | com.ash.lake.common.LakeException | java.io.IOException e) {   // lake-only
            out.println(e.getMessage());   // lake-only
            return 1;   // lake-only
        }   // lake-only
    }   // lake-only

    /**   // lake-only
     * Time travel through the SPI: the table as of its version at {@code index} in {@link com.ash.lake.spi.TableStore#versions} (a store's   // lake-only
     * own numbers differ: Iceberg's are snapshot ids), by that version or by its commit time; a negative index names version {@code index}   // lake-only
     * itself (none holds it). The rows' dates and ids, in order.   // lake-only
     */   // lake-only
    static String scanAt(com.ash.lake.spi.TableStore store, com.ash.lake.spi.TableRef t, String index, boolean byTime) throws java.io.IOException {   // lake-only
        int i = Integer.parseInt(index);   // lake-only
        var versions = store.versions(RT, t);   // lake-only
        var asOf = i < 0 ? com.ash.lake.spi.AsOf.version(i) : byTime ? com.ash.lake.spi.AsOf.time(versions.get(i).committed())   // lake-only
                : com.ash.lake.spi.AsOf.version(versions.get(i).version());   // lake-only
        Map<String, List<String>> ids = new TreeMap<>();   // lake-only
        try (var rows = store.scan(RT, t, com.ash.lake.spi.ScanRequest.all().columns("id", "business_date").asOf(asOf))) {   // lake-only
            rows.forEachRemaining(r -> ids.computeIfAbsent(String.valueOf(r.get(1)), k -> new ArrayList<>()).add(String.valueOf(r.get(0))));   // lake-only
        }   // lake-only
        StringBuilder out = new StringBuilder();   // lake-only
        ids.forEach((d, l) -> { java.util.Collections.sort(l); out.append(d).append(": ").append(l.size()).append(' ').append(l).append('\n'); });   // lake-only
        return out.toString().strip();   // lake-only
    }   // lake-only

    /**   // lake-only
     * A scan with {@code business_date = date and column = value} pushed down: its rows against the whole scan filtered by hand, and   // lake-only
     * what its {@code ScanStats} say (files skipped, every row it returned counted, no more rows read than the whole scan's).   // lake-only
     */   // lake-only
    static String scanStats(com.ash.lake.spi.TableStore store, com.ash.lake.spi.TableRef t, LocalDate date, String column, String value) throws java.io.IOException {   // lake-only
        var where = com.ash.lake.spi.Expr.and(com.ash.lake.spi.Expr.eq("business_date", date), com.ash.lake.spi.Expr.eq(column, value));   // lake-only
        List<String> all = new ArrayList<>();   // lake-only
        List<String> got = new ArrayList<>();   // lake-only
        com.ash.lake.spi.ScanStats whole;   // lake-only
        try (var rows = store.scan(RT, t, com.ash.lake.spi.ScanRequest.all())) {   // lake-only
            rows.forEachRemaining(r -> { if (where.test(r)) all.add(String.valueOf(r.get("id"))); });   // lake-only
            whole = rows.stats();   // lake-only
        }   // lake-only
        com.ash.lake.spi.ScanStats s;   // lake-only
        try (var rows = store.scan(RT, t, com.ash.lake.spi.ScanRequest.all().where(where))) {   // lake-only
            rows.forEachRemaining(r -> got.add(String.valueOf(r.get("id"))));   // lake-only
            s = rows.stats();   // lake-only
        }   // lake-only
        java.util.Collections.sort(all);   // lake-only
        java.util.Collections.sort(got);   // lake-only
        return got.size() + " rows equal=" + got.equals(all) + " skipped>0=" + (s.filesSkipped() > 0) + " returned=" + (s.rowsReturned() == got.size())   // lake-only
                + " read-less=" + (s.rowsRead() < whole.rowsRead()) + " whole-skips=" + whole.filesSkipped();   // lake-only
    }   // lake-only

    /** A files table's rows through the files store. */   // lake-only
    static Map<String, Map<String, Object>> files(JsonNode d) throws Exception {   // lake-only
        Map<String, Map<String, Object>> out = new TreeMap<>();   // lake-only
        var t = new com.ash.lake.spi.TableRef(d.path("root").asText(), d.path("domain").asText(), d.path("table").asText());   // lake-only
        try (var rows = com.ash.lake.load.Stores.builtIn("files").scan(RT, t, com.ash.lake.spi.ScanRequest.all())) {   // lake-only
            rows.forEachRemaining(r -> {   // lake-only
                Map<String, Object> row = new TreeMap<>(r.asMap());   // lake-only
                row.put("business_date", row.get("business_date").toString());   // lake-only
                put(out, row);   // lake-only
            });   // lake-only
        }   // lake-only
        return out;   // lake-only
    }   // lake-only

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

/**   // lake-only
 * The read API's verbs for the parity kit (READ_API.md section 6): each opens the lake through {@code TableStore.open} and prints what it   // lake-only
 * read in a form that does not depend on the store (counts, and digests of sorted text), so the lines of every store compare.   // lake-only
 */   // lake-only
final class ReadVerbs {   // lake-only
    private static final Map<String, Map<LocalDate, String>> STAMPS = new java.util.HashMap<>();   // lake-only

    static String run(com.ash.lake.spi.TableStore store, String[] a) throws java.io.IOException {   // lake-only
        List<String> args = List.of(a).subList(5, a.length);   // lake-only
        try (var lake = store.open(TckRunner.RT, com.ash.lake.read.LakeAddress.of(a[2], a[3]))) {   // lake-only
            return switch (a[0]) {   // lake-only
                case "read-tables" -> lake.tables() + " new " + lake.newTables(java.util.Set.of(a[4]));   // lake-only
                case "read-health" -> { var h = lake.health(java.time.Instant.now()); yield "reachable=" + h.reachable() + " memory=" + h.memoryOnly() + " engine=" + !h.engine().isEmpty(); }   // lake-only
                case "read-days" -> days(lake, a[2] + "|" + a[4], a[4]);   // lake-only
                case "read-files" -> files(snap(lake, a[4]));   // lake-only: data files per date (compaction)
                case "read-ids" -> ids(snap(lake, a[4]), LocalDate.parse(args.get(0)));   // lake-only
                case "read-layout" -> layout(snap(lake, a[4]));   // lake-only: each date's files in id order, their ranges disjoint
                case "read-doc" -> docs(snap(lake, a[4]), LocalDate.parse(args.get(0)));   // lake-only
                case "read-columns" -> columns(snap(lake, a[4]), LocalDate.parse(args.get(0)), args.subList(1, args.size()));   // lake-only
                case "read-vectors" -> vectors(snap(lake, a[4]), LocalDate.parse(args.get(0)), args.get(1), args.subList(2, args.size()));   // lake-only
                case "read-identity" -> identity(store, a, lake);   // lake-only
                case "read-purge" -> purge(lake, a[4], LocalDate.parse(args.get(0)));   // lake-only
                case "read-problems" -> problems(lake, a[4], LocalDate.parse(args.get(0)));   // lake-only
                case "read-at" -> readAt(store, lake, a, Integer.parseInt(args.get(0)), args.size() > 1 && !args.get(1).equals("version"), args.size() > 1 && args.get(1).equals("before"));   // lake-only
                case "read-keep" -> keep(snap(lake, a[4]), LocalDate.parse(args.get(0)), args.get(1), args.get(2), args.get(3));   // lake-only
                case "read-vectors-of" -> vectorsOf(snap(lake, a[4]), LocalDate.parse(args.get(0)), args.get(1));   // lake-only
                case "read-foreign" -> foreign(lake, a[4], args);   // lake-only: a foreign table through a shape (READ_API.md RA-S8)
                default -> null;   // lake-only
            };   // lake-only
        }   // lake-only
    }   // lake-only

    /**   // lake-only
     * A foreign table read through a shape: {@code <kind> [key=value…] [col:<path>[:n]…] [vec:<column>…]}, a key without {@code tables.}   // lake-only
     * (and not {@code decimals}, {@code binary}) under {@code tables.<kind>.}. Prints every date's exactness, ids, each document by id   // lake-only
     * (in full: the same JSON on every store), the asked paths' values and the vectors; a refused shape prints its words.   // lake-only
     */   // lake-only
    static String foreign(com.ash.lake.read.LakeReader lake, String name, List<String> args) throws java.io.IOException {   // lake-only
        String kind = args.get(0);   // lake-only
        Map<String, String> settings = new java.util.LinkedHashMap<>();   // lake-only
        List<com.ash.lake.read.ColumnAsk> cols = new ArrayList<>();   // lake-only
        List<String> vecs = new ArrayList<>();   // lake-only
        for (String x : args.subList(1, args.size())) {   // lake-only
            if (x.startsWith("col:")) {   // lake-only
                String path = x.substring(4).replaceAll(":n$", "");   // lake-only
                cols.add(new com.ash.lake.read.ColumnAsk(path, path, x.endsWith(":n")));   // lake-only
            } else if (x.startsWith("vec:")) {   // lake-only
                vecs.add(x.substring(4));   // lake-only
            } else {   // lake-only
                String k = x.substring(0, x.indexOf('=')), v = x.substring(x.indexOf('=') + 1);   // lake-only
                settings.put(k.startsWith("tables.") || k.equals("decimals") || k.equals("binary") ? k : "tables." + kind + "." + k, v);   // lake-only
            }   // lake-only
        }   // lake-only
        var t = lake.foreign(name, com.ash.lake.read.TableShape.of(kind, settings));   // lake-only
        com.ash.lake.read.TableSnapshot s;   // lake-only
        try {   // lake-only
            s = t.snapshot(com.ash.lake.spi.AsOf.LATEST).orElse(null);   // lake-only
        } catch (com.ash.lake.common.LakeException e) {   // lake-only
            return "refused " + e.getMessage();   // lake-only
        }   // lake-only
        if (s == null) return "no table " + name;   // lake-only
        StringBuilder out = new StringBuilder("id ").append(s.idColumn()).append(" vectors ").append(s.vectorColumns())   // lake-only
                .append(" paths ").append(new java.util.TreeSet<>(s.promoted().keySet())).append(" again-same=").append(t.snapshot(com.ash.lake.spi.AsOf.LATEST).orElseThrow() == s);   // lake-only
        for (LocalDate d : s.dates()) {   // lake-only
            var day = s.day(d).orElseThrow();   // lake-only
            long exact = day.files().stream().filter(com.ash.lake.read.DataFile::exact).count();   // lake-only
            out.append("\n").append(d.equals(LocalDate.MIN) ? "(no date)" : d.toString()).append(": exact=")   // lake-only
                    .append(exact == day.files().size() ? "all" : exact == 0 ? "none" : "some");   // lake-only
            var m = s.ids(d);   // lake-only
            List<String> all = new ArrayList<>();   // lake-only
            s.documents(d, (id, json) -> all.add(id + " " + json));   // lake-only
            all.sort(null);   // lake-only
            List<String> byId = new ArrayList<>();   // lake-only
            for (int i = 0; i < m.size(); i++) {   // lake-only
                byId.add(m.id(i) + " " + s.document(d, m.file(m.id(i)), m.id(i)).orElse("<none>"));   // lake-only
            }   // lake-only
            byId.forEach(l -> out.append("\n  ").append(l));   // lake-only
            out.append("\n  documents-same=").append(all.equals(byId)).append(" absent=").append(s.document(d, 0, "ABSENT:0").isPresent());   // lake-only
            if (!cols.isEmpty()) {   // lake-only
                var b = s.columns(d, cols);   // lake-only
                List<String> lines = new ArrayList<>();   // lake-only
                for (int i = 0; i < b.ids().length; i++) {   // lake-only
                    StringBuilder l = new StringBuilder(b.ids()[i]);   // lake-only
                    for (var c : cols) l.append(' ').append(c.path()).append('=').append(c.numeric() ? (Object) b.numbers().get(c.path())[i] : b.texts().get(c.path())[i]);   // lake-only
                    lines.add(l.toString());   // lake-only
                }   // lake-only
                lines.sort(null);   // lake-only
                lines.forEach(l -> out.append("\n  col ").append(l));   // lake-only
            }   // lake-only
            for (String v : vecs) {   // lake-only
                List<String> lines = new ArrayList<>();   // lake-only
                s.vectors(com.ash.lake.read.VectorRequest.of(d, v, List.of()), (id, values, length, texts) -> {   // lake-only
                    lines.add(id + " " + (length < 0 ? "null" : java.util.Arrays.toString(java.util.Arrays.copyOf(values, length))));   // lake-only
                });   // lake-only
                lines.sort(null);   // lake-only
                lines.forEach(l -> out.append("\n  vec ").append(v).append(' ').append(l));   // lake-only
                if (m.size() > 0) {   // lake-only
                    var of = s.vectorsOf(d, m.file(m.id(0)), java.util.Set.of(m.id(0)), List.of(v)).getOrDefault(m.id(0), Map.of());   // lake-only
                    out.append("\n  vec-of ").append(m.id(0)).append(' ').append(of.containsKey(v) ? java.util.Arrays.toString(of.get(v)) : "null");   // lake-only
                }   // lake-only
            }   // lake-only
        }   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    static com.ash.lake.read.TableSnapshot snap(com.ash.lake.read.LakeReader lake, String table) throws java.io.IOException {   // lake-only
        return lake.table(table).snapshot(com.ash.lake.spi.AsOf.LATEST).orElseThrow(() -> new java.io.IOException("no table " + table));   // lake-only
    }   // lake-only

    static String digest(List<String> lines) {   // lake-only
        List<String> sorted = new ArrayList<>(lines);   // lake-only
        java.util.Collections.sort(sorted);   // lake-only
        try {   // lake-only
            var md = java.security.MessageDigest.getInstance("SHA-256");   // lake-only
            for (String l : sorted) {   // lake-only
                md.update(l.getBytes(StandardCharsets.UTF_8));   // lake-only
                md.update((byte) 10);   // lake-only
            }   // lake-only
            return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 12);   // lake-only
        } catch (java.security.NoSuchAlgorithmException e) {   // lake-only
            throw new IllegalStateException(e);   // lake-only
        }   // lake-only
    }   // lake-only

    /** Per date its stated rows and deletes; whether its stamp changed since the last read-days of the table in this case. */   // lake-only
    static String days(com.ash.lake.read.LakeReader lake, String key, String table) throws java.io.IOException {   // lake-only
        var s = snap(lake, table);   // lake-only
        Map<LocalDate, String> was = STAMPS.get(key);   // lake-only
        Map<LocalDate, String> now = new TreeMap<>();   // lake-only
        StringBuilder out = new StringBuilder("version-dates " + s.dates().size());   // lake-only
        for (LocalDate d : s.dates()) {   // lake-only
            var day = s.day(d).orElseThrow();   // lake-only
            now.put(d, day.stamp());   // lake-only
            String again = snap(lake, table).day(d).orElseThrow().stamp();   // lake-only
            out.append("\n").append(d).append(": rows=").append(day.filesUncounted() > 0 ? "unstated" : String.valueOf(day.rows())).append(" deletes=").append(day.deletes())   // lake-only
                    .append(" files>0=").append(!day.files().isEmpty()).append(" stamp ").append(again.equals(day.stamp()) ? "stable" : "UNSTABLE")   // lake-only
                    .append(was == null ? "" : was.containsKey(d) && was.get(d).equals(day.stamp()) ? ", unchanged" : ", changed");   // lake-only
        }   // lake-only
        STAMPS.put(key, now);   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    static String files(com.ash.lake.read.TableSnapshot s) throws java.io.IOException {   // lake-only
        StringBuilder out = new StringBuilder("dates " + s.dates().size());   // lake-only
        for (LocalDate d : s.dates()) {   // lake-only
            out.append("\n").append(d).append(": files=").append(s.day(d).orElseThrow().files().size());   // lake-only
        }   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    static String layout(com.ash.lake.read.TableSnapshot s) throws java.io.IOException {   // lake-only
        StringBuilder out = new StringBuilder("dates " + s.dates().size());   // lake-only
        for (LocalDate d : s.dates()) {   // lake-only
            var files = new ArrayList<>(s.day(d).orElseThrow().files());   // lake-only
            boolean disjoint = true;   // lake-only
            if (files.size() > 1) {   // lake-only
                for (var f : files) {   // lake-only
                    var b = f.bounds().get("id");   // lake-only
                    disjoint &= b != null && b.min() != null && b.max() != null;   // lake-only
                }   // lake-only
                if (disjoint) {   // lake-only
                    files.sort(java.util.Comparator.comparing(f -> f.bounds().get("id").min()));   // lake-only
                    for (int i = 1; i < files.size(); i++) {   // lake-only
                        disjoint &= files.get(i - 1).bounds().get("id").max().compareTo(files.get(i).bounds().get("id").min()) < 0;   // lake-only
                    }   // lake-only
                }   // lake-only
            }   // lake-only
            out.append("\n").append(d).append(": files=").append(files.size()).append(" id-ranges-disjoint=").append(disjoint);   // lake-only
        }   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    static String ids(com.ash.lake.read.TableSnapshot s, LocalDate d) throws java.io.IOException {   // lake-only
        var m = s.ids(d);   // lake-only
        int files = s.day(d).map(x -> x.files().size()).orElse(0);   // lake-only
        List<String> lines = new ArrayList<>();   // lake-only
        boolean ok = true;   // lake-only
        for (int i = 0; i < m.size(); i++) {   // lake-only
            lines.add(m.id(i));   // lake-only
            int f = m.file(m.id(i));   // lake-only
            ok &= f >= 0 && f < files && (i == 0 || m.id(i - 1).compareTo(m.id(i)) < 0);   // lake-only
        }   // lake-only
        Path tmp = Files.createTempFile("tck-idmap", ".bin");   // lake-only
        String mapped;   // lake-only
        try (var mm = com.ash.lake.read.IdMaps.write(m, tmp)) {   // lake-only
            boolean same = mm.size() == m.size() && mm.file("ABSENT") == -1;   // lake-only
            for (int i = 0; i < m.size() && same; i++) {   // lake-only
                same = mm.id(i).equals(m.id(i)) && mm.file(m.id(i)) == m.file(m.id(i));   // lake-only
            }   // lake-only
            mm.close();   // lake-only
            mapped = "mapped=" + same + " closed=" + mm.closed();   // lake-only
        } finally {   // lake-only
            Files.deleteIfExists(tmp);   // lake-only
        }   // lake-only
        return m.size() + " ids " + digest(lines) + " sorted-and-filed=" + ok + " absent=" + m.file("ABSENT") + " " + mapped;   // lake-only
    }   // lake-only

    /** A digest of text lines, their order aside (as {@link #digest}). */   // lake-only
    static String digestOf(List<String> lines) {   // lake-only
        return digest(lines);   // lake-only
    }   // lake-only

    /**   // lake-only
     * What a reader sees, date by date: each id's document with its vectors filled in (the newest copy), its count and a digest; a date's   // lake-only
     * id held twice says so ({@code twice=}).   // lake-only
     */   // lake-only
    static String visible(com.ash.lake.read.TableSnapshot s, String from) throws java.io.IOException {   // lake-only
        StringBuilder out = new StringBuilder();   // lake-only
        for (LocalDate d : s.dates()) {   // lake-only
            if (from != null && d.toString().compareTo(from) < 0) {   // lake-only
                continue;   // lake-only
            }   // lake-only
            Map<String, String> docs = new TreeMap<>();   // lake-only
            int[] twice = {0};   // lake-only
            s.documents(d, (id, json) -> { if (docs.put(id, json) != null) twice[0]++; });   // lake-only
            var m = s.ids(d);   // lake-only
            Map<Integer, java.util.Set<String>> byFile = new TreeMap<>();   // lake-only
            for (int i = 0; i < m.size(); i++) {   // lake-only
                byFile.computeIfAbsent(m.file(m.id(i)), k -> new java.util.HashSet<>()).add(m.id(i));   // lake-only
            }   // lake-only
            Map<String, Map<String, double[]>> vectors = new java.util.HashMap<>();   // lake-only
            if (!s.vectorColumns().isEmpty()) {   // lake-only
                for (var e : byFile.entrySet()) {   // lake-only
                    vectors.putAll(s.vectorsOf(d, e.getKey(), e.getValue(), List.copyOf(s.vectorColumns())));   // lake-only
                }   // lake-only
            }   // lake-only
            List<String> lines = new ArrayList<>();   // lake-only
            docs.forEach((id, json) -> lines.add(id + "\t" + com.ash.lake.read.Documents.withVectors(json, vectors.getOrDefault(id, Map.of()))));   // lake-only
            out.append(out.length() == 0 ? "" : "\n").append(d).append(": ids=").append(m.size()).append(" documents=").append(docs.size()).append(" twice=").append(twice[0])   // lake-only
                    .append(" digest=").append(digest(lines));   // lake-only
        }   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    static String docs(com.ash.lake.read.TableSnapshot s, LocalDate d) throws java.io.IOException {   // lake-only
        var m = s.ids(d);   // lake-only
        List<String> byId = new ArrayList<>();   // lake-only
        List<String> filled = new ArrayList<>();   // lake-only
        for (int i = 0; i < m.size(); i++) {   // lake-only
            String id = m.id(i);   // lake-only
            String doc = s.document(d, m.file(id), id).orElse("<none>");   // lake-only
            byId.add(id + "\t" + doc);   // lake-only
            if (i < 3 && !s.vectorColumns().isEmpty()) {   // lake-only
                var v = s.vectorsOf(d, m.file(id), java.util.Set.of(id), List.copyOf(s.vectorColumns())).getOrDefault(id, Map.of());   // lake-only
                filled.add(id + "\t" + com.ash.lake.read.Documents.withVectors(doc, v));   // lake-only
            }   // lake-only
        }   // lake-only
        List<String> all = new ArrayList<>();   // lake-only
        s.documents(d, (id, json) -> all.add(id + "\t" + json));   // lake-only
        return byId.size() + " documents " + digest(byId) + " all=" + digest(all).equals(digest(byId)) + " filled " + digest(filled)   // lake-only
                + " absent=" + s.document(d, 0, "ABSENT").isPresent();   // lake-only
    }   // lake-only

    static String columns(com.ash.lake.read.TableSnapshot s, LocalDate d, List<String> asks) throws java.io.IOException {   // lake-only
        List<com.ash.lake.read.ColumnAsk> list = new ArrayList<>();   // lake-only
        for (String x : asks) {   // lake-only
            String path = x.substring(0, x.indexOf(':'));   // lake-only
            list.add(new com.ash.lake.read.ColumnAsk(path, s.promoted().getOrDefault(path, path), x.endsWith(":n")));   // lake-only
        }   // lake-only
        var b = s.columns(d, list);   // lake-only
        StringBuilder out = new StringBuilder(b.ids().length + " rows");   // lake-only
        for (var ask : list) {   // lake-only
            List<String> lines = new ArrayList<>();   // lake-only
            int empty = 0;   // lake-only
            for (int i = 0; i < b.ids().length; i++) {   // lake-only
                Object v = ask.numeric() ? (Object) b.numbers().get(ask.path())[i] : b.texts().get(ask.path())[i];   // lake-only
                empty += v == null || (v instanceof Double x && x.isNaN()) ? 1 : 0;   // lake-only
                lines.add(b.ids()[i] + "\t" + v);   // lake-only
            }   // lake-only
            out.append('\n').append(ask.path()).append(": ").append(digest(lines)).append(" empty=").append(empty);   // lake-only
        }   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    static String vectors(com.ash.lake.read.TableSnapshot s, LocalDate d, String column, List<String> texts) throws java.io.IOException {   // lake-only
        StringBuilder out = new StringBuilder();   // lake-only
        List<String> order1 = null;   // lake-only
        for (int decoders : new int[] {1, 4}) {   // lake-only
            List<String> lines = new ArrayList<>();   // lake-only
            List<String> order = new ArrayList<>();   // lake-only
            int[] nulls = {0};   // lake-only
            s.vectors(com.ash.lake.read.VectorRequest.of(d, column, texts).parallel(decoders, 1L << 20), (id, values, length, t) -> {   // lake-only
                order.add(id);   // lake-only
                StringBuilder l = new StringBuilder(id).append('|').append(length);   // lake-only
                for (int i = 0; i < length; i++) {   // lake-only
                    l.append(',').append(values[i]);   // lake-only
                }   // lake-only
                nulls[0] += length < 0 ? 1 : 0;   // lake-only
                lines.add(l.append('|').append(String.join("/", java.util.Arrays.stream(t).map(String::valueOf).toList())).toString());   // lake-only
            });   // lake-only
            if (order1 == null) {   // lake-only
                order1 = order;   // lake-only
                out.append(lines.size()).append(" rows ").append(digest(lines)).append(" null-vectors=").append(nulls[0]);   // lake-only
            } else {   // lake-only
                out.append(" decoders-4-same-order=").append(order.equals(order1));   // lake-only
            }   // lake-only
        }   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    /**   // lake-only
     * The read API as of a version (time-travel/versions-and-earlier-rows through snapshots): the table's version at {@code index} of   // lake-only
     * {@code TableStore.versions}, by the version or by its commit time; each date's ids (sorted, a digest) and the newest-wins documents.   // lake-only
     */   // lake-only
    static String readAt(com.ash.lake.spi.TableStore store, com.ash.lake.read.LakeReader lake, String[] a, int index, boolean byTime, boolean before) throws java.io.IOException {   // lake-only
        var versions = store.versions(TckRunner.RT, new com.ash.lake.spi.TableRef(a[2], a[3], a[4]));   // lake-only
        java.time.Instant when = versions.get(index).committed().minus(java.time.Duration.ofDays(before ? 1 : 0));   // lake-only: before: a day before it
        var asOf = byTime ? com.ash.lake.spi.AsOf.time(when) : com.ash.lake.spi.AsOf.version(versions.get(index).version());   // lake-only
        var s = lake.table(a[4]).snapshot(asOf).orElse(null);   // lake-only
        if (s == null) return "no table then; problems " + lake.problems().all().size();   // lake-only
        StringBuilder out = new StringBuilder("dates " + s.dates().size());   // lake-only
        for (LocalDate d : s.dates()) {   // lake-only
            out.append('\n').append(d).append(": ").append(ids(s, d).replaceAll(" mapped=.*", "")).append(" | ").append(docs(s, d).replaceAll(" filled .*", ""));   // lake-only
        }   // lake-only
        return out.toString();   // lake-only
    }   // lake-only

    /**   // lake-only
     * Statistics pruning in a vector read: {@code keep} accepts the files whose partition values or text bounds may hold {@code column =   // lake-only
     * value} (the pushdown rule); the rows with that value equal those of the full read, and no more files are read.   // lake-only
     */   // lake-only
    static String keep(com.ash.lake.read.TableSnapshot s, LocalDate d, String vector, String column, String value) throws java.io.IOException {   // lake-only
        var where = com.ash.lake.spi.Expr.eq(column, value);   // lake-only
        int[] kept = {0};   // lake-only
        java.util.function.Predicate<com.ash.lake.read.DataFile> keep = f -> {   // lake-only
            boolean k = com.ash.lake.io.read.Pruning.mayHold(where, d, f.partition(), f.bounds()::get);   // lake-only
            kept[0] += k ? 1 : 0;   // lake-only
            return k;   // lake-only
        };   // lake-only
        List<String> full = new ArrayList<>();   // lake-only
        List<String> pruned = new ArrayList<>();   // lake-only
        s.vectors(com.ash.lake.read.VectorRequest.of(d, vector, List.of(column)), (id, v, n, t) -> { if (value.equals(t[0])) full.add(id + "|" + n); });   // lake-only
        s.vectors(com.ash.lake.read.VectorRequest.of(d, vector, List.of(column)).keep(keep), (id, v, n, t) -> { if (value.equals(t[0])) pruned.add(id + "|" + n); });   // lake-only
        int files = s.day(d).map(x -> x.files().size()).orElse(0);   // lake-only
        return pruned.size() + " rows equal=" + pruned.equals(full) + " kept<=files=" + (kept[0] <= files) + " kept>0=" + (kept[0] > 0);   // lake-only
    }   // lake-only

    /** {@code vectorsOf} in one file: one id, three ids of that file, an absent id; what each finds, and its vectors' digest. */   // lake-only
    static String vectorsOf(com.ash.lake.read.TableSnapshot s, LocalDate d, String column) throws java.io.IOException {   // lake-only
        var m = s.ids(d);   // lake-only
        int file = m.file(m.id(0));   // lake-only
        List<String> same = new ArrayList<>();   // lake-only
        for (int i = 0; i < m.size() && same.size() < 3; i++) {   // lake-only
            if (m.file(m.id(i)) == file) same.add(m.id(i));   // lake-only
        }   // lake-only
        StringBuilder out = new StringBuilder();   // lake-only
        for (var ask : List.of(java.util.Set.of(same.get(0)), java.util.Set.copyOf(same), java.util.Set.of("ABSENT"))) {   // lake-only
            var got = s.vectorsOf(d, file, ask, List.of(column));   // lake-only
            List<String> lines = new ArrayList<>();   // lake-only
            got.forEach((id, v) -> lines.add(id + "|" + java.util.Arrays.toString(v.get(column))));   // lake-only
            out.append(ask.size()).append(" asked ").append(got.size()).append(" found ").append(digest(lines)).append('\n');   // lake-only
        }   // lake-only
        return out.toString().strip();   // lake-only
    }   // lake-only

    static String identity(com.ash.lake.spi.TableStore store, String[] a, com.ash.lake.read.LakeReader lake) throws java.io.IOException {   // lake-only
        var one = lake.table(a[4]).identity();   // lake-only
        var other = com.ash.lake.LakeRuntime.builder().options(com.ash.lake.LakeOptions.fromProcess()).storage(TckRunner.MEMORY).build();   // lake-only
        try (var again = store.open(other, com.ash.lake.read.LakeAddress.of(a[2], a[3]))) {   // lake-only
            var two = again.table(a[4]).identity();   // lake-only
            String differs = a.length > 5 ? " differs-from-" + a[5] + "=" + !lake.table(a[5]).identity().text().equals(one.text()) : "";   // lake-only
            return "kind-is-the-format=" + one.kind().equals(store.format()) + " stable=" + one.equals(two) + differs;   // lake-only
        }   // lake-only
    }   // lake-only

    static String purge(com.ash.lake.read.LakeReader lake, String table, LocalDate d) throws java.io.IOException {   // lake-only
        String before = ids(snap(lake, table), d);   // lake-only
        lake.purge();   // lake-only
        String after = ids(snap(lake, table), d);   // lake-only
        lake.purge(d);   // lake-only
        String again = ids(snap(lake, table), d);   // lake-only
        return "same=" + (before.equals(after) && after.equals(again));   // lake-only
    }   // lake-only

    /** A date's first data file moved away: the read fails with its reason and is listed; moved back: the problem is cleared. */   // lake-only
    static String problems(com.ash.lake.read.LakeReader lake, String table, LocalDate d) throws java.io.IOException {   // lake-only
        var s = snap(lake, table);   // lake-only
        var file = s.day(d).orElseThrow().files().get(0).local().orElseThrow();   // lake-only
        Path aside = file.resolveSibling(file.getFileName() + ".aside");   // lake-only
        Files.move(file, aside);   // lake-only
        String failed;   // lake-only
        try {   // lake-only
            s.ids(d);   // lake-only
            failed = "read";   // lake-only
        } catch (RuntimeException e) {   // lake-only
            failed = "failed " + String.valueOf(e.getMessage()).split(" ")[0];   // lake-only: the code (LK-1006)
        }   // lake-only
        boolean listed = !lake.problems().isEmpty() && lake.problems().all().get(0).where().equals(d.toString());   // lake-only
        Files.move(aside, file);   // lake-only
        s.ids(d);   // lake-only
        return failed + " listed=" + listed + " cleared=" + lake.problems().isEmpty();   // lake-only
    }   // lake-only
}   // lake-only

/**   // lake-only
 * Deletes one row of a date the way each format's other writers do (READ_API.md 6, read/deletes): a Delta deletion vector (a Spark-style   // lake-only
 * DELETE: the file kept, its row index in a vector), a files commit without the line. The loader never writes either.   // lake-only
 */   // lake-only
final class DeleteRow {   // lake-only
    static String run(String[] a) throws java.io.IOException {   // lake-only
        try {   // lake-only
            return delete(a);   // lake-only
        } catch (java.io.IOException | RuntimeException e) {   // lake-only
            throw e;   // lake-only
        } catch (Exception e) {   // lake-only
            throw new java.io.IOException(e.getMessage(), e);   // lake-only
        }   // lake-only
    }   // lake-only

    static String delete(String[] a) throws Exception {   // lake-only
        String format = a[1], root = a[2], domain = a[3], table = a[4], id = a[6];   // lake-only
        LocalDate date = LocalDate.parse(a[5]);   // lake-only
        if (format.equals("files")) {   // lake-only
            List<String> keep = new ArrayList<>();   // lake-only
            for (String l : com.ash.lake.files.FilesStore.dayLines(TckRunner.RT, root, domain, table, date)) {   // lake-only
                if (!l.contains("\"id\":\"" + id + "\"")) keep.add(l);   // lake-only
            }   // lake-only
            com.ash.lake.files.FilesStore.rewriteDay(TckRunner.RT, root, domain, table, date, keep, "delete");   // lake-only
            return "deleted one row";   // lake-only
        }   // lake-only
        if (!format.equals("delta")) throw new UnsupportedOperationException("no row delete for " + format + " in the kit");   // lake-only
        String path = root + "/" + domain + "/" + table;   // lake-only
        Engine engine = com.ash.lake.delta.NativeEngine.create(TckRunner.RT);   // lake-only
        Snapshot snapshot = io.delta.kernel.Table.forPath(engine, path).getLatestSnapshot(engine);   // lake-only
        try (var files = snapshot.getScanBuilder().build().getScanFiles(engine)) {   // lake-only
            while (files.hasNext()) {   // lake-only
                try (var rows = files.next().getRows()) {   // lake-only
                    while (rows.hasNext()) {   // lake-only
                        Row file = rows.next();   // lake-only
                        if (!date.toString().equals(InternalScanFileUtils.getPartitionValues(file).get("business_date"))) continue;   // lake-only
                        var fs = InternalScanFileUtils.getAddFileStatus(file);   // lake-only
                        long at = 0, row = -1;   // lake-only
                        try (var it = engine.getParquetHandler().readParquetFiles(Utils.singletonCloseableIterator(fs),   // lake-only
                                new io.delta.kernel.types.StructType().add("id", io.delta.kernel.types.StringType.STRING), java.util.Optional.empty())) {   // lake-only
                            while (it.hasNext() && row < 0) {   // lake-only
                                var b = it.next().getData();   // lake-only
                                for (int i = 0; i < b.getSize(); i++, at++) if (id.equals(b.getColumnVector(0).getString(i))) { row = at; break; }   // lake-only
                            }   // lake-only
                        }   // lake-only
                        if (row >= 0) return write(Path.of(path), snapshot.getVersion() + 1, fs, date.toString(), (int) row);   // lake-only
                    }   // lake-only
                }   // lake-only
            }   // lake-only
        }   // lake-only
        throw new IllegalArgumentException(id + " is not in " + path + " on " + date);   // lake-only
    }   // lake-only

    /** The row's deletion vector (a portable roaring bitmap of one array container) and the commit that sets it on the file. */   // lake-only
    static String write(Path table, long version, io.delta.kernel.utils.FileStatus file, String date, int row) throws Exception {   // lake-only
        java.nio.ByteBuffer bm = java.nio.ByteBuffer.allocate(18).order(java.nio.ByteOrder.LITTLE_ENDIAN);   // lake-only
        bm.putInt(12346).putInt(1).putShort((short) (row >>> 16)).putShort((short) 0).putInt(16).putShort((short) (row & 0xFFFF));   // lake-only
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(16 + bm.capacity()).order(java.nio.ByteOrder.LITTLE_ENDIAN);   // lake-only
        data.putInt(1681511377).putLong(1).putInt(0).put(bm.array());   // lake-only
        byte[] bytes = data.array();   // lake-only
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();   // lake-only
        crc.update(bytes);   // lake-only
        java.util.UUID uuid = java.util.UUID.randomUUID();   // lake-only
        java.io.ByteArrayOutputStream dv = new java.io.ByteArrayOutputStream();   // lake-only
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(dv)) {   // lake-only
            out.writeByte(1);   // lake-only
            out.writeInt(bytes.length);   // lake-only
            out.write(bytes);   // lake-only
            out.writeInt((int) crc.getValue());   // lake-only
        }   // lake-only
        Files.write(table.resolve("deletion_vector_" + uuid + ".bin"), dv.toByteArray());   // lake-only
        String rel = file.getPath().substring(file.getPath().indexOf("business_date="));   // lake-only
        long now = System.currentTimeMillis();   // lake-only
        String pv = "{\"business_date\":\"" + date + "\"}";   // lake-only
        String commit = String.join("\n",   // lake-only
                "{\"commitInfo\":{\"timestamp\":" + now + ",\"operation\":\"DELETE\"}}",   // lake-only
                "{\"protocol\":{\"minReaderVersion\":3,\"minWriterVersion\":7,\"readerFeatures\":[\"deletionVectors\"],\"writerFeatures\":[\"deletionVectors\"]}}",   // lake-only
                "{\"remove\":{\"path\":\"" + rel + "\",\"deletionTimestamp\":" + now + ",\"dataChange\":true,\"extendedFileMetadata\":true,\"partitionValues\":"   // lake-only
                        + pv + ",\"size\":" + file.getSize() + "}}",   // lake-only
                "{\"add\":{\"path\":\"" + rel + "\",\"partitionValues\":" + pv + ",\"size\":" + file.getSize() + ",\"modificationTime\":" + now   // lake-only
                        + ",\"dataChange\":true,\"deletionVector\":{\"storageType\":\"u\",\"pathOrInlineDv\":\""   // lake-only
                        + io.delta.kernel.internal.deletionvectors.Base85Codec.encodeUUID(uuid) + "\",\"offset\":1,\"sizeInBytes\":" + bytes.length   // lake-only
                        + ",\"cardinality\":1}}}") + "\n";   // lake-only
        Files.writeString(table.resolve("_delta_log").resolve("%020d.json".formatted(version)), commit, StandardCharsets.UTF_8);   // lake-only
        return "deleted one row";   // lake-only
    }   // lake-only
}   // lake-only

