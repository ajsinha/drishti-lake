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
package com.ash.lake.memory;

import com.ash.lake.LakeRuntime;
import com.ash.lake.LakeOptions;
import com.ash.lake.memory.store.MemoryStorageProvider;
import com.ash.lake.memory.store.MemoryStores;

/** The tests' runtime: the process's options with a memory storage of its own registered ({@code mem://}). */
public final class MemoryRuntime {

    public static final LakeRuntime RT = LakeRuntime.builder().options(LakeOptions.fromProcess()).storage(new MemoryStorageProvider()).build();

    /** The runtime's memory stores. */
    public static final MemoryStores STORES = MemoryStores.of(RT);

    private MemoryRuntime() {
    }
}
