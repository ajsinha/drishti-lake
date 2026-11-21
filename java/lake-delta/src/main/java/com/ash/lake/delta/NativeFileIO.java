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
package com.ash.lake.delta;

import com.ash.lake.spi.FileEntry;
import com.ash.lake.io.LocalStorage;
import com.ash.lake.io.NativeCodecs;
import com.ash.lake.io.ParquetInput;
import com.ash.lake.io.S3Settings;
import com.ash.lake.io.S3Storage;
import com.ash.lake.io.StorageRoutes;
import com.ash.lake.spi.Storage;
import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.InputFile;
import io.delta.kernel.defaults.engine.fileio.OutputFile;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Kernel's file access without Hadoop: local paths through {@link LocalStorage}, {@code s3://} and {@code s3a://}
 * through {@link S3Storage}. Read-only: Drishti never writes a Delta table (its writers are Python, on delta-rs), so
 * every write is refused with {@link UnsupportedOperationException}. Thread-safe; the S3 client is made on first use.
 */
public final class NativeFileIO implements FileIO {

    /** The message of every refused write. */
    static final String READ_ONLY = "the native Delta engine only reads: the server does not write Delta tables (its loaders use delta-rs)";

    private final StorageRoutes routes;
    private final Map<String, String> conf;
    private final NativeCodecs codecs;

    /** The outbound proxy of the S3 endpoint. */
    public com.ash.tools.net.ProxySettings proxy() {
        return routes.s3Settings().proxy();
    }

    /** The TLS material of the S3 endpoint, or null. */
    public com.ash.tools.net.TlsMaterial tls() {
        return routes.s3Settings().tls();
    }

    /**
     * @param runtime the runtime whose storages ({@code mem://}), options and caches this reads with
     * @param s3Settings how to reach S3 when a path asks for it
     * @param conf Kernel's tuning keys ({@code delta.kernel.default.parquet.reader.batch-size}, …), as for Hadoop
     */
    public NativeFileIO(com.ash.lake.LakeRuntime runtime, S3Settings s3Settings, Map<String, String> conf) {
        this(runtime, new LocalStorage(), s3Settings, conf);
    }

    NativeFileIO(com.ash.lake.LakeRuntime runtime, LocalStorage local, S3Settings s3Settings, Map<String, String> conf) {
        this.routes = new StorageRoutes(runtime, local, s3Settings, "the native Delta engine");
        this.conf = Map.copyOf(conf);
        this.codecs = NativeCodecs.of(this.conf.get(NativeCodecs.LZ4_DECODER), "the native Delta engine",
                "rewrite the date with Snappy or ZSTD (tools/lake/maintain.py relayout --force --dates <date>)");
    }

    /** The Parquet page decompressors this engine reads with (the LZ4 decoder follows the {@code lz4-decoder} setting). */
    NativeCodecs codecs() {
        return codecs;
    }

    /** Whether the native engine reads paths of this scheme with no provider registered ({@code file}, {@code s3}, {@code s3a}, {@code s3n}). */
    public static boolean supports(String path) {
        return StorageRoutes.supports(path);
    }

    /** Whether this reads {@code path}: the built-in schemes and those of the runtime's providers ({@code mem}). */
    public boolean reads(String path) {
        return routes.reads(path);
    }

    /** The runtime this reads with. */
    public com.ash.lake.LakeRuntime runtime() {
        return routes.runtime();
    }

    /** The storage that serves {@code path}. */
    public Storage storage(String path) {
        return routes.storage(path);
    }

    /** Releases the S3 client, if one was made. */
    public void close() {
        routes.close();
    }

    /** Kernel's form of a shared-module file entry. */
    static FileStatus kernel(FileEntry e) {
        return FileStatus.of(e.path(), e.size(), e.modified());
    }

    @Override
    public CloseableIterator<FileStatus> listFrom(String filePath) throws IOException {
        return Utils.toCloseableIterator(storage(filePath).listFrom(filePath).stream().map(NativeFileIO::kernel).iterator());
    }

    @Override
    public FileStatus getFileStatus(String path) throws IOException {
        return kernel(storage(path).status(path));
    }

    @Override
    public String resolvePath(String path) throws IOException {
        return storage(path).qualify(path);
    }

    @Override
    public InputFile newInputFile(String path, long fileSize) {
        // Kernel names a file it has not listed with size 0 (_last_checkpoint): ask the store, so a missing object is a
        // FileNotFoundException, as on local disk, never an empty file Kernel retries for seconds
        return new Streams.KernelFile(storage(path), path, fileSize > 0 ? fileSize : -1);
    }

    /** Parquet's view of the same file. */
    org.apache.parquet.io.InputFile newParquetFile(String path, long fileSize) {
        return ParquetInput.of(storage(path), path, fileSize);
    }

    @Override
    public Optional<String> getConf(String confKey) {
        return Optional.ofNullable(conf.get(confKey));
    }

    @Override
    public boolean mkdirs(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public OutputFile newOutputFile(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public boolean delete(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void copyFileAtomically(String srcPath, String destPath, boolean overwrite) {
        throw new UnsupportedOperationException(READ_ONLY);
    }
}
