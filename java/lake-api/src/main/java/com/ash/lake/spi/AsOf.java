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

import java.time.Instant;
import java.util.Objects;

/**
 * Which version of a table a scan reads (time travel): the newest ({@link #LATEST}), a version the store names ({@link #version}: a Delta
 * table's version, an Iceberg snapshot's id, a files table's commit number; {@link TableStore#versions} lists them) or the newest version
 * committed at or before a time ({@link #time}). A version the table no longer holds, or a time before its first commit, is an
 * {@link java.io.IOException} naming the table.
 */
public record AsOf(Long version, Instant time) {

    /** The table as it is now. */
    public static final AsOf LATEST = new AsOf(null, null);

    public AsOf {
        if (version != null && time != null) {
            throw new IllegalArgumentException("a version or a time, not both");
        }
    }

    public static AsOf version(long version) {
        return new AsOf(version, null);
    }

    public static AsOf time(Instant time) {
        return new AsOf(null, Objects.requireNonNull(time, "time"));
    }

    /** Whether this is the table as it is now. */
    public boolean latest() {
        return version == null && time == null;
    }

    /** The newest of {@code versions} (oldest first) committed at or before {@link #time()}; null when every one is later. */
    public TableVersion resolve(java.util.List<TableVersion> versions) {
        TableVersion out = null;
        for (TableVersion v : versions) {
            if (version != null ? v.version() == version : !v.committed().isAfter(time)) {
                out = v;
            }
        }
        return out;
    }

    /** The store-neutral refusal: no such version, or none at or before the time. */
    public String missing(String table) {
        return version != null ? table + ": no version " + version + " (the table's versions are listed by TableStore.versions)"
                : table + ": no version committed at or before " + time;
    }
}
