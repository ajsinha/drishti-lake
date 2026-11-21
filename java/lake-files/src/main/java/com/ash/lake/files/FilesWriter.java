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
package com.ash.lake.files;

import com.ash.lake.spi.LoadRow;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The loader's writer into the files store: each writer thread's rows go to a part file beside the day
 * ({@code <table>.jsonl.<name>.part}), one line per row ({@code {"domain", "kind", "id", "date", "doc"}}); the commit joins the parts
 * in the loader's order (after the day's present lines, for an append) into {@code <table>.jsonl.tmp} and moves it onto the day file in
 * one atomic rename, so a reader sees the old day or the new one. A date a replace leaves empty has no day file after the commit.
 */
final class FilesWriter implements TableWriter {

    private final WriteSpec spec;
    private final Path domainDir;
    private final List<String> leftOut = new ArrayList<>();

    FilesWriter(WriteSpec spec, Path root) {
        this.spec = spec;
        this.domainDir = root.resolve(spec.domain());
    }

    @Override
    public void open(LoadColumns columns) {
        leftOut.addAll(spec.partition());
        leftOut.addAll(columns.promoted());
        leftOut.addAll(spec.vectors());
    }

    @Override
    public void begin(LocalDate date) {
    }

    private Path day(LocalDate date) {
        return domainDir.resolve(date.toString()).resolve(spec.table() + ".jsonl");
    }

    @Override
    public FileOut newFile(LocalDate date, String[] partitionValues, String name) throws IOException {
        Path day = day(date);
        Files.createDirectories(day.getParent());
        Path part = day.resolveSibling(day.getFileName() + "." + name.replaceAll("[^A-Za-z0-9._-]", "_") + ".part");
        byte[] head = ("{\"domain\":" + quote(spec.domain()) + ",\"kind\":" + quote(spec.table()) + ",\"id\":").getBytes(StandardCharsets.UTF_8);
        byte[] dated = (",\"date\":\"" + date + "\",\"doc\":").getBytes(StandardCharsets.UTF_8);
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
        return new FileOut() {
            private long rows;
            private long bytes;

            @Override
            public void write(LoadRow row) throws IOException {
                byte[] id = quote(new String(row.id(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                byte[] doc = row.doc();
                out.write(head);
                out.write(id);
                out.write(dated);
                out.write(doc);
                out.write('}');
                out.write('\n');
                bytes += head.length + id.length + dated.length + doc.length + 2;
                rows++;
            }

            @Override
            public long length() {
                return bytes;
            }

            @Override
            public WrittenFile finish() throws IOException {
                out.close();
                return new WrittenFile(part.toString(), rows, bytes, part, partitionValues, List.of(), List.of());
            }
        };
    }

    private static String quote(String s) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(s)) + "\"";
    }

    @Override
    public long commit(LocalDate date, List<WrittenFile> files) throws IOException {
        Path day = day(date);
        if (files.isEmpty()) {
            if (!spec.appends()) {
                Files.deleteIfExists(day);
            }
            return 0;
        }
        Path tmp = day.resolveSibling(day.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            if (spec.appends() && Files.isRegularFile(day)) {
                Files.copy(day, out);
            }
            for (WrittenFile f : files) {
                Files.copy((Path) f.handle(), out);
            }
        }
        Files.move(tmp, day, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        for (WrittenFile f : files) {
            Files.deleteIfExists((Path) f.handle());
        }
        return 0;
    }

    @Override
    public boolean appended(LocalDate date) {
        return false;
    }

    @Override
    public Map<String, String> columnTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", "string");
        out.put("business_date", "date");
        out.put("doc", "string");
        return out;
    }

    @Override
    public List<String> leftOut() {
        return leftOut;
    }

    @Override
    public void close() {
    }
}
