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
package com.ash.lake.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The public API (design 3.1 and 7.1, LK-13; as Drishti Rachana's CG5). {@code java/api-surface.txt} lists every public and protected
 * member of every public class in the design's public packages, one sorted line each. The test builds the same list from the compiled
 * classes: a removed or changed signature fails, and so does an added one, so a change updates the file in the same commit and review
 * sees it. No line may name a Delta Kernel, Iceberg, Parquet or Hadoop type: the rows, columns and expressions are the product's own.
 * To update the file after a reviewed change: {@code ./mvnw -o -pl lake-guards -am verify -Dlake.api.surface.write=true}.
 */
class ApiSurfaceTest {

    /** The public packages (design 3.1) and the module that holds each. */
    static final Map<String, String> PACKAGES = Map.of(
            "com.ash.lake", "lake-api",
            "com.ash.lake.spi", "lake-api",
            "com.ash.lake.read", "lake-api",
            "com.ash.lake.config", "lake-config",
            "com.ash.lake.load", "lake-load",
            "com.ash.lake.inbox", "lake-load",
            "com.ash.lake.memory", "lake-memory",
            "com.ash.lake.memory.store", "lake-memory");

    /** The libraries that stay inside (LK-13). */
    static final List<String> LIBRARIES = List.of("io.delta.", "org.apache.iceberg.", "org.apache.parquet.", "org.apache.hadoop.");

    static Path baseline() {
        return Sources.JAVA.resolve("api-surface.txt");
    }

    static List<String> surface() throws Exception {
        TreeSet<String> out = new TreeSet<>();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(PACKAGES).entrySet()) {
            Path dir = Sources.JAVA.resolve(e.getValue()).resolve("target/classes").resolve(e.getKey().replace('.', '/'));
            if (!Files.isDirectory(dir)) {
                throw new AssertionError(dir + " is missing: build the modules first");
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".class")).sorted().toList()) {
                    String n = f.getFileName().toString();
                    if (n.contains("$") || n.equals("package-info.class")) {
                        continue;                                       // nested classes come with their owner
                    }
                    describe(Class.forName(e.getKey() + "." + n.substring(0, n.length() - 6), false, ApiSurfaceTest.class.getClassLoader()), out);
                }
            }
        }
        return new ArrayList<>(out);
    }

    static boolean visible(int mod) {
        return Modifier.isPublic(mod) || Modifier.isProtected(mod);
    }

    static void describe(Class<?> c, Set<String> out) {
        if (!visible(c.getModifiers())) {
            return;
        }
        String owner = c.getName();
        StringBuilder head = new StringBuilder("class " + c.toGenericString());
        if (c.getGenericSuperclass() != null && c.getSuperclass() != Object.class && !c.isEnum() && !c.isRecord()) {
            head.append(" extends ").append(c.getGenericSuperclass().getTypeName());
        }
        for (java.lang.reflect.Type i : c.getGenericInterfaces()) {
            head.append(" implements ").append(i.getTypeName());
        }
        out.add(head.toString());
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (visible(k.getModifiers()) && !k.isSynthetic()) {
                out.add(owner + " :: " + k.toGenericString());
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            if (visible(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()) {
                out.add(owner + " :: " + m.toGenericString());
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (visible(f.getModifiers()) && !f.isSynthetic()) {
                out.add(owner + " :: " + f.toGenericString());
            }
        }
        for (Class<?> n : c.getDeclaredClasses()) {
            describe(n, out);
        }
    }

    static List<String> libraryTypes(List<String> surface) {
        return surface.stream().filter(l -> LIBRARIES.stream().anyMatch(l::contains)).toList();
    }

    @Test
    void noLibraryTypeInAPublicSignature() throws Exception {
        List<String> now = surface();
        assertFalse(now.isEmpty(), "no public API found: the modules were not built");
        assertEquals(List.of(), libraryTypes(now), "a Delta Kernel, Iceberg, Parquet or Hadoop type in a public signature (LK-13)");
    }

    @Test
    void thePublicApiIsTheCommittedBaseline() throws Exception {
        List<String> now = surface();
        if (Boolean.getBoolean("lake.api.surface.write")) {
            List<String> file = new ArrayList<>();
            file.add("# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE.");
            file.add("# Generated by ApiSurfaceTest (lake-guards): the public and protected API of the public packages " + new TreeSet<>(PACKAGES.keySet())
                    + ". Review the diff; do not edit. Until v1.0.0 the packages also hold types L12 moves to internal packages.");
            file.addAll(now);
            Files.write(baseline(), file, StandardCharsets.UTF_8);
        }
        List<String> was = read();
        List<String> removed = new ArrayList<>(was);
        removed.removeAll(now);
        List<String> added = new ArrayList<>(now);
        added.removeAll(was);
        assertEquals(List.of(), removed, "removed or changed API: a breaking change needs the owner (then rewrite java/api-surface.txt)");
        assertEquals(List.of(), added, "added API: update java/api-surface.txt in the same commit (-Dlake.api.surface.write=true)");
    }

    @Test
    void theCheckSeesALibraryType() {
        assertEquals(List.of("x :: public io.delta.kernel.Snapshot x.s()"),
                libraryTypes(List.of("x :: public com.ash.lake.spi.Columns x.c()", "x :: public io.delta.kernel.Snapshot x.s()")));
    }

    private static List<String> read() throws IOException {
        return Files.readAllLines(baseline(), StandardCharsets.UTF_8).stream().filter(l -> !l.startsWith("#") && !l.isBlank()).toList();
    }
}
