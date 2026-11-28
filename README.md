---
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE.
name: Drishti Lake
summary: >-
  A Java library and a Python package that load and read Delta, Iceberg and plain-file lakes as one lake: the lake file, the loader and
  its inbox, retention, undo and relocation, the read API, data contracts and an in-memory lake, on the official libraries, with a
  parity kit that holds every store to the same behaviour.
coordinates: {groupId: com.ash.lake, package: com.ash.lake, python: drishti_lake}
java: 21
python: 3.13 (minimum 3.11)
modules: [lake-bom, lake-api, lake-io, lake-config, lake-delta, lake-iceberg, lake-files, lake-load, lake-memory, lake-contracts, lake-tck, lake-guards]
depends-on: [Drishti Tools (com.ash.tools), delta-kernel, iceberg-core, parquet, AWS SDK 2; Python: deltalake, pyarrow, pyyaml, jsonschema]
commands: {build: "cd java && ./mvnw clean verify", check: "python3 tools/ci/check.py", tck: "python3 tools/tck/tck.py --out results.jsonl"}
docs: [docs/GUIDE.md, docs/DESIGN.md, docs/TCK.md, docs/BUILD_AND_IDE.md, docs/BENCHMARK.md, docs/ROADMAP.md]
licence: proprietary (LICENSE)
---

<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="brand/drishti_lake-logo.svg"><img src="brand/drishti_lake-logo-light.svg" alt="Drishti Lake" width="330"></picture></p>

# Drishti Lake

<p align="center"><b>One lake. Every store.</b></p>

**Delta, Iceberg and plain files, loaded and read as one lake.**

Drishti Lake is the data-lake layer a risk or analytics system needs to keep its lake right. One library reads a lake file; writes and reads
Delta and Iceberg tables through the official libraries (Delta Kernel, iceberg-core, Parquet) and plain JSON Lines day files through the same
interface; loads JSON Lines (plain, gzip, zstd, zip, tar) concurrently; takes hand-overs from an inbox (`.ready` files); and keeps tables healthy
(retention, delete by date, undo of a load, relocation, and one maintenance command for every store: compaction, checkpoint, vacuum). The
three stores are **feature-identical**, local, on S3 or in memory,
and a parity kit proves it case by case. It is not a query engine and not a catalog service.

Author: Ashutosh Sinha. Proprietary. All rights reserved (see [LICENSE](LICENSE)).

## What it is made of

| Part | What it does |
|---|---|
| `lake-api` | the facade (`LakeRuntime`, `LakeOptions`), the SPI (`TableStore`, `StorageProvider`, `LakeCatalog`, `Capabilities`) and the read API (`LakeReader`, `TableSnapshot`) |
| `lake-io` | storage (local disk, S3 with TLS and proxies), ranged reads, cached Parquet footers, codecs |
| `lake-config` | the lake file: tables, roots, partitions, locations, checks |
| `lake-delta`, `lake-iceberg`, `lake-files` | the three stores, each on local disk, S3 and memory; Iceberg catalogs: files, REST, S3 Tables, Glue |
| `lake-load` | the loader (archives, child kinds, modes, rejects), undo, retention, check, and the inbox (`.ready` hand-over, ledger, routes) |
| `lake-memory` | the in-memory lake (`mem://`) with a hard cap, retention and a replenish source |
| `lake-contracts` | data contracts: the reader and the row checker |
| `lake-tck`, `lake-guards` | test support (S3 simulator, delta-rs runner) and the repository's guards |
| `python/drishti_lake` | the Python path: the loader for Delta on the same configuration, contracts, a front for the Java tools |
| `tck/`, `tools/` | the parity kit (cases as data, reference results), its runner, the checks, the release and benchmark scripts |

## Where it fits the Drishti platform

