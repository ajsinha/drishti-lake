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
package com.ash.lake.config;

import com.ash.lake.Placeholders;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The named lake settings, {@code lake.<name>.<key>} (a {@code [lake.<name>]} section of a settings file, a {@code -D}, the environment
 * form {@code LAKE_<NAME>_ROOT}): a site moves one lake by name, its {@code root} and its {@code storage.<key>}, and no other. Read
 * from the process (system properties, which hold the settings files' entries, then the environment), or through the view a host
 * installs ({@link #useView}). The product's own options ({@link com.ash.lake.LakeOptions}, {@code lake.home} and the like) are not a
 * lake's.
 */
public final class NamedLakeSettings {

    /** The keys read in their environment form too, whether or not a source lists them. */
    static final List<String> KNOWN = List.of("root", "format", "lake");

    private static volatile Function<String, String> lookup;
    private static volatile Supplier<Collection<String>> names = NamedLakeSettings::processNames;

    private NamedLakeSettings() {
    }

    /**
     * Installs a host's view: {@code lookup} reads one key, {@code keys} lists every key its sources name. Null for either: the process's.
     */
    public static void useView(Function<String, String> lookup, Supplier<Collection<String>> keys) {
        NamedLakeSettings.lookup = lookup;
        NamedLakeSettings.names = keys == null ? NamedLakeSettings::processNames : keys;
    }

    /** Every named lake setting, by its full key ({@code lake.risk.root}). */
    public static Map<String, String> all() {
        Map<String, String> out = new TreeMap<>();
        for (String k : List.copyOf(names.get())) {
            if (k.startsWith("lake.") && !com.ash.lake.LakeOptions.isOption(k)) {
                String v = get(k);
                if (v != null) {
                    out.put(k, v);
                }
            }
        }
        return out;
    }

    /** One lake's settings, by key within its section ({@code root}, {@code storage.s3.region}). */
    public static Map<String, String> section(String name) {
        String prefix = "lake." + name + ".";
        Set<String> keys = new LinkedHashSet<>();
        for (String k : List.copyOf(names.get())) {
            if (k.startsWith(prefix) && k.length() > prefix.length() && !com.ash.lake.LakeOptions.isOption(k)) {
                keys.add(k.substring(prefix.length()));
            }
        }
        keys.addAll(KNOWN);
        Map<String, String> out = new TreeMap<>();
        for (String k : keys) {
            String v = get(prefix + k);
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** One key through the view, resolved; null when nothing names it. */
    static String get(String key) {
        String v = Placeholders.lookup(key, lookup);
        return v == null ? null : Placeholders.resolve(v, lookup);
    }

    private static Collection<String> processNames() {
        Set<String> out = new LinkedHashSet<>(System.getProperties().stringPropertyNames());
        out.addAll(System.getenv().keySet());
        return out;
    }
}
