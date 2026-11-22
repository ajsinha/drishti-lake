<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Drishti Lake: the storage and loading layer as a product

**Status: the design record of the product.** The built behaviour is documented in the guides ([storage](../guides/STORAGE.md), [loader](../guides/LOADER.md),
[read API](../guides/READ.md), [contracts](../guides/CONTRACTS.md), [Python](../guides/PYTHON.md), [TCK](../guides/TCK.md)); the public surface is `java/api-surface.txt`.
Where a sketch below differs in detail from the built API, the guides and the API surface are right. Decisions LK-1 to LK-16 and the decisions of section 14 stand.

**The request.** Make the lake layer (Delta, Iceberg and plain files, the loader and its inbox, retention, undo, relocation) a product of its own: one library with a
parity kit that holds every store to the same behaviour, usable by any project.

## Contents

0. [The answer in one page](#0-the-answer-in-one-page) · 1. [Positioning](#1-positioning) · 2. [Scope](#2-scope-what-moves-what-stays) ·
3. [Public API and SPI](#3-public-api-and-plug-in-spi) · 4. [The parity TCK](#4-the-parity-tck-the-conformance-gate) ·
5. [Data contracts](#5-data-contracts-home) · 6. [Repository and coordinates](#6-repository-layout-and-coordinates) ·
8. [Apache XTable](#8-apache-xtable-assessment) · 13. [Decisions](#13-decisions-lk-1-to-lk-16)

## 0. The answer in one page

**Is it a simpler refactoring than Rachana? Yes in kind, no in size; three knots must be cut that the owner's picture does not
show.** About **64 bd** (74 with 15% contingency) against the Rachana split's 55, with less deep work in it.

| | Rachana split | Drishti Lake | Reading |
|---|---|---|---|
| Lines that move (main / test) | about 27,800 / 7,000 | about 40,000 / 18,500 Java, plus 7,700 / 4,000 Python | larger |
| Host types the code names | product exception, `DataNode`, Spring, Jackson, Caffeine, registry, binder | 25 types in the four lake modules (TLS and proxy settings, `FileDates`, `RowRetention`, `ConfigResolution`, `DomainVocabulary`, `SourceRegistry`, `DrishtiException`) | far fewer, all at the edges |
| Files outside that import it | about 170 | 15 main files (server 10, banking 4, packs 1) and about 13 test files | a tenth |
| Dependencies to remove | Spring, Jackson, Caffeine, own YAML and JSON written | none: delta-kernel, iceberg-core, Parquet, AWS SDK stay | no excision work |
| Semantics that can drift | a language, a binder, a second runtime (JavaScript) | none of its own: the formats are standards, proven by interop tests both ways | lower risk |
| Framework in the code | Spring | none: `ArchitectureRulesTest` already keeps `memlake`, `dataload`, `lakeio`, `lakeconfig` free of Spring and the server | already clean |
| Conformance suite | harvested in R0 before the split | does not exist as one thing: parity tests sit in four modules and `tools/lake` | **new work** |

**The three knots** (evidence in section 2.2):

1. **The lake configuration knows packs and modules.** `lakeconfig` (2,698 lines, inside `drishti-lakeio`) reads
   `ConfigResolution`, `ModuleTemplates`, `ModuleLoaders`, `DomainVocabulary`; 27 lake files name packs, 8 name source systems.
   It must be divided into a lake-file model (product) and a pack resolver (Drishti).
2. **The three connector plugins are two things in one.** `DeltaSourcePlugin` (1,530), `IcebergSourcePlugin` (1,231) and
   `IcebergExternalPlugin` (627) implement Drishti's `SourcePlugin` (entity documents, hit index, provenance): they are adapters
   and stay. The table layer beside them (`DeltaTable`, maintenance, layout, `IcebergLoader`, `SortedRuns`, `OrphanFiles`, about
   7,300 lines) is product code and moves. Today both live in one module with one test suite.
3. **The Python path leans on Drishti's tools.** `tools/lake` imports `resolved` (7 files), `javacmd` (9), `packlake` (6),
   `samplegen` (6), `demonames` (2), and holds demo and source-system code (`demo.py`, `stores.py` with `endur`, `calypso`,
   `murex`). About 90 Python files elsewhere in Drishti call into it.

Two smaller findings: the **files store is not in the lake modules** (the `file` connector in `drishti-connectors`, 2,291
lines), so "three stores, feature-identical" has no single home today; and the Delta connector still offers **two engines**
(`native`, `hadoop`, `EngineKind`), which the one-way rule and the product must settle (LK-9).

**Recommendation.** Do it in i7b as one extra lane (one Opus, one Sonnet, never more), start after G2's first wave, move first and
untangle in the new repository as Rachana did. Gate at G3 on four facts: the TCK is green on the moved code, results equal the
reference (Drishti's own jars at the same commit), a Drishti side branch on the pin passes the VaR and ES goldens, and the Python
path passes the same fixtures. Any of the four missing: i8, with nothing lost, because Drishti is untouched until G4.

**XTable: not in the core, not on any write path.** An optional `lake-convert` module after 1.0, run as its own process, behind
a small exposure SPI that 1.0 reserves (section 8).

## 1. Positioning

Drishti Lake is **a loading-and-table layer over the official libraries: rows in as JSONL under a contract, standards-compliant
tables out, the same behaviour in Delta, Iceberg, files and memory, proven by a kit anyone can run.** It is not a format, not a
query engine, and it prices nothing.

| | What it is | What it does not do that Drishti Lake does | Relation |
|---|---|---|---|
| **Apache XTable** (0.4.0-incubating) | translates table metadata between Hudi, Delta, Iceberg, Paimon, Parquet; no data rewritten | no loading, no reading, no contracts, no retention or undo, no memory store; copy-on-write views only | optional exposure back-end (section 8) |
| **DuckDB** | an in-process SQL engine that reads Delta, Iceberg and Parquet | not a governed writer: no load rules, no `.ready` hand-over, no parity between stores, no inbox | a reader of our tables; the TCK may use it as one more outside reader |
| **Polars** | a DataFrame engine; Delta through delta-rs | a library, not a loading contract; no Iceberg parity, no rules for name and date | the Python path's fast writer when installed (owner 11-16) |
| **delta-rs** | the Rust and Python Delta library | Delta only; reads column-mapped tables as nulls and no deletion vectors or v2 checkpoints at 1.6.6 (found 11-08) | the Python path's Delta writer; the interop oracle |
| **delta-kernel, iceberg-core** | the official protocol libraries | they are the protocol, not a product: no loader, no configuration, no cross-format behaviour | **what every byte goes through** |

**What a second customer gets:** one lake file per lake (tables, partitions, promoted columns, retention); a concurrent Java
loader and one Python path with identical results; an inbox with `.ready` hand-over, undo, delete-by-date, relocation,
maintenance; the same on local disk, S3 and `mem://`; a contract check before anything is written; a kit that proves a new
storage back-end or store behaves.

## 2. Scope: what moves, what stays

### 2.1 The table

| Today in Drishti | Lines main / test | Goes to | Notes |
|---|---|---|---|
| `lake/drishti-lakeio`, package `lakeio` (storage, S3, ranges, Parquet footers, codecs, `StorageProvider(s)`) | 2,224 / part of 1,889 | `lake-io` | TLS and proxy settings become the product's own records |
| `lake/drishti-lakeio`, package `lakeconfig`: `Lake`, `LakeTable`, `LakeCheck`, the lake-file half of `Lakes` and `LakeFiles` | about 1,700 of 2,698 | `lake-config` | the model and its checks |
| the same package: `ModuleConnectors`, `PackEntry`, `RemovedKinds`, `ConnectorLake`, the pack and module half of `Lakes`, `LakeFiles` | about 1,000 | **stays** (Drishti `drishti-lake-host`) | behind `LakeCatalog` (3.2) |
| `lake/drishti-deltalake` | 2,797 / 725 | `lake-delta` | on delta-kernel 4.4.0 |
| `lake/drishti-iceberg` | 6,360 / 746 | `lake-iceberg` | on iceberg-core 1.10.1, no Hadoop at run time |
| `lake/drishti-lakeload`, package `lakeload` (the loader, archives, retention, undo, relocate) | 10,189 | `lake-load` | `FileDates`, `RowRetention`, `TempFiles` come with it from `drishti-common` |
| the same module, `dataload` (inbox, `.ready`, ledger, `ReadyRule`) | 5,939 | `lake-load` (`inbox` package) | `ReadyRule` is already an interface the banking module implements |
| the same module, `memlake`, `memlake.store` | 3,252 | `lake-memory` | `ReplenishSource` and `Retention`'s use of `SourceRegistry` go behind `RowSource` (3.2); the two classes' host half stays |
| `plugins/drishti-plugin-delta`: `DeltaTable`, `NativeLake`, `LakeStore`, `NamedTables`, `NewTables`, `TableProblems`, `VectorBatches` | about 1,700 of 3,600 | `lake-delta` | |
| the same: `DeltaSourcePlugin`, `MappedIdMap`, `DeltaLakeFiles`, `EngineKind`, `HadoopLake` | about 1,900 | **stays** | adapter; `HadoopLake` per LK-9 |
| `plugins/drishti-plugin-iceberg`: maintenance, layout, `IcebergLoader`, `IcebergAppender`, `SortedRuns`, `OrphanFiles`, `KindTable`, vectors | about 4,000 of 5,529 | `lake-iceberg` | `IcebergDiskCaches` waits on `DiskCache` (LK-10) |
| the same: `IcebergSourcePlugin`, `MappedIdMap` | about 1,500 | **stays** | adapter |
| `plugins/drishti-plugin-iceberg-external`: `ExternalTable`, `Shape`, `KindConfig` | about 800 of 1,596 | `lake-iceberg` | `IcebergExternalPlugin`, `StarterSchemas` stay |
| the files store (`plugin.file` in `drishti-connectors`, 2,291; its retention and delete already in `lakeload.LakeRetention`; its writers in Python tools) | about 1,200 | `lake-files` | read and write of `<root>/<domain>/<date>/<kind>.jsonl` in one place; the `SourcePlugin` half stays |
| `tools/lake` (Python) | about 6,900 of 9,804 / 4,586 | `python/drishti_lake` | `demo.py`, `stores.py`, `feedroute.py`, `feedfiles.py`, `stage.py`, `publish.py` (2,886) stay: demo, feeds, source systems |
| `tools/contracts.py` (1,306): the contract reader, checker, page generator | about 800 | `lake-contracts` and `drishti_lake.contracts` | section 5 |
| `drishti-testkit`: `s3.*`, `LoaderRows`, `FakeProxy`, `TlsFixture` | small | `lake-tck` | `DatedSourceContract` (36 uses) tests `SourcePlugin`: stays |
| tests of everything that moves | about 18,500 | with their code, or into the TCK | moved or converted, never copied |

**Totals.** Moves: about **40,000 main and 18,500 test lines of Java, 7,700 main and 4,000 test of Python**. Stays as adapters:
about 6,400 Java (three `SourcePlugin`s, the pack resolver, two host halves of the memory lake) and 2,900 Python.

**Stays in Drishti, whole:** `tools/mrloader` (domain); every `*.contract.yaml` (143 files in 20 pack folders) and
`docs/contracts`; `config/lakes/*.yaml` (Drishti's lakes); server wiring (`server.dataload`, `server.memlake`, `server.lakes`,
`server.flight`); `VarReadyRule`, `SourceSystemReadyRule`; `DomainVocabulary`; demo lake builds.

### 2.2 Evidence for the three knots

| Knot | Measured |
|---|---|
| Configuration | `lakeio` module imports `api.resolution.{ConfigResolution, ModuleTemplates}`, `common.ModuleLoaders`, `kernel.api.vocabulary.DomainVocabulary`; `Lakes` walks live module class loaders for lakes inside `.dmod` jars; 20 `"drishti.*"` setting names, 8 `DRISHTI_*` variables and 20 `System.getProperty`/`getenv` calls in lake main code |
| Plugins | outside the lake, the types most imported are Iceberg's (`Catalog` 15, `Table` 14, `Catalogs` 13, `Expr` 10, `FileTask` 9): the plugins reach deep into the engine, so the engine's surface is today its whole package tree |
| Python | `LOADER = "com.ash.drishti.plugin.iceberg.IcebergLoader"` in `stores.py`; the loader is found "in the server's exec jar" (`build.py`); `lakeconfig.py` asks `resolved.py`, which runs Drishti's Java resolver |

History: 451 of Drishti's 5,551 commits touch the moving paths.

## 3. Public API and plug-in SPI

### 3.1 Java: the facade (`com.ash.lake`, module `lake-api` + the implementing modules)

```java
LakeRuntime rt = LakeRuntime.builder()
        .storage(new LocalStorageProvider(), new S3StorageProvider(s3), new MemoryStorageProvider(caps))
        .store(DeltaStore.create(), IcebergStore.create(), FilesStore.create())
        .contracts(ContractChecker.standard())            // optional
        .options(LakeOptions.builder().tempDir(abs).parallelism(24).build())
        .build();                                         // per instance: no static registry, no ServiceLoader, no system property

Lake lake = rt.open(LakeFile.read(Path.of("/abs/config/lakes/risklake.yaml")));   // or LakeFile.builder()
LakeTable t = lake.table("risk.pnl");

try (RowScan rows = t.scan().asOf(date).columns("BOOK", "PNL").where(Expr.eq("DESK", "FX")).open()) { ... }
LoadReport r  = lake.loader().load(LoadRequest.of(input).table("risk.pnl").date(date).mode(REPLACE_DATE));
lake.inbox(inboxDir).rules(readyRules).cycle();           // .ready hand-over
t.maintain(MaintainPlan.defaults());  t.deleteDates(dates);  t.undo(loadId);  t.relocate(newRoot);
```

Rules of the surface: packages `com.ash.lake`, `.spi`, `.config`, `.load`, `.inbox`, `.contract` are public; everything else is
`internal` and closed by the Java module boundary from the first tag. **No Delta Kernel or Iceberg type appears in a public
signature** (rows as `RowView` and Arrow batches, schema as the product's `Columns`), so a library upgrade is never a customer's
break. Errors are `LakeException` with `LK-` codes; the host maps them once at its edge (Drishti: to `DRS-`). Paths are absolute
or anchored to a named root the caller passes (`explicit-paths`); the resolved path of every table is readable (`t.location()`).
The runtime reads configuration and never writes a configuration file (`connectors-read-only`).

**What a host calls for each need** (the public packages are those of `java/api-surface.txt`; a host imports nothing else):

| Need | Public API | Not public (internal, may change in any release) |
|---|---|---|
| map Lake's errors at the edge | `com.ash.lake.LakeException` (`error()`), `com.ash.lake.LakeError` (`code()`, `status()`) | |
| a read that fails | `LakeException` with `LakeError.UNREADABLE` (`LK-1006`): the message says what cannot be read and what to do, without a folder (a moved Iceberg table names the `relocate` in `LakeReader.problems()`) | `io.PageDecodeException`, `io.UnsupportedCodec` |
| a host's own store loader (PostgreSQL, MongoDB, ...) reading loader rows by the load rules | `com.ash.lake.load.RowFeed` | |
| write rows into a Delta or Iceberg table (append or replace a date, table properties, vectors) | the loader: `com.ash.lake.load.LakeLoad.run(runtime, args, out)` with `source.format: loader-rows`, `target.mode` (`append`, `overwrite-dates`, `overwrite-partitions`), `target.properties` (`lake.append-only`, `lake.retention-hours` mapped to each format's own: `TableProperties`); the version committed is in the summary (`--summary`: `version`, one meaning on every store, and `nativeVersion`, the store's own id) | `delta.write.LakeWriter`, `LakeManifest`, `LakeCli`; `iceberg.table.IcebergAppender` |
| files beside the tables (write a file, check what a folder holds) on local disk, S3 or `mem://` | `com.ash.lake.load.LakeStorage` (`storage(path)`: a `spi.Storage`; `isLocal`, `localPath`) | `io.S3Storage`, `io.S3Settings`, `io.LocalPaths` |

### 3.2 The SPI (`com.ash.lake.spi`)

| SPI | Who implements | Shape | From today's |
|---|---|---|---|
| **`StorageProvider`** | a storage back-end (Azure, GCS, a vendor's object store) | `scheme()`, `storage(path)`, `memoryOnly()`; `Storage` = list, status, ranged read, create, `createIfAbsent`, delete | `lakeio.StorageProvider`, `Storage` (unchanged in shape; the static `StorageProviders` becomes per-runtime) |
| **`TableStore`** | a table format | open, create, scan, a `TableTarget` for the loader, maintain, delete dates, undo, relocate, the declared capability list | `lakeload.TableTarget` (today package-private, with `DeltaTarget`, `IcebergTarget`) plus the readers |
| **`InputFormat`** | a loader input | recognise by suffix, yield rows or archive members | `Inputs`, `Archives`, `TarStream`, `GzipMembers` (`.jsonl .gz .zst .zip .tar.gz`) |
| **`ReadyRule`** | the host | may this hand-over load now; `Facts` in, a verdict out | `dataload.ReadyRule` as is |
| **`ContractChecker`** | the host or the product's standard one | rows and a table declaration in, problems out, before anything is written | new seam; the standard checker from `tools/contracts.py` |
| **`LakeCatalog`** | the host | name → `LakeFile` (Drishti: its packs, modules, site overrides) | the pack half of `lakeconfig` |
| **`RowSource`** | the host | rows for a memory table's replenish | `memlake.ReplenishSource` over `SourceRegistry` |
| **`Exposure`** (reserved, 1.x) | `lake-convert` | publish a table's current state in another format | section 8 |

JSONL stays the one row format in (`jsonl-only`); `InputFormat` is about containers and compression, not about a second row
format. There is **no loader-engine SPI**: one Java loader (concurrent, the default) and one Python path (LK-7).

### 3.3 The Python path (`drishti_lake`, a wheel built from `python/`)

`drishti_lake.build`, `.maintain`, `.delete`, `.undo`, `.relocate`, `.scan`, `.contracts`, and the command `lake` (the present
`tools/lake/cli.py` verbs). One mechanism: the plain writer, Polars when it is installed, byte-identical on the 75-case fixture
(owner 11-16). It reaches the Java loader and Delta Kernel reads through the product's own runnable jar (`lake-cli`), named by an
explicit `--jar` or `DRISHTI_LAKE_JAR`, never "the server's jar". Configuration is a lake-file path or a `LakeCatalog` command
the host names; the package does not import the host. Drishti's `tools/drishti.py lake …` stays the operator's command and calls
the package (`python-only-commands`).

## 4. The parity TCK: the conformance gate

**One kit, `lake-tck`, published as a test-jar and inside the wheel; a release is not tagged unless it is green.**

| Part | Content |
|---|---|
| **Cases as data** | `tck/cases/<area>/*.yaml`: a lake file, input files (JSONL and the archives), the operation, the expected rows, columns, partition folders, or the expected refusal by `LK-` code. Harvested from `ChildLoadsTest`, `DeleteDatesParityTest`, `RetentionParityTest`, `IcebergParityTest`, `SourceShapeParityTest`, `test_delta_interop.py`, `test_child_parity.py`, the 75-case loader fixture |
| **The matrix** | every case × store (Delta, Iceberg, files, memory) × storage (local, S3 through the mock, `mem://`) × loader (Java, Python). The answer must be equal across the row |
| **Areas** | list columns, child tables, promoted columns, vectors, flatten, partitions, latest-by, load modes, name and date rules, `.ready` keys, retention (dates and rows), delete dates, undo, relocate, maintenance, special characters in names and values, concurrent writers (two loaders, one table: one wins, none corrupts), start on few cores |
| **Interop, both ways** | Delta: delta-rs and Delta Kernel read ours; we read theirs; the Spark escaping oracle. Iceberg: Apache's own reader and writer against ours, format versions 1 to 3 |
| **Capabilities** | a store that cannot do a thing declares it in `capabilities.yaml` with the reason (files: no time travel). The list is reviewed and **only shrinks**; an undeclared difference is a failure, never a skip |
| **Gates** | coverage (every lake-file key, every `.ready` key, every `LK-` code has a case); additive-only (the previous minor's cases pass unchanged); no case skipped for a missing tool (a missing oracle fails the run) |
| **For a third party** | `lake tck run --storage <provider jar>` or `--store <jar>` writes the same results file; equality with the reference results is the certificate |

**The split's own proof** uses it: at every step the kit's results over the moved code equal, file for file, the results of the
same cases run through a bridge over Drishti's jars built from the freeze commit (Rachana's `compare_results.py` pattern).

## 5. Data contracts' home

| Thing | Home | Why |
|---|---|---|
| The contract **schema** (keys, types, the 16 sections), the reader, the checker (rows against a contract; a contract against a table), the page generator | **Drishti Lake** (`lake-contracts`, `drishti_lake.contracts`) | a second customer needs "the shape of what may be loaded" as much as the loader |
| The contract **files** (143 `*.contract.yaml`), the generated pages, `docs/contracts/*.md` (computed measures) | **Drishti**, with their packs | they describe a domain |
| Host keys in a contract (`mnemonic`, `pack`, `class`, `binding-hint`) | declared by the host as a **contract profile** (extra keys the Core reader accepts and hands back untouched) | no file changes; the product names no host concept |
| Where the check runs | once, in the loader, before the first write, through `ContractChecker`; `mrloader` keeps its domain link checks and calls the same checker for shape | one way per job |

## 6. Repository layout and coordinates

```
drishti-lake/                       private, proprietary; main + integration branches as drishti-rachana
  java/  lake-api  lake-io  lake-config  lake-delta  lake-iceberg  lake-files  lake-memory
         lake-load  lake-contracts  lake-cli  lake-tck        (lake-convert: after 1.0)
  python/drishti_lake/              the wheel; tests beside it
  tck/cases/  tck/capabilities.yaml  tck/results/             cases as data, reference results per release
  tools/split/                      the reference comparison: make_reference.py, compare_results.py, known-differences.txt
  tools/release/                    tag, build, install to ~/.m2, wheel, checksums
  docs/  README.md  CHANGELOG.md  LICENSE  java/SPLIT_EXEMPTIONS.md
```

| | Recommended | Note |
|---|---|---|
| groupId | **`com.ash.lake`** | beside `com.ash.rachana` already in `~/.m2/repository/com/ash` |
| artifactIds | `lake-api`, `lake-io`, … (no `drishti-` prefix) | as `rachana-core` |
| Java package | `com.ash.lake` (+ `.spi`, `.config`, `.load`, `.inbox`, `.contract`, `.internal..`) | renamed in one commit |
| Python | distribution `drishti-lake`, import `drishti_lake` | |
| Java level | 21 | Drishti's production level |
| Version | `0.9.0` at the move, `0.10.x` while untangling, **`1.0.0`** when the G3 conditions hold | semantic versioning on the public packages |
| Third-party versions | pinned in the product's parent POM (delta-kernel 4.4.0, iceberg 1.10.1, Parquet 1.18.0 at the move) | Drishti stops managing them and inherits through the BOM `lake-bom` |
| Dependency allow-list | `lake-api`: the JDK only. `lake-io`: Parquet, AWS SDK, codecs. `lake-delta`: delta-kernel. `lake-iceberg`: iceberg-core. No Spring, no host | a guard |

## 8. Apache XTable: assessment

| Question | Finding | Source |
|---|---|---|
| Maturity | `0.4.0-incubating`, released 2026-08-25; still an Apache incubating project; pre-1.0 API | release page |
| What it does | translates **metadata only** between formats over the same Parquet files; incremental sync with fall-back to full; catalog sync to HMS and Glue | features page |
| Delta without Spark | new in 0.4.0: a Delta Kernel source and target; the older ones need `delta-core` and a Spark session | release page |
| Gaps | **deletion vectors and merge-on-read are not carried** (copy-on-write views only); Delta generated columns not synced; Delta → Iceberg may need field ids through column mapping; Parquet partition columns that exist only in folder names fail | features page |
| Concurrency | **not stated on the pages read.** By construction a sync is a second, later commit to the target's metadata: the target lags the source, the two are never atomic, and two syncs of one table need outside serialisation. To be measured in the spike, not assumed | inference |
| Fit with our rules | **Good:** writes to each format go through that format's own library, which is our rule. **Against:** a table then holds two metadata trees, so our retention, undo, delete-dates, relocation and orphan clean-up must each trigger or follow a sync, and "Iceberg" via XTable is not our Iceberg store: parity (vectors, child tables, promoted columns, row retention) is not given by translation. Its Hadoop and Hudi dependency weight on a Windows machine with no `.exe` is unmeasured |

**Recommendation (LK-12).** Do not use XTable inside the core and never on a write path: each store keeps writing through
delta-kernel or iceberg-core, and storage parity stays the property of our own stores. Offer it as **`lake-convert`, optional,
after 1.0, in its own process** (`lake expose --as iceberg <table>`), for the one job it does well: letting an outside engine
read a Delta table as Iceberg (or the reverse) without a copy. Conditions before it ships: (a) a two-day spike on the Delta
Kernel path, no Spark, on Linux and Windows; (b) TCK "exposure" cases (the other format's official reader returns exactly the
source's rows after load, delete-dates, undo and maintenance); (c) tables with deletion vectors are refused by name; (d) one
exposure writer per table, serialised by the product. 1.0 reserves only the `Exposure` SPI and the case area. The existing copy
conversion (Delta → Iceberg through the Iceberg loader) remains the way to **move** a table; exposure is the way to **show** one.

## 13. Decisions (LK-1 to LK-16)

| # | Decision | Recommendation |
|---|---|---|
| LK-1 | In i7b, or straight to i8 | **In i7b as a two-builder lane**, started after G2's first wave; G3 decides on the four facts of section 0 |
| LK-2 | Name and coordinates | **Drishti Lake**; repository `drishti-lake`; groupId `com.ash.lake`; package `com.ash.lake`; Python `drishti_lake` |
| LK-3 | Order of work | **Move first, untangle in the new repository** (`hostcompat` and exemptions that only shrink), as Rachana; Drishti read-only until G4 |
| LK-4 | The connector plugins | **Divide them**: the table layer moves, the three `SourcePlugin` classes stay in Drishti as adapters on the public API |
| LK-5 | The lake configuration | **Divide it**: the lake-file model is the product's; packs, modules and site overrides reach it through `LakeCatalog`, implemented in Drishti |
| LK-6 | Registries | **Per `LakeRuntime` instance**, registered explicitly; no static registry, no `ServiceLoader`, no system property in the core (the CLI's `main` registers the built-ins) |
| LK-7 | Loader engines | **No engine SPI.** One Java loader (default) and one Python path (Polars if installed), both under the TCK; a third way is refused |
| LK-8 | The files store | **Moves and becomes a full `TableStore`**; its gaps go into the capability list and are closed or declared before `1.0.0` |
| LK-9 | The Delta `hadoop` engine (`EngineKind`, `HadoopLake`) | **Not in the product.** Azure and GCS arrive as `StorageProvider`s. Until those exist it stays in Drishti's adapter as a listed exception with an end date; owner to confirm nobody depends on `abfs://`, `gs://` or HDFS today |
| LK-10 | The memory lake and the disk cache | **Memory lake moves** (`lake-memory`), the host supplies rows through `RowSource`. `DiskCache` (used by one Iceberg class) stays in Drishti behind a small cache hook until native maths settles its home |
| LK-11 | Contracts | **Schema, reader, checker, generator move; the 143 files and the measure documents stay** with their packs; host keys through a contract profile; no file edited |
| LK-12 | Apache XTable | **Optional `lake-convert` after 1.0, own process, never a write path**; 1.0 reserves the `Exposure` SPI and the TCK area only |
| LK-13 | Library types in the API | **None.** Rows, columns and expressions are the product's own types; Kernel and Iceberg stay `internal` |
| LK-14 | Conformance | **The TCK is the release gate** and the split's proof; capabilities declared and shrinking; a missing oracle fails |
| LK-15 | The pin | **Jars in `~/.m2` and a wheel, one version property** (R-1 as Rachana); only tagged releases, never a branch or snapshot |
| LK-16 | Freeze during the lane | **Freeze `lake/*`, the three plugins and `tools/lake` on Drishti from the freeze commit** (the Polars engine is built in the product, after the Python cut, not in Drishti) |

## 14. Decisions taken (lead, under the owner's delegation, 2026-11-21)

LK-1 to LK-8 and LK-10 to LK-16 are **accepted as recommended**. LK-9 (the Delta `hadoop` engine stays out of the product) is accepted **provisionally**: the owner is asked to confirm that no site uses `abfs://`, `gs://` or HDFS through it; until then the engine stays in Drishti untouched. R-1 is recorded in drishti-rachana `docs/design/DECISIONS.md` on `g2/integration` (2026-11-21): released jars into `~/.m2`, installed from the tag only.
