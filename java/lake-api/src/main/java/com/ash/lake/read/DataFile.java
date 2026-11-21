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

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * One data file. {@code rows}: {@code -1} when not stated; {@code deletes}: rows of it are deleted; {@code partition}: the file's constant
 * columns (logical names, values as text); {@code bounds}: per column (logical names), where the store states them.
 */
public record DataFile(String path, long size, FileFormat format, long rows, boolean deletes, Map<String, String> partition,
        Map<String, Bounds> bounds) {

    public DataFile {
        partition = Map.copyOf(partition);
        bounds = Map.copyOf(bounds);
    }

    /** The file on this machine's disk, for a host engine that reads it directly; empty for object stores and memory. */
    public Optional<Path> local() {
        if (path.startsWith("file:")) {
            return Optional.of(Path.of(java.net.URI.create(path)));
        }
        if (path.matches("^[a-zA-Z][a-zA-Z0-9+.-]+://.*")) {
            return Optional.empty();
        }
        return Optional.of(Path.of(path));
    }
}
