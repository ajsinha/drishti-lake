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
package com.ash.lake.iceberg.table;

import com.ash.lake.io.read.Problems;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the Iceberg reader words a failed read (docs/GUIDE.md, the read API; QA2-22, QA3B-03): a table moved or copied after it was written (its
 * metadata still names the old folder) is said plainly with what to do, for Admin → Health the folders and the {@code relocate} pair to
 * copy, for the reader the failure is thrown to the same in words without a folder (a host's error detail shows no path); any other
 * failure is {@link Problems#describe}. {@link Problems} keeps one problem per table and place and logs one line when it appears or
 * changes, never a stack trace.
 */
final class TableProblems implements Problems.Classifier {

    private static final int MAX_REASON = 2000;
    /**
     * A file a table's metadata names, split into the table's folder and the file under it: at the LAST {@code /metadata/} or {@code /data/}
     * of the path (an Iceberg table keeps its files in those two folders), never the first, so a lake or install under any folder named
     * data ({@code /data/lake}, {@code /var/lib/drishti/data}) is read whole.
     */
    static final Pattern PATH = Pattern.compile("((?:file:|s3a?://|gs://|abfss?://|/)\\S*)/(?:metadata|data)/([^/\\s]+(?:/[^\\s]*)?)");

    /** Where a table is now (a path-based lake: {@code <root>/<domain>/<table>}), or null when that is not known (a catalog). */
    private final Function<String, String> whereNow;
    /** The connector's {@code relocate} (none when not set). */
    private final com.ash.lake.iceberg.io.Relocation relocate;

    TableProblems(Function<String, String> whereNow, com.ash.lake.iceberg.io.Relocation relocate) {
        this.whereNow = whereNow == null ? t -> null : whereNow;
        this.relocate = relocate == null ? com.ash.lake.iceberg.io.Relocation.none() : relocate;
    }

    /**
     * For a lake's settings ({@code root}, {@code domain}, {@code table.<kind>}, {@code relocate}, a catalog): where a path-based lake's
     * tables are now; a catalog ({@code uri}, {@code catalog: rest}, S3 Tables) says where its tables are, so no folder is suggested there.
     */
    static TableProblems of(Map<String, String> settings) {
        String root = settings.getOrDefault("root", "").replaceAll("/+$", "");
        String domain = settings.getOrDefault("domain", "").replaceAll("^/+|/+$", "");
        boolean catalog = !settings.getOrDefault("uri", "").isBlank() || "rest".equalsIgnoreCase(settings.getOrDefault("catalog", ""))
                || com.ash.lake.iceberg.catalog.S3Tables.named(settings);
        Map<String, String> names = LakeTables.Named.of(settings);
        Function<String, String> now = catalog || root.isBlank() ? t -> null
                : t -> root + (domain.isBlank() ? "" : "/" + domain) + "/" + names.getOrDefault(t, t);
        com.ash.lake.iceberg.io.Relocation r;
        try {
            r = com.ash.lake.iceberg.io.Relocation.parse(settings.getOrDefault("relocate", ""));
        } catch (IllegalArgumentException e) {
            r = com.ash.lake.iceberg.io.Relocation.none();          // refused when the lake was opened: never reached
        }
        return new TableProblems(now, r);
    }

    /** Whether {@code e} says the table names a file that is not there: the table was moved or copied, or its files removed. */
    static boolean moved(Throwable e) {
        return missing(e).isPresent();
    }

    @Override
    public String reason(String table, Throwable e) {
        Optional<String> missing = missing(e);
        if (missing.isEmpty()) {
            return Problems.describe(e);
        }
        Matcher m = PATH.matcher(missing.get());
        if (!m.find()) {
            return shorten("a file its metadata names is not there (" + missing.get() + "): the table was moved or its files removed");
        }
        String old = m.group(1);
        String shownOld = com.ash.lake.iceberg.io.Relocation.shown(old);
        if (relocate.targets(old)) {
            // the read was relocated, and the files are not in the new folder either
            return shorten("relocate " + relocate + " sends its reads to " + shownOld + ", and the files are not there either (" + tail(m.group(2))
                    + "): check the new folder in relocate");
        }
        String now = whereNow.apply(table);
        // the pair to copy: the folders where the two differ, in the plain form the setting reads (QA3B-04)
        String pair = now == null ? null : com.ash.lake.iceberg.io.Relocation.suggest(old, now).orElse(null);
        String given = relocate.isEmpty() ? "" : " (relocate " + relocate + " is set, but no old folder in it holds " + shownOld + ")";
        return shorten("its metadata names files under " + shownOld + " that are not there (" + tail(m.group(2))
                + "): the table was moved or copied after it was written. Write it again where it is now, or read it with "
                + "relocate: '" + (pair == null ? shownOld + " -> <where it is now>" : pair) + "'" + given);
    }

    /** A moved table in words without a folder (the reason, with the folders and the pair, is in the read problems: Admin → Health). */
    @Override
    public String forReaders(String table, Throwable e) {
        if (!moved(e)) {
            return null;
        }
        return "the table " + table + " names files that are not there (it was moved or copied after it was written, or its files were removed); "
                + "Admin → Health says where they were and the relocate that reads it";
    }

    /** The path of a file that is not there, when {@code e} is (or is caused by) a file not found. */
    static Optional<String> missing(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.nio.file.NoSuchFileException n && n.getFile() != null) {
                return Optional.of(n.getFile());
            }
            boolean notFound = c instanceof java.io.FileNotFoundException || c.getClass().getSimpleName().equals("NotFoundException");
            if (notFound && c.getMessage() != null) {
                Matcher m = PATH.matcher(c.getMessage());
                if (m.find()) {
                    return Optional.of(m.group());
                }
            }
        }
        return Optional.empty();
    }

    private static String tail(String rest) {
        int slash = rest.lastIndexOf('/');
        return slash < 0 ? rest : rest.substring(slash + 1);
    }

    private static String shorten(String s) {
        String one = s.replace('\n', ' ');
        return one.length() <= MAX_REASON ? one : one.substring(0, MAX_REASON - 1) + "…";
    }
}
