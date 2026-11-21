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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The guards of design 7.1 that read the modules: the dependency allow-list and official libraries only (both fail), and, in report
 * mode (written to {@code lake-guards/target/guard-reports}, never failing until the step named), the public surface, relative paths
 * and configuration writes.
 */
class ModuleRulesTest {

    private static final Path REPORTS = Path.of(System.getProperty("lake.guard.reports", "target/guard-reports")).toAbsolutePath();

    /** The table formats' own files and entries: only delta-kernel, iceberg-core and Parquet's libraries name them. */
    private static final Pattern HAND_FORMAT = Pattern.compile(
            "d\\{20\\}|%020d|\"PAR1\"|\"commitInfo\"|\"metaData\"|\"minReaderVersion\"|\"manifest-list\"|\"snapshot-id\"");

    private static List<String> resource(String name) {
        try (InputStream in = ModuleRulesTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<Path> modules() {
        try (var s = Files.list(Sources.JAVA)) {
            return s.filter(p -> p.getFileName().toString().startsWith("lake-") && Files.isRegularFile(p.resolve("pom.xml"))).sorted().toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A module's direct dependencies outside test scope, as "module group:artifact". */
    static Set<String> declared(Path module) {
        String pom = Sources.read(module.resolve("pom.xml")).replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "")
                .replaceAll("(?s)<parent>.*?</parent>", "").replaceAll("(?s)<!--.*?-->", "");
        Set<String> out = new TreeSet<>();
        Matcher d = Pattern.compile("(?s)<dependency>(.*?)</dependency>").matcher(pom);
        while (d.find()) {
            String body = d.group(1);
            String scope = find(body, "scope");
            if (!"test".equals(scope)) {
                out.add(module.getFileName() + " " + find(body, "groupId") + ":" + find(body, "artifactId"));
            }
        }
        return out;
    }

    private static String find(String body, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void everyModuleDependsOnlyOnWhatTheAllowListNames() {
        Set<String> allowed = new TreeSet<>();
        resource("dependencies.txt").forEach(l -> {
            String[] w = l.split("\\s+");
            allowed.add(w[0] + " " + w[1]);
        });
        Set<String> declared = new TreeSet<>();
        modules().forEach(m -> declared.addAll(declared(m)));
        Set<String> extra = new TreeSet<>(declared);
        extra.removeAll(allowed);
        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(declared);
        assertTrue(extra.isEmpty(), "dependencies outside the allow-list (lake-guards dependencies.txt, design section 6): " + extra);
        assertTrue(stale.isEmpty(), "listed but no longer declared (remove them: the list only shrinks): " + stale);
        assertTrue(declared.stream().noneMatch(d -> d.contains("com.ash.drishti") || d.contains("springframework")), "no host, no Spring");
    }

    @Test
    void noMainSourceWritesOrParsesATableFormatByHand() {
        Map<String, String> listed = new TreeMap<>();
        resource("official-libraries.txt").forEach(l -> listed.put(l.split("\\s+")[0], l));
        Set<String> hits = new TreeSet<>();
        for (Path f : Sources.files(Sources.JAVA, ".java")) {
            String rel = Sources.JAVA.relativize(f).toString().replace('\\', '/');
            if (rel.contains("/src/main/") && HAND_FORMAT.matcher(Sources.read(f)).find()) {
                hits.add(rel);
            }
        }
        Set<String> unlisted = new TreeSet<>(hits);
        unlisted.removeAll(listed.keySet());
        Set<String> stale = new TreeSet<>(listed.keySet());
        stale.removeAll(hits);
        assertTrue(unlisted.isEmpty(), "a table format handled by hand, not through delta-kernel, iceberg-core or Parquet: " + unlisted);
        assertTrue(stale.isEmpty(), "listed in official-libraries.txt but clean now (remove the line): " + stale);
    }

    /** Report mode until L12: public types per package and library types named in public lines (the public packages: ApiSurfaceTest). */
    @Test
    void reportThePublicSurface() throws IOException {
        Pattern type = Pattern.compile("(?m)^public (?:final |abstract |sealed |non-sealed )*(class|interface|record|enum|@interface) (\\w+)");
        Pattern leak = Pattern.compile("(?m)^\\s*public [^=;{]*\\b(io\\.delta\\.kernel|org\\.apache\\.iceberg|org\\.apache\\.parquet)\\.");
        List<String> out = new ArrayList<>();
        int types = 0;
        int leaks = 0;
        for (Path f : Sources.files(Sources.JAVA, ".java")) {
            String rel = Sources.JAVA.relativize(f).toString().replace('\\', '/');
            if (!rel.contains("/src/main/")) {
                continue;
            }
            String src = Sources.read(f);
            Matcher m = type.matcher(src);
            String pkg = rel.substring(rel.indexOf("/java/") + 6, rel.lastIndexOf('/')).replace('/', '.');
            while (m.find()) {
                out.add("type " + pkg + "." + m.group(2) + " " + m.group(1));
                types++;
            }
            Matcher l = leak.matcher(src);
            while (l.find()) {
                out.add("library-type-in-public-signature " + rel + " " + l.group().strip());
                leaks++;
            }
        }
        out.add(0, "# public types: " + types + "; library types named in public lines of the internal packages (the public packages: ApiSurfaceTest fails on one; the module boundary closes the rest at L12): " + leaks);
        write("public-surface.txt", out);
    }

    /** Report mode until L6 (explicit paths) and L5 (writes no configuration). */
    @Test
    void reportRelativePathsAndConfigurationWrites() throws IOException {
        Pattern relative = Pattern.compile("(?:Path\\.of|Paths\\.get)\\(\"(?!/)[^\"]*\"");
        Pattern configWrite = Pattern.compile("Files\\.(?:write|writeString|newBufferedWriter|newOutputStream)\\([^;]*(?:yaml|yml)");
        List<String> out = new ArrayList<>();
        for (Path f : Sources.files(Sources.JAVA, ".java")) {
            String rel = Sources.JAVA.relativize(f).toString().replace('\\', '/');
            if (!rel.contains("/src/main/")) {
                continue;
            }
            String[] lines = Sources.read(f).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (relative.matcher(lines[i]).find()) {
                    out.add("relative-path " + rel + ":" + (i + 1) + " " + lines[i].strip());
                }
                if (configWrite.matcher(lines[i]).find()) {
                    out.add("configuration-write " + rel + ":" + (i + 1) + " " + lines[i].strip());
                }
            }
        }
        out.add(0, "# relative path literals and configuration writes in main code (report mode: L5, L6 make them fail)");
        write("paths-and-configuration.txt", out);
        assertEquals(out.get(0).charAt(0), '#');
    }

    private static void write(String name, List<String> lines) throws IOException {
        Files.createDirectories(REPORTS);
        Files.write(REPORTS.resolve(name), lines, StandardCharsets.UTF_8);
    }
}
