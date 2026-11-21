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
package com.ash.lake.read;

import java.util.List;

/**
 * A lake's health. {@code reachable}: the root answers; {@code memoryOnly}: the lake is in memory ({@code mem://}); {@code describe}: the
 * place in plain words; {@code engine}: the reading engine's name; {@code notes}: TLS expiry, proxy in force.
 */
public record LakeHealth(boolean reachable, boolean memoryOnly, String describe, String engine, List<String> notes) {

    public LakeHealth {
        notes = List.copyOf(notes);
    }
}
