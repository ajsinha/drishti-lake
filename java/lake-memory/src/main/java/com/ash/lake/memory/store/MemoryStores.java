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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The memory stores of one runtime, by name: every reader and writer of that runtime that meets a {@code mem://<store>/…} path reaches the
 * same store here (the Delta and Iceberg readers, the lake writer, the replenish jobs). They are held by the runtime's
 * {@link MemoryStorageProvider} ({@code LakeRuntime.builder().storage(new MemoryStorageProvider())}); two runtimes never share a store. A store asked for before the configuration defines it
 * exists with a cap of zero (it holds nothing and refuses writes), so a connector that starts first simply finds its tables empty.
 */
public final class MemoryStores {

    /** The default chunk: 1 MB. */
    public static final int DEFAULT_CHUNK_BYTES = 1 << 20;

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");
    private final Map<String, MemoryStorage> stores = new ConcurrentHashMap<>();

    public MemoryStores() {}

    /**
     * The memory stores of {@code runtime}: those of the {@link MemoryStorageProvider} registered with it.
     *
     * @throws IllegalStateException when the runtime has no memory storage registered
     */
    public static MemoryStores of(com.ash.lake.LakeRuntime runtime) {
        return runtime.provider(MemoryStorage.SCHEME + "://").filter(p -> p instanceof MemoryStorageProvider)
                .map(p -> ((MemoryStorageProvider) p).stores())
                .orElseThrow(() -> new IllegalStateException("the runtime has no memory storage: build it with .storage(new MemoryStorageProvider())"));
    }

    /** Whether {@code path} is a memory path ({@code mem://…}). */
    public static boolean isMemory(String path) {
        return path != null && path.trim().regionMatches(true, 0, MemoryStorage.SCHEME + "://", 0, MemoryStorage.SCHEME.length() + 3);
    }

    /** The store's name in a memory path: {@code mem://news/news} → {@code news}. */
    public static String storeName(String path) {
        if (!isMemory(path)) {
            throw new IllegalArgumentException("not a memory path: " + path);
        }
        String rest = path.trim().substring(MemoryStorage.SCHEME.length() + 3);
        int slash = rest.indexOf('/');
        return (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
    }

    /** The store that answers {@code path}. */
    public MemoryStorage forPath(String path) {
        return store(storeName(path));
    }

    /** The store of this name, made empty with a cap of zero when it is not defined yet. */
    public MemoryStorage store(String name) {
        check(name);
        return stores.computeIfAbsent(name, n -> new MemoryStorage(n, 0, DEFAULT_CHUNK_BYTES));
    }

    /**
     * Defines (or redefines) a store: its hard cap, its chunk size and its contract. A store already holding data keeps its chunk size (a
     * new one applies after a restart); a lower cap releases its free chunks at once.
     */
    public MemoryStorage define(String name, long capBytes, int chunkBytes, String contract) {
        check(name);
        MemoryStorage s = stores.computeIfAbsent(name, n -> new MemoryStorage(n, capBytes, chunkBytes));
        if (s.pool().chunkBytes() != chunkBytes) {
            s.rechunk(capBytes, chunkBytes);                    // the same store object: readers that met it first keep reaching it
        }
        s.pool().cap(capBytes);
        s.contract(contract);
        return s;
    }

    /** Every store, by name. */
    public List<MemoryStorage> all() {
        return stores.values().stream().sorted(Comparator.comparing(MemoryStorage::name)).toList();
    }

    /** The stores' caps added up: what the JVM's direct-memory limit must cover beyond the server's own 256 MB. */
    public long totalCapBytes() {
        return stores.values().stream().mapToLong(s -> s.pool().capBytes()).sum();
    }

    /** Drops a store and frees its memory (tests; a store removed from the configuration). */
    public void remove(String name) {
        MemoryStorage s = stores.remove(name);
        if (s != null) {
            s.clear();
            s.pool().cap(0);
        }
    }

    private static void check(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a memory store's name is lower-case letters, digits, '-' and '_': " + name);
        }
    }
}
