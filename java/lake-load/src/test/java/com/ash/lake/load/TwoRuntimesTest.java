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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.lake.LakeOptions;
import com.ash.lake.LakeRuntime;
import com.ash.lake.common.TempFiles;
import com.ash.lake.config.Lakes;
import com.ash.lake.delta.NativeEngine;
import com.ash.lake.delta.TableReader;
import com.ash.lake.iceberg.io.FileAccess;
import com.ash.lake.io.ParquetFooters;
import com.ash.lake.spi.FileEntry;
import com.ash.lake.spi.LakeCatalog;
import com.ash.lake.spi.RangeReader;
import com.ash.lake.spi.Storage;
import com.ash.lake.spi.StorageProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two runtimes in one JVM are apart (design step L6, LK-6): each sees only the storages, catalog, options and caches registered with it.
 * Two loads run at the same time, one per runtime; each runtime's reads fill its own footer cache; a scheme one runtime registers is
 * unknown to the other; a lake one runtime's catalog ships is not the other's; each keeps its own temporary folder.
 */
class TwoRuntimesTest {

    @TempDir
    Path tmp;

    /** A storage of one scheme that answers nothing: enough to see which runtime knows it. */
    record Scheme(String scheme) implements StorageProvider {
        @Override
        public Storage storage(String path) {
            return new Storage() {
                @Override
                public String qualify(String p) {
                    return p;
                }

                @Override
                public List<FileEntry> listFrom(String p) {
                    return List.of();
                }

                @Override
                public FileEntry status(String p) {
                    return new FileEntry(p, 0, 0);
                }

                @Override
                public RangeReader open(String p, long knownLength) {
                    return new RangeReader() {
                        @Override
                        public long length() {
                            return 0;
                        }

                        @Override
                        public int read(long position, ByteBuffer dst) {
                            return -1;
                        }

                        @Override
                        public void close() {
                        }
                    };
                }

                @Override
                public List<String> directories(String p) {
                    return List.of();
                }

                @Override
                public boolean isDirectory(String p) {
                    return false;
                }
            };
        }

        @Override
        public boolean memoryOnly() {
            return true;
        }
    }

    /** A host that ships one lake. */
    static LakeCatalog shipping(String name) {
        return new LakeCatalog() {
            @Override
            public List<String> shippedLakes() {
                return List.of(name);
            }

            @Override
            public InputStream shippedLake(String n) {
                return n.equals(name) ? new ByteArrayInputStream(("lake: " + name + "\nroot: /x/" + name + "\n").getBytes(StandardCharsets.UTF_8)) : null;
            }
        };
    }

    private LakeRuntime runtime(String home, LakeCatalog catalog, StorageProvider... storages) throws Exception {
        Path h = Files.createDirectories(tmp.resolve(home));
        return LakeRuntime.builder()
                .options(LakeOptions.builder().home(h).tempDir(h.resolve("tmp")).set(LakeOptions.FOOTER_CACHE_MB, home.equals("a") ? "8" : "16").build())
                .catalog(catalog).storage(storages).build();
    }

    @Test
    void twoRuntimesInOneJvmSeeOnlyWhatEachWasGiven() throws Exception {
        LakeRuntime a = runtime("a", shipping("only-a"), new Scheme("memx"));
        LakeRuntime b = runtime("b", LakeCatalog.NONE);

        // storages: a scheme registered with one runtime is unknown to the other
        try (FileAccess ia = FileAccess.local(a); FileAccess ib = FileAccess.local(b)) {
            assertThat(ia.reads("memx://feeds/t")).isTrue();
            assertThat(ib.reads("memx://feeds/t")).isFalse();
            assertThat(ia.storage("memx://feeds/t").isDirectory("memx://feeds/t")).isFalse();
            assertThatThrownBy(() -> ib.storage("memx://feeds/t")).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(a.memoryOnly("memx://feeds/t")).isTrue();
        assertThat(b.memoryOnly("memx://feeds/t")).isFalse();
        assertThatThrownBy(() -> LakeRuntime.builder().storage(new Scheme("s3")))
                .as("the built-in schemes cannot be claimed").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LakeRuntime.builder().storage(new Scheme("memx"), new Scheme("memx")))
                .as("nor one scheme twice").isInstanceOf(IllegalArgumentException.class);

        // catalogs: a lake one host ships is not the other's
        assertThat(Lakes.current(a).find("only-a")).isPresent();
        assertThat(Lakes.current(b).find("only-a")).isEmpty();

        // options and caches: each runtime its own footer cache, sized by its own options, and its own temporary folder
        assertThat(ParquetFooters.of(a)).isSameAs(ParquetFooters.of(a)).isNotSameAs(ParquetFooters.of(b));
        assertThat(TempFiles.of(a).root()).startsWith(tmp.resolve("a")).isNotEqualTo(TempFiles.of(b).root());

        // two loads at once, one in each runtime; each runtime's reads fill its own cache only
        Path in = Files.createDirectories(tmp.resolve("in"));
        PvData.write(in.resolve("pv.2026-10-27.jsonl"), PvData.lines(40, 4, 1, 7));
        LakeRuntime[] rts = {a, b};
        AtomicIntegerArray codes = new AtomicIntegerArray(2);
        Thread[] loads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int n = i;
            Path cfg = Files.writeString(tmp.resolve("load" + n + ".yaml"), PvData.config(in + "/*", tmp.resolve("lake" + n).toString(), "delta"));
            loads[n] = Thread.ofVirtual().start(() -> codes.set(n, LakeLoad.run(rts[n], new String[] {"--config", cfg.toString(), "-q"},
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))));
        }
        for (Thread t : loads) {
            t.join();
        }
        assertThat(codes.get(0)).isZero();
        assertThat(codes.get(1)).isZero();
        String table;
        try (var s = Files.walk(tmp.resolve("lake0"))) {
            table = s.filter(p -> p.getFileName().toString().equals("_delta_log")).findFirst().orElseThrow().getParent().toString();
        }
        long before = ParquetFooters.of(b).weightBytes();
        try (NativeEngine engine = NativeEngine.create(a, Map.of())) {
            assertThat(TableReader.rows(engine, table, null)).isNotEmpty();
        }
        assertThat(ParquetFooters.of(a).weightBytes()).as("a's read filled a's cache").isPositive();
        assertThat(ParquetFooters.of(b).weightBytes()).as("and not b's").isEqualTo(before);
    }
}
