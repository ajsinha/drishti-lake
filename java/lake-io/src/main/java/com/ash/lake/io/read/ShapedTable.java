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

import com.ash.lake.common.LakeError;
import com.ash.lake.common.LakeException;
import com.ash.lake.read.TableShape;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A shape resolved against one version's columns: the one rule of RA-S8 (READ_API.md) for every store. Which columns make the id, the
 * date, the document and the searchable paths; a file's dates (its partition's day when that is the business day, else its date column's
 * bounds: one day exact, a span under 400 days every day inexact, else the column read); a row's id and document; an id parsed back into
 * the id columns' values for pruning. A shape the table cannot answer is refused in words ({@code LK-1001}).
 */
public final class ShapedTable {

    /** A searchable path: its top-level column, the places within nested structs, and its field. */
    public record Path(int column, int[] within, String[] names, ShapeField field) {}

    final TableShape shape;
    final String table;
    final List<ShapeField> columns;
    final int[] idColumns;
    final int dateColumn;
    final boolean fileNameDates;
    final int documentColumn;
    final int[] documentColumns;
    final Map<String, Path> searchable;
    final ShapedDocument.Options options;

    private ShapedTable(TableShape shape, String table, List<ShapeField> columns, int[] ids, int date, boolean fileNameDates, int doc, int[] docs,
            Map<String, Path> searchable) {
        this.shape = shape;
        this.table = table;
        this.columns = columns;
        this.idColumns = ids;
        this.dateColumn = date;
        this.fileNameDates = fileNameDates;
        this.documentColumn = doc;
        this.documentColumns = docs;
        this.searchable = searchable;
        this.options = new ShapedDocument.Options(shape.decimalsAsText(), shape.omitBinary());
    }

    private static LakeException refused(String words) {
        return new LakeException(LakeError.BAD_REQUEST, words);
    }

