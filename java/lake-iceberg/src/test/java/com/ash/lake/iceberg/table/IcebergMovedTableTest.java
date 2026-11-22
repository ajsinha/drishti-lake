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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.lake.LakeError;
import com.ash.lake.LakeException;
import com.ash.lake.iceberg.catalog.Catalog;
import com.ash.lake.iceberg.catalog.Catalogs;
import com.ash.lake.read.LakeAddress;
import com.ash.lake.read.LakeReader;
import com.ash.lake.read.ReadProblem;
import com.ash.lake.spi.AsOf;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lake of Iceberg tables moved after it was written (QA2-22, QA3B-03, QA4-01: its metadata still names the old folder): every read of a
 * moved table fails with {@code LK-1006} in words without a folder, the read problems (Admin → Health) name the old folder and the
 * {@code relocate} pair that reads it, the same pair for tables moved together; with that pair the tables read again and the problems clear.
 * Delta and the plain-file store keep paths relative to the table, so a moved Delta or files table reads where it is (no "moved" there).
 */
class IcebergMovedTableTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    private static void write(Path root, String domain, String kind) throws Exception {
        var rt = com.ash.lake.LakeRuntime.fromProcess();
        try (Catalog c = Catalogs.open(rt, Map.of("root", root.toString(), "domain", domain))) {
            com.ash.lake.iceberg.Table t = NativeLayout.open(c, kind, Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 1000);
            NativeLayout.commitDays(t, Map.of(DAY, NativeLayout.writeDay(t, DAY, NativeCommitTest.rows("A", 10).iterator(), 1000)));
        }
    }

    @Test
    void aMovedTableIsClassifiedWithTheRelocateThatReadsItAndReadsAgainWithIt(@TempDir Path base) throws Exception {
        Path was = base.resolve("ice1");
        Path now = base.resolve("ice2");
        write(was, "credit", "credit-limit");
        write(was, "credit", "cva");
        Files.move(was, now);
        var rt = com.ash.lake.LakeRuntime.fromProcess();
        String pair = was + " -> " + now;
        try (LakeReader lake = IcebergLakeReader.open(rt, LakeAddress.of(now.toString(), "credit"))) {
            assertThat(lake.tables()).containsExactly("credit-limit", "cva");
            for (String kind : List.of("credit-limit", "cva")) {
                for (int i = 0; i < 2; i++) {
                    assertThatThrownBy(() -> lake.table(kind).snapshot(AsOf.LATEST))
                            .isInstanceOfSatisfying(LakeException.class, e -> assertThat(e.error()).isEqualTo(LakeError.UNREADABLE))
                            .hasMessageStartingWith("LK-1006 " + kind + " log: the table " + kind + " names files that are not there")
                            .hasMessageContaining("Admin → Health says where they were and the relocate that reads it")
                            .hasMessageNotContaining(base.toString());
                }
            }
            List<ReadProblem> problems = lake.problems().all();
            assertThat(problems).extracting(ReadProblem::table).containsExactlyInAnyOrder("credit-limit", "cva");
            for (ReadProblem p : problems) {
                assertThat(p.where()).isEqualTo("log");
                assertThat(p.reason()).startsWith("its metadata names files under " + was.resolve("credit").resolve(p.table()) + " that are not there")
                        .contains("the table was moved or copied after it was written")
                        .endsWith("relocate: '" + pair + "'");
            }
            assertThat(lake.problems().summary(5)).contains("credit-limit log: its metadata names files under").contains("cva log: ");
        }
        // the pair Health gives, as the connector's relocate: both tables read where they are now
        try (LakeReader lake = IcebergLakeReader.open(rt, LakeAddress.of(rt, Map.of("root", now.toString(), "domain", "credit", "relocate", pair)))) {
            for (String kind : List.of("credit-limit", "cva")) {
                assertThat(lake.table(kind).snapshot(AsOf.LATEST).orElseThrow().ids(DAY).size()).isEqualTo(10);
            }
            assertThat(lake.problems().isEmpty()).isTrue();
        }
    }

    @Test
    void aRelocateWhoseNewFolderLacksTheFilesSaysSo(@TempDir Path base) throws Exception {
        Path was = base.resolve("ice1");
        write(was, "credit", "cva");
        Path now = base.resolve("ice2");
        Files.move(was, now);
        var rt = com.ash.lake.LakeRuntime.fromProcess();
        Path elsewhere = Files.createDirectories(base.resolve("ice3"));
        try (LakeReader lake = IcebergLakeReader.open(rt, LakeAddress.of(rt, Map.of("root", now.toString(), "domain", "credit", "relocate",
                was + " -> " + elsewhere)))) {
            assertThatThrownBy(() -> lake.table("cva").snapshot(AsOf.LATEST)).isInstanceOf(LakeException.class)
                    .hasMessageNotContaining(base.toString());
            assertThat(lake.problems().all()).singleElement().extracting(ReadProblem::reason).asString()
                    .startsWith("relocate ").contains("the files are not there either").contains("check the new folder in relocate");
        }
    }

    @Test
    void anotherFailureKeepsItsOwnWords() {
        TableProblems words = TableProblems.of(Map.of("root", "/lake", "domain", "credit"));
        RuntimeException e = new IllegalStateException("access refused");
        assertThat(words.reason("cva", e)).isEqualTo("access refused");
        assertThat(words.forReaders("cva", e)).isNull();
        // a catalog says where its tables are: no folder is suggested
        TableProblems rest = TableProblems.of(Map.of("uri", "http://catalog", "domain", "credit"));
        assertThat(rest.reason("cva", new java.nio.file.NoSuchFileException("/old/credit/cva/metadata/snap-1.avro")))
                .contains("relocate: '/old/credit/cva -> <where it is now>'");
    }
}
