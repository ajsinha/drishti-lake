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
package com.ash.lake;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Every setting the product reads, in one place (design section 3.1): the folders a default is taken under, the temporary folder, the
 * Parquet writer's sizes, the readers' cache sizes, the archive caps and the loader's trace. Nothing else in the product reads a system
 * property or an environment variable for its own behaviour.
 *
 * <p>Two kinds: {@link #builder()} gives options of exactly the values set (nothing is read from the process: what an embedding host
 * builds); {@link #fromProcess()} reads each key when it is asked, a {@code -D} system property first, then the environment variable of
 * the same name ({@code lake.data.dir} is {@code LAKE_DATA_DIR}, {@link SettingsFile#envName}): what the command-line entry points use.
 * A {@link LakeRuntime} holds one; there is no options object in force for the whole JVM.
 */
public final class LakeOptions {

    /** The install folder: the anchor of a relative path and of every default folder. */
    public static final String HOME = "lake.home";
    /** The configuration folder (default {@code <home>/config}). */
    public static final String CONFIG_DIR = "lake.config.dir";
    /** The data folder (default {@code <home>/data}). */
    public static final String DATA_DIR = "lake.data.dir";
    /** The connectors folder (default {@code <config>/connectors}); step L5 hands connector names to the host's {@code LakeCatalog}. */
    public static final String CONNECTORS_DIR = "lake.connectors.dir";
    /** The folder of the lake files (default {@code <config>/lakes}). */
    public static final String LAKES_DIR = "lake.lakes.dir";
    /** The temporary folder's base (default {@code <data>/tmp/server}). */
    public static final String TMP_DIR = "lake.tmp.dir";
    /** A Parquet row group in KB (default 256) ... */
    public static final String ROW_GROUP_KB = "lake.parquet.row-group-kb";
    /** ... or in MB (wins when set). */
    public static final String ROW_GROUP_MB = "lake.parquet.row-group-mb";
    /** A Parquet page in KB (default 64). */
    public static final String PAGE_KB = "lake.parquet.page-kb";
    /** Whether a Delta read uses the Parquet page index (default true). */
    public static final String PAGE_INDEX = "lake.parquet.page-index";
    /** The Parquet footer cache in MB (default 64). */
    public static final String FOOTER_CACHE_MB = "lake.parquet.footer-cache-mb";
    /** The Iceberg delete-file cache in rows (default 5,000,000). */
    public static final String DELETE_CACHE_ROWS = "lake.iceberg.delete-cache-rows";
    /** The Iceberg manifest cache in bytes (default 512 MB). */
    public static final String MANIFEST_CACHE_BYTES = "lake.iceberg.manifest-cache-bytes";
    /** Whether a failed load prints its stack trace (default false). */
    public static final String LOADER_TRACE = "lake.loader.trace";
    /** The zip-bomb caps of an archive input: the expanded MB, the ratio to the archive, the member count. */
    public static final String ARCHIVE_MAX_MB = "lake.archive.max-mb";
    public static final String ARCHIVE_MAX_RATIO = "lake.archive.max-ratio";
    public static final String ARCHIVE_MAX_MEMBERS = "lake.archive.max-members";

    /**
     * The S3 store a table on {@code s3://} is reached at when a call names none ({@code s3.endpoint}, {@code s3.region},
     * {@code s3.path-style}, {@code s3.read-block-kb}): undo, retention, check and the readers use them as the loader does its
     * {@code storage-options}; credentials come from the AWS default chain, never from options.
     */
    public static final String S3_ENDPOINT = "lake.s3.endpoint";
    public static final String S3_REGION = "lake.s3.region";
    public static final String S3_PATH_STYLE = "lake.s3.path-style";
    public static final String S3_READ_BLOCK_KB = "lake.s3.read-block-kb";

    /** Every key above (a named lake's settings, {@code lake.<name>.*}, are not options). */
    private static final java.util.Set<String> KEYS = java.util.Set.of(HOME, CONFIG_DIR, DATA_DIR, CONNECTORS_DIR, LAKES_DIR, TMP_DIR,
            ROW_GROUP_KB, ROW_GROUP_MB, PAGE_KB, PAGE_INDEX, FOOTER_CACHE_MB, DELETE_CACHE_ROWS, MANIFEST_CACHE_BYTES, LOADER_TRACE,
            ARCHIVE_MAX_MB, ARCHIVE_MAX_RATIO, ARCHIVE_MAX_MEMBERS, S3_ENDPOINT, S3_REGION, S3_PATH_STYLE, S3_READ_BLOCK_KB, "lake.settings.file", "lake.repo.root", "lake.module.root");

    /** Whether a key is one of the product's options (not a named lake's setting). */
    public static boolean isOption(String key) {
        return KEYS.contains(key);
    }

    private final Function<String, String> source;
    private final boolean process;

    private LakeOptions(Function<String, String> source, boolean process) {
        this.source = source;
        this.process = process;
    }

    /** Options read from the process when asked: the {@code -D} system property, then its environment variable. */
    public static LakeOptions fromProcess() {
        return new LakeOptions(LakeOptions::processValue, true);
    }

    /** Options of exactly the values set; nothing is read from the process. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Every option these options name, by key (a loader's child process is started with them): the {@code lake.*} system properties of
     * the process's options, else the values set.
     */
    public Map<String, String> given() {
        Map<String, String> out = new java.util.TreeMap<>();
        if (process) {
            for (String k : System.getProperties().stringPropertyNames()) {
                if (KEYS.contains(k) && value(k) != null) {
                    out.put(k, value(k));
                }
            }
        } else {
            for (String k : KEYS) {
                if (value(k) != null) {
                    out.put(k, value(k));
                }
            }
        }
        return out;
    }

    /** Whether these options read the process. */
    public boolean readsProcess() {
        return process;
    }

    /** One setting's value as text, stripped; null when nothing names it. */
    public String value(String key) {
        String v = source.apply(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** The install folder as named, or null. */
    public String homeSetting() {
        return value(HOME);
    }

    /** The temporary base as named, or null. */
    public String tmpDirSetting() {
        return value(TMP_DIR);
    }

    /** A Parquet row group's target size in bytes. */
    public long rowGroupBytes() {
        String mb = value(ROW_GROUP_MB);
        return mb != null ? Long.parseLong(mb) * 1024 * 1024 : number(ROW_GROUP_KB, 256) * 1024;
    }

    /** A Parquet page's target size in bytes. */
    public long pageBytes() {
        return number(PAGE_KB, 64) * 1024;
    }

    /** Whether a Delta read uses the Parquet page index. */
    public boolean parquetPageIndex() {
        String v = value(PAGE_INDEX);
        return v == null || Boolean.parseBoolean(v);
    }

    /** The Parquet footer cache's size in bytes (at least 1 MB). */
    public long footerCacheBytes() {
        return Math.max(1, number(FOOTER_CACHE_MB, 64)) << 20;
    }

    /** The Iceberg delete-file cache's size in rows. */
    public long icebergDeleteCacheRows() {
        return number(DELETE_CACHE_ROWS, 5_000_000);
    }

    /** The Iceberg manifest cache's size in bytes. */
    public long icebergManifestCacheBytes() {
        return number(MANIFEST_CACHE_BYTES, 512L << 20);
    }

    /** Whether a failed load prints its stack trace. */
    public boolean loaderTrace() {
        return Boolean.parseBoolean(value(LOADER_TRACE));
    }

    /** The S3 settings these options give ({@code s3.endpoint}, ... from {@code lake.s3.*}), only those set; a call's own win over them. */
    public Map<String, String> s3() {
        Map<String, String> out = new java.util.TreeMap<>();
        for (String k : java.util.List.of(S3_ENDPOINT, S3_REGION, S3_PATH_STYLE, S3_READ_BLOCK_KB)) {
            String v = value(k);
            if (v != null) {
                out.put(k.substring("lake.".length()), v);
            }
        }
        return out;
    }

    /** These options as a lookup of the archive caps by key (null when unset). */
    public Function<String, String> lookup() {
        return this::value;
    }

    private long number(String key, long dflt) {
        String v = value(key);
        return v == null ? dflt : Long.parseLong(v);
    }

    private static String processValue(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            v = System.getenv(SettingsFile.envName(key));
        }
        return v;
    }

    /** Builds options of exactly the values set. */
    public static final class Builder {
        private final Map<String, String> values = new LinkedHashMap<>();

        private Builder() {
        }

        /** Any setting by its key. */
        public Builder set(String key, String value) {
            values.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        /** The install folder, absolute. */
        public Builder home(Path home) {
            return set(HOME, absolute(home, HOME));
        }

        /** The data folder, absolute. */
        public Builder dataDir(Path dir) {
            return set(DATA_DIR, absolute(dir, DATA_DIR));
        }

        /** The configuration folder, absolute. */
        public Builder configDir(Path dir) {
            return set(CONFIG_DIR, absolute(dir, CONFIG_DIR));
        }

        /** The temporary folder's base, absolute. */
        public Builder tempDir(Path dir) {
            return set(TMP_DIR, absolute(dir, TMP_DIR));
        }

        public LakeOptions build() {
            Map<String, String> set = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                if (v != null) {
                    set.put(k, v);
                }
            });
            Map<String, String> copy = Map.copyOf(set);
            return new LakeOptions(copy::get, false);
        }

        private static String absolute(Path p, String key) {
            if (p == null || !p.isAbsolute()) {
                throw new IllegalArgumentException(key + " must be an absolute path, got " + p);
            }
            return p.normalize().toString();
        }
    }
}
