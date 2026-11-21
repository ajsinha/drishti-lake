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
package bench;

import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The read API's performance proof (READ_API.md section 7): the hot reads of one table timed and their allocation counted, the same
 * body over two sides: the reference (Drishti's jars at the freeze, through the same-package shims of tools/split/bench) and this
 * build (the shims over this build's table classes, or the read API). Run by tools/bench/bench.py; prints one JSON line per measure.
 *
 * <pre>java -cp ... bench.ReadBench SIDE-CLASS ROOT DOMAIN TABLE DATE [warmups] [runs]</pre>
 */
public final class ReadBench {

    /** One side's reads of one table's one date (the shims and the read API implement it). */
    public interface Reads extends AutoCloseable {
        /** Opens the table at its latest version; the date is the one every read below reads. */
        void open(String root, String domain, String table, LocalDate date) throws Exception;

        /** Builds the date's id map; its size. */
        int ids() throws Exception;

        /** The ids of the last map, in order. */
        String[] idList();

        /** The file of {@code id} in the last map, or -1. */
        int fileOf(String id);

        /** Every row's vector of {@code column} with {@code texts}, {@code decoders} files at once. */
        void vectors(String column, List<String> texts, int decoders, Sink sink) throws Exception;

        /** The promoted columns (path to whether numeric); the rows read. */
        int columns(Map<String, Boolean> paths) throws Exception;

        /** One document by id. */
        String document(int file, String id) throws Exception;

        /** A page of ids' vectors from one file: how many found. */
        int vectorsOf(int file, Set<String> ids, List<String> columns) throws Exception;

        @Override
        void close() throws Exception;
    }

    /** The host's sink's four arguments. */
    public static final class Sink {
        long rows;
        double sum;

        public void row(String id, double[] values, int length, String[] texts) {
            rows++;
            if (length > 0) {
                sum += values[0] + values[length - 1];
            }
        }
    }

    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private record Run(double ms, long bytes) {}

    /**
     * Bytes allocated so far by every thread this JVM ran: the live threads' counters, and for a thread that ended its last counter seen
     * by a sampler polling every millisecond (the decoding threads of a parallel read live for one read; the last millisecond of each is
     * missed, so the parallel figures are a floor, the one-decoder figures exact).
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> SEEN = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long[] SAMPLER_ID = {-1};

    static {
        THREADS.setThreadAllocatedMemoryEnabled(true);
        Thread t = new Thread(() -> {
            while (true) {
                poll();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "bench-allocation-sampler");
        t.setDaemon(true);
        SAMPLER_ID[0] = t.threadId();
        t.start();
    }

    private static synchronized long poll() {
        long[] ids = THREADS.getAllThreadIds();
        long[] bytes = THREADS.getThreadAllocatedBytes(ids);
        for (int i = 0; i < ids.length; i++) {
            if (bytes[i] >= 0 && ids[i] != SAMPLER_ID[0]) {
                SEEN.merge(ids[i], bytes[i], Math::max);
            }
        }
        long sum = 0;
        for (long b : SEEN.values()) {
            sum += b;
        }
        return sum;
    }


    static long allocated() {
        return poll();
    }

    private interface Body {
        long run() throws Exception;     // rows (or items) done
    }

    private static int warmups = 3;
    private static int runs = 7;

    public static void main(String[] args) throws Exception {
        String[] cls = args[0].split(":", 2);         // a class, or a class and its one argument (the read API's store)
        Reads side = cls.length == 1 ? (Reads) Class.forName(cls[0]).getDeclaredConstructor().newInstance()
                : (Reads) Class.forName(cls[0]).getDeclaredConstructor(String.class).newInstance(cls[1]);
        String root = args[1], domain = args[2], table = args[3];
        LocalDate date = LocalDate.parse(args[4]);
        if (args.length > 5) {
            warmups = Integer.parseInt(args[5]);
        }
        if (args.length > 6) {
            runs = Integer.parseInt(args[6]);
        }
        side.open(root, domain, table, date);
        List<String> texts = List.of("DESK", "TT_BOOK", "LEGAL_ENTITY");
        Map<String, Boolean> cols = new LinkedHashMap<>();
        cols.put("QUANTITY", true);
        cols.put("BASE_PV", true);
        cols.put("TRADE_ID", false);
        cols.put("DESK", false);
        cols.put("TT_BOOK", false);
        cols.put("CCY", false);

        long[] n = new long[1];
        measure(side, "ids", () -> n[0] = side.ids());
        String[] ids = side.idList();
        Random r = new Random(7);
        String[] probe = new String[10_000];
        for (int i = 0; i < probe.length; i++) {
            probe[i] = r.nextInt(10) == 0 ? "ABSENT-" + i : ids[r.nextInt(ids.length)];
        }
        measure(side, "ids.lookup-10000", () -> {
            long found = 0;
            for (String id : probe) {
                found += side.fileOf(id) >= 0 ? 1 : 0;
            }
            return found;
        });
        for (int decoders : new int[] {1, 4}) {
            measure(side, "vectors." + decoders, () -> {
                Sink s = new Sink();
                side.vectors("SCENARIO_PV", texts, decoders, s);
                return s.rows;
            });
        }
        measure(side, "columns", () -> side.columns(cols));
        String[] docIds = new String[1_000];
        for (int i = 0; i < docIds.length; i++) {
            docIds[i] = ids[r.nextInt(ids.length)];
        }
        documents(side, docIds);
        Set<String> page = new LinkedHashSet<>();
        int pageFile = side.fileOf(ids[ids.length / 2]);
        for (int i = 0; i < ids.length && page.size() < 50; i += 7) {
            if (side.fileOf(ids[i]) == pageFile) {
                page.add(ids[i]);
            }
        }
        measure(side, "vectorsOf.50", () -> side.vectorsOf(pageFile, page, List.of("SCENARIO_PV")));
        side.close();
    }

    private static void measure(Reads side, String name, Body body) throws Exception {
        for (int i = 0; i < warmups; i++) {
            body.run();
        }
        List<Run> done = new ArrayList<>();
        long rows = 0;
        for (int i = 0; i < runs; i++) {
            long b0 = allocated();
            long t0 = System.nanoTime();
            rows = body.run();
            long t1 = System.nanoTime();
            long b1 = allocated();
            done.add(new Run((t1 - t0) / 1e6, b1 - b0));
        }
        double[] ms = done.stream().mapToDouble(Run::ms).sorted().toArray();
        long[] bytes = done.stream().mapToLong(Run::bytes).sorted().toArray();
        double med = ms[ms.length / 2];
        long medBytes = bytes[bytes.length / 2];
        System.out.printf(java.util.Locale.ROOT, "{\"measure\":\"%s\",\"ms\":%.3f,\"min_ms\":%.3f,\"rows\":%d,\"rows_per_s\":%.0f,"
                + "\"bytes\":%d,\"bytes_per_row\":%.1f}%n", name, med, ms[0], rows, rows / (med / 1000.0), medBytes,
                rows == 0 ? 0.0 : (double) medBytes / rows);
    }

    private static void documents(Reads side, String[] docIds) throws Exception {
        for (int w = 0; w < warmups; w++) {
            for (String id : docIds) {
                side.document(side.fileOf(id), id);
            }
        }
        long[] us = new long[docIds.length * runs];
        int k = 0;
        long b0 = allocated();
        for (int w = 0; w < runs; w++) {
            for (String id : docIds) {
                long t0 = System.nanoTime();
                if (side.document(side.fileOf(id), id) == null) {
                    throw new IllegalStateException("no document for " + id);
                }
                us[k++] = (System.nanoTime() - t0) / 1000;
            }
        }
        long b1 = allocated();
        Arrays.sort(us);
        System.out.printf(java.util.Locale.ROOT, "{\"measure\":\"document\",\"p50_us\":%d,\"p99_us\":%d,\"bytes_per_doc\":%d}%n",
                us[us.length / 2], us[(int) (us.length * 0.99)], (b1 - b0) / us.length);
    }
}
