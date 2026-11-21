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
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Names no host (design 7.1), in report mode while the move is untangled: no source names the host's packages, and the count of files
 * that still carry a host word (a {@code DRS-} code, a {@code drishti.} setting or {@code DRISHTI_} variable, a source-system name, the
 * host's name outside "Drishti Lake") equals the number SPLIT_EXEMPTIONS.md records, which may only go down.
 */
class NamesNoHostTest {

    /** The count when the code moved (L3, 2026-11-21); SPLIT_EXEMPTIONS.md may record less, never more. */
    private static final int HOST_WORD_FILES_AT_MOVE = 79;

    private static final Pattern HOST_PACKAGE = Pattern.compile("com\\.ash\\.drishti\\b");
    private static final Pattern HOST_WORD = Pattern.compile(
            "DRS-\\d|\\bdrishti\\.[a-z]|DRISHTI_|(?i:\\b(endur|calypso|murex|swapone)\\b)|Drishti(?! Lake)");

    private static List<Path> moved() {
        return Sources.files(Sources.JAVA, ".java", ".yaml", ".yml", ".properties", ".xml").stream()
                .filter(p -> !p.toString().contains("/lake-guards/"))
                .filter(p -> !p.getFileName().toString().equals("pom.xml"))
                .toList();
    }

    @Test
    void noSourceNamesTheHostsPackages() {
        List<String> found = moved().stream().filter(p -> HOST_PACKAGE.matcher(Sources.read(p)).find())
                .map(p -> Sources.JAVA.relativize(p).toString()).toList();
        assertEquals(List.of(), found, "the host's packages are named here; a host type the code needs is copied into hostcompat");
    }

    @Test
    void hostWordFilesOnlyGoDown() {
        long n = moved().stream().filter(p -> !Sources.hostcompat(p)).filter(p -> HOST_WORD.matcher(Sources.read(p)).find()).count();
        int recorded = Sources.recorded("host-word files");
        assertTrue(recorded <= HOST_WORD_FILES_AT_MOVE, "the recorded count may only go down from " + HOST_WORD_FILES_AT_MOVE);
        assertEquals(recorded, n, "files with a host word: record the new count in SPLIT_EXEMPTIONS.md (it may only go down)");
    }
}
