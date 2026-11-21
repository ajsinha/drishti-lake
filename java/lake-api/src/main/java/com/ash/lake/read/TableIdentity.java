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

/**
 * What a table is. {@code kind}: the format's own id when the table carries one ({@code delta}, {@code iceberg}, {@code files}), else
 * {@code location}; {@code id}: that id, or the host and path; {@code location}: where the table is.
 */
public record TableIdentity(String kind, String id, String location) {

    /** {@code delta:<id>}, {@code iceberg:<uuid>}, {@code files:<id>}, {@code location:<host>:<path>}. */
    public String text() {
        return kind + ":" + id;
    }
}
