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
package com.ash.lake.io;

/**
 * One file as a listing or a status call sees it: its canonical path (a {@code file:} or {@code s3a://} URI string, as
 * {@link Storage#qualify(String)} writes them), its length and its modification time.
 *
 * @param path the file's canonical path
 * @param size its length in bytes
 * @param modified its modification time, milliseconds since the epoch
 */
public record FileEntry(String path, long size, long modified) {}
