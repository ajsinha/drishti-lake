<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Move allow-list (step L0)

What of Drishti moves into this repository, when, and what stays, file by file, at the freeze commit **Drishti `main` `9ce7c3170`**
(2026-11-21). Decisions LK-1 to LK-16 of the design ([DRISHTI_LAKE_DESIGN](design/DRISHTI_LAKE_DESIGN.md) section 13; LK-9 provisional)
apply. The machine-readable list is [`tools/split/l3-paths.txt`](../tools/split/l3-paths.txt) (the `git filter-repo` input of step L3,
over every path the files had in the host's history); [`tools/split/l3_rename.py`](../tools/split/l3_rename.py) gives each its place here.

Verdicts:

| Verdict | Meaning |
|---|---|
| **moves (L3)** | moved with its history at step L3 (this list); the package renamed in one commit |
| **moves later (Ln)** | product code that moves at step Ln with an allow-list of its own (as Rachana's A9 and A13), because it cannot compile here before that step's seam exists |
| **divides (Ln)** | one file, two halves: the product half moves (or has moved) and is cut at step Ln; the host half stays in Drishti |
| **stays** | Drishti's: an adapter, the host's data, or a test of the host |
| **copied (hostcompat)** | a host type the moved code still names: copied with only its package changed into `com.ash.lake.hostcompat` ([SPLIT_EXEMPTIONS](../java/SPLIT_EXEMPTIONS.md)); never moved, the host keeps its own |

Scan of the listed paths (all of their history): no file over 1 MB (largest 71 KB, `iceberg-fixtures/expected.json`), no
key, token or private key pattern, no real trading-system value (source-system words such as `trade_endur` appear as table names only;
the "names no host" guard counts them).

## 1. Moves at L3: 587 files

### `java/lake-config` (16 files)

| From (Drishti) | To | Lines |
|---|---|---|
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/ConnectorLake.java` | `src/main/java/com/ash/lake/config/ConnectorLake.java` | 172 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/Lake.java` | `src/main/java/com/ash/lake/config/Lake.java` | 149 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/LakeCheck.java` | `src/main/java/com/ash/lake/config/LakeCheck.java` | 182 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/LakeFiles.java` | `src/main/java/com/ash/lake/config/LakeFiles.java` | 815 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/LakeTable.java` | `src/main/java/com/ash/lake/config/LakeTable.java` | 143 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/Lakes.java` | `src/main/java/com/ash/lake/config/Lakes.java` | 637 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/LakesCli.java` | `src/main/java/com/ash/lake/config/LakesCli.java` | 188 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/ModuleConnectors.java` | `src/main/java/com/ash/lake/config/ModuleConnectors.java` | 40 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/PackEntry.java` | `src/main/java/com/ash/lake/config/PackEntry.java` | 293 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/RemovedKinds.java` | `src/main/java/com/ash/lake/config/RemovedKinds.java` | 42 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeconfig/package-info.java` | `src/main/java/com/ash/lake/config/package-info.java` | 37 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeconfig/LakeNamedSectionTest.java` | `src/test/java/com/ash/lake/config/LakeNamedSectionTest.java` | 125 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeconfig/LakePathsGuardTest.java` | `src/test/java/com/ash/lake/config/LakePathsGuardTest.java` | 93 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeconfig/LakesTest.java` | `src/test/java/com/ash/lake/config/LakesTest.java` | 429 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeconfig/TestVocabulary.java` | `src/test/java/com/ash/lake/config/TestVocabulary.java` | 28 |
| `lake/drishti-lakeio/src/test/resources/META-INF/services/com.ash.drishti.kernel.api.vocabulary.DomainVocabulary` | `src/test/resources/META-INF/services/com.ash.drishti.kernel.api.vocabulary.DomainVocabulary` | 1 |

### `java/lake-delta` (46 files)

| From (Drishti) | To | Lines |
|---|---|---|
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/Lz4RoutingEngine.java` | `src/main/java/com/ash/lake/delta/Lz4RoutingEngine.java` | 147 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/NativeEngine.java` | `src/main/java/com/ash/lake/delta/NativeEngine.java` | 189 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/NativeFileIO.java` | `src/main/java/com/ash/lake/delta/NativeFileIO.java` | 193 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/NativeFileSystemClient.java` | `src/main/java/com/ash/lake/delta/NativeFileSystemClient.java` | 84 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/NativeJsonHandler.java` | `src/main/java/com/ash/lake/delta/NativeJsonHandler.java` | 57 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/NativeParquetHandler.java` | `src/main/java/com/ash/lake/delta/NativeParquetHandler.java` | 101 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/ParquetBatches.java` | `src/main/java/com/ash/lake/delta/ParquetBatches.java` | 240 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/Streams.java` | `src/main/java/com/ash/lake/delta/Streams.java` | 105 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/read/DeltaRowsCli.java` | `src/main/java/com/ash/lake/delta/read/DeltaRowsCli.java` | 246 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/write/LakeCli.java` | `src/main/java/com/ash/lake/delta/write/LakeCli.java` | 90 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/write/LakeManifest.java` | `src/main/java/com/ash/lake/delta/write/LakeManifest.java` | 125 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/write/LakeWriter.java` | `src/main/java/com/ash/lake/delta/write/LakeWriter.java` | 598 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/write/LocalWriteFileIO.java` | `src/main/java/com/ash/lake/delta/write/LocalWriteFileIO.java` | 163 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/write/ReadingFileIO.java` | `src/main/java/com/ash/lake/delta/write/ReadingFileIO.java` | 62 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/write/StorageWriteFileIO.java` | `src/main/java/com/ash/lake/delta/write/StorageWriteFileIO.java` | 153 |
| `lake/drishti-deltalake/src/main/java/com/ash/drishti/deltalake/write/TableUpkeep.java` | `src/main/java/com/ash/lake/delta/write/TableUpkeep.java` | 244 |
| `lake/drishti-deltalake/src/test/java/com/ash/drishti/deltalake/HadoopFreeProbe.java` | `src/test/java/com/ash/lake/delta/HadoopFreeProbe.java` | 53 |
| `lake/drishti-deltalake/src/test/java/com/ash/drishti/deltalake/LakeWriterTest.java` | `src/test/java/com/ash/lake/delta/LakeWriterTest.java` | 202 |
| `lake/drishti-deltalake/src/test/java/com/ash/drishti/deltalake/NativeEngineTest.java` | `src/test/java/com/ash/lake/delta/NativeEngineTest.java` | 226 |
| `lake/drishti-deltalake/src/test/java/com/ash/drishti/deltalake/NoHadoopFileSystemTest.java` | `src/test/java/com/ash/lake/delta/NoHadoopFileSystemTest.java` | 124 |
| `lake/drishti-deltalake/src/test/java/com/ash/drishti/deltalake/TableReader.java` | `src/test/java/com/ash/lake/delta/TableReader.java` | 120 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000000.json` | `src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000000.json` | 4 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000001.json` | `src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000001.json` | 2 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000002.json` | `src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000002.json` | 2 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000003.json` | `src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000003.json` | 2 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000004.json` | `src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000004.json` | 2 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000005.json` | `src/test/resources/lake-layout/desk/trade/_delta_log/00000000000000000005.json` | 2 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/business_date=2026-09-29/part-00000-2e42af68-2a58-4e33-9cad-c66ba7724661-c000.snappy.parquet` | `src/test/resources/lake-layout/desk/trade/business_date=2026-09-29/part-00000-2e42af68-2a58-4e33-9cad-c66ba7724661-c000.snappy.parquet` | 27 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/business_date=2026-09-29/part-00000-39473f53-2092-4a76-b5a2-fb38f385a8b5-c000.snappy.parquet` | `src/test/resources/lake-layout/desk/trade/business_date=2026-09-29/part-00000-39473f53-2092-4a76-b5a2-fb38f385a8b5-c000.snappy.parquet` | 40 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/business_date=2026-09-29/part-00000-3b697c7c-d9ee-48d9-95f0-13a63061e20c-c000.snappy.parquet` | `src/test/resources/lake-layout/desk/trade/business_date=2026-09-29/part-00000-3b697c7c-d9ee-48d9-95f0-13a63061e20c-c000.snappy.parquet` | 42 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/business_date=2026-09-30/part-00000-16e397ee-2969-458e-9fa5-cbd2a9b1a014-c000.snappy.parquet` | `src/test/resources/lake-layout/desk/trade/business_date=2026-09-30/part-00000-16e397ee-2969-458e-9fa5-cbd2a9b1a014-c000.snappy.parquet` | 25 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/business_date=2026-09-30/part-00000-a2cc265d-7ca6-482b-b39c-51e583f6dba1-c000.snappy.parquet` | `src/test/resources/lake-layout/desk/trade/business_date=2026-09-30/part-00000-a2cc265d-7ca6-482b-b39c-51e583f6dba1-c000.snappy.parquet` | 41 |
| `lake/drishti-deltalake/src/test/resources/lake-layout/desk/trade/business_date=2026-09-30/part-00000-da825085-1b3d-46d2-b87e-b959b91c3e81-c000.snappy.parquet` | `src/test/resources/lake-layout/desk/trade/business_date=2026-09-30/part-00000-da825085-1b3d-46d2-b87e-b959b91c3e81-c000.snappy.parquet` | 42 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/counterparty/_delta_log/00000000000000000000.json` | `src/test/resources/lake/desk/counterparty/_delta_log/00000000000000000000.json` | 5 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/counterparty/business_date=2026-09-28/part-00000-465056b0-0996-4f48-ae15-63d9ae7e7736-c000.snappy.parquet` | `src/test/resources/lake/desk/counterparty/business_date=2026-09-28/part-00000-465056b0-0996-4f48-ae15-63d9ae7e7736-c000.snappy.parquet` | 1 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/counterparty/business_date=2026-09-30/part-00000-2c7514b3-6c06-4976-aaea-1cf7aa411a9b-c000.snappy.parquet` | `src/test/resources/lake/desk/counterparty/business_date=2026-09-30/part-00000-2c7514b3-6c06-4976-aaea-1cf7aa411a9b-c000.snappy.parquet` | 1 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/trade/_delta_log/00000000000000000000.json` | `src/test/resources/lake/desk/trade/_delta_log/00000000000000000000.json` | 6 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/trade/_delta_log/00000000000000000001.json` | `src/test/resources/lake/desk/trade/_delta_log/00000000000000000001.json` | 3 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/trade/business_date=2026-09-28/part-00000-ccab8199-e94f-4b59-ac04-aae22bf3dff5-c000.snappy.parquet` | `src/test/resources/lake/desk/trade/business_date=2026-09-28/part-00000-ccab8199-e94f-4b59-ac04-aae22bf3dff5-c000.snappy.parquet` | 3 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/trade/business_date=2026-09-29/part-00000-1aa81e0d-71f8-41f5-b4a4-1a038ca4851d-c000.snappy.parquet` | `src/test/resources/lake/desk/trade/business_date=2026-09-29/part-00000-1aa81e0d-71f8-41f5-b4a4-1a038ca4851d-c000.snappy.parquet` | 1 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/trade/business_date=2026-09-30/part-00000-19aec187-4d67-44c4-a93d-9a645b83b3d7-c000.snappy.parquet` | `src/test/resources/lake/desk/trade/business_date=2026-09-30/part-00000-19aec187-4d67-44c4-a93d-9a645b83b3d7-c000.snappy.parquet` | 1 |
| `lake/drishti-deltalake/src/test/resources/lake/desk/trade/business_date=2026-09-30/part-00000-19f24c79-7acc-4ff7-b8c8-3cb64280bc18-c000.snappy.parquet` | `src/test/resources/lake/desk/trade/business_date=2026-09-30/part-00000-19f24c79-7acc-4ff7-b8c8-3cb64280bc18-c000.snappy.parquet` | 1 |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/DeltaTable.java` | `src/main/java/com/ash/lake/delta/table/DeltaTable.java` | 1031 |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/TableProblems.java` | `src/main/java/com/ash/lake/delta/table/TableProblems.java` | 177 |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/VectorBatches.java` | `src/main/java/com/ash/lake/delta/table/VectorBatches.java` | 104 |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/VectorBatchesTest.java` | `src/test/java/com/ash/lake/delta/table/VectorBatchesTest.java` | 100 |

### `java/lake-iceberg` (76 files)

| From (Drishti) | To | Lines |
|---|---|---|
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/Table.java` | `src/main/java/com/ash/lake/iceberg/Table.java` | 135 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/catalog/Catalog.java` | `src/main/java/com/ash/lake/iceberg/catalog/Catalog.java` | 71 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/catalog/Catalogs.java` | `src/main/java/com/ash/lake/iceberg/catalog/Catalogs.java` | 98 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/catalog/PathCatalog.java` | `src/main/java/com/ash/lake/iceberg/catalog/PathCatalog.java` | 133 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/catalog/PathTableOps.java` | `src/main/java/com/ash/lake/iceberg/catalog/PathTableOps.java` | 285 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/catalog/RestCatalog.java` | `src/main/java/com/ash/lake/iceberg/catalog/RestCatalog.java` | 172 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/catalog/S3Tables.java` | `src/main/java/com/ash/lake/iceberg/catalog/S3Tables.java` | 212 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/catalog/StaticCatalog.java` | `src/main/java/com/ash/lake/iceberg/catalog/StaticCatalog.java` | 90 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/expr/Expr.java` | `src/main/java/com/ash/lake/iceberg/expr/Expr.java` | 93 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/io/DrishtiFileIO.java` | `src/main/java/com/ash/lake/iceberg/io/DrishtiFileIO.java` | 329 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/io/FileAccess.java` | `src/main/java/com/ash/lake/iceberg/io/FileAccess.java` | 178 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/io/Reasons.java` | `src/main/java/com/ash/lake/iceberg/io/Reasons.java` | 65 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/io/Relocation.java` | `src/main/java/com/ash/lake/iceberg/io/Relocation.java` | 241 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/manifest/DataFile.java` | `src/main/java/com/ash/lake/iceberg/manifest/DataFile.java` | 57 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/meta/Bridge.java` | `src/main/java/com/ash/lake/iceberg/meta/Bridge.java` | 277 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/meta/NameMapping.java` | `src/main/java/com/ash/lake/iceberg/meta/NameMapping.java` | 34 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/meta/PartitionSpec.java` | `src/main/java/com/ash/lake/iceberg/meta/PartitionSpec.java` | 83 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/meta/Snapshot.java` | `src/main/java/com/ash/lake/iceberg/meta/Snapshot.java` | 36 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/meta/SortOrder.java` | `src/main/java/com/ash/lake/iceberg/meta/SortOrder.java` | 37 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/meta/TableMetadata.java` | `src/main/java/com/ash/lake/iceberg/meta/TableMetadata.java` | 182 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/meta/Transform.java` | `src/main/java/com/ash/lake/iceberg/meta/Transform.java` | 362 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/read/Converters.java` | `src/main/java/com/ash/lake/iceberg/read/Converters.java` | 387 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/read/Deletes.java` | `src/main/java/com/ash/lake/iceberg/read/Deletes.java` | 203 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/read/JsonValues.java` | `src/main/java/com/ash/lake/iceberg/read/JsonValues.java` | 144 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/read/ParquetRowReader.java` | `src/main/java/com/ash/lake/iceberg/read/ParquetRowReader.java` | 417 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/read/TaskReader.java` | `src/main/java/com/ash/lake/iceberg/read/TaskReader.java` | 129 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/scan/FileTask.java` | `src/main/java/com/ash/lake/iceberg/scan/FileTask.java` | 40 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/scan/Planner.java` | `src/main/java/com/ash/lake/iceberg/scan/Planner.java` | 107 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/scan/Pruning.java` | `src/main/java/com/ash/lake/iceberg/scan/Pruning.java` | 137 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/types/NestedField.java` | `src/main/java/com/ash/lake/iceberg/types/NestedField.java` | 39 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/types/Schema.java` | `src/main/java/com/ash/lake/iceberg/types/Schema.java` | 157 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/types/Type.java` | `src/main/java/com/ash/lake/iceberg/types/Type.java` | 185 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/types/UnsupportedFeature.java` | `src/main/java/com/ash/lake/iceberg/types/UnsupportedFeature.java` | 48 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/types/Values.java` | `src/main/java/com/ash/lake/iceberg/types/Values.java` | 170 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/write/MetricsConfig.java` | `src/main/java/com/ash/lake/iceberg/write/MetricsConfig.java` | 63 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/write/Operations.java` | `src/main/java/com/ash/lake/iceberg/write/Operations.java` | 263 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/write/ParquetDataWriter.java` | `src/main/java/com/ash/lake/iceberg/write/ParquetDataWriter.java` | 589 |
| `lake/drishti-iceberg/src/main/java/com/ash/drishti/iceberg/write/ParquetSchemas.java` | `src/main/java/com/ash/lake/iceberg/write/ParquetSchemas.java` | 112 |
| `lake/drishti-iceberg/src/test/java/com/ash/drishti/iceberg/catalog/CatalogNamedMetadataTest.java` | `src/test/java/com/ash/lake/iceberg/catalog/CatalogNamedMetadataTest.java` | 71 |
| `lake/drishti-iceberg/src/test/java/com/ash/drishti/iceberg/catalog/S3TablesRestTest.java` | `src/test/java/com/ash/lake/iceberg/catalog/S3TablesRestTest.java` | 118 |
| `lake/drishti-iceberg/src/test/java/com/ash/drishti/iceberg/catalog/S3TablesTest.java` | `src/test/java/com/ash/lake/iceberg/catalog/S3TablesTest.java` | 89 |
| `lake/drishti-iceberg/src/test/java/com/ash/drishti/iceberg/io/LiteralPathsTest.java` | `src/test/java/com/ash/lake/iceberg/io/LiteralPathsTest.java` | 61 |
| `lake/drishti-iceberg/src/test/java/com/ash/drishti/iceberg/io/RelocationTest.java` | `src/test/java/com/ash/lake/iceberg/io/RelocationTest.java` | 91 |
| `lake/drishti-iceberg/src/test/java/com/ash/drishti/iceberg/io/WindowsPathsTest.java` | `src/test/java/com/ash/lake/iceberg/io/WindowsPathsTest.java` | 68 |
| `lake/drishti-iceberg/src/test/java/com/ash/drishti/iceberg/meta/TransformTest.java` | `src/test/java/com/ash/lake/iceberg/meta/TransformTest.java` | 102 |
| `lake/drishti-iceberg/src/test/java/com/ash/drishti/iceberg/write/WriterSpikeTest.java` | `src/test/java/com/ash/lake/iceberg/write/WriterSpikeTest.java` | 146 |
| `plugins/drishti-plugin-iceberg-external/src/main/java/com/ash/drishti/plugin/icebergext/ExternalTable.java` | `src/main/java/com/ash/lake/iceberg/external/ExternalTable.java` | 516 |
| `plugins/drishti-plugin-iceberg-external/src/main/java/com/ash/drishti/plugin/icebergext/KindConfig.java` | `src/main/java/com/ash/lake/iceberg/external/KindConfig.java` | 130 |
| `plugins/drishti-plugin-iceberg-external/src/main/java/com/ash/drishti/plugin/icebergext/Shape.java` | `src/main/java/com/ash/lake/iceberg/external/Shape.java` | 164 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/IcebergAppender.java` | `src/main/java/com/ash/lake/iceberg/table/IcebergAppender.java` | 76 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/IcebergLayout.java` | `src/main/java/com/ash/lake/iceberg/table/IcebergLayout.java` | 73 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/IcebergLoader.java` | `src/main/java/com/ash/lake/iceberg/table/IcebergLoader.java` | 493 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/IcebergMaintenance.java` | `src/main/java/com/ash/lake/iceberg/table/IcebergMaintenance.java` | 396 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/IcebergVectors.java` | `src/main/java/com/ash/lake/iceberg/table/IcebergVectors.java` | 177 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/KindTable.java` | `src/main/java/com/ash/lake/iceberg/table/KindTable.java` | 457 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/LakeTables.java` | `src/main/java/com/ash/lake/iceberg/table/LakeTables.java` | 184 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/LayoutLake.java` | `src/main/java/com/ash/lake/iceberg/table/LayoutLake.java` | 219 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/NativeKindTable.java` | `src/main/java/com/ash/lake/iceberg/table/NativeKindTable.java` | 222 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/NativeLayout.java` | `src/main/java/com/ash/lake/iceberg/table/NativeLayout.java` | 283 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/NativeMaintenance.java` | `src/main/java/com/ash/lake/iceberg/table/NativeMaintenance.java` | 524 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/OrphanFiles.java` | `src/main/java/com/ash/lake/iceberg/table/OrphanFiles.java` | 156 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/SortedRuns.java` | `src/main/java/com/ash/lake/iceberg/table/SortedRuns.java` | 409 |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/TableProblems.java` | `src/main/java/com/ash/lake/iceberg/table/TableProblems.java` | 214 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/Fixtures.java` | `src/test/java/com/ash/lake/iceberg/table/Fixtures.java` | 90 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergLoaderFailureTest.java` | `src/test/java/com/ash/lake/iceberg/table/IcebergLoaderFailureTest.java` | 88 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergSpecInteropTest.java` | `src/test/java/com/ash/lake/iceberg/table/IcebergSpecInteropTest.java` | 387 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/NativeCommitTest.java` | `src/test/java/com/ash/lake/iceberg/table/NativeCommitTest.java` | 132 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/NativePlanningTest.java` | `src/test/java/com/ash/lake/iceberg/table/NativePlanningTest.java` | 147 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/NativeReadInteropTest.java` | `src/test/java/com/ash/lake/iceberg/table/NativeReadInteropTest.java` | 108 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/NativeRefusalTest.java` | `src/test/java/com/ash/lake/iceberg/table/NativeRefusalTest.java` | 66 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/NativeRestProtocolTest.java` | `src/test/java/com/ash/lake/iceberg/table/NativeRestProtocolTest.java` | 165 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/OracleLake.java` | `src/test/java/com/ash/lake/iceberg/table/OracleLake.java` | 339 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/OracleLayout.java` | `src/test/java/com/ash/lake/iceberg/table/OracleLayout.java` | 288 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/OracleTable.java` | `src/test/java/com/ash/lake/iceberg/table/OracleTable.java` | 256 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/ShardSortedLayoutTest.java` | `src/test/java/com/ash/lake/iceberg/table/ShardSortedLayoutTest.java` | 35 |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/TableProblemsPathTest.java` | `src/test/java/com/ash/lake/iceberg/table/TableProblemsPathTest.java` | 39 |

### `java/lake-io` (30 files)

| From (Drishti) | To | Lines |
|---|---|---|
| `kernel/drishti-common/src/main/java/com/ash/drishti/common/FileDates.java` | `src/main/java/com/ash/lake/common/FileDates.java` | 376 |
| `kernel/drishti-common/src/main/java/com/ash/drishti/common/JvmTempDir.java` | `src/main/java/com/ash/lake/common/JvmTempDir.java` | 169 |
| `kernel/drishti-common/src/main/java/com/ash/drishti/common/RowRetention.java` | `src/main/java/com/ash/lake/common/RowRetention.java` | 213 |
| `kernel/drishti-common/src/main/java/com/ash/drishti/common/TempFiles.java` | `src/main/java/com/ash/lake/common/TempFiles.java` | 266 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/FileEntry.java` | `src/main/java/com/ash/lake/io/FileEntry.java` | 26 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/LocalPaths.java` | `src/main/java/com/ash/lake/io/LocalPaths.java` | 159 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/LocalStorage.java` | `src/main/java/com/ash/lake/io/LocalStorage.java` | 235 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/Lz4Pages.java` | `src/main/java/com/ash/lake/io/Lz4Pages.java` | 110 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/NativeCodecs.java` | `src/main/java/com/ash/lake/io/NativeCodecs.java` | 206 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/PageCodec.java` | `src/main/java/com/ash/lake/io/PageCodec.java` | 61 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/PageDecodeException.java` | `src/main/java/com/ash/lake/io/PageDecodeException.java` | 52 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/ParquetFooters.java` | `src/main/java/com/ash/lake/io/ParquetFooters.java` | 106 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/ParquetInput.java` | `src/main/java/com/ash/lake/io/ParquetInput.java` | 186 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/RangeCursor.java` | `src/main/java/com/ash/lake/io/RangeCursor.java` | 74 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/RangeReader.java` | `src/main/java/com/ash/lake/io/RangeReader.java` | 50 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/S3Output.java` | `src/main/java/com/ash/lake/io/S3Output.java` | 120 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/S3Settings.java` | `src/main/java/com/ash/lake/io/S3Settings.java` | 114 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/S3Storage.java` | `src/main/java/com/ash/lake/io/S3Storage.java` | 430 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/Storage.java` | `src/main/java/com/ash/lake/io/Storage.java` | 112 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/StorageProvider.java` | `src/main/java/com/ash/lake/io/StorageProvider.java` | 41 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/StorageProviders.java` | `src/main/java/com/ash/lake/io/StorageProviders.java` | 90 |
| `lake/drishti-lakeio/src/main/java/com/ash/drishti/lakeio/UnsupportedCodec.java` | `src/main/java/com/ash/lake/io/UnsupportedCodec.java` | 52 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeio/DirectMemorySoakTest.java` | `src/test/java/com/ash/lake/io/DirectMemorySoakTest.java` | 151 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeio/LocalPathsTest.java` | `src/test/java/com/ash/lake/io/LocalPathsTest.java` | 114 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeio/LocalStorageContractTest.java` | `src/test/java/com/ash/lake/io/LocalStorageContractTest.java` | 38 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeio/Lz4PagesTest.java` | `src/test/java/com/ash/lake/io/Lz4PagesTest.java` | 166 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeio/ParquetFootersWeightTest.java` | `src/test/java/com/ash/lake/io/ParquetFootersWeightTest.java` | 69 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeio/S3StorageProxyTest.java` | `src/test/java/com/ash/lake/io/S3StorageProxyTest.java` | 144 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeio/S3StorageTlsTest.java` | `src/test/java/com/ash/lake/io/S3StorageTlsTest.java` | 123 |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeio/StorageContract.java` | `src/test/java/com/ash/lake/io/StorageContract.java` | 190 |

### `java/lake-load` (91 files)

| From (Drishti) | To | Lines |
|---|---|---|
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/Claims.java` | `src/main/java/com/ash/lake/inbox/Claims.java` | 237 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/Cycle.java` | `src/main/java/com/ash/lake/inbox/Cycle.java` | 881 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/DataInbox.java` | `src/main/java/com/ash/lake/inbox/DataInbox.java` | 404 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/DataLoadSettings.java` | `src/main/java/com/ash/lake/inbox/DataLoadSettings.java` | 88 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/Inbox.java` | `src/main/java/com/ash/lake/inbox/Inbox.java` | 220 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/InboxStatus.java` | `src/main/java/com/ash/lake/inbox/InboxStatus.java` | 602 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/Ledger.java` | `src/main/java/com/ash/lake/inbox/Ledger.java` | 261 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/LoadRunner.java` | `src/main/java/com/ash/lake/inbox/LoadRunner.java` | 39 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/LoaderProcess.java` | `src/main/java/com/ash/lake/inbox/LoaderProcess.java` | 309 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/Outbox.java` | `src/main/java/com/ash/lake/inbox/Outbox.java` | 211 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/ReadyCheck.java` | `src/main/java/com/ash/lake/inbox/ReadyCheck.java` | 55 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/ReadyChecks.java` | `src/main/java/com/ash/lake/inbox/ReadyChecks.java` | 521 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/ReadyDesk.java` | `src/main/java/com/ash/lake/inbox/ReadyDesk.java` | 728 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/ReadyForm.java` | `src/main/java/com/ash/lake/inbox/ReadyForm.java` | 29 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/ReadyNote.java` | `src/main/java/com/ash/lake/inbox/ReadyNote.java` | 156 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/ReadyRule.java` | `src/main/java/com/ash/lake/inbox/ReadyRule.java` | 52 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/ReadyWriter.java` | `src/main/java/com/ash/lake/inbox/ReadyWriter.java` | 101 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/Recovery.java` | `src/main/java/com/ash/lake/inbox/Recovery.java` | 292 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/Routes.java` | `src/main/java/com/ash/lake/inbox/Routes.java` | 610 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/RowForms.java` | `src/main/java/com/ash/lake/inbox/RowForms.java` | 93 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/dataload/Signal.java` | `src/main/java/com/ash/lake/inbox/Signal.java` | 50 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/Archives.java` | `src/main/java/com/ash/lake/load/Archives.java` | 635 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/Bytes.java` | `src/main/java/com/ash/lake/load/Bytes.java` | 120 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/ChildKinds.java` | `src/main/java/com/ash/lake/load/ChildKinds.java` | 126 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/ChildLoads.java` | `src/main/java/com/ash/lake/load/ChildLoads.java` | 236 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/DatasetLoads.java` | `src/main/java/com/ash/lake/load/DatasetLoads.java` | 225 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/DateWriter.java` | `src/main/java/com/ash/lake/load/DateWriter.java` | 157 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/DeltaTarget.java` | `src/main/java/com/ash/lake/load/DeltaTarget.java` | 427 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/FileRows.java` | `src/main/java/com/ash/lake/load/FileRows.java` | 185 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/GzipMembers.java` | `src/main/java/com/ash/lake/load/GzipMembers.java` | 219 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/IcebergTarget.java` | `src/main/java/com/ash/lake/load/IcebergTarget.java` | 307 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/Inputs.java` | `src/main/java/com/ash/lake/load/Inputs.java` | 267 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/LakeLoad.java` | `src/main/java/com/ash/lake/load/LakeLoad.java` | 370 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/LakeRelocate.java` | `src/main/java/com/ash/lake/load/LakeRelocate.java` | 479 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/LakeRetention.java` | `src/main/java/com/ash/lake/load/LakeRetention.java` | 446 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/LakeState.java` | `src/main/java/com/ash/lake/load/LakeState.java` | 144 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/LakeUndo.java` | `src/main/java/com/ash/lake/load/LakeUndo.java` | 440 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/Latest.java` | `src/main/java/com/ash/lake/load/Latest.java` | 99 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/LoadConfig.java` | `src/main/java/com/ash/lake/load/LoadConfig.java` | 788 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/LoadReport.java` | `src/main/java/com/ash/lake/load/LoadReport.java` | 528 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/Loader.java` | `src/main/java/com/ash/lake/load/Loader.java` | 634 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/Meter.java` | `src/main/java/com/ash/lake/load/Meter.java` | 85 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/ParseWorker.java` | `src/main/java/com/ash/lake/load/ParseWorker.java` | 404 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/PyJson.java` | `src/main/java/com/ash/lake/load/PyJson.java` | 174 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/RowParser.java` | `src/main/java/com/ash/lake/load/RowParser.java` | 756 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/RowPlan.java` | `src/main/java/com/ash/lake/load/RowPlan.java` | 258 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/RowView.java` | `src/main/java/com/ash/lake/load/RowView.java` | 166 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/Runs.java` | `src/main/java/com/ash/lake/load/Runs.java` | 548 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/SourceReader.java` | `src/main/java/com/ash/lake/load/SourceReader.java` | 260 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/TableDateRows.java` | `src/main/java/com/ash/lake/load/TableDateRows.java` | 119 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/TableTarget.java` | `src/main/java/com/ash/lake/load/TableTarget.java` | 337 |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/lakeload/TarStream.java` | `src/main/java/com/ash/lake/load/TarStream.java` | 250 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/BatchAndOverlapTest.java` | `src/test/java/com/ash/lake/inbox/BatchAndOverlapTest.java` | 167 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/ClaimsAndLedgerTest.java` | `src/test/java/com/ash/lake/inbox/ClaimsAndLedgerTest.java` | 122 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/CycleTest.java` | `src/test/java/com/ash/lake/inbox/CycleTest.java` | 263 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/DataInboxLoaderTest.java` | `src/test/java/com/ash/lake/inbox/DataInboxLoaderTest.java` | 296 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/Fixture.java` | `src/test/java/com/ash/lake/inbox/Fixture.java` | 219 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/InboxChecksTest.java` | `src/test/java/com/ash/lake/inbox/InboxChecksTest.java` | 98 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/InboxStatusTest.java` | `src/test/java/com/ash/lake/inbox/InboxStatusTest.java` | 241 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/InboxTest.java` | `src/test/java/com/ash/lake/inbox/InboxTest.java` | 170 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/LakeRoutingTest.java` | `src/test/java/com/ash/lake/inbox/LakeRoutingTest.java` | 213 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/NewLakeKeysTest.java` | `src/test/java/com/ash/lake/inbox/NewLakeKeysTest.java` | 292 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/ReadmeFoldersTest.java` | `src/test/java/com/ash/lake/inbox/ReadmeFoldersTest.java` | 91 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/ReadyDeskTest.java` | `src/test/java/com/ash/lake/inbox/ReadyDeskTest.java` | 339 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/RecoveryTest.java` | `src/test/java/com/ash/lake/inbox/RecoveryTest.java` | 96 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/RoutesTest.java` | `src/test/java/com/ash/lake/inbox/RoutesTest.java` | 186 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/dataload/RowFormsTest.java` | `src/test/java/com/ash/lake/inbox/RowFormsTest.java` | 90 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/ArchivesTest.java` | `src/test/java/com/ash/lake/load/ArchivesTest.java` | 258 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/ChildLoadsTest.java` | `src/test/java/com/ash/lake/load/ChildLoadsTest.java` | 114 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/CodecsTest.java` | `src/test/java/com/ash/lake/load/CodecsTest.java` | 149 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/ConnectorSetStampTest.java` | `src/test/java/com/ash/lake/load/ConnectorSetStampTest.java` | 58 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/FileDatesTest.java` | `src/test/java/com/ash/lake/load/FileDatesTest.java` | 267 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/LakeFileLoadTest.java` | `src/test/java/com/ash/lake/load/LakeFileLoadTest.java` | 144 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/LakeLoadTest.java` | `src/test/java/com/ash/lake/load/LakeLoadTest.java` | 223 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/LakeRelocateTest.java` | `src/test/java/com/ash/lake/load/LakeRelocateTest.java` | 229 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/LatestTest.java` | `src/test/java/com/ash/lake/load/LatestTest.java` | 51 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/ListColumnsLoadTest.java` | `src/test/java/com/ash/lake/load/ListColumnsLoadTest.java` | 71 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/LoadConfigSettingsFileTest.java` | `src/test/java/com/ash/lake/load/LoadConfigSettingsFileTest.java` | 60 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/ManyDatesMemoryTest.java` | `src/test/java/com/ash/lake/load/ManyDatesMemoryTest.java` | 92 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/PvData.java` | `src/test/java/com/ash/lake/load/PvData.java` | 167 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/RowDatesTest.java` | `src/test/java/com/ash/lake/load/RowDatesTest.java` | 111 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/S3LoadTest.java` | `src/test/java/com/ash/lake/load/S3LoadTest.java` | 163 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/SharedTableSliceTest.java` | `src/test/java/com/ash/lake/load/SharedTableSliceTest.java` | 132 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/TableDump.java` | `src/test/java/com/ash/lake/load/TableDump.java` | 233 |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/ThroughputTest.java` | `src/test/java/com/ash/lake/load/ThroughputTest.java` | 134 |
| `lake/drishti-lakeload/src/test/resources/childkinds/deal.2026-11-03.jsonl` | `src/test/resources/childkinds/deal.2026-11-03.jsonl` | 5 |
| `lake/drishti-lakeload/src/test/resources/childkinds/edge.jsonl` | `src/test/resources/childkinds/edge.jsonl` | 5 |
| `lake/drishti-lakeload/src/test/resources/childkinds/expected-deal-leg.jsonl` | `src/test/resources/childkinds/expected-deal-leg.jsonl` | 5 |
| `lake/drishti-lakeload/src/test/resources/childkinds/expected-edge.jsonl` | `src/test/resources/childkinds/expected-edge.jsonl` | 5 |
| `lake/drishti-lakeload/src/test/resources/childkinds/pack.yaml` | `src/test/resources/childkinds/pack.yaml` | 28 |
| `lake/drishti-lakeload/src/test/resources/logback-test.xml` | `src/test/resources/logback-test.xml` | 24 |

### `java/lake-tck` (6 files)

| From (Drishti) | To | Lines |
|---|---|---|
| `testing/drishti-testkit/src/main/java/com/ash/drishti/testkit/FakeProxy.java` | `src/main/java/com/ash/lake/tck/FakeProxy.java` | 299 |
| `testing/drishti-testkit/src/main/java/com/ash/drishti/testkit/TestPki.java` | `src/main/java/com/ash/lake/tck/TestPki.java` | 293 |
| `testing/drishti-testkit/src/main/java/com/ash/drishti/testkit/TlsFixture.java` | `src/main/java/com/ash/lake/tck/TlsFixture.java` | 121 |
| `testing/drishti-testkit/src/main/java/com/ash/drishti/testkit/s3/S3Endpoint.java` | `src/main/java/com/ash/lake/tck/s3/S3Endpoint.java` | 69 |
| `testing/drishti-testkit/src/main/java/com/ash/drishti/testkit/s3/S3MockServer.java` | `src/main/java/com/ash/lake/tck/s3/S3MockServer.java` | 78 |
| `testing/drishti-testroots/src/main/java/com/ash/drishti/testroots/TestRoots.java` | `src/main/java/com/ash/lake/tck/TestRoots.java` | 70 |

### Iceberg fixtures (301 files)

`plugins/drishti-plugin-iceberg/src/test/resources/iceberg-fixtures/**` to `java/lake-iceberg/src/test/resources/iceberg-fixtures/**`, every
file (written by the official Iceberg library; the native reader's and writer's interop oracle).

### Loader fixtures (21 files)

`tools/lake/testdata/archives/**` and `tools/lake/testdata/file-dates/**` to `tck/fixtures/archives/**` and `tck/fixtures/file-dates/**`:
the archives and file names both loaders (Java and Python) are proven on; the moved Java tests read them here.

## 2. Divides (moved at L3, cut later)

| File (here) | Product half | Host half (stays in Drishti) | Step |
|---|---|---|---|
| `lake-config/.../config/Lakes.java` | the lake-file model: read, check, locate | the walk over modules' class loaders and the shipped index (`ModuleLoaders`, `NamedResources`, `drishti/lakes/shipped.txt`) | L5 |
| `lake-config/.../config/LakeFiles.java` | adding tables to a lake file | pack folders, `ConfigResolution`, `DomainVocabulary` | L5 |
| `lake-config/.../config/ModuleConnectors.java` | none | all: module templates (`ModuleTemplates`) | L5 (leaves) |
| `lake-config/.../config/PackEntry.java` | none | all: a pack's lake entry | L5 (leaves) |
| `lake-config/.../config/ConnectorLake.java` | none | all: a connector's `lake:` expansion | L5 (leaves) |
| `lake-config/.../config/RemovedKinds.java` | none | all | L5 (leaves) |
| `lake-config/.../config/LakesCli.java` | the `lakes` verbs of `lake-cli` | settings-file placeholders | L5 |
| `lake-load/.../inbox/LoaderProcess.java` | the loader process | module homes (`ModuleHomes`) | L4 |
| `lake-load/.../inbox/ReadyDesk.java` | the hand-over desk | `DRS-` errors become `LK-` (`DrishtiException`) | L4 |
| `lake-io/.../io/S3Settings.java`, `S3Storage.java`; `lake-delta/.../NativeEngine.java`, `NativeFileIO.java`; `lake-iceberg/.../catalog/RestCatalog.java` | the storage | TLS and proxy settings become the product's own records | L4 |
| `plugins/drishti-connectors/src/main/java/com/ash/drishti/plugin/file/CsvReader.java` (not moved yet) | | files store: read and write of `<root>/<domain>/<date>/<kind>.jsonl` moves into `lake-files`; the plug-in half stays | L8 |
| `plugins/drishti-connectors/src/main/java/com/ash/drishti/plugin/file/FileDatedIndex.java` (not moved yet) | | files store: read and write of `<root>/<domain>/<date>/<kind>.jsonl` moves into `lake-files`; the plug-in half stays | L8 |
| `plugins/drishti-connectors/src/main/java/com/ash/drishti/plugin/file/FileSet.java` (not moved yet) | | files store: read and write of `<root>/<domain>/<date>/<kind>.jsonl` moves into `lake-files`; the plug-in half stays | L8 |
| `plugins/drishti-connectors/src/main/java/com/ash/drishti/plugin/file/JsonlDay.java` (not moved yet) | | files store: read and write of `<root>/<domain>/<date>/<kind>.jsonl` moves into `lake-files`; the plug-in half stays | L8 |
| `plugins/drishti-connectors/src/main/java/com/ash/drishti/plugin/file/JsonlFormat.java` (not moved yet) | | files store: read and write of `<root>/<domain>/<date>/<kind>.jsonl` moves into `lake-files`; the plug-in half stays | L8 |
| `plugins/drishti-connectors/src/main/java/com/ash/drishti/plugin/file/JsonlLoader.java` (not moved yet) | | files store: read and write of `<root>/<domain>/<date>/<kind>.jsonl` moves into `lake-files`; the plug-in half stays | L8 |
| `tools/contracts.py` (not moved yet) | | reader, checker and page generator move (`lake-contracts`, `drishti_lake.contracts`); the 143 contract files and the measure documents stay | L11 |

## 3. Moves later

| File (Drishti) | Step | Why not now |
|---|---|---|
| `tools/lake/archives.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/build.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/childload.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/cli.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/compare_lakes.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/delete.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/deltaread.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/filedate.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/icebergwrite.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/javaload.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/jsonstream.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/lakeconfig.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/laketables.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/maintain.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/maintain_iceberg.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/maintain_rows.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/maintconfig.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/maintschedule.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/memory_caps.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/memory_cli.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/ownership.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/relocate.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/s3sim.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/scan.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/stores_equality.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_archives.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_build.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_delete.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_delta_interop.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_filedate.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_javaload.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_lakeconfig.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_laketables.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_maintain.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_maintain_admin.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_maintain_iceberg.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_maintain_rows.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_memory_caps.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_names.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_older_layout.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_retention.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_s3_simulators.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_undo.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/test_vectors.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/undo.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/writers.py` | L10 | the Python path: `python/drishti_lake`, cut from `resolved`, `javacmd`, `packlake`, `samplegen` |
| `tools/lake/testdata/delta-features/column-mapping/_delta_log/00000000000000000000.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/column-mapping/_delta_log/00000000000000000001.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/column-mapping/be/part-00000-7bb39f5b-0cb8-4296-ad75-3a4342438aa0-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/column-mapping/d7/part-00000-f091ab8f-477b-4bb7-8072-d8fa9ce800ba-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/_delta_log/00000000000000000000.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/_delta_log/00000000000000000001.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/_delta_log/00000000000000000002.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/_delta_log/00000000000000000003.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/_delta_log/00000000000000000004.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/_delta_log/00000000000000000005.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/_delta_log/00000000000000000006.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/_delta_log/00000000000000000007.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/business_date=2026-09-29/part-00000-2e42af68-2a58-4e33-9cad-c66ba7724661-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/business_date=2026-09-29/part-00000-39473f53-2092-4a76-b5a2-fb38f385a8b5-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/business_date=2026-09-29/part-00000-3b697c7c-d9ee-48d9-95f0-13a63061e20c-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/business_date=2026-09-30/part-00000-16e397ee-2969-458e-9fa5-cbd2a9b1a014-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/business_date=2026-09-30/part-00000-a2cc265d-7ca6-482b-b39c-51e583f6dba1-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/business_date=2026-09-30/part-00000-da825085-1b3d-46d2-b87e-b959b91c3e81-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/deletion_vector_59ae97a2-15c1-4f8f-b795-60982d2c349a.bin` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/deletion-vectors/deletion_vector_a8d4065c-6bf3-4caa-9076-3fc0511bed56.bin` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/_delta_log/00000000000000000000.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/_delta_log/00000000000000000001.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/_delta_log/00000000000000000002.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/_delta_log/00000000000000000003.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/_delta_log/00000000000000000004.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/_delta_log/00000000000000000005.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/business_date=2026-09-29/part-00000-2e42af68-2a58-4e33-9cad-c66ba7724661-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/business_date=2026-09-29/part-00000-39473f53-2092-4a76-b5a2-fb38f385a8b5-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/business_date=2026-09-29/part-00000-3b697c7c-d9ee-48d9-95f0-13a63061e20c-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/business_date=2026-09-30/part-00000-16e397ee-2969-458e-9fa5-cbd2a9b1a014-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/business_date=2026-09-30/part-00000-a2cc265d-7ca6-482b-b39c-51e583f6dba1-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/plain/business_date=2026-09-30/part-00000-da825085-1b3d-46d2-b87e-b959b91c3e81-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/_delta_log/00000000000000000006.checkpoint.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/_delta_log/00000000000000000006.json` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/_delta_log/_last_checkpoint` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/business_date=2026-09-29/part-00000-2e42af68-2a58-4e33-9cad-c66ba7724661-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/business_date=2026-09-29/part-00000-39473f53-2092-4a76-b5a2-fb38f385a8b5-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/business_date=2026-09-29/part-00000-3b697c7c-d9ee-48d9-95f0-13a63061e20c-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/business_date=2026-09-30/part-00000-16e397ee-2969-458e-9fa5-cbd2a9b1a014-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/business_date=2026-09-30/part-00000-a2cc265d-7ca6-482b-b39c-51e583f6dba1-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `tools/lake/testdata/delta-features/v2-checkpoint/business_date=2026-09-30/part-00000-da825085-1b3d-46d2-b87e-b959b91c3e81-c000.snappy.parquet` | L10 | fixture of the Python path (reviewed again at L10: synthetic only) |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/LakeStore.java` | L7 | opens a Delta lake through the engine the settings choose; `LakeStore` names `HadoopLake` (LK-9), so it moves with the `TableStore` work |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/NamedTables.java` | L7 | opens a Delta lake through the engine the settings choose; `LakeStore` names `HadoopLake` (LK-9), so it moves with the `TableStore` work |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/NativeLake.java` | L7 | opens a Delta lake through the engine the settings choose; `LakeStore` names `HadoopLake` (LK-9), so it moves with the `TableStore` work |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/NewTables.java` | L7 | opens a Delta lake through the engine the settings choose; `LakeStore` names `HadoopLake` (LK-9), so it moves with the `TableStore` work |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/HostChecks.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/MemoryBinding.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/MemoryCompactor.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/MemoryLake.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/MemoryLakeSettings.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/MemoryTable.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/MemoryUpkeep.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/ReplenishSource.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/Retention.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/package-info.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/store/ChunkPool.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/store/MemoryStorage.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/store/MemoryStorageProvider.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/store/MemoryStoreFullException.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/store/MemoryStores.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/java/com/ash/drishti/memlake/store/package-info.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/DeleteDatesParityTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/DeltaInMemoryTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/IcebergInMemoryTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/MemoryLakeTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/MemoryRatiosTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/RetentionParityTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/RetentionTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/RowRetentionTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/store/MemoryStorageContractTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/memlake/store/MemoryStorageTest.java` | L8 | the memory lake: moves into `lake-memory` behind `RowSource`; `ReplenishSource`, `Retention`, `MemoryBinding` name the host's connector registry and keep their host half |
| `lake/drishti-lakeload/src/main/resources/META-INF/services/com.ash.drishti.lakeio.StorageProvider` | L8 | registers the memory storage (`mem://`); `LakeRuntime` (L6) registers providers per instance instead |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/FileCoverageTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/FileCsvDeprecationTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/FileEffectiveReverseTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/FileHealthWarningsTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/FileMultiDayTypeAheadTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/FileReadFailureTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/FileSourcePluginTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/FileTableIdentityTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/JsonlFilesTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/JsonlHostileLinesTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |
| `plugins/drishti-connectors/src/test/java/com/ash/drishti/plugin/file/JsonlMixedColumnsTest.java` | L8 | files-store tests: those of the read and write half follow it into `lake-files`; the `SourcePlugin` ones stay |

## 4. Stays in Drishti

| File (Drishti) | Why |
|---|---|
| `lake/drishti-lakeio/src/main/resources/drishti/lakes/capitalmarket.yaml` | the host's shipped lake files (design 2.1: `config/lakes/*.yaml` stay) |
| `lake/drishti-lakeio/src/main/resources/drishti/lakes/demo.yaml` | the host's shipped lake files (design 2.1: `config/lakes/*.yaml` stay) |
| `lake/drishti-lakeio/src/main/resources/drishti/lakes/newslake.yaml` | the host's shipped lake files (design 2.1: `config/lakes/*.yaml` stay) |
| `lake/drishti-lakeio/src/main/resources/drishti/lakes/referencelake.yaml` | the host's shipped lake files (design 2.1: `config/lakes/*.yaml` stay) |
| `lake/drishti-lakeio/src/main/resources/drishti/lakes/regulators.yaml` | the host's shipped lake files (design 2.1: `config/lakes/*.yaml` stay) |
| `lake/drishti-lakeio/src/main/resources/drishti/lakes/risklake.yaml` | the host's shipped lake files (design 2.1: `config/lakes/*.yaml` stay) |
| `lake/drishti-lakeio/src/main/resources/drishti/lakes/shipped.txt` | the host's shipped lake files (design 2.1: `config/lakes/*.yaml` stay) |
| `lake/drishti-lakeio/src/main/resources/drishti/lakes/worldlake.yaml` | the host's shipped lake files (design 2.1: `config/lakes/*.yaml` stay) |
| `lake/drishti-lakeio/src/test/java/com/ash/drishti/lakeconfig/LakeFileWritesTest.java` | reads the host's shipped demo lake file; its cases go to the TCK with a lake file of the kit's own (L5) |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/AppendNewestLoadTest.java` | reads through the host's `DeltaSourcePlugin`/`IcebergSourcePlugin` (`DatedSourceContract`); its cases go to the TCK (L2/L9) |
| `lake/drishti-lakeload/src/test/java/com/ash/drishti/lakeload/PythonPathEqualityTest.java` | reads through the host's `DeltaSourcePlugin`/`IcebergSourcePlugin` (`DatedSourceContract`); its cases go to the TCK (L2/L9) |
| `plugins/drishti-connectors/src/main/java/com/ash/drishti/plugin/file/FileSourcePlugin.java` | adapter: the files store's `SourcePlugin` half |
| `plugins/drishti-connectors/src/main/java/com/ash/drishti/plugin/file/package-info.java` | the adapter package's description |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/DeltaLakeFiles.java` | adapter: implements the host's `SourcePlugin` (LK-4) |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/DeltaSourcePlugin.java` | adapter: implements the host's `SourcePlugin` (LK-4) |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/MappedIdMap.java` | adapter: implements the host's `SourcePlugin` (LK-4) |
| `plugins/drishti-plugin-iceberg-external/src/main/java/com/ash/drishti/plugin/icebergext/IcebergExternalPlugin.java` | adapter: implements the host's `SourcePlugin` (LK-4) |
| `plugins/drishti-plugin-iceberg-external/src/main/java/com/ash/drishti/plugin/icebergext/StarterSchemas.java` | adapter: implements the host's `SourcePlugin` (LK-4) |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/IcebergSourcePlugin.java` | adapter: implements the host's `SourcePlugin` (LK-4) |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/MappedIdMap.java` | adapter: implements the host's `SourcePlugin` (LK-4) |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/EngineKind.java` | the Delta `hadoop` engine (LK-9, provisional): stays in the host's adapter |
| `plugins/drishti-plugin-delta/src/main/java/com/ash/drishti/plugin/delta/HadoopLake.java` | the Delta `hadoop` engine (LK-9, provisional): stays in the host's adapter |
| `plugins/drishti-plugin-delta/src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin` | the plug-in registration of the adapter |
| `plugins/drishti-plugin-iceberg-external/src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin` | the plug-in registration of the adapter |
| `plugins/drishti-plugin-iceberg/src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin` | the plug-in registration of the adapter |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeletionVectorFixture.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaAfterMaintenanceTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaColumnMappingTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaDeletionVectorTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaDocDiskCacheTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaDocStalenessTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaLayoutTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaLz4Test.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaNewestLoadTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaOnAwsS3Test.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaOnS3Test.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaParallelVectorsTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaPartitionColumnTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaSourcePluginTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaUnreadableTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaVectorColumnTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaWarmUpTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/MappedIdMapTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/NamedTablesTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/NewTablesTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg-external/src/test/java/com/ash/drishti/plugin/icebergext/ExternalFixtures.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg-external/src/test/java/com/ash/drishti/plugin/icebergext/IcebergExternalPluginTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/GlueLiveTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergDiskCacheTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergDocStalenessTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergEnginesBenchTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergLayoutNativeTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergLayoutTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergMovedLoaderTableTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergMovedTableTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergNamedTableTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergNativeNoHadoopTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergOrphanFilesTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergParallelVectorsTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergParityTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergRestCatalogNativeTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergRestCatalogTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergS3TablesTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergSourcePluginNativeTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergSourcePluginTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/IcebergVectorsTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/NativeOnS3SimulatorTest.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/NativeOnS3Test.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/OfficialS3Test.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/VectorLake.java` | tests the adapter (`SourcePlugin`, `DatedSourceContract`); the table-layer cases go to the TCK (L2/L9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaEnginesAgreeTest.java` | tests the Delta `hadoop` engine (LK-9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaLayoutHadoopTest.java` | tests the Delta `hadoop` engine (LK-9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaNativeNoHadoopTest.java` | tests the Delta `hadoop` engine (LK-9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaOnS3HadoopTest.java` | tests the Delta `hadoop` engine (LK-9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/DeltaSourcePluginHadoopTest.java` | tests the Delta `hadoop` engine (LK-9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/EngineKindTest.java` | tests the Delta `hadoop` engine (LK-9) |
| `plugins/drishti-plugin-delta/src/test/java/com/ash/drishti/plugin/delta/HadoopLakeProxyTest.java` | tests the Delta `hadoop` engine (LK-9) |
| `plugins/drishti-plugin-delta/src/test/resources/lake-codecs/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-delta/src/test/resources/lake-colmap/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-delta/src/test/resources/lake-layout-colmap/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-delta/src/test/resources/lake-layout/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-delta/src/test/resources/lake-partitioned-colmap/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-delta/src/test/resources/lake-partitioned/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-delta/src/test/resources/lake-vectors-colmap/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-delta/src/test/resources/lake-vectors/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-delta/src/test/resources/lake/**` | fixtures of the adapter tests |
| `plugins/drishti-plugin-iceberg/src/main/java/com/ash/drishti/plugin/iceberg/IcebergDiskCaches.java` | waits on the host's `DiskCache` (LK-10) |
| `plugins/drishti-plugin-iceberg/src/test/java/com/ash/drishti/plugin/iceberg/PyicebergReadsNativeTest.java` | runs the host's `tools/iceberg/readback.py`; becomes a TCK interop case at L9 |
| `tools/lake/demo.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/feedfiles.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/feedroute.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/publish.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/stage.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/stores.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/test_demo.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/test_feedfiles.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/test_feedroute.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/test_publish.py` | demo, feeds and source systems (design 2.1) |
| `tools/lake/test_stores.py` | demo, feeds and source systems (design 2.1) |

Also stays, whole (design 2.1): `tools/mrloader`; every `*.contract.yaml` and `docs/contracts`; `config/lakes/*.yaml`; the server's
wiring (`server.dataload`, `server.memlake`, `server.lakes`, `server.flight`); `VarReadyRule`, `SourceSystemReadyRule`; `DomainVocabulary`;
the demo lake builds; the rest of `drishti-testkit` (`DatedSourceContract`, 36 uses, tests `SourcePlugin`).

## 5. Copied into `hostcompat` (not moved)

See [SPLIT_EXEMPTIONS](../java/SPLIT_EXEMPTIONS.md): each host type the moved code still names, with the step that removes it.
