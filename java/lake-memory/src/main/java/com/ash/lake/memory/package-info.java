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
/**
 * The in-memory lake, plain Java: the tables of the memory stores and their replenish
 * jobs ({@link com.ash.lake.memory.MemoryLake}, {@link com.ash.lake.memory.ReplenishSource}), the eviction ladder, automated
 * compaction ({@link com.ash.lake.memory.MemoryCompactor}), the expiry of old versions ({@link com.ash.lake.memory.MemoryUpkeep}),
 * the never-on-disk guard
 * ({@link com.ash.lake.memory.Retention}) and the host checks. The storage itself, {@code mem://}, is
 * {@link com.ash.lake.memory.store}.
 *
 * <h2>The dependency direction</h2>
 *
 * <p>This module depends on {@code lake-io} and {@code lake-delta} (it writes its tables with the lake writer and reads them with Delta
 * Kernel); nothing below it depends on it. The lake readers reach {@code mem://} the other way round: {@code lake-api} defines
 * {@link com.ash.lake.spi.StorageProvider}, and the host registers {@link com.ash.lake.memory.store.MemoryStorageProvider} with the
 * runtime it builds ({@code LakeRuntime.builder().storage(..)}). The Delta and Iceberg engines and the lake writer ask the runtime for a
 * path's storage, and whether it is memory-only, and never name a class of this module. So there is no cycle; the readers stay the same
 * code for every storage; and {@code mem://} works exactly in a runtime that registered it and fails clearly elsewhere: nothing can
 * quietly read or write a memory table through a disk.
 *
 * <p>The host keeps its wiring: binding its settings onto {@link com.ash.lake.memory.MemoryLakeSettings}, the cron parser, its connectors
 * as a {@link com.ash.lake.spi.RowSource}, the owner node's routing, and its screens.
 */
package com.ash.lake.memory;
