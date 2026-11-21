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

import java.util.List;

/**
 * One field of the documents of a table read through a {@link TableShape} (a table not in the product's layout): its name in the document,
 * its kind, its nested fields, and the roles the shape gave it.
 *
 * @param name     the name in the document (after the shape's renames)
 * @param kind     {@code boolean, int, long, float, double, decimal, date, time, timestamp, timestamptz, string, uuid, binary, struct, list}
 *                 or {@code map}
 * @param children a struct's fields; a list's element (one entry); a map's key and value; else empty
 * @param id       whether the field is (one of) the id
 * @param date     whether the field gives the business date (the shape's date column, or the table's day partition)
 */
public record DocumentField(String name, String kind, List<DocumentField> children, boolean id, boolean date) {

    public DocumentField {
        children = List.copyOf(children);
    }
}
