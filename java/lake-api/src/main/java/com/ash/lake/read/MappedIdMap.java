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

/** An id map read from a file mapped into memory ({@link IdMaps}): the heap holds its header only. */
public interface MappedIdMap extends IdMap, AutoCloseable {

    /** The file's size. */
    long fileBytes();

    /** Whether {@link #close} ran (a closed map answers nothing). */
    boolean closed();

    @Override
    void close();
}
