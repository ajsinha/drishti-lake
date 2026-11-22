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
package com.ash.lake.load;

import com.ash.lake.LakeRuntime;
import com.ash.lake.io.LocalPaths;
import com.ash.lake.io.LocalStorage;
import com.ash.lake.io.S3Settings;
import com.ash.lake.io.StorageRoutes;
import com.ash.lake.spi.Storage;
import java.nio.file.Path;
import java.util.Map;

/**
 * The files of a lake's place for a host that writes beside its tables (a results folder of JSON Lines, a check of which format a table
 * folder holds): local disk (a plain path or a {@code file:} URI), S3 ({@code s3://}, {@code s3a://}) and any scheme a provider of the
 * runtime serves ({@code mem://}), each a {@link Storage} (list, status, read, {@link Storage#put}, {@link Storage#isDirectory}). S3 is
 * reached as the loader and the readers reach it: the runtime's {@code lake.s3.*} options with the given settings' {@code s3.*} keys over
 * them (endpoint, region, credentials, path style, TLS). Close it to release the S3 client.
 *
 * <pre>
 * try (LakeStorage files = LakeStorage.open(runtime, settings)) {
 *     Storage s = files.storage(root);
 *     boolean delta = s.isDirectory(root + "/risk-results/var/_delta_log");
 *     s.put(root + "/risk-results/jsonl/var/2026-11-20/var-v3.jsonl", bytes);
 * }
 * </pre>
 */
public final class LakeStorage implements AutoCloseable {

    private final StorageRoutes routes;

    private LakeStorage(StorageRoutes routes) {
        this.routes = routes;
    }

    /** The storages of {@code runtime} with {@code settings}' {@code s3.*} keys over its {@code lake.s3.*} options. */
    public static LakeStorage open(LakeRuntime runtime, Map<String, String> settings) {
        return new LakeStorage(new StorageRoutes(runtime, new LocalStorage(), S3Settings.from(runtime, settings == null ? Map.of() : settings),
                "the lake's storage"));
    }

    /**
     * The storage that serves {@code path}.
     *
     * @throws UnsupportedOperationException for a scheme nothing here serves ({@code gs://}, {@code abfs://})
     */
    public Storage storage(String path) {
        return routes.storage(path);
    }

    /** Whether {@code path} is on the local disk (a plain path or a {@code file:} URI). */
    public static boolean isLocal(String path) {
        return LocalPaths.isLocal(path);
    }

    /** The local folder or file of a local {@code path} ({@link #isLocal}), in this system's form. */
    public static Path localPath(String path) {
        return Path.of(LocalPaths.toLocal(path, LocalPaths.WINDOWS));
    }

    /** Releases the S3 client, if one was made. */
    @Override
    public void close() {
        routes.close();
    }
}
