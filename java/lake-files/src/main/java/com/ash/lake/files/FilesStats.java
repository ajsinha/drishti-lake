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
package com.ash.lake.files;

import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A commit's statistics (READ_API.md defect F1): for the id and each text column, the least and greatest value and the nulls, read once
 * as the commit is made, so a reader prunes a date's file from the log alone. A column with a value of 32 characters or more states no
 * bounds (as Delta's writer cuts such values), only its nulls.
 */
final class FilesStats {

    static final int CUT = 32;
    private static final JsonFactory FACTORY = new JsonFactory();

    private FilesStats() {
    }

    private static final class One {
        String min;
        String max;
        long nulls;
        boolean cut;

        void add(String v) {
            if (v == null) {
                nulls++;
                return;
            }
            if (v.length() >= CUT) {
                cut = true;
            }
            if (min == null || v.compareTo(min) < 0) {
                min = v;
            }
            if (max == null || v.compareTo(max) > 0) {
                max = v;
            }
        }
    }

    /** The statistics of a content file of {@code cols} (the lines' {@code id} and their {@code columns}' text columns). */
    static JsonNode of(Place content, Columns cols) throws IOException {
        Map<String, One> by = new LinkedHashMap<>();
        by.put("id", new One());
        for (var c : cols.all()) {
            if (c.type() == ColumnType.STRING && !c.name().equals("id") && !c.name().equals("doc")) {
                by.put(c.name(), new One());
            }
        }
        long rows = 0;
        try (BufferedReader r = content.reader()) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                rows++;
                Map<String, String> seen = new HashMap<>();
                read(line, by, seen);
                for (Map.Entry<String, One> e : by.entrySet()) {
                    e.getValue().add(seen.get(e.getKey()));
                }
            }
        }
        ObjectNode out = FilesLog.JSON.createObjectNode().put("numRecords", rows);
        ObjectNode cs = out.putObject("columns");
        by.forEach((name, o) -> {
            ObjectNode n = cs.putObject(name).put("nulls", o.nulls);
            if (!o.cut && o.min != null) {
                n.put("min", o.min).put("max", o.max);
            }
        });
        return out;
    }

    /** The line's {@code id} and its {@code columns}' values of the wanted names, as text. */
    private static void read(String line, Map<String, One> wanted, Map<String, String> seen) throws IOException {
        try (JsonParser p = FACTORY.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String f = p.currentName();
                JsonToken t = p.nextToken();
                if (f.equals("id") && t.isScalarValue() && t != JsonToken.VALUE_NULL) {
                    seen.put("id", p.getText());
                } else if (f.equals("columns") && t == JsonToken.START_OBJECT) {
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        String c = p.currentName();
                        JsonToken v = p.nextToken();
                        if (wanted.containsKey(c) && !c.equals("id") && v.isScalarValue() && v != JsonToken.VALUE_NULL) {
                            seen.put(c, p.getText());
                        } else {
                            p.skipChildren();
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
    }
}
