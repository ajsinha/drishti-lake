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

import com.ash.lake.iceberg.catalog.Catalog;
import com.ash.lake.iceberg.catalog.Catalogs;
import com.ash.lake.read.ColumnAsk;
import com.ash.lake.read.LakeAddress;
import com.ash.lake.read.LakeReader;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.AsOf;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.PositionDelete;
import org.apache.iceberg.deletes.PositionDeleteWriter;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.parquet.Parquet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read API leaves an Iceberg row deleted by a position delete file (iceberg-core's writer, as Spark or another engine deletes) out
 * of every read: ids, documents, promoted columns, file scans (READ_API.md defect I1; the kit's case read/deleted-rows-not-read for
 * Delta and files).
 */
class IcebergReadDeletesTest {

    @Test
    void aRowDeletedByAPositionDeleteFileIsInNoRead(@TempDir Path root) throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 27);
        var rt = com.ash.lake.LakeRuntime.fromProcess();
        String location;
        try (Catalog c = Catalogs.open(rt, Map.of("root", root.toString(), "domain", "desk"))) {
            com.ash.lake.iceberg.Table t = NativeLayout.open(c, "trade", Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 1000);
            NativeLayout.commitDays(t, Map.of(day, NativeLayout.writeDay(t, day, NativeCommitTest.rows("A", 10).iterator(), 1000)));
            location = t.metadata().location();
        }
        Table lib = new HadoopTables(new Configuration()).load(location);
        DataFile file;
        try (CloseableIterable<FileScanTask> tasks = lib.newScan().planFiles()) {
            file = tasks.iterator().next().file();
        }
        PositionDeleteWriter<Record> pw = Parquet.writeDeletes(lib.io().newOutputFile(location + "/data/pos-delete.parquet"))
                .forTable(lib).rowSchema(null).withSpec(lib.spec()).withPartition(file.partition()).overwrite().buildPositionWriter();
        try (pw) {
            pw.write(PositionDelete.<Record>create().set(file.location(), 3L, null));       // A-003: the rows are written sorted by id
        }
        lib.newRowDelta().addDeletes(pw.toDeleteFile()).commit();

        try (LakeReader lake = IcebergLakeReader.open(rt, LakeAddress.of(root.toString(), "desk"))) {
            TableSnapshot s = lake.table("trade").snapshot(AsOf.LATEST).orElseThrow();
            assertThat(s.deletes()).isTrue();
            assertThat(s.day(day).orElseThrow().deletes()).isTrue();
            var ids = s.ids(day);
            assertThat(ids.size()).isEqualTo(9);
            assertThat(ids.file("A-003")).isEqualTo(-1);
            List<String> docs = new ArrayList<>();
            s.documents(day, (id, json) -> docs.add(id));
            assertThat(docs).hasSize(9).doesNotContain("A-003");
            var block = s.columns(day, List.of(new ColumnAsk("mtm", "mtm", true)));
            assertThat(block.ids()).hasSize(9).doesNotContain("A-003");
            assertThat(block.numbers().get("mtm")).doesNotContain(3.0);
            List<String> scanned = new ArrayList<>();
            s.scanFile(day, 0, List.of("id"), com.ash.lake.spi.Expr.TRUE, v -> scanned.add((String) v[0]));
            assertThat(scanned).hasSize(9).doesNotContain("A-003");
        }
    }
}
