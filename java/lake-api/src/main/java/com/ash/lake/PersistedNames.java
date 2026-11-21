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
package com.ash.lake;

import java.util.Map;

/**
 * The names the product writes into a table's own metadata (Iceberg snapshot summaries and table properties): {@code lake.<name>}, the
 * only ones it reads. A table written before Drishti Lake carries its host's prefix instead; such a table is refused with the way out
 * (rebuild the lake: load its tables again), never read by the older name and never rewritten (no migration code).
 */
public final class PersistedNames {

    /** The prefix the product writes and reads. */
    public static final String PREFIX = "lake.";
    /** The prefix of tables written before Drishti Lake: only recognised, to refuse them. */
    static final String EARLIER = "drishti" + ".";

    private PersistedNames() {
    }

    /** The key written for {@code name} ({@code business-dates} is {@code lake.business-dates}). */
    public static String key(String name) {
        return PREFIX + name;
    }

    /**
     * {@code name}'s value in {@code m} ({@code lake.<name>}); null when it is not there.
     *
     * @throws IllegalStateException when {@code m} names it only under the prefix of a table written before Drishti Lake
     */
    public static String get(Map<String, String> m, String name) {
        if (m == null) {
            return null;
        }
        String v = m.get(PREFIX + name);
        if (v == null && m.containsKey(EARLIER + name)) {
            throw new IllegalStateException("the table was written before Drishti Lake: its metadata names " + EARLIER + name + ", Drishti "
                    + "Lake reads " + PREFIX + name + " only; rebuild the lake (load its tables again into a new lake folder)");
        }
        return v;
    }

    /** As {@link #get}, {@code dflt} when the name is not there. */
    public static String get(Map<String, String> m, String name, String dflt) {
        String v = get(m, name);
        return v != null ? v : dflt;
    }

    /** Whether {@code setting} (a lake file's reference, {@code drishti.data.dir}) has the host's prefix: refused, with the product's name. */
    public static boolean earlier(String setting) {
        return setting != null && setting.startsWith(EARLIER);
    }

    /** The product's name of a setting with the host's prefix ({@code drishti.data.dir}: {@code lake.data.dir}). */
    public static String product(String setting) {
        return PREFIX + setting.substring(EARLIER.length());
    }
}
