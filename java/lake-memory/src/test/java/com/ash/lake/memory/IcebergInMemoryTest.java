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
package com.ash.lake.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.lake.iceberg.expr.Expr;
import com.ash.lake.iceberg.io.FileAccess;
import com.ash.lake.iceberg.manifest.DataFile;
import com.ash.lake.iceberg.read.ParquetRowReader;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import com.ash.lake.iceberg.types.Type;
import com.ash.lake.iceberg.write.ParquetDataWriter;
import com.ash.lake.io.ParquetInput;
import com.ash.lake.memory.store.MemoryStorage;
import com.ash.lake.memory.store.MemoryStores;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** An Iceberg data file in a memory warehouse: written and read through the same Storage. */
class IcebergInMemoryTest {

    private static final Schema SCHEMA = new Schema(0, List.of(NestedField.required(1, "id", Type.Primitive.STRING),
            NestedField.optional(2, "doc", Type.Primitive.STRING), NestedField.required(3, "business_date", Type.Primitive.DATE),
            NestedField.optional(4, "mtm", Type.Primitive.DOUBLE)));

    @AfterEach
    void drop() {
        MemoryRuntime.STORES.remove("ice");
    }

    @Test
    void aDataFileIsWrittenAndReadInMemory() throws Exception {
        MemoryStorage store = MemoryRuntime.STORES.define("ice", 32L << 20, 64 * 1024, "");
        FileAccess io = FileAccess.local(MemoryRuntime.RT);
        assertThat(io.reads("mem://ice/wh/t")).isTrue();
        String path = "mem://ice/wh/news/data/f-1.parquet";
        DataFile f;
        try (ParquetDataWriter w = new ParquetDataWriter(io, path, SCHEMA, Map.of("write.parquet.compression-codec", "zstd"), 0,
                new Object[] {20_000}, 1)) {
            for (int i = 0; i < 300; i++) {
                w.write(new Object[] {String.format("N-%04d", i), "{\"t\":\"headline " + i + "\"}", 20_000, i * 1.0});
            }
            f = w.finish();
        }
        assertThat(f.recordCount()).isEqualTo(300);
        assertThat(store.status(path).size()).isEqualTo(f.fileSizeInBytes());
        int found = 0;
        try (ParquetRowReader r = new ParquetRowReader(ParquetInput.of(io.storage(path), path, f.fileSizeInBytes()), path, f.fileSizeInBytes(),
                SCHEMA.columns(), null, Map.of(), Expr.eq(1, "N-0123"), io.codecs(), com.ash.lake.io.ParquetFooters.of(io.runtime()), false)) {
            while (r.hasNext()) {
                Object[] row = r.next().values();
                if ("N-0123".equals(row[0])) {
                    assertThat(row[1]).isEqualTo("{\"t\":\"headline 123\"}");
                    found++;
                }
            }
        }
        assertThat(found).isEqualTo(1);
    }
}
