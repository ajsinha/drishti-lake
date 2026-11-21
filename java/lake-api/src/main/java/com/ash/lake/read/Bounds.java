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

/** A column's statistics in one file, as text; a null part: not stated (a value the writer may have cut is not stated). */
public record Bounds(String min, String max, Long nulls) {

    /** Whether the file may hold a value in {@code [low, high]} (text order; null ends are open) or a null when {@code nulls} asked. */
    public boolean mayHold(String low, String high, boolean orNull) {
        if (orNull && (nulls == null || nulls > 0)) {
            return true;
        }
        if (min == null || max == null) {
            return true;
        }
        return (high == null || min.compareTo(high) <= 0) && (low == null || max.compareTo(low) >= 0);
    }
}
