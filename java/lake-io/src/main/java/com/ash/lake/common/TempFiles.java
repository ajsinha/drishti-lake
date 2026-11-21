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
package com.ash.lake.common;

import com.ash.lake.LakeRuntime;
import com.ash.tools.files.TempFolder;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where the product makes temporary files and folders: one {@link TempFolder} per {@link LakeRuntime}, {@code lake-<pid>-<n>}, under
 * the runtime's temporary base ({@code lake.tmp.dir}, a relative value under the install folder; not set: the JVM's temporary
 * folder), marked {@value #MARKER}. Made the first time something needs it, removed when the JVM stops cleanly (or by
 * {@link TempFolder#cleanUp()}). The product never changes {@code java.io.tmpdir}: a host that wants its native libraries unpacked
 * elsewhere starts Java with {@code -Djava.io.tmpdir=}.
 */
public final class TempFiles {

    /** Names a folder as one of ours. */
    static final String MARKER = "lake-temp-folder.txt";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private TempFiles() {}

    /** The temporary folder of {@code runtime}: one for its life. */
    public static TempFolder of(LakeRuntime runtime) {
        return runtime.part(TempFolder.class, () -> TempFolder.of(base(runtime), "lake-", SEQUENCE.incrementAndGet(), MARKER));
    }

    /** The temporary folders under operators' own folders (a {@code --spill-dir}), one per folder for the process's life. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, TempFolder> UNDER = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The temporary folder for a job's scratch files: {@code <dir>/lake-<pid>} when the operator names a folder ({@code --spill-dir},
     * {@code memory.spill-dir}; a relative one under the install folder), else the runtime's ({@link #of}). Removed when the JVM stops
     * cleanly; a job removes its own sub-folder when it ends.
     */
    public static TempFolder under(LakeRuntime runtime, String dir) {
        if (dir == null || dir.isBlank()) {
            return of(runtime);
        }
        Path base = runtime.roots().anchored(dir).toAbsolutePath().normalize();
        return UNDER.computeIfAbsent(base, b -> TempFolder.of(b, "lake-", MARKER));
    }

    static Path base(LakeRuntime runtime) {
        String p = runtime.options().tmpDirSetting();
        return (p == null ? Path.of(System.getProperty("java.io.tmpdir", "/tmp")) : runtime.roots().anchored(p)).toAbsolutePath().normalize();
    }
}
