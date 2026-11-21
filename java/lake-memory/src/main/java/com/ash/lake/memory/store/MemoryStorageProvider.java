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
package com.ash.lake.memory.store;

import com.ash.lake.spi.Storage;
import com.ash.lake.spi.StorageProvider;

/**
 * {@code mem://} for every lake engine of one runtime: registered when the runtime is built
 * ({@code LakeRuntime.builder().storage(new MemoryStorageProvider())}), so the native Delta engine, the lake writer and the Iceberg
 * engine find the memory stores through the runtime without depending on this module. Memory-only: nothing read from it may reach a disk.
 */
public final class MemoryStorageProvider implements StorageProvider {

    private final MemoryStores stores;

    /** A provider holding stores of its own (one per runtime). */
    public MemoryStorageProvider() {
        this(new MemoryStores());
    }

    public MemoryStorageProvider(MemoryStores stores) {
        this.stores = java.util.Objects.requireNonNull(stores, "stores");
    }

    /** The stores this provider answers {@code mem://} with. */
    public MemoryStores stores() {
        return stores;
    }

    @Override
    public String scheme() {
        return MemoryStorage.SCHEME;
    }

    @Override
    public Storage storage(String path) {
        return stores.forPath(path);
    }

    @Override
    public boolean memoryOnly() {
        return true;
    }
}
