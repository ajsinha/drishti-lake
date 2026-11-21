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

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every source carries the copyright header, and no file is longer than the shared cap (design 7.1). */
class LicenceHeaderAndLineCapTest {

    /** The owner's file cap, one constant for every guard. */
    static final int MAX_LINES = 2500;

    private static List<Path> sources() {
        return Sources.files(Sources.REPO, ".java", ".py", "pom.xml").stream()
                .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("/tck/fixtures/")).toList();
    }

    @Test
    void everySourceCarriesTheHeader() {
        List<String> missing = sources().stream()
                .filter(p -> !Sources.read(p).lines().limit(20).anyMatch(l -> l.contains("Copyright (c) 2026 Ashutosh Sinha")))
                .map(p -> Sources.REPO.relativize(p).toString()).toList();
        assertEquals(List.of(), missing, "files without the copyright header");
    }

    @Test
    void noFileIsOverTheCap() {
        List<String> over = sources().stream().filter(p -> Sources.read(p).lines().count() > MAX_LINES)
                .map(p -> Sources.REPO.relativize(p).toString()).toList();
        assertEquals(List.of(), over, "files over " + MAX_LINES + " lines");
    }
}
