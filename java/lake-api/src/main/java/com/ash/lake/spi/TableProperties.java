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
package com.ash.lake.spi;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A load's {@code target.properties} on every store (docs/guides/DATA_LAKE_GUIDE.md, "Table properties"): two properties mean the same on
 * Delta, Iceberg and the files store, each written as the format's native property where it has one; every other key is the format's own
 * and is written as given.
 *
 * <table>
 *   <caption>The mapping</caption>
 *   <tr><th>product name</th><th>Delta</th><th>Iceberg</th><th>files</th></tr>
 *   <tr><td>{@code lake.append-only}</td><td>{@code delta.appendOnly}</td><td>{@code lake.append-only} (Iceberg has none)</td>
 *       <td>{@code lake.append-only}</td></tr>
 *   <tr><td>{@code lake.retention-hours}</td><td>{@code delta.deletedFileRetentionDuration} and {@code delta.logRetentionDuration}
 *       ({@code interval N days}, or hours)</td><td>{@code history.expire.max-snapshot-age-ms}</td><td>{@code lake.retention-hours}</td></tr>
 * </table>
 *
 * A load may give the product name or any format's native name of the two (Delta's on an Iceberg table, say): it is written as the
 * target's native one. Two names of one property with different values are refused, and so is a key with the prefix of tables written before
 * Drishti Lake (PersistedNames: {@code lake.} is the product's). The properties are set when the load creates the table; an existing table
 * keeps its own. What they do, on every store:
 * <ul>
 *   <li><b>append-only</b>: the table takes appends only. A replace (load mode {@code replace-date}, {@code overwrite-partitions}), a delete
 *       of dates, row retention, keep-days and an undo that would remove rows are refused; compaction, relayout, checkpoints and vacuum
 *       (which change no row) run.</li>
 *   <li><b>retention</b>: time travel reaches back at least that long: {@code lake maintain --vacuum-hours H} keeps the files of the last
 *       {@code max(H, retention)} hours (Delta's VACUUM, Iceberg's snapshot expiry, the files store's old contents).</li>
 * </ul>
 */
public final class TableProperties {

    /** The product's name of the append-only mark. */
    public static final String APPEND_ONLY = "lake.append-only";
    /** The product's name of the retention, in hours. */
    public static final String RETENTION_HOURS = "lake.retention-hours";

    static final String DELTA_APPEND_ONLY = "delta.appendOnly";
    static final String DELTA_FILE_RETENTION = "delta.deletedFileRetentionDuration";
    static final String DELTA_LOG_RETENTION = "delta.logRetentionDuration";
    static final String ICEBERG_MAX_AGE = "history.expire.max-snapshot-age-ms";
    private static final long HOUR_MS = 3_600_000L;

    private TableProperties() {
    }

    /** A table's two product properties as its store holds them: append-only, and the retention in hours (null: none set). */
    public record Effective(boolean appendOnly, Long retentionHours) {

        /** As the product names them ({@code lake.append-only}, {@code lake.retention-hours}; a property not set is left out). */
        public Map<String, String> named() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put(APPEND_ONLY, String.valueOf(appendOnly));
            if (retentionHours != null) {
                m.put(RETENTION_HOURS, String.valueOf(retentionHours));
            }
            return m;
        }
    }

    /**
     * The native properties {@code format}'s table is created with for a load's {@code target.properties} (the class doc's mapping).
     *
     * @throws IllegalArgumentException a key of the prefix before Drishti Lake, a value that is not one, or two names of one property that disagree
     */
    public static Map<String, String> forStore(String format, Map<String, String> given) {
        if (given == null || given.isEmpty()) {
            return Map.of();
        }
        Boolean appendOnly = null;
        Long hours = null;
        String hoursFrom = null;
        String appendFrom = null;
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : given.entrySet()) {
            String k = e.getKey();
            String v = e.getValue() == null ? "" : e.getValue().strip();
            if (com.ash.lake.PersistedNames.earlier(k)) {
                throw new IllegalArgumentException("target.properties." + k + ": the product's properties are lake.* (" + APPEND_ONLY + ", "
                        + RETENTION_HOURS + "); " + k.substring(0, k.indexOf('.') + 1) + "* names tables written before Drishti Lake");
            }
            switch (k) {
                case APPEND_ONLY, DELTA_APPEND_ONLY -> {
                    boolean b = bool(k, v);
                    if (appendOnly != null && appendOnly != b) {
                        throw disagree(appendFrom, k);
                    }
                    appendOnly = b;
                    appendFrom = k;
                }
                case RETENTION_HOURS, ICEBERG_MAX_AGE, DELTA_FILE_RETENTION -> {
                    long h = k.equals(RETENTION_HOURS) ? hours(k, v) : k.equals(ICEBERG_MAX_AGE) ? ceilHours(millis(k, v)) : ceilHours(interval(k, v));
                    if (hours != null && hours != h) {
                        throw disagree(hoursFrom, k);
                    }
                    hours = h;
                    hoursFrom = k;
                }
                case DELTA_LOG_RETENTION -> {
                    interval(k, v);                                 // checked; Delta's own (how long its log reaches back), kept on Delta
                    if (format.equals("delta")) {
                        out.put(k, v);
                    }
                }
                default -> out.put(k, e.getValue());               // the format's own
            }
        }
        switch (format) {
            case "delta" -> {
                if (appendOnly != null) {
                    out.put(DELTA_APPEND_ONLY, String.valueOf(appendOnly));
                }
                if (hours != null) {
                    String native_ = given.containsKey(DELTA_FILE_RETENTION) ? given.get(DELTA_FILE_RETENTION).strip() : interval(hours);
                    out.put(DELTA_FILE_RETENTION, native_);
                    out.putIfAbsent(DELTA_LOG_RETENTION, native_);
                }
            }
            case "iceberg" -> {
                if (appendOnly != null) {
                    out.put(APPEND_ONLY, String.valueOf(appendOnly));
                }
                if (hours != null) {
                    out.put(ICEBERG_MAX_AGE, given.containsKey(ICEBERG_MAX_AGE) ? given.get(ICEBERG_MAX_AGE).strip() : String.valueOf(hours * HOUR_MS));
                }
            }
            default -> {
                if (appendOnly != null) {
                    out.put(APPEND_ONLY, String.valueOf(appendOnly));
                }
                if (hours != null) {
                    out.put(RETENTION_HOURS, String.valueOf(hours));
                }
            }
        }
        return out;
    }

    /** The two product properties of a table whose store holds {@code props} (any format's native names, or the product's). */
    public static Effective effective(Map<String, String> props) {
        if (props == null || props.isEmpty()) {
            return new Effective(false, null);
        }
        boolean append = "true".equalsIgnoreCase(props.getOrDefault(DELTA_APPEND_ONLY, props.getOrDefault(APPEND_ONLY, "false")).strip());
        Long hours = null;
        try {
            if (props.containsKey(DELTA_FILE_RETENTION)) {
                hours = ceilHours(interval(DELTA_FILE_RETENTION, props.get(DELTA_FILE_RETENTION).strip()));
            } else if (props.containsKey(ICEBERG_MAX_AGE)) {
                hours = ceilHours(millis(ICEBERG_MAX_AGE, props.get(ICEBERG_MAX_AGE).strip()));
            } else if (props.containsKey(RETENTION_HOURS)) {
                hours = hours(RETENTION_HOURS, props.get(RETENTION_HOURS).strip());
            }
        } catch (IllegalArgumentException e) {
            hours = null;                                           // a value another writer set that is not one: no retention of ours
        }
        return new Effective(append, hours);
    }

    /**
     * Refuses {@code what} on an append-only table (its store's {@code props}): the words every store and the Python path give.
     *
     * @throws IllegalStateException when the table is append-only
     */
    public static void refuseRewrite(Map<String, String> props, String table, String what) {
        if (effective(props).appendOnly()) {
            throw new IllegalStateException(table + " is append-only (" + APPEND_ONLY + "): " + what + " is refused; the table takes appends only");
        }
    }

    /** Refuses a replace (a load of {@code spec}'s replace mode) of an append-only table: {@link #refuseRewrite}. */
    public static void refuseReplace(Map<String, String> props, WriteSpec spec) {
        if (!spec.appends()) {
            refuseRewrite(props, spec.domain() + "/" + spec.table(), spec.partitionsOnly() ? "a replace of partitions (load mode overwrite-partitions)"
                    : "a replace of dates (load mode overwrite-dates)");
        }
    }

    private static IllegalArgumentException disagree(String a, String b) {
        return new IllegalArgumentException("target.properties: " + a + " and " + b + " name one property with different values");
    }

    private static boolean bool(String k, String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("target.properties." + k + ": true or false, not " + v);
        };
    }

    private static long hours(String k, String v) {
        try {
            long h = Long.parseLong(v);
            if (h >= 0) {
                return h;
            }
        } catch (NumberFormatException e) {
            // below
        }
        throw new IllegalArgumentException("target.properties." + k + ": whole hours, 0 or more, not " + v);
    }

    private static long millis(String k, String v) {
        try {
            long ms = Long.parseLong(v);
            if (ms >= 0) {
                return ms;
            }
        } catch (NumberFormatException e) {
            // below
        }
        throw new IllegalArgumentException("target.properties." + k + ": milliseconds, 0 or more, not " + v);
    }

    /** Delta's {@code interval N unit} in milliseconds (unit: week(s), day(s), hour(s), minute(s), second(s)). */
    static long interval(String k, String v) {
        String[] p = v.toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (p.length == 3 && p[0].equals("interval")) {
            try {
                long n = Long.parseLong(p[1]);
                long unit = switch (p[2].endsWith("s") ? p[2].substring(0, p[2].length() - 1) : p[2]) {
                    case "week" -> 7 * 24 * HOUR_MS;
                    case "day" -> 24 * HOUR_MS;
                    case "hour" -> HOUR_MS;
                    case "minute" -> 60_000L;
                    case "second" -> 1_000L;
                    default -> -1;
                };
                if (n >= 0 && unit > 0) {
                    return Math.multiplyExact(n, unit);
                }
            } catch (NumberFormatException | ArithmeticException e) {
                // below
            }
        }
        throw new IllegalArgumentException("target.properties." + k + ": interval N days (or weeks, hours, minutes, seconds), not " + v);
    }

    /** Hours as Delta writes them: {@code interval N days} for whole days, else {@code interval N hours}. */
    static String interval(long hours) {
        return hours % 24 == 0 ? "interval " + hours / 24 + " days" : "interval " + hours + " hours";
    }

    private static long ceilHours(long ms) {
        return (ms + HOUR_MS - 1) / HOUR_MS;
    }
}
