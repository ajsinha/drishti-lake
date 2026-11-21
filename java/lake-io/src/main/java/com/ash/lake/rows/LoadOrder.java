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
package com.ash.lake.rows;

/**
 * The one rule of which load is the newer, for every store's "the newest load wins": a load id is {@code <run>.<file>} with the run
 * {@code yyyyMMdd'T'HHmmss.SSS-<pid>[-<n>]} (or the run the caller gave, {@code --run-id}). Two loads are ordered by the run's time
 * (the text before the first {@code -}); loads that started in the same millisecond, by their commit order (the process id and run
 * number are not an order: {@code -10} sorts before {@code -9}); then by the whole id (one commit's files).
 */
public final class LoadOrder {

    private LoadOrder() {
    }

    /** The time part of a load id: the text before its first {@code -} (the whole id when it has none); "" for null. */
    public static String time(String loadId) {
        if (loadId == null) {
            return "";
        }
        int dash = loadId.indexOf('-');
        return dash < 0 ? loadId : loadId.substring(0, dash);
    }

    /** The run of a load id: up to the first {@code .} after the time part (its files share it); "" for null. */
    public static String run(String loadId) {
        if (loadId == null) {
            return "";
        }
        int dash = loadId.indexOf('-');
        int dot = dash < 0 ? -1 : loadId.indexOf('.', dash);
        return dot < 0 ? loadId : loadId.substring(0, dot);
    }

    /**
     * Whether two different runs of {@code loadIds} started in the same millisecond: only then is the commit order needed (a store reads
     * it for those files alone; otherwise every commit order may be given as 0).
     */
    public static boolean needsCommits(Iterable<String> loadIds) {
        java.util.Map<String, String> runOf = new java.util.HashMap<>();
        for (String id : loadIds) {
            String r = runOf.putIfAbsent(time(id), run(id));
            if (r != null && !r.equals(run(id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares two loads' copies: by time, then by commit order ({@code commitA}/{@code commitB}: a table version or sequence number;
     * 0 for both when {@link #needsCommits} is false), then by the whole id. Negative when {@code a} is the older.
     */
    public static int compare(String a, long commitA, String b, long commitB) {
        int c = time(a).compareTo(time(b));
        if (c == 0) {
            c = Long.compare(commitA, commitB);
        }
        return c != 0 ? c : (a == null ? "" : a).compareTo(b == null ? "" : b);
    }
}
