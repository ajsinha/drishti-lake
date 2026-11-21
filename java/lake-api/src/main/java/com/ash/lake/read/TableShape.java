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
package com.ash.lake.read;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * How a foreign table's rows are a kind's documents ({@link LakeReader#foreign}): built from a connector's {@code tables.<kind>.*} settings
 * by {@link #of}.
 *
 * @param kind           the kind the table's rows are
 * @param table          where the table is: {@code name}, or {@code namespace.name} (levels joined by dots)
 * @param id             the id's columns, joined by {@code idSeparator}; empty: the table's identifier fields (Iceberg only)
 * @param idSeparator    between the parts of a composite id ({@code :})
 * @param date           the business date's column (a date, or a timestamp with {@code dateFromDay}); null: the partition's day (or,
 *                       for a folder of files, the date in each file's name); none of those: the table has no dates
 *                       ({@link java.time.LocalDate#MIN} is its one date)
 * @param dateFromDay    a timestamp's day in {@code dateZone} is the business date ({@code date-from: day})
 * @param dateZone       the zone of a timestamp's day (UTC)
 * @param documentColumn a column holding each row's JSON document; null: the row is the document ({@code document: row})
 * @param exclude        top-level columns left out of the document and of the searchable paths
 * @param rename         column name to its name in the document
 * @param links          searchable path to the kind it names (for the host's links)
 * @param depth          how deep nested structures give searchable paths ({@code a.b} is depth 2)
 * @param decimalsAsText decimals as JSON text ({@code decimals: string}) rather than numbers
 * @param omitBinary     binary values left out ({@code binary: omit}) rather than base64 text
 */
public record TableShape(String kind, String table, List<String> id, String idSeparator, String date, boolean dateFromDay, ZoneId dateZone,
        String documentColumn, Set<String> exclude, Map<String, String> rename, Map<String, String> links, int depth, boolean decimalsAsText,
        boolean omitBinary) {

    public TableShape {
        id = List.copyOf(id);
        exclude = Set.copyOf(exclude);
        rename = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rename));
        links = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(links));
        if (depth < 1) {
            throw new IllegalArgumentException("tables." + kind + ".depth is 1 or more, not " + depth);
        }
    }

    /** The name a column has in the document. */
    public String nameOf(String column) {
        return rename.getOrDefault(column, column);
    }

    /** The namespace's levels ({@code risk_mart.trades}: [risk_mart]); empty: the reader's domain. */
    public List<String> namespace() {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? List.of() : Arrays.asList(table.substring(0, dot).split("\\."));
    }

    /** The table's own name, without its namespace. */
    public String tableName() {
        return table.substring(table.lastIndexOf('.') + 1);
    }

    /**
     * One kind's shape from {@code settings}: {@code tables.<kind>.table} (default the kind), {@code .id} ({@code [a, b]} or {@code a,b}),
     * {@code .id-separator}, {@code .date}, {@code .date-from} ({@code day}), {@code .date-zone}, {@code .document} ({@code row} or
     * {@code column:<name>}), {@code .columns.exclude}, {@code .columns.rename.<column>}, {@code .links.<path>}, {@code .depth}; and the
     * connector's {@code decimals} ({@code number}, {@code string}) and {@code binary} ({@code base64}, {@code omit}).
     */
    public static TableShape of(String kind, Map<String, String> settings) {
        return of(kind, settings, null);
    }

    /** As {@link #of(String, Map)}, the table {@code table} when it was found rather than named ({@code tables: auto}). */
    public static TableShape of(String kind, Map<String, String> s, String table) {
        String prefix = "tables." + kind + ".";
        String t = table != null ? table : s.getOrDefault(prefix + "table", kind).trim();
        Map<String, String> rename = new LinkedHashMap<>();
        Map<String, String> links = new LinkedHashMap<>();
        new java.util.TreeMap<>(s).forEach((k, v) -> {
            if (k.startsWith(prefix + "columns.rename.")) {
                rename.put(k.substring((prefix + "columns.rename.").length()), v.trim());
            } else if (k.startsWith(prefix + "links.")) {
                links.put(k.substring((prefix + "links.").length()), v.trim());
            }
        });
        String document = s.getOrDefault(prefix + "document", "row").trim();
        String docColumn = document.startsWith("column:") ? document.substring(7).trim() : null;
        if (docColumn == null && !document.equals("row")) {
            throw new IllegalArgumentException(prefix + "document must be row or column:<name>, not " + document);
        }
        String dateFrom = s.getOrDefault(prefix + "date-from", "").trim();
        if (!dateFrom.isEmpty() && !dateFrom.equalsIgnoreCase("day")) {
            throw new IllegalArgumentException(prefix + "date-from takes day (the business date of a timestamp), not " + dateFrom);
        }
        String decimals = s.getOrDefault("decimals", "number").trim();
        if (!decimals.equalsIgnoreCase("number") && !decimals.equalsIgnoreCase("string")) {
            throw new IllegalArgumentException("decimals must be number or string, not " + decimals);
        }
        String binary = s.getOrDefault("binary", "base64").trim();
        if (!binary.equalsIgnoreCase("base64") && !binary.equalsIgnoreCase("omit")) {
            throw new IllegalArgumentException("binary must be base64 or omit, not " + binary);
        }
        String date = s.getOrDefault(prefix + "date", "").trim();
        int depth;
        try {
            depth = Integer.parseInt(s.getOrDefault(prefix + "depth", "2").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(prefix + "depth is a whole number, not " + s.get(prefix + "depth"));
        }
        return new TableShape(kind, t, list(s.getOrDefault(prefix + "id", "")), s.getOrDefault(prefix + "id-separator", ":"),
                date.isEmpty() ? null : date, !dateFrom.isEmpty(), ZoneId.of(s.getOrDefault(prefix + "date-zone", "UTC").trim()), docColumn,
                new TreeSet<>(list(s.getOrDefault(prefix + "columns.exclude", ""))), rename, links, depth,
                decimals.equalsIgnoreCase("string"), binary.equalsIgnoreCase("omit"));
    }

    /** The kinds {@code settings} name ({@code tables.<kind>.*}), each with its shape, in kind order. */
    public static List<TableShape> all(Map<String, String> settings) {
        Set<String> kinds = new TreeSet<>();
        settings.keySet().forEach(k -> {
            if (k.startsWith("tables.")) {
                String rest = k.substring(7);
                int dot = rest.indexOf('.');
                if (dot > 0) {
                    kinds.add(rest.substring(0, dot));
                }
            }
        });
        List<TableShape> out = new ArrayList<>();
        for (String kind : kinds) {
            out.add(of(kind, settings));
        }
        return out;
    }

    /** A found table's kind: its name in lower case with {@code -} for {@code _} ({@code RISK_TRADES}: {@code risk-trades}). */
    public static String kindOf(String tableName) {
        return tableName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static List<String> list(String v) {
        List<String> out = new ArrayList<>();
        for (String p : v.replaceAll("^\\[|]$", "").split(",")) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        return out;
    }
}
