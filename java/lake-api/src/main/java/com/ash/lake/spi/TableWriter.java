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
 * it was. The loader makes the writer when the load starts, before it reads its input: the table as it is then is the load's base. It
 * calls {@link #open} once, then per date {@link #appended}, {@link #begin}, files, {@link #commit}; then {@link #close}.
 *
 * <p>Replaces are optimistic, on every store: a replace of a date that another writer changed after this load's base (a concurrent
 * replace, an append, a delete of that date or of this load's slice of it) commits nothing and throws {@link LostReplace}; the other
 * writer's rows stand. Appends of one date by writers at once all land.
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

    /**
     * Commits the date in one commit (replace or append, {@link WriteSpec#appends()}); returns the table's version after it: a number that
     * grows with every commit of the table, on every store (Delta's version, the Iceberg snapshot's sequence number, the files store's log
     * number), {@code -1} when nothing was committed. {@link #nativeVersion} gives the store's own id of that commit.
     */
    long commit(LocalDate date, List<WrittenFile> files) throws IOException;

    /**
     * The store's own id of the commit {@link #commit} answered {@code version} for: what a read at a version takes ({@link TableVersion},
     * {@link AsOf}). Delta's and the files store's are the version itself; Iceberg's is the snapshot's id.
     */
    default long nativeVersion(long version) {
        return version;
    }

    /**
     * The partitions a replace of {@link WriteSpec#partitionsOnly} takes out of a date: the partition values (after the date) of the files
     * written for it, an empty value as null (as every store keeps it).
     */
    static java.util.Set<List<String>> partitionsOf(List<WrittenFile> files) {
        java.util.Set<List<String>> out = new java.util.HashSet<>();
        for (WrittenFile f : files) {
            List<String> k = new java.util.ArrayList<>(f.partition().length);
            for (String v : f.partition()) {
                k.add(v == null || v.isEmpty() ? null : v);
            }
            out.add(k);
        }
        return out;
    }

    /** Whether an append of this load's batch was committed for {@code date} already: the loader then skips the date. */
    boolean appended(LocalDate date) throws IOException;

    /** The table's columns as written ({@code name: string|double|date|vector|...}). */
    Map<String, String> columnTypes();

    /** Columns of the load the existing table does not have (they are not written). */
    List<String> leftOut();

    @Override
    void close();

    /**
     * A replace that lost to another writer: the date (this load's slice of it, on a shared table) changed after the load began, so
     * nothing of this load's date was committed and the rows that are there stand. The load fails; running it again replaces the date.
     */
    final class LostReplace extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public LostReplace(String table, java.time.LocalDate date) {
            super(table + ": " + date + " was replaced or changed by another load while this one ran; this load's " + date
                    + " was not committed (the other load's rows stand): run it again");
        }
    }

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