Lake is the third product of the platform build. The platform's `build_all.py` (and its IntelliJ launcher `platform-clean-install`) builds
**Drishti Tools -> Drishti Rachana -> Drishti Lake -> Drishti** in order and installs each product's jars into the local Maven repository
(`~/.m2/repository/com/ash/lake/`, Windows `C:\Users\<user>\.m2\repository\com\ash\lake\`) before the next one builds. Lake builds against Drishti Tools
(`com.ash.tools`) and Drishti builds against Lake, each from its **release tag**, never a branch. The Python package `drishti_lake` runs the
loader for Delta on the same lake file and holds the data contracts.

## Requirements

| Need | Version | For |
|---|---|---|
| JDK | 21 or later | the Java build (`java/mvnw` downloads Maven) |
| Python | 3.13 (3.11 is the package's minimum) | the Python package, the parity kit, the checks |

Python needs `deltalake`, `pyarrow`, `pyyaml` and `jsonschema`. Drishti never makes a virtual environment: install them into the Python you choose
(`python3 -m pip install -e "python[test]"`; Windows `py -3.13 -m pip install -e "python[test]"`).

## Build and test on its own

| | Linux / macOS | Windows |
|---|---|---|
| every module, test and guard | `cd java && ./mvnw clean verify` | `cd java` then `mvnw.cmd clean verify` |
| compile and package only | `./mvnw clean verify -DskipTests` | `mvnw.cmd clean verify -DskipTests` |
| one module and what it needs | `./mvnw clean verify -pl lake-load -am` | `mvnw.cmd clean verify -pl lake-load -am` |
| the Python tests | `python3 -m pytest -q python/tests` (build first: they run the Java loader) | `py -3.13 -m pytest -q python\tests` |
| the parity kit (TCK) | `python3 tools/tck/tck.py --out results.jsonl` | `py -3.13 tools\tck\tck.py --out results.jsonl` |
| the whole check: build, delta-rs interop, Python tests, the parity kit twice and its gates | `python3 tools/ci/check.py` | `py -3.13 tools\ci\check.py` |
| install a release (on its tag only) | `git checkout vX.Y.Z && cd java && ./mvnw clean install -DskipTests` | `mvnw.cmd clean install -DskipTests` |

Never `install` from a working tree into `~/.m2`.

## Build and run in the IDE

Every launcher is checked in under `run-configs/` at the repository root (`*.run.xml`, named `Lake: <verb> <what>`). Open the **repository root** in
IntelliJ IDEA or PyCharm (in IntelliJ link `java/pom.xml`; `python/` is the Python package); both read the same files. JDK 21 is the project SDK.

| IDE | Opens | Launcher groups |
|---|---|---|
| **IntelliJ IDEA** | the repository root, `java/pom.xml` linked | **IntelliJ-lake-build** (build all), **IntelliJ-lake-test** (test), **IntelliJ-lake-check** (run check, CLI), **IntelliJ-lake-run** (run loader, CLI; give it your own `--config`), **IntelliJ-lake-install** (install release, tag only) |
| **PyCharm** | the repository root; mark `python/` as sources, choose a Python 3.13 interpreter with the package's dependencies | **PyCharm-lake-test** (test Python, test TCK), **PyCharm-lake-check** (check all, maintain release plan (dry run)) |
| **VS Code** | `drishti-lake.code-workspace` | Maven tasks, the TCK and the check; Run and Debug for `LakeLoad`, `LakeCheck` and the TCK |

Steps, the Windows `PKIX path building failed` fix and the install order: [docs/BUILD_AND_IDE.md](docs/BUILD_AND_IDE.md).

## Documents

| Document | What |
|---|---|
| [docs/GUIDE.md](docs/GUIDE.md) | how to use it: the three stores and the lake file, the loader and the inbox, the read API, data contracts, the Python package; tutorials that were run |
| [docs/DESIGN.md](docs/DESIGN.md) | why it is shaped so: positioning, the rules of the design, shaped reads of foreign tables |
| [docs/TCK.md](docs/TCK.md) | the parity kit: cases, matrix, gates, and what a backend author implements |
| [docs/BUILD_AND_IDE.md](docs/BUILD_AND_IDE.md) | prepare, build, install from a tag, IntelliJ, PyCharm, VS Code, Windows |
| [docs/BENCHMARK.md](docs/BENCHMARK.md) | the benchmark runs |
| [docs/ROADMAP.md](docs/ROADMAP.md) | wanted, not built |
| [java/README.md](java/README.md), [java/SPLIT_EXEMPTIONS.md](java/SPLIT_EXEMPTIONS.md) | the reactor and what the code still owes its first host (the guards read it) |
| [tools/split/README.md](tools/split/README.md) | the reference-comparison tools |
| [docs/contracts/](docs/contracts/README.md) | generated sample contract pages |
| [CHANGELOG.md](CHANGELOG.md) | what changed when |
