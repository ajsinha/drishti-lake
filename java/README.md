<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# The Java reactor

`./mvnw clean verify` (JDK 21 or later) builds and tests every module; `-pl <module> -am` one module and what it needs. Never
`install` from a working tree: releases are installed from a tag only (decision R-1, as Drishti Rachana).

| Module | Packages | Depends on |
|---|---|---|
| `lake-hostcompat` | `com.ash.lake.hostcompat` (temporary; [SPLIT_EXEMPTIONS](SPLIT_EXEMPTIONS.md)) | Jackson |
| `lake-io` | `com.ash.lake.io`, `com.ash.lake.common` | hostcompat; Parquet, AWS SDK S3, snappy, zstd, lz4, Caffeine |
| `lake-config` | `com.ash.lake.config` | hostcompat; Jackson YAML |
| `lake-delta` | `com.ash.lake.delta` (`.read`, `.write`, `.table`) | lake-io; Delta Kernel 4.4.0 (native engine) |
| `lake-iceberg` | `com.ash.lake.iceberg` (`.catalog`, `.expr`, `.io`, `.manifest`, `.meta`, `.read`, `.scan`, `.types`, `.write`, `.table`, `.external`) | lake-io; iceberg-core 1.10.1, iceberg-aws |
| `lake-load` | `com.ash.lake.load`, `com.ash.lake.inbox` | lake-io, lake-config, lake-delta, lake-iceberg |
| `lake-tck` | `com.ash.lake.tck` (S3 mock, TLS and proxy fixtures, test roots) | test support only |
| `lake-guards` | the guards | nothing |

Third-party versions are the host's at the freeze commit (`java/pom.xml` properties); until `lake-bom` (L12) the host's Spring Boot
bill of materials is imported for versions only (an exemption). The enforcer refuses any `com.ash.drishti` artifact and any Spring
artifact outside test scope.

Tests: four JVMs (`lake.test.forks`), each with its temp folder under the module's `target/tmp`; the setting names the moved tests
still read (`drishti.data.dir`, `drishti.repo.root`, ...) are set to absolute folders by the surefire configuration until L4.
