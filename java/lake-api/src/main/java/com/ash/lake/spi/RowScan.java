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

import java.util.Iterator;
import java.util.List;

/** The rows of a scan (a store may read them ahead), closed when done. */
public interface RowScan extends Iterator<Row>, AutoCloseable {

    /** The scan's columns. */
    Columns columns();

    @Override
    void close();

    /** What the scan read and skipped; {@link ScanStats#UNKNOWN} when the store does not count. */
    default ScanStats stats() {
        return ScanStats.UNKNOWN;
    }

    /** A scan over rows already read. */
    static RowScan of(Columns columns, List<Row> rows) {
        return of(columns, rows, ScanStats.UNKNOWN);
    }

    /** A scan over rows already read, with what reading them read and skipped. */
    static RowScan of(Columns columns, List<Row> rows, ScanStats stats) {
        Iterator<Row> it = rows.iterator();
        return new RowScan() {
            @Override
            public ScanStats stats() {
                return stats;
            }

            @Override
            public Columns columns() {
                return columns;
            }

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Row next() {
                return it.next();
            }

            @Override
            public void close() {
            }
        };
    }
}
