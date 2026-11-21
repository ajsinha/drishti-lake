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

import com.ash.lake.spi.AsOf;
import java.io.IOException;
import java.util.Optional;

/** One table of a {@link LakeReader}. */
public interface TableReader {

    /** The host's name of the table. */
    String name();

    /** What the table is, the same on every replica and after a reopen. */
    TableIdentity identity() throws IOException;

    /**
     * The table at {@code asOf} ({@link AsOf#LATEST}, a version, a time); empty: no table then (none yet, or a time before its first
     * commit, on every store). A version the table does not hold is an {@link IOException}. Snapshots are kept per version.
     */
    Optional<TableSnapshot> snapshot(AsOf asOf) throws IOException;
}
