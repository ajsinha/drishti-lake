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
 * The one rule of which load is the newer, for every store's "the newest load wins": the later commit (a table version, a data sequence
 * number, the place in a files log), whatever the loaders' clocks said; between rows of one commit, a file marked as holding older copies
 * ({@link #OLDER_TAG}: what a rewrite of a date by compaction, an undo or row retention writes besides the rows readers see) before the
 * others, and between two such files of one commit no order here (the caller's: the file, then the row, as the rewrite wrote them in
 * order); else the run's time, then the whole id. A load id is {@code <run>.<file>} with the run {@code yyyyMMdd'T'HHmmss.SSS-<pid>[-<n>]}
 * (or the run the caller gave, {@code --run-id}); its time is the text before the first {@code -}, a name, never an order across commits.
 * A value in the data never decides between two commits.
 */
public final class LoadOrder {

    /**
     * The mark of a data file that holds only older copies of its date's ids (a rewrite writes the rows readers see into files of their
     * own and every older copy, by id and in the readers' order, into files marked so): on Delta the add action's tag of this name with
     * the value {@link #OLDER_VALUE}; on Iceberg the file's name holding {@link #OLDER_NAME} (the files store orders each line by its place in the day).
     */
    public static final String OLDER_TAG = com.ash.lake.PersistedNames.key("copies");
    /** The value of {@link #OLDER_TAG} on a file of older copies. */
    public static final String OLDER_VALUE = "older";
    /** What the name of a file of older copies holds (Iceberg, where a data file has no tags). */
    public static final String OLDER_NAME = "-older-";

    private LoadOrder() {
    }

    /** Whether the data file at {@code path} is a file of older copies by its name ({@link #OLDER_NAME}). */
    public static boolean olderByName(String path) {
        if (path == null) {
            return false;
        }
        return path.substring(path.lastIndexOf('/') + 1).contains(OLDER_NAME);
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
     * Whether {@code loadIds} hold two different runs: only then is the commit order needed (a store reads it for those files alone;
     * otherwise every commit order may be given as 0).
     */
    public static boolean needsCommits(Iterable<String> loadIds) {
        String first = null;
        for (String id : loadIds) {
            String r = run(id);
            if (first == null) {
                first = r;
            } else if (!first.equals(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares two loads' copies: by commit order ({@code commitA}/{@code commitB}: a table version or sequence number; 0 for both when
     * {@link #needsCommits} is false), then by the run's time, then by the whole id. Negative when {@code a} is the older.
     */
    public static int compare(String a, long commitA, String b, long commitB) {
        return compare(a, commitA, false, b, commitB, false);
    }

    /**
     * As {@link #compare(String, long, String, long)}, with each copy's file marked or not as a file of older copies ({@link #OLDER_TAG}):
     * in one commit a marked file's copy is the older; two marked files' copies of one commit compare equal (the caller orders them by
     * file, then row).
     */
    public static int compare(String a, long commitA, boolean olderA, String b, long commitB, boolean olderB) {
        int c = Long.compare(commitA, commitB);
        if (c != 0) {
            return c;
        }
        if (olderA != olderB) {
            return olderA ? -1 : 1;
        }
        if (olderA) {
            return 0;
        }
        c = time(a).compareTo(time(b));
        return c != 0 ? c : (a == null ? "" : a).compareTo(b == null ? "" : b);
    }
}
