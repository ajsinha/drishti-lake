---
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE.
name: Drishti Lake
summary: >-
  A Java library and a Python package that load and read Delta, Iceberg and plain-file lakes as one lake: the lake file, the loader and
  its inbox, retention, undo and relocation, the read API, data contracts and an in-memory lake, on the official libraries, with a
  parity kit that holds every store to the same behaviour.
coordinates: {groupId: com.ash.lake, package: com.ash.lake, python: drishti_lake}
java: 21
python: 3.11
modules: [lake-bom, lake-api, lake-io, lake-config, lake-delta, lake-iceberg, lake-files, lake-load, lake-memory, lake-contracts, lake-tck, lake-guards]
depends-on: [Drishti Tools (com.ash.tools), delta-kernel, iceberg-core, parquet, AWS SDK 2; Python: deltalake, pyarrow, pyyaml, jsonschema]
commands: {build: "cd java && ./mvnw clean verify", check: "python3 tools/ci/check.py", tck: "python3 tools/tck/tck.py --out results.jsonl"}
docs: [docs/BUILD_AND_IDE.md, docs/guides/, docs/design/]
licence: proprietary (LICENSE)
---

# Drishti Lake

**Delta, Iceberg and plain files, loaded and read as one lake.**

Drishti Lake is the data-lake layer a risk or analytics system needs to keep its lake right. One library reads a lake file; writes and reads
Delta and Iceberg tables through the official libraries (Delta Kernel, iceberg-core, Parquet) and plain JSON Lines day files through the same
interface; loads JSON Lines (plain, gzip, zstd, zip, tar) concurrently; takes hand-overs from an inbox (`.ready` files); and keeps tables healthy
(retention, delete by date, undo of a load, relocation, Iceberg maintenance). The three stores are **feature-identical**, local, on S3 or in memory,
and a parity kit proves it case by case. It is not a query engine and not a catalog service.

Author: Ashutosh Sinha. Proprietary. All rights reserved (see [LICENSE](LICENSE)).

## What it is made of

| Part | What it does |
|---|---|
| `lake-api` | the facade (`LakeRuntime`, `LakeOptions`), the SPI (`TableStore`, `StorageProvider`, `LakeCatalog`, `Capabilities`) and the read API (`LakeReader`, `TableSnapshot`) |
| `lake-io` | storage (local disk, S3 with TLS and proxies), ranged reads, cached Parquet footers, codecs |
| `lake-config` | the lake file: tables, roots, partitions, locations, checks |
| `lake-delta`, `lake-iceberg`, `lake-files` | the three stores; Iceberg catalogs: files, REST, S3 Tables, Glue |
| `lake-load` | the loader (archives, child kinds, modes, rejects), undo, retention, check, and the inbox (`.ready` hand-over, ledger, routes) |
| `lake-memory` | the in-memory lake (`mem://`) with a hard cap, retention and a replenish source |
| `lake-contracts` | data contracts: the reader and the row checker |
| `lake-tck`, `lake-guards` | test support (S3 simulator, delta-rs runner) and the repository's guards |
| `python/drishti_lake` | the Python path: the loader for Delta on the same configuration, contracts, a front for the Java tools |
| `tck/`, `tools/` | the parity kit (cases as data, reference results), its runner, the checks, the release and benchmark scripts |

## Install and build

The Drishti family installs in this order: **Rachana (tag) -> Drishti Tools (tag) -> Drishti Lake (tag) -> Drishti**; each from its release tag into
`~/.m2/repository/com/ash/<product>/` (Windows `C:\Users\<user>\.m2\repository\com\ash\<product>\`), never from a branch.

```text
cd java && ./mvnw clean verify       # JDK 21 or later; every module, test and guard
python3 tools/ci/check.py            # the whole check: build, delta-rs interop, Python tests, the parity kit twice and its gates
```

## Build and run in the IDE

**IntelliJ IDEA**: open `java/pom.xml`; run configurations in `java/run-configs/` (folder **Lake**). **PyCharm**: open the repository root; launchers in
`tools/run-configs/`; the Python package is `python/`. **VS Code**: open `drishti-lake.code-workspace`. JDK 21 as the project SDK in every case. Steps,
the Windows `PKIX` fix and the install order: [docs/BUILD_AND_IDE.md](docs/BUILD_AND_IDE.md).

## Documents

| Document | What |
|---|---|
| [docs/BUILD_AND_IDE.md](docs/BUILD_AND_IDE.md) | prepare, build, install from a tag, IntelliJ, PyCharm, VS Code, Windows |
| [docs/guides/STORAGE.md](docs/guides/STORAGE.md) | the three stores and their parity, storage, the lake file, the runtime's options |
| [docs/guides/LOADER.md](docs/guides/LOADER.md) | the loader's configuration and rules, undo, retention, check, the inbox; a tutorial that was run |
| [docs/guides/READ.md](docs/guides/READ.md) | the read API, with a tutorial reading the same data from the three stores |
| [docs/guides/CONTRACTS.md](docs/guides/CONTRACTS.md) | data contracts: format, checks, pages ([generated samples](docs/contracts/README.md)) |
| [docs/guides/PYTHON.md](docs/guides/PYTHON.md) | the Python package and its commands |
| [docs/guides/TCK.md](docs/guides/TCK.md) | the parity kit: cases, matrix, gates, and what a backend author implements |
| [docs/BENCHMARK.md](docs/BENCHMARK.md) | the benchmark runs |
| [docs/design/](docs/design/) | design records: [the product](docs/design/DRISHTI_LAKE_DESIGN.md), [the read API](docs/design/READ_API.md) |
| [java/README.md](java/README.md), [java/SPLIT_EXEMPTIONS.md](java/SPLIT_EXEMPTIONS.md) | the reactor and what the code still owes its first host (the guards read it) |
| [tools/split/README.md](tools/split/README.md) | the reference-comparison tools |
| [CHANGELOG.md](CHANGELOG.md) | what changed when |
