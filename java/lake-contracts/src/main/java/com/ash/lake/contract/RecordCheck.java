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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** The standard {@link ContractChecker} (the Python path's {@code checker.record}, word for word). Stateless, shared. */
final class RecordCheck implements ContractChecker {

    static final RecordCheck STANDARD = new RecordCheck();
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})");
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private RecordCheck() {
    }

    @Override
    public List<String> problems(Contract c, JsonNode r) {
        List<String> bad = new ArrayList<>();
        for (var it = r.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!c.fields().containsKey(k)) {
                bad.add(k + ": not a field of the contract");
            }
        }
        for (String name : c.required()) {
            if (!name.equals(c.datedBy()) && empty(r.get(name))) {
                bad.add(name + ": required, missing");
            }
        }
        for (var it = r.fields(); it.hasNext(); ) {
            var e = it.next();
            String name = e.getKey();
            JsonNode v = e.getValue();
            JsonNode f = c.fields().get(name);
            if (f == null || empty(v)) {                      // an empty value: a required one is missing (above), an optional one absent
                continue;
            }
            if (c.derived().contains(name)) {
                bad.add(name + ": derived, never loaded");
                continue;
            }
            List<JsonNode> en = c.enums().get(name);
            List<String> probs = value(name, f, v, en, c.patterns().get(name));
            bad.addAll(probs);
            if (!probs.isEmpty()) {
                continue;
            }
            String t = f.path("type").asText("unknown");
            if (t.equals("record") && v.isObject()) {
                bad.addAll(nested(c, name, v, name));
            } else if (t.equals("record list") && v.isArray()) {
                for (int i = 0; i < v.size(); i++) {
                    JsonNode x = v.get(i);
                    if (x.isObject()) {
                        bad.addAll(nested(c, name + "[]", x, name + "[" + i + "]"));
                    } else {
                        bad.add(name + "[" + i + "]: not a record");
                    }
                }
            }
        }
        return bad;
    }

    private static List<String> nested(Contract c, String path, JsonNode r, String shown) {
        Map<String, JsonNode> fields = c.nested().get(path);
        List<String> bad = new ArrayList<>();
        if (fields == null) {
            return bad;
        }
        for (var it = r.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!fields.containsKey(k)) {
                bad.add(shown + "." + k + ": not a field of the contract");
            }
        }
        for (var e : fields.entrySet()) {
            JsonNode f = e.getValue();
            JsonNode v = r.get(e.getKey());
            String at = shown + "." + e.getKey();
            if (empty(v)) {
                if ("required".equals(f.path("presence").asText())) {
                    bad.add(at + ": required, missing");
                }
                continue;
            }
            if ("derived".equals(f.path("presence").asText())) {
                bad.add(at + ": derived, never loaded");
                continue;
            }
            List<JsonNode> en = f.path("enum").isArray() && !f.path("enum").isEmpty() ? list(f.path("enum")) : null;
            String p = f.hasNonNull("pattern") && !f.path("pattern").asText().isEmpty() ? f.path("pattern").asText() : null;
            bad.addAll(value(at, f, v, en, p));
        }
        return bad;
    }

    private static List<JsonNode> list(JsonNode a) {
        List<JsonNode> out = new ArrayList<>();
        a.forEach(out::add);
        return out;
    }

    private static boolean empty(JsonNode v) {
        return v == null || v.isNull() || v.isTextual() && v.asText().isEmpty();
    }

    private static List<String> value(String shown, JsonNode f, JsonNode v, List<JsonNode> enums, String pattern) {
        String why = typeError(f.path("type").asText("unknown"), v);
        if (why != null) {
            return List.of(shown + ": " + why);
        }
        List<String> out = new ArrayList<>(2);
        if (enums != null && enums.stream().noneMatch(e -> same(e, v))) {
            out.add(shown + ": not one of the contract's values");
        }
        if (pattern != null && v.isTextual() && !PATTERNS.computeIfAbsent(pattern, Pattern::compile).matcher(v.asText()).find()) {
            out.add(shown + ": does not match " + pattern);
        }
        return out;
    }

    /** Two JSON values equal within their kind: texts, booleans, finite numbers by value (1 is 1.0); never across kinds. */
    static boolean same(JsonNode a, JsonNode b) {
        if (a.isTextual() && b.isTextual()) {
            return a.asText().equals(b.asText());
        }
        if (a.isBoolean() && b.isBoolean()) {
            return a.asBoolean() == b.asBoolean();
        }
        if (number(a) && number(b)) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return false;
    }

    private static boolean number(JsonNode v) {
        return v.isNumber() && Double.isFinite(v.asDouble());
    }

    /** Why {@code v} is not of the contract type {@code t}; null when it is (an unknown or a plain list type takes anything). */
    static String typeError(String t, JsonNode v) {
        return switch (t) {
            case "text", "id" -> v.isTextual() ? null : "not text";
            case "amount", "rate" -> number(v) ? null : "not a finite number";
            case "record" -> v.isObject() ? null : "not a record";
            case "record list" -> v.isArray() ? null : "not a list of records";
            case "text list" -> v.isArray() && all(v, JsonNode::isTextual) ? null : "not a list of text";
            case "integer" -> v.isIntegralNumber() ? null : "not an integer";
            case "date" -> v.isTextual() && DATE.matcher(v.asText()).matches() ? null : "not a date (YYYY-MM-DD)";
            case "timestamp" -> v.isTextual() && TIMESTAMP.matcher(v.asText()).matches() ? null : "not an ISO-8601 timestamp";
            case "boolean" -> v.isBoolean() ? null : "not true or false";
            case "vector" -> vector(v);
            case "id list" -> v.isArray() && all(v, x -> x.isTextual() && !x.asText().isEmpty()) ? null : "not a list of ids (text)";
            case "date list" -> v.isArray() && all(v, x -> x.isTextual() && DATE.matcher(x.asText()).matches()) ? null
                    : "not a list of dates (YYYY-MM-DD)";
            default -> null;
        };
    }

    private static String vector(JsonNode v) {
        if (!v.isArray() || v.isEmpty()) {
            return "not a list of numbers";
        }
        int bad = 0;
        int first = -1;
        for (int i = 0; i < v.size(); i++) {
            if (!number(v.get(i))) {
                bad++;
                first = first < 0 ? i : first;
            }
        }
        return bad == 0 ? null : bad + " value(s) not finite numbers (first at index " + first + "); never filled with zero";
    }

    private static boolean all(JsonNode a, java.util.function.Predicate<JsonNode> p) {
        for (JsonNode x : a) {
            if (!p.test(x)) {
                return false;
            }
        }
        return true;
    }
}
