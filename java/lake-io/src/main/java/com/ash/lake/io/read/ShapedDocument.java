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
package com.ash.lake.io.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A foreign row's values as JSON, one rule for every store (docs/DESIGN.md, shaped reads): structs as objects, lists as arrays, a map with text keys
 * as an object (else an array of {@code {key, value}}), dates and times ISO ({@code 2026-09-30T18:30:00}, a fraction only when there is
 * one; a timestamp with a zone ends in {@code Z}, UTC), decimals as numbers or text, binary as base64 or left out, a null as JSON null.
 */
public final class ShapedDocument {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Decimals as text; binary left out. */
    public record Options(boolean decimalsAsText, boolean omitBinary) {}

    private ShapedDocument() {}

    /** One value of {@code f}; null when it is left out (binary, {@code omitBinary}). */
    public static JsonNode value(ShapeField f, Object v, Options o) {
        if (v == null) {
            return F.nullNode();
        }
        switch (f.kind()) {
            case STRUCT -> {
                ObjectNode n = F.objectNode();
                if (v instanceof Object[] a) {
                    for (int i = 0; i < f.children().size(); i++) {
                        put(n, f.children().get(i).name(), value(f.children().get(i), i < a.length ? a[i] : null, o));
                    }
                    return n;
                }
                if (v instanceof Map<?, ?> m) {
                    m.forEach((k, x) -> put(n, String.valueOf(k), value(child(f, String.valueOf(k), x), x, o)));
                    return n;
                }
                return plain(v, o);
            }
            case LIST -> {
                if (!(v instanceof List<?> l)) {
                    return plain(v, o);
                }
                ArrayNode n = F.arrayNode();
                ShapeField e = f.children().isEmpty() ? null : f.children().get(0);
                for (Object x : l) {
                    JsonNode j = e == null ? plain(x, o) : value(e, x, o);
                    n.add(j == null ? F.nullNode() : j);
                }
                return n;
            }
            case MAP -> {
                if (!(v instanceof Map<?, ?> m) || f.children().size() < 2) {
                    return plain(v, o);
                }
                ShapeField key = f.children().get(0);
                ShapeField val = f.children().get(1);
                if (key.kind() == ShapeField.Kind.STRING) {
                    ObjectNode n = F.objectNode();
                    m.forEach((k, x) -> {
                        JsonNode j = value(val, x, o);
                        n.set(String.valueOf(k), j == null ? F.nullNode() : j);
                    });
                    return n;
                }
                ArrayNode n = F.arrayNode();
                m.forEach((k, x) -> {
                    ObjectNode e = n.addObject();
                    e.set("key", value(key, k, o));
                    JsonNode j = value(val, x, o);
                    e.set("value", j == null ? F.nullNode() : j);
                });
                return n;
            }
            default -> {
                return primitive(f, v, o);
            }
        }
    }

    private static void put(ObjectNode n, String name, JsonNode v) {
        if (v != null) {
            n.set(name, v);
        }
    }

    private static ShapeField child(ShapeField f, String name, Object v) {
        for (ShapeField c : f.children()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return new ShapeField(name, v instanceof Map ? ShapeField.Kind.STRUCT : v instanceof List ? ShapeField.Kind.LIST : ShapeField.Kind.STRING, 0,
                List.of());
    }

    private static JsonNode primitive(ShapeField f, Object v, Options o) {
        return switch (f.kind()) {
            case DATE -> v instanceof Number n ? F.textNode(LocalDate.ofEpochDay(n.longValue()).toString()) : plain(v, o);
            case TIME -> v instanceof Number n ? F.textNode(time(LocalTime.ofNanoOfDay(n.longValue() * 1000))) : plain(v, o);
            case TIMESTAMP -> v instanceof Long n ? F.textNode(timestamp(n)) : plain(v, o);
            case TIMESTAMPTZ -> v instanceof Long n ? F.textNode(timestamp(n) + "Z") : plain(v, o);
            case INT -> v instanceof Number n ? F.numberNode(n.intValue()) : plain(v, o);
            case FLOAT -> v instanceof Number n ? F.numberNode(n.floatValue()) : plain(v, o);
            default -> plain(v, o);
        };
    }

    /** A value by its own type. */
    private static JsonNode plain(Object v, Options o) {
        return switch (v) {
            case null -> F.nullNode();
            case Boolean b -> F.booleanNode(b);
            case Integer i -> F.numberNode(i);
            case Long l -> F.numberNode(l);
            case Float x -> F.numberNode(x);
            case Double d -> F.numberNode(d);
            case BigDecimal d -> o.decimalsAsText() ? F.textNode(d.toPlainString()) : F.numberNode(d);
            case java.math.BigInteger b -> F.numberNode(b);
            case byte[] b -> o.omitBinary() ? null : F.textNode(Base64.getEncoder().encodeToString(b));
            case java.nio.ByteBuffer b -> {
                byte[] a = new byte[b.remaining()];
                b.duplicate().get(a);
                yield o.omitBinary() ? null : F.textNode(Base64.getEncoder().encodeToString(a));
            }
            case Map<?, ?> m -> {
                ObjectNode n = F.objectNode();
                m.forEach((k, x) -> put(n, String.valueOf(k), plain(x, o)));
                yield n;
            }
            case List<?> l -> {
                ArrayNode n = F.arrayNode();
                l.forEach(x -> {
                    JsonNode j = plain(x, o);
                    n.add(j == null ? F.nullNode() : j);
                });
                yield n;
            }
            case JsonNode j -> j;
            default -> F.textNode(String.valueOf(v));
        };
    }

    /** Microseconds from the epoch as {@code 2026-09-30T18:30:00} (UTC), with {@code .ffffff} when there is a fraction. */
    public static String timestamp(long micros) {
        LocalDateTime t = LocalDateTime.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), (int) Math.floorMod(micros, 1_000_000L) * 1000,
                ZoneOffset.UTC);
        return t.toLocalDate() + "T" + time(t.toLocalTime());
    }

    private static String time(LocalTime t) {
        String s = String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
        int micros = t.getNano() / 1000;
        return micros == 0 ? s : s + String.format(".%06d", micros);
    }

    /** An id part as text: strings as they are, decimals plainly, others as their JSON text. */
    public static String text(ShapeField f, Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        JsonNode n = value(f, v, new Options(true, false));
        return n == null ? "" : n.isTextual() ? n.asText() : n.toString();
    }
}