    /** {@code shape} over the columns of {@code v}; refused in words when the table cannot answer it. */
    public static ShapedTable resolve(TableShape shape, ForeignSource.Version v) {
        List<ShapeField> cols = v.columns();
        String kind = shape.kind();
        List<Integer> ids = new ArrayList<>();
        if (shape.id().isEmpty()) {
            for (String c : v.identifierColumns()) {
                int at = indexOf(cols, c);
                if (at >= 0) {
                    ids.add(at);
                }
            }
            if (ids.isEmpty()) {
                throw refused(kind + " (" + shape.table() + "): no id: the table has no identifier fields; set tables." + kind + ".id");
            }
        } else {
            for (String c : shape.id()) {
                ids.add(column(shape, cols, c, "id"));
            }
        }
        int date = -1;
        boolean fromNames = false;
        if (shape.date() != null) {
            date = column(shape, cols, shape.date(), "date");
            ShapeField f = cols.get(date);
            if (!f.timestamp() && f.kind() != ShapeField.Kind.DATE) {
                throw refused(kind + ": the date column " + shape.date() + " is " + f.kind().name().toLowerCase(Locale.ROOT)
                        + ", not a date or timestamp");
            }
            if (f.timestamp() && !shape.dateFromDay()) {
                throw refused(kind + ": the date column " + shape.date() + " is a timestamp: add date-from: day");
            }
        } else {
            for (String p : v.dayPartitions()) {
                int at = indexOf(cols, p);
                if (at >= 0) {
                    date = at;
                    break;
                }
            }
            fromNames = date < 0 && v.fileNameDates();
        }
        int doc = shape.documentColumn() == null ? -1 : column(shape, cols, shape.documentColumn(), "document");
        List<Integer> docs = new ArrayList<>();
        Map<String, Path> searchable = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            ShapeField f = cols.get(i);
            if (shape.exclude().contains(f.name()) || i == doc) {
                continue;
            }
            docs.add(i);
            flatten(shape.nameOf(f.name()), i, f, new int[0], new String[0], shape.depth(), searchable);
        }
        return new ShapedTable(shape, shape.table(), List.copyOf(cols), ints(ids), date, fromNames, doc, ints(docs), searchable);
    }

    private static int[] ints(List<Integer> l) {
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int indexOf(List<ShapeField> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static void flatten(String path, int column, ShapeField f, int[] within, String[] names, int depth, Map<String, Path> out) {
        if (f.kind() == ShapeField.Kind.STRUCT) {
            if (within.length < depth - 1) {
                for (int i = 0; i < f.children().size(); i++) {
                    int[] next = Arrays.copyOf(within, within.length + 1);
                    next[within.length] = i;
                    String[] nextNames = Arrays.copyOf(names, names.length + 1);
                    nextNames[names.length] = f.children().get(i).name();
                    flatten(path + "." + f.children().get(i).name(), column, f.children().get(i), next, nextNames, depth, out);
                }
            }
        } else if (f.kind() != ShapeField.Kind.LIST && f.kind() != ShapeField.Kind.MAP) {
            out.put(path, new Path(column, within, names, f));
        }
    }

    private static int column(TableShape shape, List<ShapeField> cols, String name, String what) {
        int at = indexOf(cols, name);
        if (at < 0) {
            for (Map.Entry<String, String> r : shape.rename().entrySet()) {     // the document's name of a renamed column
                if (r.getValue().equals(name)) {
                    at = indexOf(cols, r.getKey());
                }
            }
        }
        if (at < 0) {
            throw refused(shape.kind() + ": the " + what + " column " + name + " is not a top-level column of " + shape.table());
        }
        return at;
    }

    /** Whether the table has business dates (else its one date is {@link LocalDate#MIN}). */
    public boolean dated() {
        return dateColumn >= 0 || fileNameDates;
    }

    /** A row's business date from its date column's value; null when it has none. */
    public LocalDate dateOf(Object v) {
        if (v == null || dateColumn < 0) {
            return null;
        }
        ShapeField f = columns.get(dateColumn);
        if (v instanceof String s) {                                   // a JSON line's text
            try {
                return s.length() == 10 ? LocalDate.parse(s) : OffsetDateTime.parse(s).atZoneSameInstant(shape.dateZone()).toLocalDate();
            } catch (RuntimeException e) {
                return null;
            }
        }
        long n = ((Number) v).longValue();
        return switch (f.kind()) {
            case DATE -> LocalDate.ofEpochDay(n);
            case TIMESTAMP -> LocalDate.ofEpochDay(Math.floorDiv(n, 86_400_000_000L));     // wall-clock time: its own day
            default -> Instant.ofEpochSecond(Math.floorDiv(n, 1_000_000L)).atZone(shape.dateZone()).toLocalDate();
        };
    }

    /** Whether a partition's day is the business day of each row in it. */
    private boolean partitionDayIsBusinessDay() {
        ShapeField f = columns.get(dateColumn);
        return f.kind() != ShapeField.Kind.TIMESTAMPTZ || shape.dateZone().normalized().equals(ZoneOffset.UTC);
    }

    /** The business dates {@code file} may hold, each with whether every row of it is of that date; null: read its date column. */
    public Map<LocalDate, Boolean> datesOf(ForeignSource.File file) {
        Map<LocalDate, Boolean> out = new TreeMap<>();
        if (!dated()) {
            out.put(LocalDate.MIN, true);
            return out;
        }
        if (fileNameDates) {
            Integer d = file.days().get("");
            out.put(d == null ? LocalDate.MIN : LocalDate.ofEpochDay(d), true);
            return out;
        }
        String name = columns.get(dateColumn).name();
        Integer day = file.days().get(name);
        if (day != null && partitionDayIsBusinessDay()) {
            out.put(LocalDate.ofEpochDay(day), true);
            return out;
        }
        Object[] b = file.bounds().get(name);
        if (b != null && b[0] != null && b[1] != null) {
            LocalDate lo = dateOf(b[0]);
            LocalDate hi = dateOf(b[1]);
            if (lo != null && lo.equals(hi)) {
                out.put(lo, true);
                return out;
            }
            if (lo != null && hi != null && !hi.isBefore(lo) && lo.plusDays(400).isAfter(hi)) {
                for (LocalDate d = lo; !d.isAfter(hi); d = d.plusDays(1)) {
                    out.put(d, false);
                }
                return out;
            }
        }
        return null;
    }

    /** A row's id: its id columns at {@code slots}, joined by the separator. */
    public String idOf(Object[] row, int[] slots) {
        if (slots.length == 1) {
            return ShapedDocument.text(columns.get(idColumns[0]), row[slots[0]]);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) {
                b.append(shape.idSeparator());
            }
            b.append(ShapedDocument.text(columns.get(idColumns[i]), row[slots[i]]));
        }
        return b.toString();
    }

    /** The id's parts as the id columns' values (for pruning), or empty when the id cannot be one of this table's. */
    public Optional<Object[]> parse(String id) {
        String[] parts = idColumns.length == 1 ? new String[] {id} : id.split(java.util.regex.Pattern.quote(shape.idSeparator()), -1);
        if (parts.length != idColumns.length) {
            return Optional.empty();
        }
        Object[] out = new Object[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                ShapeField f = columns.get(idColumns[i]);
                out[i] = switch (f.kind()) {
                    case STRING -> parts[i];
                    case INT -> Integer.parseInt(parts[i]);
                    case LONG -> Long.parseLong(parts[i]);
                    case DATE -> (int) LocalDate.parse(parts[i]).toEpochDay();
                    case DECIMAL -> new BigDecimal(parts[i]).setScale(f.scale());
                    case UUID -> UUID.fromString(parts[i]);
                    case BOOLEAN -> Boolean.parseBoolean(parts[i]);
                    default -> null;                         // compared as text after reading
                };
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.of(out);
    }

    /** The id columns' values as {@code equal} of a read (column index to value). */
    public Map<Integer, Object> equal(Object[] values) {
        Map<Integer, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                out.put(idColumns[i], values[i]);
            }
        }
        return out;
    }

    /** Whether {@code file}'s bounds allow every value of {@code equal}. */
    public boolean mayHold(ForeignSource.File file, Map<Integer, Object> equal) {
        for (Map.Entry<Integer, Object> e : equal.entrySet()) {
            Object[] b = file.bounds().get(columns.get(e.getKey()).name());
            if (b == null || b[0] == null || b[1] == null) {
                continue;
            }
            Integer lo = compare(b[0], e.getValue());
            Integer hi = compare(e.getValue(), b[1]);
            if (lo != null && lo > 0 || hi != null && hi > 0) {
                return false;
            }
        }
        return true;
    }

    /** {@code a} against {@code b} when they are of one comparable kind; null otherwise. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Integer compare(Object a, Object b) {
        if (a instanceof String x && b instanceof String y) {
            return Arrays.compare(x.codePoints().toArray(), y.codePoints().toArray());
        }
        if (a instanceof Number x && b instanceof Number y && !(a instanceof BigDecimal) && !(b instanceof BigDecimal)) {
            boolean whole = (a instanceof Integer || a instanceof Long) && (b instanceof Integer || b instanceof Long);
            return whole ? Long.compare(x.longValue(), y.longValue()) : Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable c) {
            return c.compareTo(b);
        }
        return null;
    }

    /** A row read with the document's columns at {@code slots} as the kind's JSON document. */
    public String document(Object[] row, int[] slots) {
        if (documentColumn >= 0) {
            Object v = row[slots[0]];
            return v == null ? null : v instanceof String s ? s : ShapedDocument.value(columns.get(documentColumn), v, options).toString();
        }
        com.fasterxml.jackson.databind.node.ObjectNode doc = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < documentColumns.length; i++) {
            ShapeField f = columns.get(documentColumns[i]);
            com.fasterxml.jackson.databind.JsonNode v = ShapedDocument.value(f, row[slots[i]], options);
            if (v != null) {
                doc.set(shape.nameOf(f.name()), v);
            }
        }
        return doc.toString();
    }

    /** The top-level columns a document is read from, in order. */
    public int[] documentRead() {
        return documentColumn >= 0 ? new int[] {documentColumn} : documentColumns;
    }

    /** The value of a searchable path in a row whose top-level column is at {@code slot}. */
    public static Object at(Object[] row, int slot, Path p) {
        Object v = row[slot];
        for (int k = 0; k < p.within().length; k++) {
            int i = p.within()[k];
            if (v instanceof Object[] struct) {
                v = i < struct.length ? struct[i] : null;
            } else if (v instanceof Map<?, ?> m) {
                v = m.get(p.names()[k]);
            } else {
                return null;
            }
        }
        return v;
    }
}
