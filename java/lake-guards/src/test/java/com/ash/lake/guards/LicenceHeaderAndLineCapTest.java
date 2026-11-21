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
import com.ash.tools.guards.kit.GuardFiles;
import com.ash.tools.guards.kit.HeaderAndLineCap;
import org.junit.jupiter.api.Test;

/** Every source carries the copyright header, and no file is longer than the shared cap (design 7.1). */
class LicenceHeaderAndLineCapTest {

    private static List<Path> sources() {
        return Sources.files(Sources.REPO, ".java", ".py", "pom.xml").stream()
                .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("/tck/fixtures/")).toList();
    }

    @Test
    void everySourceCarriesTheHeader() {
        assertEquals(List.of(), HeaderAndLineCap.withoutHeader(Sources.REPO, sources()), "files without the copyright header");
    }

    @Test
    void noFileIsOverTheCap() {
        assertEquals(List.of(), HeaderAndLineCap.overCap(Sources.REPO, sources()), "files over " + GuardFiles.MAX_LINES + " lines");
    }
}
