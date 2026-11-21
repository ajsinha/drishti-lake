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

import java.time.LocalDate;
import java.util.List;

/**
 * One business date of a snapshot. {@code rows}: the sum of the files' stated rows; {@code bytesUncounted}, {@code filesUncounted}: the
 * files without a row count; {@code stamp}: changes when and only when the date's files change (a cache key: the SHA-256 of the date's
 * {@code path:size} lines, sorted, first 16 hex digits, on every store).
 */
public record DayFiles(LocalDate date, List<DataFile> files, long rows, long bytes, long bytesUncounted, int filesUncounted,
        boolean deletes, String stamp) {

    public DayFiles {
        files = List.copyOf(files);
    }

    /** The day of {@code files}: counts summed, the stamp computed. */
    public static DayFiles of(LocalDate date, List<DataFile> files) {
        long rows = 0, bytes = 0, uncounted = 0;
        int filesUncounted = 0;
        boolean deletes = false;
        for (DataFile f : files) {
            bytes += f.size();
            deletes |= f.deletes();
            if (f.rows() < 0) {
                uncounted += f.size();
                filesUncounted++;
            } else {
                rows += f.rows();
            }
        }
        return new DayFiles(date, files, rows, bytes, uncounted, filesUncounted, deletes, stamp(files));
    }

    /** The stamp of a date's files (the class's rule). */
    public static String stamp(List<DataFile> files) {
        List<String> lines = new java.util.ArrayList<>(files.size());
        for (DataFile f : files) {
            lines.add(f.path() + ":" + f.size());
        }
        java.util.Collections.sort(lines);
        java.security.MessageDigest md = com.ash.tools.codec.Hashes.sha256();
        for (String l : lines) {
            com.ash.tools.codec.Hashes.update(md, l + "\n");
        }
        return com.ash.tools.codec.Hashes.hex(md).substring(0, 16);
    }
}
