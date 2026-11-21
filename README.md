---
name: Drishti Lake
summary: >-
  A Java library (and, from step L10, a Python package) that loads and reads Delta, Iceberg and plain-file lakes as one lake:
  storage (local disk, S3), the lake file, the loader and its inbox, retention, undo and relocation, on the official libraries.
status: moved from Drishti with history (step L3); being untangled from its first host step by step (docs/SPLIT_STATUS.md)
coordinates: {groupId: com.ash.lake, package: com.ash.lake, python: drishti_lake (planned)}
java: 21
modules: [lake-io, lake-config, lake-delta, lake-iceberg, lake-load, lake-tck, lake-hostcompat, lake-guards]
depends-on: [delta-kernel 4.4.0, iceberg-core 1.10.1, parquet 1.18.0, AWS SDK 2]
commands: {build: "cd java && ./mvnw clean verify"}
first-customer: Drishti (consumes pinned releases from G4)
licence: proprietary (LICENSE)
---

# Drishti Lake

**Delta, Iceberg and plain files, loaded and read as one lake.**

Drishti Lake is the data-lake layer of the Drishti product, made a product of its own: one library that reads a lake file, writes and
reads Delta and Iceberg tables through the official libraries (Delta Kernel, iceberg-core, Parquet), loads JSONL (plain, gzip, zstd,
zip, tar) concurrently, takes hand-overs from an inbox (`.ready` files), and keeps tables healthy (maintenance, retention, delete by
date, undo, relocation). It is not a query engine and not a catalog service: it is what a risk or analytics system needs to keep
its lake right.

Author: Ashutosh Sinha. Proprietary. All rights reserved (see [LICENSE](LICENSE)).

## Status

The code **moved here from Drishti `main` `9ce7c3170` with its git history** (283 commits; step L3) and builds green on its own:
about 45,200 lines of main Java in 206 files and 11,200 lines of tests (348 tests). It still names a few host types, parked in
`com.ash.lake.hostcompat` and listed in [java/SPLIT_EXEMPTIONS.md](java/SPLIT_EXEMPTIONS.md), which only shrinks. Drishti is not
modified: it keeps its copy until it consumes a pinned release (G4). Where each step stands, and what comes next:
[docs/SPLIT_STATUS.md](docs/SPLIT_STATUS.md).

## What it is made of

| Module | What it does |
|---|---|
| `lake-io` | storage (local disk, S3 with TLS and proxies), ranged reads, cached Parquet footers, codecs (snappy, zstd, lz4) |
| `lake-config` | the lake file: tables, roots, partitions, locations, checks |
| `lake-delta` | Delta tables on Delta Kernel's native engine: read, write, layout, deletion vectors, column mapping |
| `lake-iceberg` | Iceberg tables on iceberg-core: catalogs (files, REST, S3 Tables, Glue), the native reader and writer, maintenance, layout |
| `lake-load` | the loader (archives, child kinds, retention, undo, relocate) and the inbox (`.ready` hand-over, ledger, routes) |
| `lake-tck` | test support today; the conformance kit (TCK) grows here (steps L2, L9) |
| `lake-hostcompat` | host types the moved code still names (temporary) |
| `lake-guards` | the repository's guards: names no host, exemptions only shrink, header, file cap |

Planned (design section 6): `lake-api` (the facade `LakeRuntime`, `Lake`, `LakeTable`), `lake-files` (the files store),
`lake-memory` (the in-memory lake), `lake-contracts`, `lake-cli`, and the Python package `drishti_lake`.

## Build

```text
cd java && ./mvnw clean verify      # JDK 21 or later; about two minutes, four test JVMs
```

Every path a test writes is under the module's `target/`; Maven's own temporary files go to `java/build-tmp/` (ignored).

## Documents

| Document | What |
|---|---|
| [docs/design/DRISHTI_LAKE_DESIGN.md](docs/design/DRISHTI_LAKE_DESIGN.md) | the design: scope, API and SPI, the TCK, the steps, the decisions (LK-1 to LK-16) |
| [docs/SPLIT_STATUS.md](docs/SPLIT_STATUS.md) | each step's state and proof, and the next steps |
| [docs/MOVE_ALLOWLIST.md](docs/MOVE_ALLOWLIST.md) | what of Drishti moved, moves later, divides or stays, file by file |
| [java/SPLIT_EXEMPTIONS.md](java/SPLIT_EXEMPTIONS.md) | what the moved code still owes its host, with the step that removes each |
| [java/README.md](java/README.md) | the reactor, its modules and its dependencies |
| [tools/split/README.md](tools/split/README.md) | how the move was made (filter-repo, rename) |
| [CHANGELOG.md](CHANGELOG.md) | what changed when |
