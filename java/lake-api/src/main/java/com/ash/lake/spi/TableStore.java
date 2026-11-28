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
package com.ash.lake.spi;

import com.ash.lake.LakeRuntime;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;

/**
 * A table format (design section 3.2): Delta, Iceberg, plain files, memory, or a third party's. It gives the loader a writer, reads a
 * table's columns, dates and rows, removes dates and undoes a load, and declares what it cannot do ({@link #capabilities()}). Every
 * signature is in the product's own types: no table library's type appears, so a library upgrade is never a caller's change.
 *
 * <p>A store is registered with the runtime that uses it ({@link LakeRuntime.Builder#store}); the operations take that runtime. An
 * operation the store's capabilities lack throws {@link UnsupportedOperationException} naming the reason.
 */
public interface TableStore {

    /** The format's name as a lake file and a load name it ({@code delta}, {@code iceberg}, {@code files}, {@code memory}). */
    String format();

    /** What the store cannot do, with the reasons. */
    Capabilities capabilities();

    /** Whether the table exists. */
    boolean exists(LakeRuntime runtime, TableRef table) throws IOException;

    /** The table's columns. */
    Columns columns(LakeRuntime runtime, TableRef table) throws IOException;

    /** The business dates the table holds, with their rows ({@code -1}: not stated without reading). */
    SortedMap<LocalDate, Long> dates(LakeRuntime runtime, TableRef table) throws IOException;

    /**
     * The table's versions a scan can read ({@link ScanRequest#asOf}), oldest first: the versions the store still holds (an absent table:
     * none).
     */
    List<TableVersion> versions(LakeRuntime runtime, TableRef table) throws IOException;

    /**
     * The rows the request names, of the version it names (an absent table: no rows; a version the table does not hold: an
     * {@link IOException} naming it).
     */
    RowScan scan(LakeRuntime runtime, TableRef table, ScanRequest request) throws IOException;

    /**
     * The tables of one domain of a lake, open for reading (docs/GUIDE.md, the read API): snapshots, days and files, ids, documents, promoted columns,
     * vectors, file scans, identity, health, problems and purges, in the product's types. The same calls answer the same on every store.
     */
    com.ash.lake.read.LakeReader open(LakeRuntime runtime, com.ash.lake.read.LakeAddress address) throws IOException;

    /**
     * The table's properties as its store holds them (Delta's configuration, Iceberg's table properties, the files store's
     * {@code properties.json}); empty for an absent table. {@link TableProperties#effective} reads the product's two from them.
     */
    default java.util.Map<String, String> properties(LakeRuntime runtime, TableRef table) throws IOException {
        return java.util.Map.of();
    }

    /** The loader's writer for one load. */
    TableWriter writer(WriteSpec spec) throws IOException;

    /** Removes whole business dates in one commit; a line per date removed. Nothing is changed on a dry run. */
    List<DateChange> deleteDates(LakeRuntime runtime, TableRef table, Set<LocalDate> dates, boolean dryRun) throws IOException;

    /** Undoes load {@code loadId}: a line per date it changed. Nothing is changed on a dry run. */
    List<DateChange> undo(LakeRuntime runtime, TableRef table, String loadId, boolean dryRun) throws IOException;
}
