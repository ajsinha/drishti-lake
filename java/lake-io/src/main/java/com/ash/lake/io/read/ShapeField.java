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

import java.util.List;

/**
 * A foreign table's field as the shape rule sees it, the same for every store (docs/DESIGN.md, shaped reads). Values arrive in one form:
 * {@code BOOLEAN} Boolean, {@code INT} Integer, {@code LONG} Long, {@code FLOAT} Float, {@code DOUBLE} Double, {@code DECIMAL} BigDecimal,
 * {@code DATE} Integer (days from the epoch), {@code TIME} Long (micros of the day), {@code TIMESTAMP} and {@code TIMESTAMPTZ} Long (micros
 * from the epoch, UTC), {@code STRING} String, {@code UUID} java.util.UUID, {@code BINARY} byte[], {@code STRUCT} Object[] (by
 * {@code children}) or a Map of name to value (a JSON object), {@code LIST} java.util.List, {@code MAP} java.util.Map. A value of another
 * form than its kind (a JSON line's odd value) is rendered by its own type.
 *
 * @param name     the field's name
 * @param kind     its kind
 * @param scale    a decimal's scale
 * @param children a struct's fields; a list's element; a map's key and value
 */
public record ShapeField(String name, Kind kind, int scale, List<ShapeField> children) {

    /** The kinds of value. */
    public enum Kind { BOOLEAN, INT, LONG, FLOAT, DOUBLE, DECIMAL, DATE, TIME, TIMESTAMP, TIMESTAMPTZ, STRING, UUID, BINARY, STRUCT, LIST, MAP }

    public ShapeField {
        children = List.copyOf(children);
    }

    /** A field of a primitive kind. */
    public static ShapeField of(String name, Kind kind) {
        return new ShapeField(name, kind, 0, List.of());
    }

    /** Whether values of it are numbers. */
    public boolean numeric() {
        return switch (kind) {
            case INT, LONG, FLOAT, DOUBLE, DECIMAL -> true;
            default -> false;
        };
    }

    /** Whether it is a timestamp. */
    public boolean timestamp() {
        return kind == Kind.TIMESTAMP || kind == Kind.TIMESTAMPTZ;
    }

    /** Whether it is a list of numbers (a vector). */
    public boolean vector() {
        return kind == Kind.LIST && !children.isEmpty() && children.get(0).numeric();
    }
}
