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

/** A date's ids, sorted, and the file each is in (an index into {@link DayFiles#files()}). */
public interface IdMap {

    int size();

    /** The i-th id in order. */
    String id(int i);

    /** The file index of {@code id}, or -1. */
    int file(String id);

    /** The heap it holds (a mapped map: its small header). */
    long bytes();

    /** Every id in order. */
    default String[] ids() {
        String[] out = new String[size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = id(i);
        }
        return out;
    }

    /** An id map over sorted ids and their files (the arrays are kept, not copied). */
    static IdMap of(String[] sortedIds, int[] files) {
        return new HeapIdMap(sortedIds, files);
    }
}
