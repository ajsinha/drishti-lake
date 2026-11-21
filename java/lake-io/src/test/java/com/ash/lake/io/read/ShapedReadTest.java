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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.lake.common.LakeException;
import com.ash.lake.read.DataFile;
import com.ash.lake.read.FileFormat;
import com.ash.lake.read.TableIdentity;
import com.ash.lake.read.TableShape;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.AsOf;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** The shape rule of RA-S8 on a store-neutral table: dates from partitions, zones and bounds; ids; documents; refusals. */
class ShapedReadTest {

    static final ShapeField ID = ShapeField.of("id", ShapeField.Kind.LONG);
    static final ShapeField BOOK = ShapeField.of("book", ShapeField.Kind.STRING);
    static final ShapeField AT = ShapeField.of("at", ShapeField.Kind.TIMESTAMPTZ);
    static final ShapeField DAY = ShapeField.of("day", ShapeField.Kind.DATE);
    static final ShapeField PRICE = new ShapeField("price", ShapeField.Kind.DECIMAL, 2, List.of());
    static final ShapeField BLOB = ShapeField.of("blob", ShapeField.Kind.BINARY);
    static final ShapeField TAGS = new ShapeField("tags", ShapeField.Kind.MAP, 0, List.of(ShapeField.of("key", ShapeField.Kind.STRING),
            ShapeField.of("value", ShapeField.Kind.INT)));

