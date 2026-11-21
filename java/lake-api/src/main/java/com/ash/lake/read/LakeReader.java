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

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * One domain of one lake, open for reading ({@link com.ash.lake.spi.TableStore#open}): its tables, health, read problems and purges.
 * Thread-safe; one per connector.
 */
public interface LakeReader extends AutoCloseable {

    /** {@code delta}, {@code iceberg}, {@code files}. */
    String format();

    /** Every table of the domain now, by the host's names ({@link LakeAddress#tableNames}), sorted. */
    List<String> tables() throws IOException;

    /** The tables now that are not in {@code known} (tables loaded after the reader opened), sorted. */
    List<String> newTables(Set<String> known) throws IOException;

    /** A table; cheap: nothing is read until a snapshot is asked for. */
    TableReader table(String name);

    /** Whether the lake answers, where it is, the reading engine, notes (TLS expiry, proxy in force). */
    LakeHealth health(Instant now);

    /** The reads that failed and have not read well since. */
    ReadProblems problems();

    /** The newest versions are read again at the next {@link TableReader#snapshot}. */
    void refresh();

    /** Drops every cache the product holds for this lake (file footers, snapshots). */
    void purge();

    /** Drops what the product holds for one business date. */
    void purge(LocalDate date);

    @Override
    void close();
}
