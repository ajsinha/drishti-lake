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
package com.ash.lake.read;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The product's one id-map file format: {@code LKIDMAP1}, the count, the files (int each), the ids' end offsets (int each) and the ids'
 * UTF-8 bytes. Where the files live, how long and how many is the host's: it gives the path.
 */
public final class IdMaps {

    private static final byte[] MAGIC = "LKIDMAP1".getBytes(StandardCharsets.US_ASCII);

    private IdMaps() {
    }

    /** Writes {@code map} to {@code file} (whole or not at all: a temporary file moved into place) and maps it. */
    public static MappedIdMap write(IdMap map, Path file) throws IOException {
        int n = map.size();
        byte[][] ids = new byte[n][];
        long blob = 0;
        for (int i = 0; i < n; i++) {
            ids[i] = map.id(i).getBytes(StandardCharsets.UTF_8);
            blob += ids[i].length;
        }
        long total = MAGIC.length + 4L + 8L * n + blob;
        if (total > Integer.MAX_VALUE) {
            throw new IOException("an id map of " + n + " ids is too large for one file: " + total + " bytes");
        }
        ByteBuffer b = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        b.put(MAGIC).putInt(n);
        for (int i = 0; i < n; i++) {
            b.putInt(map.file(map.id(i)));
        }
        int end = 0;
        for (byte[] id : ids) {
            end += id.length;
            b.putInt(end);
        }
        for (byte[] id : ids) {
            b.put(id);
        }
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, file.getFileName().toString(), ".part");
        try {
            Files.write(tmp, b.array());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return open(file);
    }

    /** Maps an id-map file written by {@link #write}. */
    public static MappedIdMap open(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            ByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[MAGIC.length];
            m.get(0, magic);
            if (size < MAGIC.length + 4 || !java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException(file + " is not an id-map file");
            }
            return new Mapped(m, m.getInt(MAGIC.length), size);
        }
    }

    private static final class Mapped implements MappedIdMap {
        private final ByteBuffer m;
        private final int n;
        private final long size;
        private final int files;
        private final int ends;
        private final int blob;
        private volatile boolean closed;

        Mapped(ByteBuffer m, int n, long size) {
            this.m = m;
            this.n = n;
            this.size = size;
            this.files = MAGIC.length + 4;
            this.ends = files + 4 * n;
            this.blob = ends + 4 * n;
        }

        private void open() {
            if (closed) {
                throw new IllegalStateException("the id map was closed");
            }
        }

        @Override
        public int size() {
            return n;
        }

        @Override
        public String id(int i) {
            open();
            int from = i == 0 ? 0 : m.getInt(ends + 4 * (i - 1));
            int to = m.getInt(ends + 4 * i);
            byte[] b = new byte[to - from];
            m.get(blob + from, b);
            return new String(b, StandardCharsets.UTF_8);
        }

        @Override
        public int file(String id) {
            open();
            int lo = 0, hi = n - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = id(mid).compareTo(id);
                if (c < 0) {
                    lo = mid + 1;
                } else if (c > 0) {
                    hi = mid - 1;
                } else {
                    return m.getInt(files + 4 * mid);
                }
            }
            return -1;
        }

        @Override
        public long bytes() {
            return 64;
        }

        @Override
        public long fileBytes() {
            return size;
        }

        @Override
        public boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
