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
package com.ash.lake.io.read;

import com.ash.lake.read.ColumnAsk;
import com.ash.lake.read.ColumnBlock;
import com.ash.lake.read.DataFile;
import com.ash.lake.read.DocumentField;
import com.ash.lake.read.DayFiles;
import com.ash.lake.read.DocumentSink;
import com.ash.lake.read.IdMap;
import com.ash.lake.read.RowSink;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.read.VectorRequest;
import com.ash.lake.read.VectorSink;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.Expr;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * One version of a foreign table read through its shape (docs/DESIGN.md, shaped reads): every read written once over a store's {@link ForeignSource}.
 * A date's files are the files that may hold rows of it, in path order; an inexact file's rows of other dates are skipped. An id held by
 * two files of a date is the first file's.
 */
public final class ShapedSnapshot implements TableSnapshot {

    /** One date's files: indexes into the version's files, and whether each holds that date alone. */
    private record Day(List<ForeignSource.File> files, boolean[] exact) {}

    private final String name;
    private final ForeignSource.Version version;
    private final ShapedTable shape;
    private final ExecutorService pool;
    private final Problems problems;
    private final Columns columns;
    private final Map<String, String> promoted;
    private final Set<String> vectors;
    private final NavigableMap<LocalDate, Day> days = new TreeMap<>();
    private final boolean deletes;

