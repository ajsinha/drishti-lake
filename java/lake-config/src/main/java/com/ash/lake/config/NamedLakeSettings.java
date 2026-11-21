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

import com.ash.tools.settings.Placeholders;
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
 * through one runtime's settings ({@link com.ash.lake.LakeRuntime#settings()}, {@link com.ash.lake.LakeRuntime#settingNames()}), or
 * through a lookup alone (then only the keys every lake has: {@code root}, {@code format}, {@code lake}). The product's own options ({@link com.ash.lake.LakeOptions}, {@code lake.home} and the like) are not a
 * lake's.
 */
public final class NamedLakeSettings {

    /** The keys read in their environment form too, whether or not a source lists them. */
    static final List<String> KNOWN = List.of("root", "format", "lake");

    private final Function<String, String> lookup;
    private final Supplier<Collection<String>> names;

    private NamedLakeSettings(Function<String, String> lookup, Supplier<Collection<String>> names) {
        this.lookup = lookup;
        this.names = names;
    }

    /** The named lake settings of {@code runtime}'s settings. */
    public static NamedLakeSettings of(com.ash.lake.LakeRuntime runtime) {
        return new NamedLakeSettings(runtime.settings(), runtime::settingNames);
    }

    /** The named lake settings {@code lookup} gives (no list of names: the keys every lake has). */
    public static NamedLakeSettings over(Function<String, String> lookup) {
        return new NamedLakeSettings(lookup == null ? k -> null : lookup, List::of);
    }

    /** Every named lake setting, by its full key ({@code lake.risk.root}). */
    public Map<String, String> all() {
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
    public Map<String, String> section(String name) {
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
    String get(String key) {
        String v = Placeholders.lookup(key, lookup);
        return v == null ? null : Placeholders.resolve(v, lookup);
    }
}
