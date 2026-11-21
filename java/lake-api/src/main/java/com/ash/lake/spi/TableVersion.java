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

import java.time.Instant;
import java.util.Objects;

/** One version of a table ({@link TableStore#versions}): the store's version number (Delta's version, an Iceberg snapshot's id, a files commit's number) and when it was committed. */
public record TableVersion(long version, Instant committed) {

    public TableVersion {
        Objects.requireNonNull(committed, "committed");
    }
}
