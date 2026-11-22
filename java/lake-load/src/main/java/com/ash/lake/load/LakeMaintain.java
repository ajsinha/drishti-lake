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

import com.ash.lake.common.RowRetention;
import com.ash.lake.spi.TableRef;
import com.ash.tools.time.LoadGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * The one table upkeep of every store ({@code lake maintain}, docs/guides/LOADER.md "Maintenance"): on a Delta table, an Iceberg table or a
 * files table, by the same options and in this order, each step an ordinary commit of the table (a reader sees the table before or after
 * it, never half of it):
 *
 * <ol>
 *   <li>{@code --keep-days N}: the business dates older than the table's newest N on or before {@code --as-of} (the business today) are
 *       removed in one commit ({@link LakeRetention#dropDates}); refused, nothing removed, when that is more than {@code --max-drop-share}
 *       (0.5) of the table's rows, unless {@code --force-drop} ({@link LoadGuard#checkDrop}); never a date within {@code --floor-days} of
 *       {@code --as-of} (the retention floor);</li>
 *   <li>{@code --ttl}, {@code --on}, {@code --max-rows}: the row retention ({@link LakeRetention#apply});</li>
 *   <li>{@code --compact} ({@code --target-file-mb}, 128): the small files of each business date merged (Delta: a date in two or more
 *       files under the target rewritten, OPTIMIZE; Iceberg: a date that drifted from its layout rewritten sorted; files: a date is always
 *       one file, nothing to do);</li>
 *   <li>{@code --checkpoint}: Delta: a checkpoint of the latest version; Iceberg: the manifests regrouped one per month; files: nothing
 *       to do (the day files are the current state, the log is read only for history);</li>
 *   <li>{@code --vacuum-hours H}: the files no version of the last H hours references are deleted, so time travel reaches back H hours
 *       (Delta: VACUUM; Iceberg: snapshots older than H expired, then orphan files; files: the log's content files of older versions). A
 *       file no commit names goes only when older than 72 hours too (it may belong to a load still running). {@code --floor-days D} raises
 *       H to D days.</li>
 * </ol>
 *
 * <pre>LakeMaintain --lake ROOT --format delta|iceberg|files --table domain/table [--keep-days N [--as-of D] [--zone Z] [--max-drop-share F]
 *     [--force-drop]] [--floor-days D] [--ttl 8h [--on FIELD] [--max-rows N]] [--compact [--target-file-mb N]] [--checkpoint]
 *     [--vacuum-hours H] [--dry-run] [--json]</pre>
 * The line it prints (without {@code --json}) is the same on every store: the dates and rows before and after and the steps run; with
 * {@code --json}, the whole answer of {@link #maintain} (each step's store-specific detail). Exit codes: 0 done, 1 failed.
 */
public final class LakeMaintain {

    static final ObjectMapper JSON = new ObjectMapper();

    private LakeMaintain() {}

    /** What one run does: every step off unless set. */
    public record Steps(int keepDays, LocalDate asOf, String zone, double maxDropShare, boolean forceDrop, int floorDays, RowRetention rows,
            boolean compact, long targetFileMb, boolean checkpoint, Long vacuumHours) {

        /** No step: a run that only reports the table. */
        public static Steps none() {
            return new Steps(0, null, null, 0.5, false, 0, new RowRetention(null, null, null), false, 128, false, null);
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** Runs as the command line does, with the process's settings (no host). */
    public static int run(String[] args, PrintStream out) {
        return run(com.ash.lake.LakeRuntime.fromProcess(), args, out);
    }

    public static int run(com.ash.lake.LakeRuntime runtime, String[] args, PrintStream out) {
        String root = null;
        String format = "delta";
        String table = null;
        int keepDays = 0;
        LocalDate asOf = null;
        String zone = null;
        double share = 0.5;
        boolean force = false;
        int floor = 0;
        String ttl = null;
        String on = null;
        Integer maxRows = null;
        boolean compact = false;
        long target = 128;
        boolean checkpoint = false;
        Long vacuum = null;
        boolean dry = false;
        boolean json = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--lake" -> root = value(args, ++i);
                    case "--format" -> format = value(args, ++i);
                    case "--table" -> table = value(args, ++i);
                    case "--keep-days" -> keepDays = Integer.parseInt(value(args, ++i));
                    case "--as-of" -> asOf = LocalDate.parse(value(args, ++i));
                    case "--zone" -> zone = value(args, ++i);
                    case "--max-drop-share" -> share = Double.parseDouble(value(args, ++i));
                    case "--force-drop" -> force = true;
                    case "--floor-days" -> floor = Integer.parseInt(value(args, ++i));
                    case "--ttl" -> ttl = value(args, ++i);
                    case "--on" -> on = value(args, ++i);
                    case "--max-rows" -> maxRows = Integer.valueOf(value(args, ++i));
                    case "--compact" -> compact = true;
                    case "--target-file-mb" -> target = Long.parseLong(value(args, ++i));
                    case "--checkpoint" -> checkpoint = true;
                    case "--vacuum-hours" -> vacuum = Long.valueOf(value(args, ++i));
                    case "--dry-run" -> dry = true;
                    case "--json" -> json = true;
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (root == null || table == null) {
                throw new IllegalArgumentException("give --lake ROOT and --table domain/table");
            }
            Steps steps = new Steps(keepDays, asOf, zone, share, force, floor, new RowRetention(ttl, on, maxRows), compact, target, checkpoint, vacuum);
            Map<String, Object> done = maintain(runtime, root, format, table, steps, Instant.now(), dry);
            out.println(json ? JSON.writeValueAsString(done) : line(done, steps, dry));
            return 0;
        } catch (Exception e) {
            out.println("lakemaintain: " + e.getMessage());
            return 1;
        }
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException(args[i - 1] + " needs a value");
        }
        return args[i];
    }

    /**
     * Maintains {@code domain/table} of the lake (or files store) at {@code root}: {@code table}, {@code format}, {@code dryRun},
     * {@code before} and {@code after} ({@code dates}, {@code rows}), and a key per step run: {@code keepDays} ({@code from}, {@code dates},
     * {@code rows}), {@code rowRetention}, {@code compact} ({@code dates}), {@code checkpoint}, {@code vacuum} ({@code hours}, {@code files},
     * {@code bytes}, {@code first}). An absent table: refused.
     */
    public static Map<String, Object> maintain(com.ash.lake.LakeRuntime runtime, String root, String format, String table, Steps steps, Instant now,
            boolean dryRun) throws Exception {
        int slash = table.indexOf('/');
        String domain = slash < 0 ? "" : table.substring(0, slash);
        String kind = table.substring(slash + 1);
        var store = Stores.of(runtime, format);
        TableRef ref = new TableRef(root, domain, kind);
        if (!store.exists(runtime, ref)) {
            throw new java.io.IOException("no table " + table + " in " + root);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", table);
        m.put("format", format);
        m.put("dryRun", dryRun);
        m.put("before", shape(store.dates(runtime, ref)));
        String path = LakeRetention.tablePath(root, table);
        Long vacuum = steps.vacuumHours();
        if (vacuum != null && steps.floorDays() > 0 && vacuum < steps.floorDays() * 24L) {
            vacuum = steps.floorDays() * 24L;                    // time travel is kept as long as the dates are
        }
        if (steps.keepDays() > 0) {
            m.put("keepDays", keepDays(runtime, root, format, table, store.dates(runtime, ref), steps, dryRun));
        }
        if (steps.rows().active()) {
            m.put("rowRetention", LakeRetention.apply(runtime, root, format, table, steps.rows(), now, dryRun).get("dates"));
        }
        java.nio.file.Path spill = com.ash.lake.common.TempFiles.of(runtime).subDir("iceberg-maintain");
        Map<String, Object> iceberg = format.equals("iceberg") && (steps.compact() || steps.checkpoint() || vacuum != null)
                ? com.ash.lake.iceberg.table.IcebergMaintenance.maintainTable(runtime, root, domain, kind, steps.compact(), steps.checkpoint(), vacuum,
                        dryRun, spill)
                : Map.of();
        if (steps.compact()) {
            m.put("compact", switch (format) {
                case "delta" -> Map.of("dates", DeltaUpkeep.compact(runtime, path, steps.targetFileMb() << 20, dryRun));
                case "iceberg" -> iceberg.get("compact");
                default -> Map.of("dates", List.of(), "note", "a files table holds each date in one file");
            });
        }
        if (steps.checkpoint()) {
            m.put("checkpoint", switch (format) {
                case "delta" -> dryRun ? Map.of("version", "would be written") : Map.of("version", DeltaUpkeep.checkpoint(runtime, path));
                case "iceberg" -> iceberg.get("checkpoint");
                default -> Map.of("note", "a files table's day files are its current state: nothing to replay");
            });
        }
        if (vacuum != null) {
            long at = Math.max(now.toEpochMilli(), System.currentTimeMillis());   // after the steps before it: their commits are older than the cut
            m.put("vacuum", switch (format) {
                case "delta" -> DeltaUpkeep.vacuum(runtime, path, vacuum, at, dryRun);
                case "iceberg" -> iceberg.get("vacuum");
                default -> com.ash.lake.files.FilesStore.vacuum(runtime, root, domain, kind, vacuum, at, dryRun);
            });
        }
        m.put("after", shape(store.dates(runtime, ref)));
        return m;
    }

    /** The keep-days step: the dates before the cut removed in one commit, under the drop guard and the floor. */
    static Map<String, Object> keepDays(com.ash.lake.LakeRuntime runtime, String root, String format, String table, SortedMap<LocalDate, Long> held,
            Steps steps, boolean dryRun) throws Exception {
        java.time.ZoneId zone = steps.zone() == null || steps.zone().isBlank() ? runtime.options().businessZone() : java.time.ZoneId.of(steps.zone());
        LoadGuard guard = new LoadGuard(zone, java.time.Clock.systemUTC(), steps.asOf(), 1, steps.maxDropShare(), steps.forceDrop());
        LocalDate floor = steps.floorDays() > 0 ? guard.asOf().minusDays(steps.floorDays() - 1L) : null;
        var from = guard.keepFromNewest(held.keySet(), steps.keepDays());
        Map<String, Object> out = new LinkedHashMap<>();
        if (from.isPresent() && floor != null && floor.isBefore(from.get())) {
            from = java.util.Optional.of(floor);                   // the floor keeps more
        }
        List<String> dates = new ArrayList<>();
        long rows = 0;
        long total = 0;
        boolean counted = true;
        for (Map.Entry<LocalDate, Long> e : held.entrySet()) {
            counted &= e.getValue() >= 0;
            total += Math.max(0, e.getValue());
            if (from.isPresent() && e.getKey().isBefore(from.get())) {
                dates.add(e.getKey().toString());
                rows += Math.max(0, e.getValue());
            }
        }
        out.put("from", from.map(LocalDate::toString).orElse(null));
        out.put("dates", dates);
        out.put("rows", rows);
        if (dates.isEmpty()) {
            return out;
        }
        if (counted) {
            guard.checkDrop(table, rows, total, "rows");
        } else {
            guard.checkDrop(table, dates.size(), held.size(), "business days");
        }
        if (!dryRun) {
            java.util.Set<String> gone = new java.util.HashSet<>(dates);
            LakeRetention.dropDates(runtime, root, format, table, gone::contains, false);
        }
        return out;
    }

    private static Map<String, Object> shape(SortedMap<LocalDate, Long> dates) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dates", dates.size());
        m.put("rows", dates.values().stream().anyMatch(n -> n < 0) ? -1 : dates.values().stream().mapToLong(Long::longValue).sum());
        return m;
    }

    /** The line every store prints alike: the table before and after, and the steps run. */
    @SuppressWarnings("unchecked")
    static String line(Map<String, Object> m, Steps s, boolean dryRun) {
        StringBuilder b = new StringBuilder(String.valueOf(m.get("table"))).append(dryRun ? " (dry run): " : ": ");
        Map<String, Object> before = (Map<String, Object>) m.get("before");
        Map<String, Object> after = (Map<String, Object>) m.get("after");
        b.append(before.get("dates")).append(" business dates, ").append(rows(before)).append(" before");
        List<String> steps = new ArrayList<>();
        if (m.containsKey("keepDays")) {
            Map<String, Object> k = (Map<String, Object>) m.get("keepDays");
            steps.add("keep-days " + s.keepDays() + " " + (dryRun ? "would remove " : "removed ") + k.get("dates"));
        }
        if (m.containsKey("rowRetention")) {
            List<Map<String, Object>> d = (List<Map<String, Object>>) m.get("rowRetention");
            steps.add("row retention " + (dryRun ? "would drop " : "dropped ") + d.stream().mapToLong(x -> ((Number) x.get("dropped")).longValue()).sum()
                    + " rows");
        }
        if (m.containsKey("compact")) {
            steps.add("compacted");
        }
        if (m.containsKey("checkpoint")) {
            steps.add("checkpoint");
        }
        if (m.containsKey("vacuum")) {
            steps.add("vacuum " + ((Map<String, Object>) m.get("vacuum")).get("hours") + "h");
        }
        if (!steps.isEmpty()) {
            b.append("; ").append(String.join("; ", steps));
        }
        b.append("; ").append(after.get("dates")).append(" business dates, ").append(rows(after)).append(" after");
        return b.toString();
    }

    private static String rows(Map<String, Object> shape) {
        long n = ((Number) shape.get("rows")).longValue();
        return n < 0 ? "rows unstated" : n + " rows";
    }
}