    /** A table held in memory: files of rows, each with its days and bounds. */
    record Fake(List<ShapeField> columns, List<String> dayPartitions, List<ForeignSource.File> files, Map<String, List<Object[]>> rows)
            implements ForeignSource, ForeignSource.Version {
        @Override
        public Optional<Version> version(AsOf asOf) {
            return Optional.of(this);
        }

        @Override
        public TableIdentity identity() {
            return new TableIdentity("location", "fake", "fake");
        }

        @Override
        public long version() {
            return 1;
        }

        @Override
        public Instant committed() {
            return Instant.EPOCH;
        }

        @Override
        public long sequence() {
            return 1;
        }

        @Override
        public void read(File file, int[] cols, Map<Integer, Object> equal, Rows sink) {
            for (Object[] r : rows.get(file.file().path())) {
                Object[] out = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    out[i] = r[cols[i]];
                }
                sink.row(out);
            }
        }
    }

    static ForeignSource.File file(String path, Map<String, Integer> days, Map<String, Object[]> bounds) {
        return new ForeignSource.File(new DataFile(path, 10, FileFormat.PARQUET, -1, false, Map.of(), Map.of()), days, bounds, null);
    }

    static long micros(String instant) {
        Instant i = Instant.parse(instant);
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1000;
    }

    static TableShape shape(Map<String, String> s) {
        Map<String, String> all = new LinkedHashMap<>();
        s.forEach((k, v) -> all.put(k.equals("decimals") || k.equals("binary") ? k : "tables.t." + k, v));
        return TableShape.of("t", all);
    }

    @Test
    void aTimestampsDayInAZoneSpansTwoDatesAndIsCheckedRowByRow() throws IOException {
        List<ShapeField> cols = List.of(ID, BOOK, AT);
        Object[] a = {1L, "A", micros("2026-11-20T15:00:00Z")};
        Object[] b = {2L, "B", micros("2026-11-21T02:30:00Z")};     // 2026-11-20 in New York
        Object[] c = {3L, "C", micros("2026-11-21T14:00:00Z")};
        ForeignSource.File f = file("f1", Map.of(), Map.of("at", new Object[] {a[2], c[2]}, "id", new Object[] {1L, 3L}));
        Fake fake = new Fake(cols, List.of(), List.of(f), Map.of("f1", List.of(a, b, c)));
        TableShape shape = shape(Map.of("id", "id", "date", "at", "date-from", "day", "date-zone", "America/New_York"));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            TableSnapshot s = new ForeignTableReader("t", shape, fake, pool, new Problems("fake", Problems::describe)).snapshot(AsOf.LATEST)
                    .orElseThrow();
            assertEquals(List.of(LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-21")), List.copyOf(s.dates()));
            assertFalse(s.day(LocalDate.parse("2026-11-20")).orElseThrow().files().get(0).exact());
            assertEquals(List.of("1", "2"), List.of(s.ids(LocalDate.parse("2026-11-20")).ids()));
            assertEquals("{\"id\":3,\"book\":\"C\",\"at\":\"2026-11-21T14:00:00Z\"}", s.document(LocalDate.parse("2026-11-21"), 0, "3").orElseThrow());
            assertTrue(s.document(LocalDate.parse("2026-11-21"), 0, "1").isEmpty(), "a row of another date");
            assertTrue(s.document(LocalDate.parse("2026-11-21"), 0, "9").isEmpty(), "outside the id bounds");
            assertTrue(s.document(LocalDate.parse("2026-11-21"), 0, "x").isEmpty(), "not a number");
        }
    }

    @Test
    void aPartitionDayIsExactUnlessItIsAnotherZonesDay() {
        List<ShapeField> cols = List.of(ID, DAY, AT);
        ForeignSource.File f = file("f", Map.of("day", 20412, "at", 20412), Map.of());
        Fake fake = new Fake(cols, List.of("day"), List.of(f), Map.of());
        ShapedTable byDay = ShapedTable.resolve(shape(Map.of("id", "id")), fake);
        assertEquals(Map.of(LocalDate.ofEpochDay(20412), true), byDay.datesOf(f));
        Fake ts = new Fake(cols, List.of("at"), List.of(f), Map.of());
        ShapedTable utc = ShapedTable.resolve(shape(Map.of("id", "id")), ts);
        assertEquals(Map.of(LocalDate.ofEpochDay(20412), true), utc.datesOf(f));
        ShapedTable ny = ShapedTable.resolve(shape(Map.of("id", "id", "date-zone", "America/New_York")), ts);
        assertNull(ny.datesOf(f), "a UTC day is not New York's: the column is read");
    }

    @Test
    void compositeIdsParseBackAndValuesRenderAsTheyAreAsked() {
        List<ShapeField> cols = List.of(BOOK, ID, PRICE, BLOB, TAGS);
        Fake fake = new Fake(cols, List.of(), List.of(), Map.of());
        ShapedTable t = ShapedTable.resolve(shape(Map.of("id", "[book, id]", "id-separator", "/", "decimals", "string", "binary", "omit")), fake);
        Map<String, Integer> tags = new LinkedHashMap<>();
        tags.put("x", 1);
        Object[] row = {"B1", 7L, new BigDecimal("12.50"), new byte[] {1, 2}, tags};
        assertEquals("B1/7", t.idOf(row, new int[] {0, 1}));
        assertEquals(List.of("B1", 7L), List.of(t.parse("B1/7").orElseThrow()));
        assertTrue(t.parse("B1").isEmpty());
        assertEquals("{\"book\":\"B1\",\"id\":7,\"price\":\"12.50\",\"tags\":{\"x\":1}}", t.document(row, new int[] {0, 1, 2, 3, 4}));
        ShapedTable plain = ShapedTable.resolve(shape(Map.of("id", "[book, id]")), fake);
        assertEquals("{\"book\":\"B1\",\"id\":7,\"price\":12.50,\"blob\":\"AQI=\",\"tags\":{\"x\":1}}", plain.document(row, new int[] {0, 1, 2, 3, 4}));
    }

    @Test
    void aShapeTheTableCannotAnswerIsRefusedInWords() {
        Fake fake = new Fake(List.of(ID, BOOK, AT), List.of(), List.of(), Map.of());
        assertEquals("LK-1001 t (t): no id: the table has no identifier fields; set tables.t.id",
                assertThrows(LakeException.class, () -> ShapedTable.resolve(shape(Map.of()), fake)).getMessage());
        assertEquals("LK-1001 t: the date column book is string, not a date or timestamp",
                assertThrows(LakeException.class, () -> ShapedTable.resolve(shape(Map.of("id", "id", "date", "book")), fake)).getMessage());
        assertEquals("LK-1001 t: the date column at is a timestamp: add date-from: day",
                assertThrows(LakeException.class, () -> ShapedTable.resolve(shape(Map.of("id", "id", "date", "at")), fake)).getMessage());
        assertEquals("LK-1001 t: the id column nope is not a top-level column of t",
                assertThrows(LakeException.class, () -> ShapedTable.resolve(shape(Map.of("id", "nope")), fake)).getMessage());
        assertEquals(List.of(), ShapedTable.resolve(shape(Map.of("id", "id")), fake).dated() ? List.of(1) : List.of());
    }

    @Test
    void searchablePathsStopAtTheDepth() {
        ShapeField rating = new ShapeField("rating", ShapeField.Kind.STRUCT, 0, List.of(BOOK));
        ShapeField cp = new ShapeField("cp", ShapeField.Kind.STRUCT, 0, List.of(ShapeField.of("name", ShapeField.Kind.STRING), rating));
        Fake fake = new Fake(List.of(ID, cp), List.of(), List.of(), Map.of());
        assertEquals(List.of("id", "cp.name"), new ArrayList<>(ShapedTable.resolve(shape(Map.of("id", "id")), fake).searchable.keySet()));
        assertEquals(List.of("id", "cp.name", "cp.rating.book"),
                new ArrayList<>(ShapedTable.resolve(shape(Map.of("id", "id", "depth", "3")), fake).searchable.keySet()));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("rating", null);
        json.put("name", "N");                                         // a JSON object's fields in another order
        assertEquals("N", ShapedTable.at(new Object[] {1L, json}, 1, ShapedTable.resolve(shape(Map.of("id", "id")), fake).searchable.get("cp.name")));
    }

    @Test
    void theDocumentsFieldsKeepTheirNestedFieldsAndTheIdAndDateRoles() throws IOException {
        ShapeField cp = new ShapeField("cp", ShapeField.Kind.STRUCT, 0, List.of(ShapeField.of("name", ShapeField.Kind.STRING)));
        Fake fake = new Fake(List.of(ID, DAY, cp, BOOK), List.of("day"), List.of(), Map.of());     // the date is the day partition's
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            TableSnapshot s = new ForeignTableReader("t", shape(Map.of("id", "id", "columns.rename.book", "desk")), fake, pool,
                    new Problems("fake", Problems::describe)).snapshot(AsOf.LATEST).orElseThrow();
            List<com.ash.lake.read.DocumentField> f = s.documentFields();
            assertEquals(List.of("id", "day", "cp", "desk"), f.stream().map(com.ash.lake.read.DocumentField::name).toList());
            assertEquals(List.of(true, false, false, false), f.stream().map(com.ash.lake.read.DocumentField::id).toList());
            assertEquals(List.of(false, true, false, false), f.stream().map(com.ash.lake.read.DocumentField::date).toList());
            assertEquals("struct", f.get(2).kind());
            assertEquals("name", f.get(2).children().get(0).name());
            assertEquals("string", f.get(2).children().get(0).kind());
        }
    }

    @Test
    void aMissingTablesAbsenceIsItsSourcesAccountAndThePlainWordsOtherwise() {
        Fake fake = new Fake(List.of(ID), List.of(), List.of(), Map.of());
        ForeignSource none = new ForeignSource() {
            @Override
            public Optional<Version> version(AsOf asOf) {
                return Optional.empty();
            }

            @Override
            public String missing() {
                return "no table t: looked for metadata/v1.metadata.json";
            }

            @Override
            public TableIdentity identity() {
                return fake.identity();
            }
        };
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            ForeignTableReader r = new ForeignTableReader("t", shape(Map.of("id", "id")), none, pool, new Problems("fake", Problems::describe));
            assertEquals(Optional.empty(), r.snapshot(AsOf.LATEST));
            assertEquals("no table t: looked for metadata/v1.metadata.json", r.absence());
            assertEquals("no table", fake.missing());
        }
    }
}
