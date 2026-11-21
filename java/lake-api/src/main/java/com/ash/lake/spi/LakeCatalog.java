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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What a host adds to the product's lake configuration (design section 3.2, LK-5): the lake files its code ships, the packs and
 * connector templates it knows, the lake writers its domains name, the extensions (a {@code ReadyRule}) its loaded modules bring, and
 * the settings a loader's child process needs to see the host as this process does. The product's own lake-file model, its lake
 * folder and its named {@code [lake.<name>]} settings need no catalog. With no host, {@link #NONE}: nothing shipped, no templates, and a
 * pack folder is read as it lies (its packs and its domain folders' packs).
 *
 * <p>A host registers its catalog with the {@link com.ash.lake.LakeRuntime} it builds; there is no catalog in force for the whole JVM.
 */
public interface LakeCatalog {

    /** No host: the defaults below. */
    LakeCatalog NONE = new LakeCatalog() { };

    /** The lake files the host's code ships, by name. */
    default List<String> shippedLakes() {
        return List.of();
    }

    /** One shipped lake file's text, or null when the host ships none of that name. */
    default InputStream shippedLake(String name) {
        return null;
    }

    /**
     * The {@code pack.yaml} files of the packs under a folder, in name order: the folder's packs and those of its domain folders one level
     * down; a pack name two folders hold is in neither. A host may answer from its own resolution (its site folder's packs).
     */
    default List<Path> packFiles(Path root) {
        Map<String, List<Path>> holders = new TreeMap<>();
        List<Path> roots = new ArrayList<>(List.of(root));
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).filter(d -> !Files.isRegularFile(d.resolve("pack.yaml"))).sorted().forEach(roots::add);
        } catch (IOException e) {
            return List.of();                                       // an unreadable folder has no packs to offer
        }
        for (Path r : roots) {
            try (Stream<Path> s = Files.list(r)) {
                s.filter(d -> Files.isRegularFile(d.resolve("pack.yaml")))
                        .forEach(d -> holders.computeIfAbsent(d.getFileName().toString(), k -> new ArrayList<>()).add(d));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list the pack folder " + r + ": " + e.getMessage(), e);
            }
        }
        List<Path> out = new ArrayList<>();
        holders.values().stream().filter(at -> at.size() == 1).forEach(at -> out.add(at.get(0).resolve("pack.yaml")));
        return out;
    }

    /** The connector templates the host brings for a pack whose kinds are {@code kinds}, by connector name. */
    default Map<String, Map<String, Object>> connectorTemplates(Collection<String> kinds) {
        return Map.of();
    }

    /** The connector plug-ins that write a lake of their own name (a stream writer), whose demo root a migration rewrites. */
    default Set<String> namedLakeWriters() {
        return Set.of();
    }

    /** The extensions of a type the host's loaded code brings now (a {@code ReadyRule}). */
    default <T> List<T> extensions(Class<T> type) {
        return List.of();
    }

    /** The system properties a loader's child process is started with, so it sees the host as this process does. */
    default Map<String, String> childProcessSettings() {
        return Map.of();
    }
}
