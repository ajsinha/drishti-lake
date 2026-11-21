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

/**
 * What a {@link TableStore} can do. A store declares what it cannot do with the reason ({@link Capabilities}); the parity kit runs the
 * cases of a missing capability as refusals, never as skips, so a difference is either declared or a failure.
 */
public enum Capability {
    /** Load whole business dates, each replacing that date's earlier rows in one commit. */
    LOAD_REPLACE,
    /** Load by appending, a named batch committed once per date. */
    LOAD_APPEND,
    /** Read rows ({@link TableStore#scan}). */
    SCAN,
    /** The table's columns and types ({@link TableStore#columns}). */
    COLUMNS,
    /** The business dates held and their rows ({@link TableStore#dates}). */
    DATES,
    /** Remove whole business dates ({@link TableStore#deleteDates}). */
    DELETE_DATES,
    /** Undo one load by its load id ({@link TableStore#undo}). */
    UNDO,
    /** Read the table as it was at an earlier commit. */
    TIME_TRAVEL,
    /** A reader never sees a date half written: one commit per date, all or nothing. */
    ATOMIC_COMMIT,
    /** Columns a later load brings are added to the table. */
    SCHEMA_EVOLUTION,
    /** Vector columns ({@code list<double>}) and list columns ({@code list<string>}) as typed columns. */
    TYPED_LIST_COLUMNS,
    /** Promoted columns as typed columns beside the document. */
    PROMOTED_COLUMNS
}
