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
package com.ash.lake.tck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * delta-rs, the other Delta implementation, for the interop tests (owner rule: 100% Delta compliant, tested both ways): runs
 * {@code tools/ci/delta_rs.py} under {@code uv run --with <pin>} with the pins of {@code tools/ci/interop-pins.txt} (from uv's cache
 * when it holds them, else fetched). Never a skip: a machine without uv fails the test that asks, naming what is missing.
 */
public final class DeltaRs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DeltaRs() {
    }

    /** The repository's root, the folder with {@code tools/ci} ({@link TestRoots#repo}, or the one above it: the Maven reactor's). */
    static Path repo() {
        Path r = Path.of(TestRoots.repo()).toAbsolutePath().normalize();
        return Files.isDirectory(r.resolve("tools/ci")) || r.getParent() == null ? r : r.getParent();
    }

    /** The pinned packages, one a line ({@code #} comments). */
    public static List<String> pins() throws IOException {
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(repo().resolve("tools/ci/interop-pins.txt"), StandardCharsets.UTF_8)) {
            if (!l.isBlank() && !l.strip().startsWith("#")) {
                out.add(l.strip());
            }
        }
        return out;
    }

    /** uv: on the PATH, else {@code ~/.local/bin/uv} (its installer's place). */
    static String uv() {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, "uv"))) {
                return Path.of(dir, "uv").toString();
            }
        }
        Path home = Path.of(System.getProperty("user.home"), ".local", "bin", "uv");
        if (Files.isExecutable(home)) {
            return home.toString();
        }
        throw new AssertionError("the Delta interop tests need uv (https://docs.astral.sh/uv/) to run delta-rs; none on the PATH or at " + home);
    }

    /** Reads {@code table} with delta-rs: {@code rows} and {@code dataset} (pyarrow.dataset), {@code adds} (add.path), {@code missing}. */
    public static JsonNode read(Path table, String... columns) throws IOException {
        return run("read", table.toString(), String.join(",", columns));
    }

    /** delta-rs appends {@code spec} ({@code {columns: {name: string|date|double|long}, rows: [...]}}) to {@code table}, partitioned by {@code partitionBy}. */
    public static void write(Path table, JsonNode spec, String... partitionBy) throws IOException {
        Path rows = Files.createTempFile(table.getParent(), "delta-rs-rows", ".json");
        try {
            JSON.writeValue(rows.toFile(), spec);
            run("write", table.toString(), rows.toString(), String.join(",", partitionBy));
        } finally {
            Files.deleteIfExists(rows);
        }
    }

    private static JsonNode run(String... args) throws IOException {
        String text = null;
        for (boolean offline : new boolean[] {true, false}) {          // from uv's cache first: no network when the pins are held
            List<String> cmd = new ArrayList<>(List.of(uv(), "run", "--quiet", "--no-project"));
            if (offline) {
                cmd.add("--offline");
            }
            for (String p : pins()) {
                cmd.addAll(List.of("--with", p));
            }
            cmd.addAll(List.of("python", "-I", repo().resolve("tools/ci/delta_rs.py").toString()));
            cmd.addAll(List.of(args));
            Path out = Files.createTempFile("delta-rs", ".out");
            Path err = Files.createTempFile("delta-rs", ".err");
            try {
                Process p = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
                boolean done;
                try {
                    done = p.waitFor(180, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                if (!done) {
                    p.destroyForcibly();
                    throw new AssertionError("delta-rs did not finish in 180 s: " + String.join(" ", cmd));
                }
                text = Files.readString(out, StandardCharsets.UTF_8).strip();
                if (p.exitValue() == 0 && !text.isEmpty()) {
                    return JSON.readTree(text.lines().reduce((a, b) -> b).orElseThrow());
                }
                text = "exit " + p.exitValue() + ": " + Files.readString(err, StandardCharsets.UTF_8).strip() + "\n" + text;
            } finally {
                Files.deleteIfExists(out);
                Files.deleteIfExists(err);
            }
        }
        throw new AssertionError("delta-rs failed (" + String.join(" ", args) + "): " + text);
    }
}