    /** Lists the version's files and places each under its dates (a file without stated dates is read for them). */
    public ShapedSnapshot(String name, ForeignSource.Version version, ShapedTable shape, ExecutorService pool, Problems problems) throws IOException {
        this.name = name;
        this.version = version;
        this.shape = shape;
        this.pool = pool;
        this.problems = problems;
        List<Column> cols = new ArrayList<>();
        Set<String> vs = new LinkedHashSet<>();
        for (ShapeField f : shape.columns) {
            cols.add(new Column(f.name(), typeOf(f)));
            if (f.vector() && !shape.shape.exclude().contains(f.name())) {
                vs.add(f.name());
            }
        }
        this.columns = Columns.of(cols);
        this.vectors = Collections.unmodifiableSet(vs);
        Map<String, String> p = new LinkedHashMap<>();
        shape.searchable.keySet().forEach(k -> p.put(k, k));
        this.promoted = Collections.unmodifiableMap(p);
        List<ForeignSource.File> files = new ArrayList<>(version.files());
        files.sort(Comparator.comparing(f -> f.file().path()));
        boolean dels = false;
        List<Map<LocalDate, Boolean>> dates = perFile(files.size(), i -> {
            Map<LocalDate, Boolean> d = shape.datesOf(files.get(i));
            return d != null ? d : read(files.get(i));
        });
        Map<LocalDate, List<ForeignSource.File>> byDate = new TreeMap<>();
        Map<LocalDate, List<Boolean>> exact = new TreeMap<>();
        for (int i = 0; i < files.size(); i++) {
            dels |= files.get(i).file().deletes();
            for (Map.Entry<LocalDate, Boolean> e : dates.get(i).entrySet()) {
                byDate.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(files.get(i));
                exact.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        byDate.forEach((d, fs) -> {
            boolean[] ex = new boolean[fs.size()];
            for (int i = 0; i < ex.length; i++) {
                ex[i] = exact.get(d).get(i);
            }
            days.put(d, new Day(List.copyOf(fs), ex));
        });
        this.deletes = dels;
    }

    /** A file's own dates, from its date column (each inexact; a file of one date is exact). */
    private Map<LocalDate, Boolean> read(ForeignSource.File f) throws IOException {
        Map<LocalDate, Boolean> out = new TreeMap<>();
        version.read(f, new int[] {shape.dateColumn}, Map.of(), r -> {
            LocalDate d = shape.dateOf(r[0]);
            if (d != null) {
                out.put(d, false);
            }
        });
        if (out.size() == 1) {
            out.replaceAll((k, v) -> true);
        }
        return out;
    }

    static ColumnType typeOf(ShapeField f) {
        return switch (f.kind()) {
            case BOOLEAN -> ColumnType.BOOLEAN;
            case INT -> ColumnType.INT;
            case LONG -> ColumnType.LONG;
            case FLOAT, DOUBLE, DECIMAL -> ColumnType.DOUBLE;
            case DATE -> ColumnType.DATE;
            case TIMESTAMP, TIMESTAMPTZ -> ColumnType.TIMESTAMP;
            case STRING, UUID -> ColumnType.STRING;
            case BINARY -> ColumnType.BINARY;
            case LIST -> f.vector() ? (f.children().get(0).kind() == ShapeField.Kind.FLOAT ? ColumnType.FLOAT_LIST : ColumnType.DOUBLE_LIST)
                    : !f.children().isEmpty() && f.children().get(0).kind() == ShapeField.Kind.STRING ? ColumnType.STRING_LIST : ColumnType.OTHER;
            default -> ColumnType.OTHER;
        };
    }

    // ---------------------------------------------------------------------------------------------------------------- reading

    @FunctionalInterface
    private interface PerFile<T> {
        T read(int file) throws IOException;
    }

    /** Each of {@code n} files read on its own virtual thread, the answers in file order. */
    private <T> List<T> perFile(int n, PerFile<T> read) throws IOException {
        List<Future<T>> running = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int f = i;
            running.add(pool.submit(() -> read.read(f)));
        }
        List<T> out = new ArrayList<>();
        try {
            for (Future<T> f : running) {
                out.add(f.get());
            }
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw c instanceof RuntimeException re ? re : new IllegalStateException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        return out;
    }

    private Day dayOf(LocalDate date) {
        Day d = days.get(date);
        return d == null ? new Day(List.of(), new boolean[0]) : d;
    }

    /**
     * The rows of file {@code f} of {@code day} of {@code date}: the top-level columns {@code cols} (more after them when the date must be
     * checked), rows of other dates skipped.
     */
    private void scan(LocalDate date, Day day, int f, int[] cols, Map<Integer, Object> equal, ForeignSource.Rows sink) throws IOException {
        boolean check = !day.exact()[f] && shape.dateColumn >= 0 && !LocalDate.MIN.equals(date);
        int[] read = cols;
        int dateSlot = -1;
        if (check) {
            for (int i = 0; i < cols.length && dateSlot < 0; i++) {
                if (cols[i] == shape.dateColumn) {
                    dateSlot = i;
                }
            }
            if (dateSlot < 0) {
                read = Arrays.copyOf(cols, cols.length + 1);
                read[cols.length] = shape.dateColumn;
                dateSlot = cols.length;
            }
        }
        int ds = dateSlot;
        version.read(day.files().get(f), read, equal, r -> {
            if (!check || date.equals(shape.dateOf(r[ds]))) {
                sink.row(r);
            }
        });
    }

    private <T> T guarded(LocalDate date, Problems.Read<T> body) {
        return problems.read(name, date, version.version(), body);
    }

    private static int[] slots(int n) {
        int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            s[i] = i;
        }
        return s;
    }

    /** {@code first} then {@code more} (each once), with each one's place in the combined list. */
    private static int[] concat(int[] first, int[] more, int[] placeOfMore) {
        List<Integer> all = new ArrayList<>();
        for (int c : first) {
            all.add(c);
        }
        for (int i = 0; i < more.length; i++) {
            int at = all.indexOf(more[i]);
            if (at < 0) {
                at = all.size();
                all.add(more[i]);
            }
            placeOfMore[i] = at;
        }
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---------------------------------------------------------------------------------------------------------------- the API

    @Override
    public long version() {
        return version.version();
    }

    @Override
    public Instant committed() {
        return version.committed();
    }

    @Override
    public long sequence() {
        return version.sequence();
    }

    @Override
    public Columns columns() {
        return columns;
    }

    /** The documents' fields (nested ones included) with the id and date roles. */
    @Override
    public List<DocumentField> documentFields() {
        List<DocumentField> out = new ArrayList<>();
        for (int c : shape.documentColumns) {
            ShapeField f = shape.columns.get(c);
            boolean id = false;
            for (int i : shape.idColumns) {
                id |= i == c;
            }
            out.add(describe(shape.shape.nameOf(f.name()), f, id, c == shape.dateColumn));
        }
        return out;
    }

    private static DocumentField describe(String name, ShapeField f, boolean id, boolean date) {
        List<DocumentField> kids = new ArrayList<>();
        for (ShapeField k : f.children()) {
            kids.add(describe(k.name(), k, false, false));
        }
        return new DocumentField(name, f.kind().name().toLowerCase(java.util.Locale.ROOT), kids, id, date);
    }

    /** The id's columns by their names in the document, joined by commas. */
    @Override
    public String idColumn() {
        List<String> out = new ArrayList<>();
        for (int c : shape.idColumns) {
            out.add(shape.shape.nameOf(shape.columns.get(c).name()));
        }
        return String.join(",", out);
    }

    /** Every searchable path (to itself): the paths {@link #columns(LocalDate, List)} answers. */
    @Override
    public Map<String, String> promoted() {
        return promoted;
    }

    @Override
    public Set<String> vectorColumns() {
        return vectors;
    }

    @Override
    public boolean deletes() {
        return deletes;
    }

    /** The business dates; a table without dates has one, {@link LocalDate#MIN}. */
    @Override
    public NavigableSet<LocalDate> dates() {
        return Collections.unmodifiableNavigableSet(new TreeSet<>(days.keySet()));
    }

    @Override
    public Optional<DayFiles> day(LocalDate date) {
        Day d = days.get(date);
        if (d == null) {
            return Optional.empty();
        }
        List<DataFile> out = new ArrayList<>();
        for (int i = 0; i < d.files().size(); i++) {
            DataFile f = d.files().get(i).file();
            out.add(d.exact()[i] ? f : f.inexact());
        }
        return Optional.of(DayFiles.of(date, out));
    }

    @Override
    public IdMap ids(LocalDate date) {
        return guarded(date, () -> {
            Day d = dayOf(date);
            int[] slots = slots(shape.idColumns.length);
            List<List<String>> perFile = perFile(d.files().size(), f -> {
                List<String> ids = new ArrayList<>();
                scan(date, d, f, shape.idColumns, Map.of(), r -> ids.add(shape.idOf(r, slots)));
                return ids;
            });
            Map<String, Integer> first = new HashMap<>();
            for (int f = 0; f < perFile.size(); f++) {
                for (String id : perFile.get(f)) {
                    first.putIfAbsent(id, f);
                }
            }
            String[] ids = first.keySet().toArray(new String[0]);
            Arrays.sort(ids);
            int[] files = new int[ids.length];
            for (int i = 0; i < ids.length; i++) {
                files[i] = first.get(ids[i]);
            }
            return IdMap.of(ids, files);
        });
    }

    @Override
    public Optional<String> document(LocalDate date, int file, String id) {
        return guarded(date, () -> {
            Day d = dayOf(date);
            Optional<Object[]> values = shape.parse(id);
            if (file < 0 || file >= d.files().size() || values.isEmpty()) {
                return Optional.<String>empty();
            }
            Map<Integer, Object> equal = shape.equal(values.get());
            if (!shape.mayHold(d.files().get(file), equal)) {
                return Optional.<String>empty();
            }
            int[] docs = shape.documentRead();
            int[] idSlots = new int[shape.idColumns.length];
            int[] read = concat(docs, shape.idColumns, idSlots);
            int[] docSlots = slots(docs.length);
            String[] found = new String[1];
            scan(date, d, file, read, equal, r -> {
                if (found[0] == null && id.equals(shape.idOf(r, idSlots))) {
                    found[0] = shape.document(r, docSlots);
                }
            });
            return Optional.ofNullable(found[0]);
        });
    }

    @Override
    public void documents(LocalDate date, DocumentSink sink) {
        guarded(date, () -> {
            Day d = dayOf(date);
            int[] docs = shape.documentRead();
            int[] idSlots = new int[shape.idColumns.length];
            int[] read = concat(docs, shape.idColumns, idSlots);
            int[] docSlots = slots(docs.length);
            Set<String> seen = new HashSet<>();
            for (int f = 0; f < d.files().size(); f++) {
                scan(date, d, f, read, Map.of(), r -> {
                    String id = shape.idOf(r, idSlots);
                    if (seen.add(id)) {
                        String doc = shape.document(r, docSlots);
                        if (doc != null) {
                            sink.document(id, doc);
                        }
                    }
                });
            }
            return null;
        });
    }

    private ShapedTable.Path path(String path, String column) {
        ShapedTable.Path p = shape.searchable.get(path);
        if (p == null && column != null) {
            p = shape.searchable.get(column);
        }
        if (p == null) {
            throw new IllegalArgumentException("no searchable path " + path + " in " + name + " (it has " + shape.searchable.keySet() + ")");
        }
        return p;
    }

    @Override
    public ColumnBlock columns(LocalDate date, List<ColumnAsk> asks) {
        List<ShapedTable.Path> where = new ArrayList<>();
        int[] tops = new int[asks.size()];
        for (int i = 0; i < asks.size(); i++) {
            where.add(path(asks.get(i).path(), asks.get(i).column()));
            tops[i] = where.get(i).column();
        }
        return guarded(date, () -> {
            Day d = dayOf(date);
            int[] at = new int[asks.size()];
            int[] read = concat(shape.idColumns, tops, at);
            int[] idSlots = slots(shape.idColumns.length);
            List<List<Object[]>> perFile = perFile(d.files().size(), f -> {
                List<Object[]> rows = new ArrayList<>();
                scan(date, d, f, read, Map.of(), r -> {
                    Object[] out = new Object[asks.size() + 1];
                    out[0] = shape.idOf(r, idSlots);
                    for (int i = 0; i < asks.size(); i++) {
                        ShapedTable.Path p = where.get(i);
                        Object v = ShapedTable.at(r, at[i], p);
                        out[i + 1] = v == null ? null : asks.get(i).numeric() ? (Object) number(v) : ShapedDocument.text(p.field(), v);
                    }
                    rows.add(out);
                });
                return rows;
            });
            List<Object[]> all = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            perFile.forEach(rows -> rows.forEach(r -> {
                if (seen.add((String) r[0])) {
                    all.add(r);
                }
            }));
            String[] ids = new String[all.size()];
            Map<String, double[]> numbers = new LinkedHashMap<>();
            Map<String, String[]> texts = new LinkedHashMap<>();
            for (int i = 0; i < asks.size(); i++) {
                if (asks.get(i).numeric()) {
                    numbers.put(asks.get(i).path(), new double[all.size()]);
                } else {
                    texts.put(asks.get(i).path(), new String[all.size()]);
                }
            }
            for (int r = 0; r < all.size(); r++) {
                Object[] row = all.get(r);
                ids[r] = (String) row[0];
                for (int i = 0; i < asks.size(); i++) {
                    if (asks.get(i).numeric()) {
                        numbers.get(asks.get(i).path())[r] = row[i + 1] == null ? Double.NaN : (Double) row[i + 1];
                    } else {
                        texts.get(asks.get(i).path())[r] = (String) row[i + 1];
                    }
                }
            }
            return new ColumnBlock(ids, numbers, texts);
        });
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private int vectorColumn(String column) {
        int at = columns.indexOf(column);
        if (at < 0 || !shape.columns.get(at).vector()) {
            throw new IllegalArgumentException("no vector column " + column + " in " + name + " (it has " + vectors + ")");
        }
        return at;
    }

    /** A list of numbers into {@code buf[0]} (grown when short): its length, -1 for null. */
    private static int copy(Object v, double[][] buf) {
        if (!(v instanceof List<?> l)) {
            return -1;
        }
        if (buf[0].length < l.size()) {
            buf[0] = new double[l.size()];
        }
        for (int i = 0; i < l.size(); i++) {
            buf[0][i] = l.get(i) instanceof Number n ? n.doubleValue() : Double.NaN;
        }
        return l.size();
    }

    @Override
    public void vectors(VectorRequest request, VectorSink sink) {
        LocalDate date = request.date();
        int column = vectorColumn(request.column());
        List<ShapedTable.Path> texts = new ArrayList<>();
        int[] tops = new int[request.texts().size() + 1];
        tops[0] = column;
        for (int i = 0; i < request.texts().size(); i++) {
            texts.add(path(request.texts().get(i), null));
            tops[i + 1] = texts.get(i).column();
        }
        guarded(date, () -> {
            Day d = dayOf(date);
            List<DataFile> files = day(date).map(DayFiles::files).orElse(List.of());
            int[] at = new int[tops.length];
            int[] read = concat(shape.idColumns, tops, at);
            int[] idSlots = slots(shape.idColumns.length);
            double[][] buf = {new double[0]};
            String[] out = new String[texts.size()];
            Set<String> seen = new HashSet<>();
            for (int f = 0; f < d.files().size(); f++) {
                if (!request.keep().test(files.get(f))) {
                    continue;
                }
                scan(date, d, f, read, Map.of(), r -> {
                    String id = shape.idOf(r, idSlots);
                    if (!seen.add(id)) {
                        return;
                    }
                    int len = copy(r[at[0]], buf);
                    for (int i = 0; i < out.length; i++) {
                        Object v = ShapedTable.at(r, at[i + 1], texts.get(i));
                        out[i] = v == null ? null : ShapedDocument.text(texts.get(i).field(), v);
                    }
                    sink.row(id, buf[0], len, out);
                });
            }
            return null;
        });
    }

    @Override
    public Map<String, Map<String, double[]>> vectorsOf(LocalDate date, int file, Set<String> ids, List<String> cols) {
        int[] tops = new int[cols.size()];
        for (int i = 0; i < tops.length; i++) {
            tops[i] = vectorColumn(cols.get(i));
        }
        return guarded(date, () -> {
            Day d = dayOf(date);
            if (file < 0 || file >= d.files().size() || cols.isEmpty() || ids.isEmpty()) {
                return Map.<String, Map<String, double[]>>of();
            }
            int[] at = new int[tops.length];
            int[] read = concat(shape.idColumns, tops, at);
            int[] idSlots = slots(shape.idColumns.length);
            Map<Integer, Object> equal = Map.of();
            if (ids.size() == 1) {
                Optional<Object[]> v = shape.parse(ids.iterator().next());
                if (v.isEmpty()) {
                    return Map.<String, Map<String, double[]>>of();
                }
                equal = shape.equal(v.get());
            }
            Map<String, Map<String, double[]>> out = new HashMap<>();
            scan(date, d, file, read, equal, r -> {
                String id = shape.idOf(r, idSlots);
                if (!ids.contains(id) || out.containsKey(id)) {
                    return;
                }
                Map<String, double[]> vs = new LinkedHashMap<>();
                for (int c = 0; c < cols.size(); c++) {
                    double[][] one = {new double[0]};
                    int len = copy(r[at[c]], one);
                    if (len >= 0) {
                        vs.put(cols.get(c), Arrays.copyOf(one[0], len));
                    }
                }
                out.put(id, vs);
            });
            return out;
        });
    }

    @Override
    public void scanFile(LocalDate date, int file, List<String> wanted, Expr where, RowSink sink) {
        List<String> all = columns.names();
        int[] pick = new int[wanted.size()];
        for (int i = 0; i < pick.length; i++) {
            pick[i] = all.indexOf(wanted.get(i));
            if (pick[i] < 0) {
                throw new IllegalArgumentException("no column " + wanted.get(i) + " in " + name + " (it has " + all + ")");
            }
        }
        guarded(date, () -> {
            Day d = dayOf(date);
            if (file < 0 || file >= d.files().size()) {
                return null;
            }
            Object[] out = new Object[pick.length];
            scan(date, d, file, slots(all.size()), Map.of(), r -> {
                Object[] v = new Object[r.length];
                for (int i = 0; i < v.length; i++) {
                    v[i] = plain(shape.columns.get(i), r[i]);
                }
                if (where.test(new com.ash.lake.spi.Row(columns, v))) {
                    for (int i = 0; i < pick.length; i++) {
                        out[i] = v[pick[i]];
                    }
                    sink.row(out);
                }
            });
            return null;
        });
    }

    /** A value as the other stores' scans give it: a date as a LocalDate, a timestamp as an Instant, a decimal as a double. */
    private static Object plain(ShapeField f, Object v) {
        if (v == null) {
            return null;
        }
        return switch (f.kind()) {
            case DATE -> v instanceof Number n ? LocalDate.ofEpochDay(n.longValue()) : v;
            case TIMESTAMP, TIMESTAMPTZ -> v instanceof Long n ? Instant.ofEpochSecond(Math.floorDiv(n, 1_000_000L), Math.floorMod(n, 1_000_000L) * 1000)
                    : v;
            case DECIMAL -> v instanceof BigDecimal b ? b.doubleValue() : v;
            default -> v;
        };
    }
}
