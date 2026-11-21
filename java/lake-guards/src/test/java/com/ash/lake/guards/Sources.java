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
package com.ash.lake.guards;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The repository's files as the guards read them, and the numbers SPLIT_EXEMPTIONS.md records. */
final class Sources {

    /** The repository's root: the build names it outright (lake.repo.root), never the folder the JVM started in. */
    static final Path REPO = Path.of(System.getProperty("lake.repo.root", "../..")).toAbsolutePath().normalize();
    static final Path JAVA = REPO.resolve("java");
    static final Path EXEMPTIONS = JAVA.resolve("SPLIT_EXEMPTIONS.md");

    private Sources() {
    }

    /** Every file under {@code root} with one of the suffixes, build output left out. */
    static List<Path> files(Path root, String... suffixes) {
        try (Stream<Path> w = Files.walk(root)) {
            return w.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/.mvn/"))
                    .filter(p -> Stream.of(suffixes).anyMatch(s -> p.getFileName().toString().endsWith(s)))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The whole number SPLIT_EXEMPTIONS.md records after {@code "- <key>: "}. */
    static int recorded(String key) {
        Matcher m = Pattern.compile("(?m)^- " + Pattern.quote(key) + ": (\\d+)\\s*$").matcher(read(EXEMPTIONS));
        if (!m.find()) {
            throw new AssertionError("SPLIT_EXEMPTIONS.md records no \"- " + key + ": <n>\" line");
        }
        return Integer.parseInt(m.group(1));
    }

    /** Whether {@code p} is a copied host type (package com.ash.lake.hostcompat). */
    static boolean hostcompat(Path p) {
        return p.toString().replace('\\', '/').contains("/com/ash/lake/hostcompat/");
    }
}
