<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Split exemptions

The lake code moved here from its host with history (step L3, [MOVE_ALLOWLIST](../docs/MOVE_ALLOWLIST.md)) **before** it was
untangled, so for a while it still names host types, reads host setting names and uses a few things the design's rules forbid. Each
is listed below with the step that removes it. The guards in `lake-guards` read this file (`ExemptionsOnlyShrinkTest`,
`NamesNoHostTest`): a host type not listed here fails the build, a listed one no longer needed fails it too, and every count may only
go down from its value at the move. **This list only shrinks**; at `v1.0.0` it is empty and the file is deleted.

## Counts (read by the guards)

- hostcompat types: 23
- host-word files: 79
- parked checks: 2

## Dependencies

| What | Where | Until |
|---|---|---|
| `org.springframework.boot:spring-boot-dependencies` imported as a bill of materials (versions only: the host's parent chose Jackson, SLF4J, JUnit, AssertJ, Logback and the libraries' shared transitive versions; no Spring artifact reaches a compile or run-time class path, the enforcer says so) | `java/pom.xml` | L12: `lake-bom` pins its own versions |
| `org.jetbrains.kotlin:kotlin-bom` (the host's override of its parent's Kotlin version, for the S3 mock) | `java/pom.xml` | with the S3 mock |
| `com.adobe.testing:s3mock` (a Spring Boot application; test support only, in `lake-tck`, whose enforcer run is off for that reason) | `lake-tck`, test scope elsewhere | L9: the TCK's storage cases decide the S3 simulator |
| Hadoop client (`hadoop-client-api`, `hadoop-client-runtime`) on the class path of `lake-io` and `lake-delta`: Parquet's writer and the Delta kernel's defaults name Hadoop types; no Hadoop file system is used (`NoHadoopFileSystemTest`) | `lake-io`, `lake-delta` | L7 (reviewed with the `TableStore` surface) |

## Host types

In package `com.ash.lake.hostcompat` (module `lake-hostcompat`; `RowFeed` sits in `lake-io` because it names `FileDates`). Copied
from the host at the freeze commit (Drishti `main` `9ce7c3170`) with only their package changed, so behaviour stays as the host's;
two edits: `ModuleLoaders` lost the static block that joined the host's `DatesInUse` registry, and `TlsContexts` logs as
`com.ash.lake.hostcompat.tls`.

| Type | Host type | Removed by |
|---|---|---|
| `ConnectorSecrets` | `com.ash.drishti.common.ConnectorSecrets` | L5: with `NamedResources` (the connector half leaves for the host's `LakeCatalog`) |
| `DataDir` | `com.ash.drishti.api.DataDir` | L4: `LakeOptions` and the product's own small utilities (`RowFeed`: the loader's rules, taken into `lake-load`) |
| `ModuleHomes` | `com.ash.drishti.api.resolution.ModuleHomes` | L4: `LakeOptions` and the product's own small utilities (`RowFeed`: the loader's rules, taken into `lake-load`) |
| `Placeholders` | `com.ash.drishti.settingsfile.Placeholders` | L4: `LakeOptions` and the product's own small utilities (`RowFeed`: the loader's rules, taken into `lake-load`) |
| `Roots` | `com.ash.drishti.api.Roots` | L4: `LakeOptions` and the product's own small utilities (`RowFeed`: the loader's rules, taken into `lake-load`) |
| `RowFeed` | `com.ash.drishti.common.RowFeed` | L4: `LakeOptions` and the product's own small utilities (`RowFeed`: the loader's rules, taken into `lake-load`) |
| `SettingsFile` | `com.ash.drishti.settingsfile.SettingsFile` | L4: `LakeOptions` and the product's own small utilities (`RowFeed`: the loader's rules, taken into `lake-load`) |
| `Extensions` | `com.ash.drishti.common.Extensions` | L5: with `Vocabularies` (the pack and module half leaves for the host's `LakeCatalog`) |
| `ConfigResolution` | `com.ash.drishti.api.resolution.ConfigResolution` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `DomainVocabulary` | `com.ash.drishti.kernel.api.vocabulary.DomainVocabulary` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `ModuleLoaders` | `com.ash.drishti.common.ModuleLoaders` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `ModuleTemplates` | `com.ash.drishti.api.resolution.ModuleTemplates` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `NamedResources` | `com.ash.drishti.common.NamedResources` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `PackFolders` | `com.ash.drishti.api.PackFolders` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `PackResolver` | `com.ash.drishti.api.resolution.PackResolver` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `ResolutionSettings` | `com.ash.drishti.api.resolution.ResolutionSettings` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `Vocabularies` | `com.ash.drishti.kernel.api.vocabulary.Vocabularies` | L5: the pack and module half leaves for the host's `LakeCatalog` |
| `ColumnSet` | `com.ash.drishti.api.ColumnSet` | L7: the product's own row, column and vector types (`TableStore`) |
| `IdText` | `com.ash.drishti.api.IdText` | L7: the product's own row, column and vector types (`TableStore`) |
| `ListValues` | `com.ash.drishti.api.ListValues` | L7: the product's own row, column and vector types (`TableStore`) |
| `UnreadableData` | `com.ash.drishti.api.UnreadableData` | L7: the product's own row, column and vector types (`TableStore`) |
| `VectorReads` | `com.ash.drishti.api.VectorReads` | L7: the product's own row, column and vector types (`TableStore`) |
| `VectorSink` | `com.ash.drishti.api.VectorSink` | L7: the product's own row, column and vector types (`TableStore`) |

## Setting names and host words (read by `NamesNoHostTest`)

The moved code still reads the host's setting names (`drishti.data.dir`, `drishti.repo.root`, `drishti.module.root`,
`drishti.home`, the `DRISHTI_*` variables: the surefire configuration in `java/pom.xml` sets the first ones, absolute) and carries
`DRS-` codes, source-system names (`trade_endur`) and the host's name in comments. The count of such files is the "host-word files"
line above. L4 removes the settings and codes, L5 the pack and source-system words, L13 the rest of the prose.

## Parked checks

A test, or an assertion inside one, that reads the host's own data (its shipped packs, its shipped lake files) is marked
`SPLIT EXEMPTION (parked` where it stood; the count is the "parked checks" line above.

| Check | Reads | Returns at |
|---|---|---|
| `LakesTest.aCatalogLakeNeedsNoRootAndTheDemoLakeIsShipped` (its last two lines) | the host's shipped demo lake file | L5: the host's adapter checks its shipped lakes; this repository's catalog test covers the rest |
| `CodecsTest.aPackGivesTheDomainTableLayoutKeyAndFlattenedFieldsAsLakeBuildReadsThem` | the host's shipped packs `market-risk` and `trading` | L5: a pack of the kit's own through `LakeCatalog` |

Tests that stayed in the host whole (they test its adapters, or read through its `SourcePlugin`s) are listed in
[MOVE_ALLOWLIST](../docs/MOVE_ALLOWLIST.md) section 4.
