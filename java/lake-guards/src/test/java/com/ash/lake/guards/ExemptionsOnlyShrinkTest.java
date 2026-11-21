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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The split's exemptions only shrink (design 7.1): the host types copied into {@code com.ash.lake.hostcompat} are exactly those
 * SPLIT_EXEMPTIONS.md lists, each still needed by the moved code (directly or through another listed one), and never more than at the
 * move; the parked checks (a test or an assertion that reads the host, marked {@code SPLIT EXEMPTION (parked}) are counted the same way.
 */
class ExemptionsOnlyShrinkTest {

    /** The counts when the code moved (L3, 2026-11-21). */
    private static final int HOSTCOMPAT_TYPES_AT_MOVE = 41;
    private static final int PARKED_CHECKS_AT_MOVE = 2;

    private static final Pattern LISTED = Pattern.compile("(?m)^\\| `(\\w+)` \\|");

    @Test
    void theHostTypesAreThoseListedEachStillNeeded() {
        List<Path> java = Sources.files(Sources.JAVA, ".java");
        Map<String, Path> copies = java.stream().filter(Sources::hostcompat)
                .collect(Collectors.toMap(p -> p.getFileName().toString().replace(".java", ""), Function.identity()));
        String md = Sources.read(Sources.EXEMPTIONS);
        String section = md.substring(md.indexOf("## Host types"), md.indexOf("\n## ", md.indexOf("## Host types") + 3));
        Set<String> listed = new TreeSet<>();
        Matcher m = LISTED.matcher(section);
        while (m.find()) {
            listed.add(m.group(1));
        }
        assertEquals(new TreeSet<>(copies.keySet()), listed, "hostcompat and SPLIT_EXEMPTIONS.md disagree");
        assertEquals(listed.size(), Sources.recorded("hostcompat types"), "record the number of host types");
        assertTrue(listed.size() <= HOSTCOMPAT_TYPES_AT_MOVE, "a host type was added: the list only shrinks");

        // still needed: named by the moved code, or by a needed copy
        Set<String> needed = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>();
        String moved = java.stream().filter(p -> !Sources.hostcompat(p)).filter(p -> !p.toString().contains("/lake-guards/"))
                .map(Sources::read).collect(Collectors.joining("\n"));
        for (String t : copies.keySet()) {
            if (Pattern.compile("\\b" + t + "\\b").matcher(moved).find()) {
                needed.add(t);
                todo.add(t);
            }
        }
        while (!todo.isEmpty()) {
            String text = Sources.read(copies.get(todo.pop()));
            for (String t : copies.keySet()) {
                if (!needed.contains(t) && Pattern.compile("\\b" + t + "\\b").matcher(text).find()) {
                    needed.add(t);
                    todo.add(t);
                }
            }
        }
        Set<String> unused = new TreeSet<>(copies.keySet());
        unused.removeAll(needed);
        assertEquals(Set.of(), unused, "host types no longer needed: delete them and their lines in SPLIT_EXEMPTIONS.md");
    }

    @Test
    void parkedChecksOnlyGoDown() {
        long n = Sources.files(Sources.JAVA, ".java").stream().filter(p -> !p.toString().contains("/lake-guards/"))
                .mapToLong(p -> Sources.read(p).split("SPLIT EXEMPTION \\(parked", -1).length - 1).sum();
        int recorded = Sources.recorded("parked checks");
        assertTrue(recorded <= PARKED_CHECKS_AT_MOVE, "a check was parked: the list only shrinks");
        assertEquals(recorded, n, "record the number of parked checks in SPLIT_EXEMPTIONS.md");
    }
}
