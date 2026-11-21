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

import com.ash.lake.spi.LakeCatalog;
import java.util.Set;

/** A host's catalog for the tests: its domain names a lake writer of its own name ({@code var-pv-stream}). */
public final class TestCatalog implements LakeCatalog {

    /** The catalog with the writer. */
    static final LakeCatalog WRITERS = new TestCatalog();

    @Override
    public Set<String> namedLakeWriters() {
        return Set.of("var-pv-stream");
    }
}
