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

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * A store's declared capabilities: every {@link Capability} it lacks, with the reason. The list only shrinks between releases (the parity
 * kit compares it with the stored one).
 */
public final class Capabilities {

    private final Map<Capability, String> missing;

    private Capabilities(Map<Capability, String> missing) {
        this.missing = Collections.unmodifiableMap(missing);
    }

    /** Every capability. */
    public static Capabilities all() {
        return new Capabilities(new EnumMap<>(Capability.class));
    }

    /** Every capability but {@code missing} (capability, reason, capability, reason, ...). */
    public static Capabilities allBut(Object... missing) {
        if (missing.length % 2 != 0) {
            throw new IllegalArgumentException("pairs of a capability and its reason");
        }
        Map<Capability, String> m = new EnumMap<>(Capability.class);
        for (int i = 0; i < missing.length; i += 2) {
            String reason = (String) missing[i + 1];
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("a missing capability states its reason: " + missing[i]);
            }
            m.put((Capability) missing[i], reason);
        }
        return new Capabilities(m);
    }

    public boolean supports(Capability c) {
        return !missing.containsKey(c);
    }

    /** The capabilities missing, with their reasons. */
    public Map<Capability, String> missing() {
        return missing;
    }

    /**
     * Refuses an operation the store cannot do.
     *
     * @throws UnsupportedOperationException naming the store, the capability and the reason
     */
    public void require(String format, Capability c) {
        if (!supports(c)) {
            throw new UnsupportedOperationException("the " + format + " store cannot " + c + ": " + missing.get(c));
        }
    }

    @Override
    public String toString() {
        return "missing " + missing;
    }
}
