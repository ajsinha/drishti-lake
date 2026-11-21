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
package com.ash.lake.delta.table;

import com.ash.lake.io.LocalPaths;
import com.ash.lake.delta.NativeEngine;
import com.ash.lake.spi.Storage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A lake read by the {@link NativeEngine}: local disk through {@code java.nio} (Windows included, no {@code winutils.exe}), S3
 * ({@code s3://}, {@code s3a://}) through the AWS SDK, or a scheme the runtime's storage providers serve ({@code mem://}). No Hadoop file
 * system is used. Thread-safe.
 *
 * @param base the domain's folder as the engine's canonical URI ({@code file:/C:/lakes/banking}, {@code s3a://b/lake})
 */
record NativeLake(String base, NativeEngine engine, Storage storage) implements LakeStore {

    static NativeLake open(com.ash.lake.LakeRuntime runtime, String root, String domain, Map<String, String> settings) {
        NativeEngine engine = NativeEngine.create(runtime, settings);
        String r = LakeStore.isRemote(root) ? root.replaceAll("/+$", "") : root;
        Storage storage = engine.storage(r);
        String base;
        try {
            base = storage.qualify(r);
        } catch (IOException e) {
            engine.close();
            throw new UncheckedIOException(e);
        }
        String d = domain.replace('\\', '/').replaceAll("^/+|/+$", "");
        return new NativeLake(d.isEmpty() ? base : base + (base.endsWith("/") ? "" : "/") + d, engine, storage);
    }

    @Override
    public String table(String kind) {
        return base + "/" + kind;
    }

    @Override
    public List<String> tables() throws IOException {
        List<String> out = new ArrayList<>();
        for (String dir : storage.directories(base)) {
            if (storage.isDirectory(table(dir) + "/_delta_log")) {
                out.add(dir);
            }
        }
        return out;
    }

    @Override
    public boolean reachable() {
        try {
            return storage.isDirectory(base);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return LocalPaths.isLocal(base) ? LocalPaths.toLocal(base, LocalPaths.WINDOWS) : base;
    }

    @Override
    public void close() {
        engine.close();
    }

    @Override
    public com.ash.tools.net.TlsMaterial tls() {
        return engine.tls();
    }

    @Override
    public com.ash.tools.net.ProxySettings proxy() {
        return engine.proxy();
    }
}
