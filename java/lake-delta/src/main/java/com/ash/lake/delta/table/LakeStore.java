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

import io.delta.kernel.engine.Engine;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Where a Delta Lake lives and the Kernel engine that reads it, so a lake on local disk and one in object storage read the same way. A
 * {@code root} with a scheme ({@code s3a://bucket/lake}, or a scheme a runtime's {@code StorageProvider} serves, {@code mem://}) is not a
 * local folder; anything else (including {@code C:\lakes} and {@code file:} URIs) is. A reader asks for a table's URI, the list of
 * tables, and the engine.
 *
 * <p>One engine: the {@link com.ash.lake.delta.NativeEngine} (Delta Kernel over {@code java.nio}, the AWS SDK and the runtime's storage
 * providers, no Hadoop file system). The Hadoop engine is not in the product (LK-9): Azure and Google Cloud Storage arrive as
 * {@code StorageProvider}s; {@code engine: hadoop} is refused naming that.
 */
public interface LakeStore extends AutoCloseable {

    /** The Delta table URI for {@code kind}. */
    String table(String kind);

    /** The kinds that have a Delta table ({@code <kind>/_delta_log}) under the domain, sorted. */
    List<String> tables() throws IOException;

    /** Whether the domain's folder exists (for health). */
    boolean reachable();

    /** For messages: where this is. */
    String describe();

    /** The Kernel engine that reads this lake, shared by every read. */
    Engine engine();

    /** {@code native}, for health. */
    default String engineName() {
        return "native";
    }

    /** Releases the engine's clients (an S3 connection pool). */
    @Override
    default void close() {}

    /** The TLS material of the object store's endpoint ({@code tls.*}), or null: its certificates' expiry is shown in health. */
    default com.ash.tools.net.TlsMaterial tls() {
        return null;
    }

    /** The outbound proxy of the object store's endpoint ({@code proxy.*} or the site default); {@code none} for local disk. */
    default com.ash.tools.net.ProxySettings proxy() {
        return com.ash.tools.net.ProxySettings.none();
    }

    /**
     * The lake at {@code root}, read by the native engine of {@code runtime}.
     *
     * @param runtime the runtime whose storages, options and caches the engine reads with
     * @param root {@code /lakes/risk}, {@code C:\lakes\risk}, or a URI {@code s3a://bucket/lake}, {@code mem://feeds}
     * @param domain the data domain folder under the root (may be blank)
     * @param settings the reader's settings: for S3, {@code s3.endpoint}, {@code s3.access-key}, {@code s3.secret-key}, {@code s3.region},
     *     {@code s3.path-style} (credentials otherwise from the AWS chain), {@code tls.*}, {@code proxy.*}; {@code kernel.<key>} (or
     *     {@code hadoop.<key>}) as the Kernel's option {@code <key>}; {@code table.<kind>} a kind read from a folder of another name;
     *     {@code engine} {@code native} or {@code auto} (both the native engine; {@code hadoop} is refused)
     */
    static LakeStore of(com.ash.lake.LakeRuntime runtime, String root, String domain, Map<String, String> settings) {
        if (domain.contains("..") || domain.startsWith("/") || domain.startsWith("\\")) {
            throw new IllegalArgumentException("domain escapes the Delta root: " + domain);
        }
        String engine = settings.getOrDefault("engine", "native").strip().toLowerCase(java.util.Locale.ROOT);
        if (!engine.isEmpty() && !engine.equals("native") && !engine.equals("auto")) {
            throw new IllegalArgumentException("engine " + engine + ": the Delta lake is read by the native engine only (native, auto); the Hadoop "
                    + "engine is not in the product, and abfs://, gs:// and HDFS are read through a storage provider registered with the runtime");
        }
        LakeStore lake = NativeLake.open(runtime, root, domain, settings);
        Map<String, String> names = NamedTables.of(settings);
        return names.isEmpty() ? lake : new NamedTables(lake, names);     // table.<kind>: a kind read from a folder of another name
    }

    /** Whether {@code root} is a URI (a scheme other than {@code file:}, not a drive letter): object storage or a provider's store. */
    static boolean isRemote(String root) {
        return root.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") && !root.regionMatches(true, 0, "file:", 0, 5);
    }
}
