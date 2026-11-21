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

import com.ash.lake.common.FileDates;
import com.ash.lake.io.read.ForeignSource;
import com.ash.lake.io.read.ShapeField;
import com.ash.lake.read.DataFile;
import com.ash.lake.read.FileFormat;
import com.ash.lake.read.TableIdentity;
import com.ash.lake.spi.AsOf;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.ash.lake.spi.FileEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * A folder of JSON Lines other systems wrote ({@code <root>/<domain>/<table>/}: {@code *.jsonl}, {@code *.jsonl.gz}), as the shape rule
 * reads it (READ_API.md, RA-S8). A file's date is the one its name carries ({@code <table>.<YYYY-MM-DD>.jsonl}, the loader's name rule),
 * else each line's date field. The columns are the lines' top-level fields, their kinds from the first values seen (text that is a date
 * or an instant with a zone reads as one, so a date field answers as it does in Delta and Iceberg). A plain folder keeps no versions:
 * only its present state is read.
 */
final class FilesForeign implements ForeignSource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final int SAMPLE = 1000;

    private final Place dir;

    FilesForeign(Place dir) {
        this.dir = dir;
    }

    @Override
    public String missing() {
        return "no folder " + dir + " of JSON Lines files";
    }

    @Override
    public TableIdentity identity() {
        String where = dir.path();
        return new TableIdentity("location", where, where);
    }

    private List<FileEntry> files() throws IOException {
        List<FileEntry> out = new ArrayList<>();
        for (FileEntry e : dir.storage().files(dir.path())) {
            String n = name(e);
            if (!n.startsWith(".") && (n.endsWith(".jsonl") || n.endsWith(".jsonl.gz") || n.endsWith(".json") || n.endsWith(".json.gz"))) {
                out.add(e);
            }
        }
        return out;
    }

    private static String name(FileEntry e) {
        return e.path().substring(e.path().lastIndexOf('/') + 1);
    }

    @Override
    public Optional<ForeignSource.Version> version(AsOf asOf) throws IOException {
        if (!dir.isDirectory()) {
            return Optional.empty();
        }
        if (!asOf.latest()) {
            throw new IOException(dir + " is a folder of plain files: it keeps no earlier versions (" + asOf.missing(dir.toString()) + ")");
        }
        List<FileEntry> files = files();
        long hash = 1125899906842597L;
        long newest = 0;
        for (FileEntry p : files) {
            long m = p.modified();
            hash = 31 * hash + (name(p) + ":" + p.size() + ":" + m).hashCode();
            newest = Math.max(newest, m);
        }
        return Optional.of(new Version(files, hash & Long.MAX_VALUE, newest));
    }

    private InputStream open(FileEntry p) throws IOException {
        InputStream in = dir.resolve(name(p)).in();
        return name(p).endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in;
    }

    @FunctionalInterface
    private interface Lines {
        boolean line(JsonNode n);           // false: stop
    }

    private void lines(FileEntry p, Lines sink) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(open(p), StandardCharsets.UTF_8), 1 << 16)) {
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                if (l.isBlank()) {
                    continue;
                }
                JsonNode n = JSON.readTree(l);
                if (n.isObject() && !sink.line(n)) {
                    return;
                }
            }
        }
    }

    /** A value's field: its kind from the value (text that is a date or an instant with a zone is one). */
    static ShapeField infer(String name, JsonNode v) {
        if (v.isObject()) {
            List<ShapeField> children = new ArrayList<>();
            v.fields().forEachRemaining(e -> children.add(infer(e.getKey(), e.getValue())));
            return new ShapeField(name, ShapeField.Kind.STRUCT, 0, children);
        }
        if (v.isArray()) {
            for (JsonNode e : v) {
                if (!e.isNull()) {
                    return new ShapeField(name, ShapeField.Kind.LIST, 0, List.of(infer("element", e)));
                }
            }
            return new ShapeField(name, ShapeField.Kind.LIST, 0, List.of(ShapeField.of("element", ShapeField.Kind.STRING)));
        }
        if (v.isBoolean()) {
            return ShapeField.of(name, ShapeField.Kind.BOOLEAN);
        }
        if (v.isIntegralNumber()) {
            return ShapeField.of(name, ShapeField.Kind.LONG);
        }
        if (v.isNumber()) {
            return ShapeField.of(name, ShapeField.Kind.DOUBLE);
        }
        String t = v.asText();
        if (DATE.matcher(t).matches() && date(t) != null) {
            return ShapeField.of(name, ShapeField.Kind.DATE);
        }
        if (t.length() > 10 && t.charAt(10) == 'T' && instant(t) != null) {
            return ShapeField.of(name, ShapeField.Kind.TIMESTAMPTZ);
        }
        return ShapeField.of(name, ShapeField.Kind.STRING);
    }

    private static Integer date(String t) {
        try {
            return (int) LocalDate.parse(t).toEpochDay();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long instant(String t) {
        try {
            Instant i = OffsetDateTime.parse(t).toInstant();
            return Math.multiplyExact(i.getEpochSecond(), 1_000_000L) + i.getNano() / 1000;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A JSON value in the shape rule's form, as {@code f} says where it fits, else by its own type. */
    static Object value(ShapeField f, JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                ShapeField c = null;
                for (ShapeField x : f == null ? List.<ShapeField>of() : f.children()) {
                    if (x.name().equals(e.getKey())) {
                        c = x;
                    }
                }
                out.put(e.getKey(), value(c, e.getValue()));
            }
            return out;
        }
        if (v.isArray()) {
            ShapeField e = f != null && f.kind() == ShapeField.Kind.LIST && !f.children().isEmpty() ? f.children().get(0) : null;
            List<Object> out = new ArrayList<>(v.size());
            v.forEach(x -> out.add(value(e, x)));
            return out;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            if (f != null && f.kind() == ShapeField.Kind.DOUBLE) {
                return v.doubleValue();
            }
            return v.isIntegralNumber() ? (Object) (v.canConvertToLong() ? v.longValue() : v.bigIntegerValue()) : (Object) v.doubleValue();
        }
        String t = v.asText();
        if (f != null && f.kind() == ShapeField.Kind.DATE) {
            Integer d = DATE.matcher(t).matches() ? date(t) : null;
            return d != null ? d : t;
        }
        if (f != null && f.kind() == ShapeField.Kind.TIMESTAMPTZ) {
            Long m = instant(t);
            return m != null ? m : t;
        }
        return t;
    }

    /** The folder's present state. */
    private final class Version implements ForeignSource.Version {
        private final List<FileEntry> files;
        private final long version;
        private final long newest;
        private final List<ShapeField> columns = new ArrayList<>();

        Version(List<FileEntry> files, long version, long newest) throws IOException {
            this.files = files;
            this.version = version;
            this.newest = newest;
            Map<String, ShapeField> seen = new LinkedHashMap<>();
            int[] left = {SAMPLE};
            for (FileEntry p : files) {
                if (left[0] <= 0) {
                    break;
                }
                lines(p, n -> {
                    n.fields().forEachRemaining(e -> {
                        ShapeField had = seen.get(e.getKey());
                        if (had == null || had.kind() == ShapeField.Kind.STRING && e.getValue().isNull() == false && hadOnlyNulls(had)) {
                            if (!e.getValue().isNull()) {
                                seen.put(e.getKey(), infer(e.getKey(), e.getValue()));
                            } else if (had == null) {
                                seen.put(e.getKey(), new ShapeField(e.getKey(), ShapeField.Kind.STRING, -1, List.of()));
                            }
                        }
                    });
                    return --left[0] > 0;
                });
            }
            seen.values().forEach(f -> columns.add(f.scale() == -1 ? ShapeField.of(f.name(), ShapeField.Kind.STRING) : f));
        }

        /** A field seen only as null so far (marked by scale -1 until a value shows its kind). */
        private boolean hadOnlyNulls(ShapeField f) {
            return f.scale() == -1;
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public Instant committed() {
            return Instant.ofEpochMilli(newest);
        }

        @Override
        public long sequence() {
            return newest;
        }

        @Override
        public List<ShapeField> columns() {
            return columns;
        }

        @Override
        public boolean fileNameDates() {
            for (FileEntry p : files) {
                if (FileDates.of(name(p), LocalDate.MAX) != null) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<ForeignSource.File> files() throws IOException {
            List<ForeignSource.File> out = new ArrayList<>();
            for (FileEntry p : files) {
                LocalDate d = FileDates.of(name(p), LocalDate.MAX);
                DataFile api = new DataFile(p.path(), p.size(), FileFormat.JSONL, -1, false, Map.of(), Map.of());
                out.add(new ForeignSource.File(api, d == null ? Map.of() : Map.of("", (int) d.toEpochDay()), Map.of(), p));
            }
            return out;
        }

        @Override
        public void read(ForeignSource.File file, int[] cols, Map<Integer, Object> equal, ForeignSource.Rows sink) throws IOException {
            Object[] out = new Object[cols.length];
            lines((FileEntry) file.handle(), n -> {
                for (int i = 0; i < cols.length; i++) {
                    ShapeField f = columns.get(cols[i]);
                    out[i] = value(f, n.get(f.name()));
                }
                sink.row(out);
                return true;
            });
        }
    }
}
