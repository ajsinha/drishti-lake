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

/** A column's type as the product names it, whatever the table format's own type system calls it. */
public enum ColumnType {
    STRING, DOUBLE, LONG, INT, BOOLEAN, DATE, TIMESTAMP, BINARY,
    /** {@code list<string>}: a list attribute. */
    STRING_LIST,
    /** {@code list<double>}: a vector. */
    DOUBLE_LIST,
    /** Anything else the format holds (a nested structure): read as its text. */
    OTHER
}
