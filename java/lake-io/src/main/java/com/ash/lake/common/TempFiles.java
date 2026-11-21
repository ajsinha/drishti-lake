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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The one place the product makes temporary files and folders: one folder per {@link LakeRuntime}, {@code lake-<pid>-<n>}, under the
 * runtime's temporary base ({@code lake.tmp.dir}, a relative value under the install folder; not set: the JVM's temporary folder). Made the
 * first time something needs it, removed when the JVM stops cleanly (or by {@link #cleanUp()}). The product never changes
 * {@code java.io.tmpdir}: a host that wants its native libraries unpacked elsewhere starts Java with {@code -Djava.io.tmpdir=}.
 */
public final class TempFiles {

    /** Names a folder as one of ours. */
    static final String MARKER = "lake-temp-folder.txt";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final Path base;
    private volatile Path root;

    private TempFiles(Path base) {
        this.base = base;
    }

    /** The temporary files of {@code runtime}: one folder for its life. */
    public static TempFiles of(LakeRuntime runtime) {
        return runtime.part(TempFiles.class, () -> new TempFiles(base(runtime)));
    }

    static Path base(LakeRuntime runtime) {
        String p = runtime.options().tmpDirSetting();
        return (p == null ? Path.of(System.getProperty("java.io.tmpdir", "/tmp")) : runtime.roots().anchored(p)).toAbsolutePath().normalize();
    }

    /** The base the folder is made under. */
    public Path base() {
        return base;
    }

    /** The runtime's folder, made the first time it is asked for. */
    public synchronized Path root() {
        if (root == null) {
            long pid = ProcessHandle.current().pid();
            Path dir = base.resolve("lake-" + pid + "-" + SEQUENCE.incrementAndGet());
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(MARKER), "pid " + pid + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException("cannot make the temp folder under " + base + ": " + e.getMessage(), e);
            }
            root = dir;
            Runtime.getRuntime().addShutdownHook(new Thread(this::cleanUp, "lake-tmp-cleanup"));
        }
        return root;
    }

    /** Removes the runtime's folder (the shutdown hook; also callable by a host when it closes the runtime). */
    public synchronized void cleanUp() {
        Path r = root;
        if (r != null) {
            deleteTree(r);
            root = null;
        }
    }

    /** A new empty file in {@code dir}, for a write that is then moved over its target (same folder, so the move is atomic). */
    public static Path createFile(Path dir, String prefix, String suffix) throws IOException {
        return Files.createTempFile(dir, prefix, suffix);
    }

    /** A new empty file in the runtime's folder. */
    public Path createFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(root(), prefix, suffix);
    }

    /** A new folder in {@code parent}. */
    public static Path createDir(Path parent, String prefix) throws IOException {
        return Files.createTempDirectory(parent, prefix);
    }

    /** A new folder in the runtime's folder. */
    public Path createDir(String prefix) throws IOException {
        return Files.createTempDirectory(root(), prefix);
    }

    /** A named subfolder of the runtime's folder (made if absent), for a library's spill space. */
    public Path subDir(String name) {
        try {
            return Files.createDirectories(root().resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    // a file still open elsewhere
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // already gone
        }
    }
}
