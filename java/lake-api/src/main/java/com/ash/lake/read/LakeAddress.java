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

import com.ash.lake.LakeRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where a lake's tables of one domain are and how they are reached: what a connector's settings say, read once. {@code settings} are the
 * connector's settings as given (storage options, the S3 Tables catalog, {@code relocate}, ...: the store that opens the address checks
 * its own and refuses a setting it cannot use, with the {@code LK-} code of its message); {@code tableNames} are the {@code table.<name>}
 * settings (a table read from a folder or catalog entry of another name).
 */
public record LakeAddress(String root, String domain, Map<String, String> settings, Map<String, String> tableNames) {

    public LakeAddress {
        root = root == null ? "" : root.strip();
        domain = domain == null ? "" : domain.strip();
        if (domain.contains("..") || domain.startsWith("/") || domain.startsWith("\\")) {
            throw new IllegalArgumentException("domain escapes the lake's root: " + domain);
        }
        settings = Map.copyOf(settings);
        tableNames = Map.copyOf(tableNames);
        tableNames.forEach((k, v) -> {
            if (v.isBlank() || v.contains("/") || v.contains("\\") || v.contains("..")) {
                throw new IllegalArgumentException("table." + k + ": a table is a name under the domain, not a path: " + v);
            }
        });
    }

    /** From a connector's settings: {@code root}, {@code domain}, and {@code table.<name>} (or a {@code table} map flattened so). */
    public static LakeAddress of(LakeRuntime runtime, Map<String, String> settings) {
        Map<String, String> names = new TreeMap<>();
        settings.forEach((k, v) -> {
            if (k.startsWith("table.") && v != null) {
                names.put(k.substring(6), v.strip());
            }
        });
        String root = settings.getOrDefault("root", "");
        return new LakeAddress(root, settings.getOrDefault("domain", ""), new LinkedHashMap<>(settings), names);
    }

    /** The address of {@code domain} under {@code root} with no other setting. */
    public static LakeAddress of(String root, String domain) {
        return new LakeAddress(root, domain, Map.of("root", root, "domain", domain), Map.of());
    }
}
