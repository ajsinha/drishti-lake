<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Split exemptions

The lake code came from its first host with its history **before** it was
untangled, so for a while it still names host types, reads host setting names and uses a few things the design's rules forbid. Each
is listed below with the step that removes it. The guards in `lake-guards` read this file (`ExemptionsOnlyShrinkTest`,
`NamesNoHostTest`): a host type not listed here fails the build, a listed one no longer needed fails it too, and every count may only
go down from its value at the move. **This list only shrinks**; at `v1.0.0` it is empty and the file is deleted.

## Counts (read by the guards)

- hostcompat types: 0
- host-word files: 66
- parked checks: 1

## Dependencies

| What | Where | Until |
|---|---|---|
| `com.adobe.testing:s3mock` (a Spring Boot application) and the 25 versions pinned for it alone in `java/pom.xml` | `lake-tck` (optional: no module gets it unless it declares it; `lake-load` in test scope), whose enforcer run is off for that reason | decided at L9: it stays as the S3 simulator (maintained, in-process, no Docker or native binary, so it runs on Windows); test support only, never on a run-time class path; moves from this list to the dependency allow-list's test-support section when the list is emptied (v1.0.0) |
| Hadoop client (`hadoop-client-api`, `hadoop-client-runtime`) on the class path of `lake-io` and `lake-delta`: Parquet's writer and the Delta kernel's defaults name Hadoop types; no Hadoop file system is used (`NoHadoopFileSystemTest`; the Hadoop engine is not in the product, LK-9, `LakeStore` native only) | `lake-io`, `lake-delta` | L12 (reviewed at L7: `parquet-hadoop` and the Kernel's default engine need Hadoop's `Configuration` on the class path; a Hadoop-free Parquet path is the way out) |

## Host types

None since step L7: the last six (`ColumnSet`, `IdText`, `ListValues`, `UnreadableData`, `VectorReads`, `VectorSink`) are the
product's own row, column and vector types (`com.ash.lake.rows`, module `lake-io`), and the module `lake-hostcompat` is gone.

| Type | Host type | Removed by |
|---|---|---|

## Setting names and host words (read by `NamesNoHostTest`)

The product reads its own settings only, through `LakeOptions` (`lake.*` names, `LAKE_*` variables; the surefire configuration in
`java/pom.xml` sets `lake.home`, `lake.data.dir`, `lake.repo.root`, `lake.module.root`, absolute). What still carries a host word: the lake
files' placeholder text `${drishti.data.dir:./data}` and the demo root `DRISHTI_DELTA_ROOT` (configuration the host writes and sets,
read as text; the connector-migration helpers name the host's connector files: L12 decides whether they stay in the product). The
**persisted names** are decided (L7, `PersistedNames`): the product writes `lake.<name>` into Iceberg snapshot summaries and table
properties (`lake.business-dates`, `lake.load-ids`, `lake.layout.*`; the per-column writer options are `lake.parquet.*`) and reads
`lake.<name>` first, then the host's `drishti.<name>`, so tables written before the split read the same and nothing is rewritten; a lake
file's `${drishti.x}` and `${lake.x}` resolve each other's value when only one is set; the Delta commit's engine info names the
product ("Drishti Lake"). Left: `DRS-` codes in messages, source-system names (`trade_endur`) and the host's
name in comments (L13 the prose). The count of such files is the "host-word files" line above.

## Parked checks

A test, or an assertion inside one, that reads the host's own data (its shipped packs, its shipped lake files) is marked
`SPLIT EXEMPTION (parked` where it stood; the count is the "parked checks" line above.

| Check | Reads | Returns at |
|---|---|---|
| `CodecsTest.aPackGivesTheDomainTableLayoutKeyAndFlattenedFieldsAsLakeBuildReadsThem` | the host's shipped packs `market-risk` and `trading` | L9: a pack of the kit's own (the parity kit's pack cases) |

