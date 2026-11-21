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
 * {@code mem://}: the memory stores of one runtime. Each store holds files as fixed-size chunks of
 * off-heap memory under a hard cap ({@link com.ash.lake.memory.store.ChunkPool}) and meets the {@link com.ash.lake.spi.Storage}
 * contract exactly ({@link com.ash.lake.memory.store.MemoryStorage}). The lake engines reach it only through the runtime, with
 * which the host registers {@link com.ash.lake.memory.store.MemoryStorageProvider} (why this way round: the package documentation of
 * {@link com.ash.lake.memory}).
 */
package com.ash.lake.memory.store;
