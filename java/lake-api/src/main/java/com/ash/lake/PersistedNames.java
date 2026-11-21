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
 * The names the product writes into a table's own metadata (Iceberg snapshot summaries and table properties): {@code lake.<name>}.
 * Tables written before the split carry the host's prefix ({@code drishti.<name>}); a reader takes {@code lake.<name>} first, then the
 * older name, so those tables read as before and nothing is rewritten (step L7, read both, write the product's).
 */
public final class PersistedNames {

    /** The prefix the product writes. */
    public static final String PREFIX = "lake.";
    /** The prefix of tables written before the split, read only. */
    static final String EARLIER = "drishti" + ".";

    private PersistedNames() {
    }

    /** The key written for {@code name} ({@code business-dates} is {@code lake.business-dates}). */
    public static String key(String name) {
        return PREFIX + name;
    }

    /** {@code name}'s value in {@code m}: the product's key, else the earlier one; null when neither is there. */
    public static String get(Map<String, String> m, String name) {
        if (m == null) {
            return null;
        }
        String v = m.get(PREFIX + name);
        return v != null ? v : m.get(EARLIER + name);
    }

    /** As {@link #get}, {@code dflt} when neither key is there. */
    public static String get(Map<String, String> m, String name, String dflt) {
        String v = get(m, name);
        return v != null ? v : dflt;
    }

    /**
     * The other prefix's name of a dotted setting a lake file refers to ({@code drishti.data.dir} and {@code lake.data.dir}), so a lake
     * file of either age resolves where its host or the product names the value; null when {@code name} has neither prefix.
     */
    public static String alias(String name) {
        if (name.startsWith(PREFIX)) {
            return EARLIER + name.substring(PREFIX.length());
        }
        if (name.startsWith(EARLIER)) {
            return PREFIX + name.substring(EARLIER.length());
        }
        return null;
    }
}
