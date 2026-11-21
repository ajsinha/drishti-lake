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

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Where a load's rows go: the loader's target in one table, given by {@link TableStore#writer}. Files are written by many threads at
 * once ({@link #newFile}); {@link #commit} makes one date's files that date's data in ONE commit that also takes the date's earlier rows
 * out (a replace), so a reader sees the old date or the new one, never a mix, and a load that stops before its commit leaves the table as
 * it was. The loader calls {@link #open} once, then per date {@link #appended}, {@link #begin}, files, {@link #commit}; then
 * {@link #close}.
 */
public interface TableWriter extends AutoCloseable {

    /**
     * The columns a load has: per promoted column whether every value was a number, whether any value was seen, and whether every value
     * was a list of simple values (a {@code list<string>} column).
     */
    record LoadColumns(List<String> promoted, boolean[] numeric, boolean[] seen, boolean[] list) {}

    /** Creates the table or checks it, and adds the columns it lacks or reports them left out ({@link #leftOut}). */
    void open(LoadColumns columns) throws IOException;

    /** Starts a business date. */
    void begin(LocalDate date) throws IOException;

    /** A new data file for rows of {@code date} with the given partition values. */
    FileOut newFile(LocalDate date, String[] partitionValues, String name) throws IOException;

    /** Commits the date in one commit (replace or append, {@link WriteSpec#appends()}); returns the table's version or snapshot id. */
    long commit(LocalDate date, List<WrittenFile> files) throws IOException;

    /** Whether an append of this load's batch was committed for {@code date} already: the loader then skips the date. */
    boolean appended(LocalDate date) throws IOException;

    /** The table's columns as written ({@code name: string|double|date|vector|...}). */
    Map<String, String> columnTypes();

    /** Columns of the load the existing table does not have (they are not written). */
    List<String> leftOut();

    @Override
    void close();

    /** A file being written by one thread. */
    interface FileOut {
        void write(LoadRow row) throws IOException;

        /** The bytes written so far and buffered (to cut files by size). */
        long length();

        /** Finishes the file. */
        WrittenFile finish() throws IOException;
    }

    /**
     * A finished data file: where, its rows and bytes ({@code -1}: not known before the commit), the writer's own description of it
     * (opaque to the loader), its partition values and its first and last sort keys.
     */
    record WrittenFile(String path, long rows, long bytes, Object handle, String[] partition, List<Object> first, List<Object> last) {}
}
