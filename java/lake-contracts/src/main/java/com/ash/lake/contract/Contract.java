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
package com.ash.lake.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The data contract of one table kind, read from its YAML file ({@code <kind>.contract.yaml}; the format: the Python package's
 * {@code drishti_lake/contracts/schema.json}): its fields (type, presence, enum, pattern), the nested records' fields, the required list
 * (the validation rule {@code required} when there is one, else the fields marked required), the derived fields (never loaded). Every
 * other key, a host's own ones too, stays in {@link #node()} untouched. The same reading as {@code drishti_lake.contracts.Contract}.
 */
public final class Contract {

    private final JsonNode node;
    private final Map<String, JsonNode> fields = new LinkedHashMap<>();
    private final Map<String, Map<String, JsonNode>> nested = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();
    private final Map<String, List<JsonNode>> enums = new LinkedHashMap<>();
    private final Map<String, String> patterns = new LinkedHashMap<>();
    private final Set<String> derived = new LinkedHashSet<>();

    private Contract(JsonNode node) {
        this.node = node;
        for (JsonNode f : node.path("fields")) {
            fields.put(f.path("name").asText(), f);
        }
        JsonNode rules = node.path("validation");
        JsonNode req = null;
        for (JsonNode r : rules) {
            String rule = r.path("rule").asText();
            if (rule.equals("required") && req == null) {
                req = r;
            } else if (rule.equals("enum")) {
                enums.put(r.path("field").asText(), list(r.path("values")));
            } else if (rule.equals("pattern")) {
                patterns.put(r.path("field").asText(), r.path("pattern").asText());
            }
        }
        if (req != null) {
            req.path("fields").forEach(x -> required.add(x.asText()));
        } else {
            required.addAll(requiredFields());
        }
        fields.forEach((n, f) -> {
            if (f.path("enum").isArray() && !f.path("enum").isEmpty()) {
                enums.putIfAbsent(n, list(f.path("enum")));
            }
            if (f.hasNonNull("pattern") && !f.path("pattern").asText().isEmpty()) {
                patterns.putIfAbsent(n, f.path("pattern").asText());
            }
            if ("derived".equals(f.path("presence").asText())) {
                derived.add(n);
            }
        });
        for (var it = node.path("nested").fields(); it.hasNext(); ) {
            var e = it.next();
            Map<String, JsonNode> fs = new LinkedHashMap<>();
            e.getValue().forEach(f -> fs.put(f.path("name").asText(), f));
            nested.put(e.getKey(), fs);
        }
    }

    /** A contract file; refused when it cannot be read or is not a mapping. */
    public static Contract read(Path file) throws IOException {
        JsonNode n = new YAMLMapper().readTree(Files.readString(file));
        if (n == null || !n.isObject()) {
            throw new IOException(file + ": a data contract is a mapping (kind:, version:, fields:, ...)");
        }
        return new Contract(n);
    }

    /** A contract already parsed (a mapping). */
    public static Contract of(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("a data contract is a mapping (kind:, version:, fields:, ...)");
        }
        return new Contract(node);
    }

    private static List<JsonNode> list(JsonNode a) {
        List<JsonNode> out = new ArrayList<>();
        a.forEach(out::add);
        return out;
    }

    /** The contract as written: every key, a host's own ones too. */
    public JsonNode node() {
        return node;
    }

    public String kind() {
        return node.path("kind").asText("");
    }

    public String version() {
        return node.path("version").asText("");
    }

    /** The field that dates the kind ({@code dated-by}), or "". */
    public String datedBy() {
        return node.path("dated-by").asText("");
    }

    /** The key's fields, in order. */
    public List<String> key() {
        JsonNode k = node.path("key");
        List<String> out = new ArrayList<>();
        if (k.isArray()) {
            k.forEach(x -> out.add(x.asText()));
        } else if (k.isTextual() && !k.asText().isBlank()) {
            out.add(k.asText());
        }
        return out;
    }

    /** The one field that identifies a row; null when the key has several fields or none. */
    public String singleKey() {
        List<String> k = key().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        return k.size() == 1 && !k.get(0).contains(",") ? k.get(0) : null;
    }

    /** The top-level fields by name, in the file's order. */
    public Map<String, JsonNode> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /** The nested records' fields: path ({@code KYC}, {@code LEGS[]}) to name to field. */
    public Map<String, Map<String, JsonNode>> nested() {
        return Collections.unmodifiableMap(nested);
    }

    /** The fields a record must have: the validation rule {@code required}, else the fields marked required. */
    public List<String> required() {
        return Collections.unmodifiableList(required);
    }

    /** The top-level fields marked {@code presence: required}, in order. */
    public List<String> requiredFields() {
        List<String> out = new ArrayList<>();
        fields.forEach((n, f) -> {
            if ("required".equals(f.path("presence").asText())) {
                out.add(n);
            }
        });
        return out;
    }

    Map<String, List<JsonNode>> enums() {
        return enums;
    }

    Map<String, String> patterns() {
        return patterns;
    }

    Set<String> derived() {
        return derived;
    }
}
