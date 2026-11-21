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

import com.ash.lake.spi.Columns;
import com.ash.lake.spi.Expr;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;

/**
 * One version of a table, immutable: every read is its method, so reads across calls are of one version. A date's reads apply "the
 * newest load wins" and the table's deletes: an older load's copy of an id and a deleted row are never given.
 */
public interface TableSnapshot {

    /** Delta's version, Iceberg's snapshot id, the files store's commit number. */
    long version();

    /** When the version was committed. */
    Instant committed();

    /** Rises with every commit (Iceberg's sequence number; the version elsewhere). */
    long sequence();

    /** The table's columns by their logical names. */
    Columns columns();

    /** The id column's name. */
    String idColumn();

    /** Document path to column, for the paths the table keeps as columns (promoted). */
    Map<String, String> promoted();

    /** The vector columns. */
    Set<String> vectorColumns();

    /** Whether any date carries deleted rows (deletion vectors, delete files). */
    boolean deletes();

    /** The business dates. */
    NavigableSet<LocalDate> dates();

    /** One date's files and counts, from the log or manifests alone: no data file is read. */
    Optional<DayFiles> day(LocalDate date);

    /** The date's ids, sorted, each with its file. */
    IdMap ids(LocalDate date) throws IOException;

    /** One document by id in one file of the date ({@link IdMap#file}); vector markers left as stored ({@link Documents#withVectors}). */
    Optional<String> document(LocalDate date, int file, String id) throws IOException;

    /** Every document of the date, file by file. */
    void documents(LocalDate date, DocumentSink sink) throws IOException;

    /** Promoted columns of the date as arrays, without reading a document. */
    ColumnBlock columns(LocalDate date, List<ColumnAsk> asks) throws IOException;

    /** One vector column of the date with some text columns, row by row in file order. */
    void vectors(VectorRequest request, VectorSink sink) throws IOException;

    /** Some ids' vector columns from one file of the date in one read; an id the file does not hold is absent. */
    Map<String, Map<String, double[]>> vectorsOf(LocalDate date, int file, Set<String> ids, List<String> columns) throws IOException;

    /** The rows of one file of the date that {@code where} accepts, with {@code columns} (logical names), in file order. */
    void scanFile(LocalDate date, int file, List<String> columns, Expr where, RowSink sink) throws IOException;
}
