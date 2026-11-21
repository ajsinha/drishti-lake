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

import com.ash.lake.LakeRuntime;
import com.ash.lake.spi.Storage;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Which storage serves a path, for an engine's file access: local paths and {@code file:} URIs on {@link LocalStorage}, {@code s3://},
 * {@code s3a://} and {@code s3n://} on one {@link S3Storage} made on first use and shared, any scheme a runtime's provider registers
 * ({@code mem://}) on that provider; anything else ({@code abfs://}, {@code gs://}, {@code hdfs://}) refused, naming the engine.
 * Thread-safe.
 */
public final class StorageRoutes implements AutoCloseable {

    private static final Set<String> S3_SCHEMES = Set.of("s3", "s3a", "s3n");

    private final LakeRuntime runtime;
    private final LocalStorage local;
    private final S3Settings s3Settings;
    private final String engine;
    private final ReentrantLock s3Lock = new ReentrantLock();
    private volatile S3Storage s3;

    /**
     * @param runtime the runtime whose providers ({@code mem://}) serve the schemes it registers
     * @param local the local disk
     * @param s3Settings how to reach S3 when a path asks for it
     * @param engine who reads, for the refusal of an unknown scheme ({@code the Iceberg engine})
     */
    public StorageRoutes(LakeRuntime runtime, LocalStorage local, S3Settings s3Settings, String engine) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.local = local;
        this.s3Settings = s3Settings;
        this.engine = engine;
    }

    /** Whether {@code path} is served with no provider registered: local paths and {@code file:}, {@code s3:}, {@code s3a:}, {@code s3n:} URIs. */
    public static boolean supports(String path) {
        String scheme = LocalPaths.scheme(path);
        return scheme.equals("file") || S3_SCHEMES.contains(scheme);
    }

    /** Whether this serves {@code path}: the built-in schemes and those of the runtime's providers. */
    public boolean reads(String path) {
        return supports(path) || runtime.supports(path);
    }

    /** The runtime. */
    public LakeRuntime runtime() {
        return runtime;
    }

    /** The local disk. */
    public LocalStorage local() {
        return local;
    }

    /** How S3 is reached. */
    public S3Settings s3Settings() {
        return s3Settings;
    }

    /** The storage serving {@code path}. */
    public Storage storage(String path) {
        String scheme = LocalPaths.scheme(path);
        if (scheme.equals("file")) {
            return local;
        }
        if (S3_SCHEMES.contains(scheme)) {
            return s3();
        }
        if (runtime.supports(path)) {
            return runtime.storage(path);                            // mem://, a lake in memory: never a disk
        }
        throw new UnsupportedOperationException(engine + " reads local disk, S3 (s3://, s3a://) and memory (mem://), not " + scheme + "://");
    }

    private S3Storage s3() {
        S3Storage s = s3;
        if (s != null) {
            return s;
        }
        s3Lock.lock();
        try {
            if (s3 == null) {
                s3 = new S3Storage(s3Settings);
            }
            return s3;
        } finally {
            s3Lock.unlock();
        }
    }

    /** Releases the S3 client, if one was made. */
    @Override
    public void close() {
        s3Lock.lock();
        try {
            if (s3 != null) {
                s3.close();
                s3 = null;
            }
        } finally {
            s3Lock.unlock();
        }
    }
}
