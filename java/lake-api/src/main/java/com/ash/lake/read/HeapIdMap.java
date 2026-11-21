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

/** {@link IdMap#of}: the ids and files in the heap. */
record HeapIdMap(String[] idsInOrder, int[] files) implements IdMap {

    @Override
    public int size() {
        return idsInOrder.length;
    }

    @Override
    public String id(int i) {
        return idsInOrder[i];
    }

    @Override
    public int file(String id) {
        int i = java.util.Arrays.binarySearch(idsInOrder, id);
        return i < 0 ? -1 : files[i];
    }

    @Override
    public String[] ids() {
        return idsInOrder;
    }

    @Override
    public long bytes() {
        long b = 64L + 4L * files.length;
        for (String s : idsInOrder) {
            b += 48L + 2L * s.length();
        }
        return b;
    }
}
