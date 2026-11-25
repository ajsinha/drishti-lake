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
 * What a scan read and what it skipped (docs/GUIDE.md, the read API): files read and skipped by dates and statistics, rows read and rows the
 * request's {@code where} accepted. {@code -1}: not counted by the store.
 */
public record ScanStats(long filesRead, long filesSkipped, long rowsRead, long rowsReturned) {

    /** A scan that counted nothing. */
    public static final ScanStats UNKNOWN = new ScanStats(-1, -1, -1, -1);
}
