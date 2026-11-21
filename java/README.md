<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# The Java reactor

`./mvnw clean verify` (JDK 21 or later) builds and tests every module; `-pl <module> -am` one module and what it needs. Never
`install` from a working tree: releases are installed from a tag only (decision R-1, as Drishti Rachana).

| Module | Packages | Depends on |
|---|---|---|
| `lake-bom` | | the product's own pinned third-party versions, for hosts to import |
| `lake-api` | `com.ash.lake` (`LakeRuntime`, `LakeOptions`, `LakeRoots`), `com.ash.lake.spi` (`TableStore`, `StorageProvider`, `Storage`, `LakeCatalog`, `RowSource`, `Capabilities`), `com.ash.lake.read` (`LakeReader`, `TableReader`, `TableSnapshot`) | the JDK; tools-settings |
| `lake-io` | `com.ash.lake.io`, `com.ash.lake.common`, `com.ash.lake.rows` | lake-api, tools-net; Parquet, AWS SDK S3, snappy, zstd, lz4, Caffeine |
| `lake-config` | `com.ash.lake.config` (the lake file) | lake-api; Jackson YAML |
| `lake-delta` | `com.ash.lake.delta` (`.read`, `.write`, `.table`) | lake-io, tools-net; Delta Kernel (native engine) |
| `lake-iceberg` | `com.ash.lake.iceberg` (`.catalog`, `.expr`, `.io`, `.manifest`, `.meta`, `.read`, `.scan`, `.types`, `.write`, `.table`, `.external`) | lake-io, tools-net, tools-concurrent, tools-time; iceberg-core, iceberg-aws |
| `lake-files` | `com.ash.lake.files` (the plain-file store) | lake-io |
| `lake-load` | `com.ash.lake.load` (the loader, undo, retention, check, relocate), `com.ash.lake.inbox` | lake-io, lake-config, lake-delta, lake-iceberg, lake-files, lake-contracts |
| `lake-memory` | `com.ash.lake.memory` (`.store`): the in-memory lake, `mem://` | lake-io, lake-config, lake-delta |
| `lake-contracts` | `com.ash.lake.contract` (`Contract`, `ContractChecker`) | Jackson YAML |
| `lake-tck` | `com.ash.lake.tck` (the S3 simulator, delta-rs runner, test roots) | test support only |
| `lake-guards` | the guards | nothing |

Third-party versions are pinned in `java/pom.xml` and exported to hosts by `lake-bom`; the product's own pins are the versions the build
resolves. The enforcer refuses any host artifact and any Spring artifact outside test scope.

Tests: four JVMs (`lake.test.forks`), each with its temp folder under the module's `target/tmp`. What the code still owes its first host, and
the guards that make that list only shrink: [SPLIT_EXEMPTIONS.md](SPLIT_EXEMPTIONS.md). Build, install and the IDE: [../docs/BUILD_AND_IDE.md](../docs/BUILD_AND_IDE.md).
