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
package com.ash.lake.io.read;

import com.ash.lake.read.DataFile;
import com.ash.lake.read.TableIdentity;
import com.ash.lake.spi.AsOf;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a store gives the shape rule for a foreign table (READ_API.md, RA-S8): its versions, and per version its columns, its files with their
 * partition days and bounds, and a read of some top-level columns of one file with its deletes applied. Everything else (dates, ids,
 * documents, columns, vectors) is {@link ShapedSnapshot}'s, written once.
 */
public interface ForeignSource {

    /** The table at {@code asOf}; empty: there is no table (yet, or then). */
    Optional<Version> version(AsOf asOf) throws IOException;

    /** What the table is, the same after a reopen. */
    TableIdentity identity() throws IOException;

    /** One version of the table. */
    interface Version {

        long version();

        Instant committed();

        long sequence();

        /** The top-level columns. */
        List<ShapeField> columns();

        /** The columns the table names as its row identity (Iceberg's identifier fields); empty elsewhere. */
        default List<String> identifierColumns() {
            return List.of();
        }

        /** The top-level columns the table is partitioned by day (identity of a date, a timestamp's day), in the partitioning's order. */
        default List<String> dayPartitions() {
            return List.of();
        }

        /** Whether the files' names carry their dates (a folder of JSON Lines: {@code <table>.<YYYY-MM-DD>.jsonl}). */
        default boolean fileNameDates() {
            return false;
        }

        /** Every data file of the version (live rows only after deletes). */
        List<File> files() throws IOException;

        /**
         * The rows of {@code file}: the top-level columns at {@code columns} (indexes into {@link #columns}), in that order, deletes applied.
         * {@code equal} (column index to value) may be used to skip parts of the file that cannot hold such rows; rows that do not match
         * may still be given.
         */
        void read(File file, int[] columns, Map<Integer, Object> equal, Rows sink) throws IOException;
    }

    /** One row's values (the array may be reused for the next row). */
    @FunctionalInterface
    interface Rows {
        void row(Object[] values);
    }

    /**
     * A data file.
     *
     * @param file   as the read API lists it
     * @param days   a partition column's day (days from the epoch) by column; {@code ""}: the day the file's name carries
     * @param bounds per top-level column its lower and upper value where stated (as {@link ShapeField}'s forms)
     * @param handle the store's own object for the file
     */
    record File(DataFile file, Map<String, Integer> days, Map<String, Object[]> bounds, Object handle) {
        public File {
            days = Map.copyOf(days);
            bounds = Map.copyOf(bounds);
        }
    }
}
