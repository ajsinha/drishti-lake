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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Start on few cores (design section 4, the TCK's area of that name; moved from the host's test of its connectors' start): in a JVM
 * whose virtual threads share one carrier thread, eight callers on virtual threads each load a table at the same time, Delta and
 * Iceberg alternately, the first touch of every holder (the storage providers, the codecs, the catalogs) among them. A wait that pins
 * the one carrier (a class initialisation another caller holds, a monitor held across a park) hangs the JVM; the test then fails
 * with the hung JVM's threads in its message.
 */
class StartOnFewCoresTest {

    private static final int CALLERS = 8;

    /** The forked JVM: {@code <work folder>}; prints how many loads succeeded. */
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        List<Thread> callers = new ArrayList<>();
        AtomicInteger loaded = new AtomicInteger();
        for (int i = 0; i < CALLERS; i++) {
            int n = i;
            Path in = Files.createDirectories(work.resolve("in" + n));
            PvData.write(in.resolve("pv.2026-10-27.jsonl"), PvData.lines(30, 4, 1, n + 1));
            String yaml = PvData.config(in + "/*", work.resolve("lake" + n).toString(), n % 2 == 0 ? "delta" : "iceberg");
            Path cfg = Files.writeString(work.resolve("load" + n + ".yaml"), yaml);
            callers.add(Thread.ofVirtual().name("caller-" + n).unstarted(() -> {
                ByteArrayOutputStream said = new ByteArrayOutputStream();
                int code = LakeLoad.run(new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(said, true, StandardCharsets.UTF_8));
                if (code == 0) {
                    loaded.incrementAndGet();
                } else {
                    System.out.println("caller " + n + " exit " + code + ": " + said.toString(StandardCharsets.UTF_8));
                }
            }));
        }
        callers.forEach(Thread::start);
        for (Thread t : callers) {
            t.join();
        }
        System.out.println("LOADED " + loaded.get());
        System.exit(0);
    }

    @Test
    void eightLoadsAtOnceFinishWithOneCarrierThread(@TempDir Path dir) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path work = Files.createDirectories(dir.resolve("work"));
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx1g", "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1", "-Djava.io.tmpdir=" + Files.createDirectories(dir.resolve("tmp"))));
        for (String key : List.of(com.ash.lake.LakeOptions.HOME, com.ash.lake.LakeOptions.DATA_DIR)) {
            String v = System.getProperty(key);
            if (v != null) {
                cmd.add("-D" + key + "=" + v);
            }
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), StartOnFewCoresTest.class.getName(), work.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve("out.txt").toFile()).start();
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) {                                                 // the threads of the hung JVM, for the failure message
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
            new ProcessBuilder(jcmd.toString(), Long.toString(p.pid()), "Thread.dump_to_file", dir.resolve("threads.txt").toString())
                    .redirectErrorStream(true).redirectOutput(dir.resolve("jcmd.txt").toFile()).start().waitFor(30, TimeUnit.SECONDS);
            p.destroyForcibly().waitFor();
        }
        String out = Files.readString(dir.resolve("out.txt"))
                + (Files.exists(dir.resolve("threads.txt")) ? Files.readString(dir.resolve("threads.txt")) : "");
        assertThat(done).as("eight loads finished within 120 s with one carrier thread (a hang is a start-up deadlock)\n" + out).isTrue();
        assertThat(out).contains("LOADED " + CALLERS);
    }
}
