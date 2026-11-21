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
package com.ash.lake.files;

import com.ash.lake.LakeRuntime;
import com.ash.lake.io.LocalStorage;
import com.ash.lake.io.S3Settings;
import com.ash.lake.io.StorageRoutes;
import com.ash.lake.spi.FileEntry;
import com.ash.lake.spi.RangeReader;
import com.ash.lake.spi.Storage;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A place of the files store: a file or a folder on the {@link Storage} its lake lives on (local disk, S3, memory: the same code on
 * each). Paths are the storage's strings, joined with {@code /}. Immutable; safe from any thread.
 */
final class Place {

    private final Storage storage;
    private final String path;

    private Place(Storage storage, String path) {
        this.storage = storage;
        this.path = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * The lake root {@code root} (a local folder, {@code file:}, {@code s3://}, or a scheme the runtime's providers serve:
     * {@code mem://}), on the storage that serves it; {@code storage} the load's or connector's storage options (S3 endpoint, keys).
     */
    static Place root(LakeRuntime runtime, String root, Map<String, String> storage) {
        String r = root.replace('\\', '/');
        return new Place(Routes.of(runtime).storage(runtime, r, storage == null ? Map.of() : storage), r);
    }

    /** {@code root} with the runtime's own storage options. */
    static Place root(LakeRuntime runtime, String root) {
        return root(runtime, root, Map.of());
    }

    Place resolve(String name) {
        return new Place(storage, path + "/" + name);
    }

    String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    Place parent() {
        int slash = path.lastIndexOf('/');
        return new Place(storage, slash <= 0 ? "/" : path.substring(0, slash));
    }

    String path() {
        return path;
    }

    Storage storage() {
        return storage;
    }

    @Override
    public String toString() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Place p && p.storage == storage && p.path.equals(path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    // ------------------------------------------------------------------------------------------------------------ reads

    boolean isDirectory() throws IOException {
        return storage.isDirectory(path);
    }

    /** The file's size and time, or null when there is no such file (a folder is not a file). */
    FileEntry entry() throws IOException {
        if (storage.isDirectory(path)) {
            return null;
        }
        try {
            return storage.status(path);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    boolean isFile() throws IOException {
        return entry() != null;
    }

    long size() throws IOException {
        return status().size();
    }

    FileEntry status() throws IOException {
        FileEntry e = entry();
        if (e == null) {
            throw new java.nio.file.NoSuchFileException(path);
        }
        return e;
    }

    /** The names of the folders in this folder, sorted (none when it does not exist). */
    List<String> directories() throws IOException {
        return storage.directories(path);
    }

    /** The files in this folder (not deeper), sorted by name; none when it does not exist. */
    List<Place> files() throws IOException {
        List<Place> out = new ArrayList<>();
        for (FileEntry e : storage.files(path)) {
            String n = e.path().substring(e.path().lastIndexOf('/') + 1);
            out.add(resolve(n));
        }
        return out;
    }

    RangeReader open() throws IOException {
        return storage.open(path, -1);
    }

    byte[] readAll() throws IOException {
        return storage.readAll(path);
    }

    /** The file as a stream, read in blocks from its storage. */
    InputStream in() throws IOException {
        RangeReader r = storage.open(path, -1);
        return new InputStream() {
            private final ByteBuffer buf = ByteBuffer.allocate(1 << 16).flip();
            private long at;

            private boolean fill() throws IOException {
                if (buf.hasRemaining()) {
                    return true;
                }
                buf.clear();
                int n = r.read(at, buf);
                buf.flip();
                if (n <= 0) {
                    return false;
                }
                at += n;
                return true;
            }

            @Override
            public int read() throws IOException {
                return fill() ? buf.get() & 0xff : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (!fill()) {
                    return -1;
                }
                int n = Math.min(len, buf.remaining());
                buf.get(b, off, n);
                return n;
            }

            @Override
            public void close() throws IOException {
                r.close();
            }
        };
    }

    BufferedReader reader() throws IOException {
        return new BufferedReader(new InputStreamReader(in(), StandardCharsets.UTF_8), 1 << 16);
    }

    /** The file's non-blank lines. */
    List<String> lines() throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = reader()) {
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                if (!l.isBlank()) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    /** The number of non-blank lines. */
    long countLines() throws IOException {
        long n = 0;
        try (BufferedReader r = reader()) {
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                if (!l.isBlank()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Copies the file into {@code out}. */
    void copyTo(OutputStream out) throws IOException {
        try (InputStream in = in()) {
            in.transferTo(out);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ writes

    /** A new file here, replacing one that is there; seen under its name once the stream is closed (an object store, memory). */
    OutputStream out() throws IOException {
        return storage.create(path);
    }

    /** {@code content} as the whole file, replacing it; a reader sees the old file or the new one. */
    void put(byte[] content) throws IOException {
        storage.put(path, content);
    }

    /** Creates the file with {@code content} only if the name is free: of two writers, one wins (the log's commit). */
    boolean createIfAbsent(byte[] content) throws IOException {
        return storage.createIfAbsent(path, content);
    }

    /** This file made a copy of {@code from}, replacing it, as one step for a reader. */
    void publish(Place from) throws IOException {
        storage.publish(from.path, path);
    }

    boolean delete() throws IOException {
        try {
            return storage.delete(path);
        } catch (FileNotFoundException gone) {
            return false;
        }
    }

    /** The storages of one runtime: local disk, the providers' schemes, and one S3 client per set of S3 options. */
    private static final class Routes {
        private final LocalStorage local = new LocalStorage();
        private final Map<S3Settings, StorageRoutes> byS3 = new ConcurrentHashMap<>();

        static Routes of(LakeRuntime runtime) {
            return runtime.part(Routes.class, Routes::new);
        }

        Storage storage(LakeRuntime runtime, String root, Map<String, String> options) {
            if (com.ash.lake.io.LocalPaths.isLocal(root)) {
                return local;
            }
            S3Settings s3 = S3Settings.from(runtime, options);
            return byS3.computeIfAbsent(s3, s -> new StorageRoutes(runtime, local, s, "the files store")).storage(root);
        }
    }
}
