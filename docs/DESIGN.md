<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Design

Why Drishti Lake is shaped as it is. How to use it is in [GUIDE.md](GUIDE.md); the public surface is `java/api-surface.txt`; the parity
kit is [TCK.md](TCK.md); measurements are [BENCHMARK.md](BENCHMARK.md); what is wanted but not built is [ROADMAP.md](ROADMAP.md).

## Positioning

Drishti Lake is **a loading-and-table layer over the official libraries: rows in as JSONL under a contract, standards-compliant tables
out, the same behaviour in Delta, Iceberg, files and memory, proven by a kit anyone can run.** It is not a format, not a query engine, and
it prices nothing.

| | What it is | What it does not do that Drishti Lake does | Relation |
|---|---|---|---|
| **Apache XTable** | translates table metadata between formats; no data rewritten | no loading, reading, contracts, retention or undo, no memory store; copy-on-write views only | not used: see ROADMAP.md |
| **DuckDB** | an in-process SQL engine that reads Delta, Iceberg and Parquet | not a governed writer: no load rules, `.ready` hand-over, parity between stores, inbox | a reader of our tables |
| **Polars** | a DataFrame engine; Delta through delta-rs | a library, not a loading contract; no Iceberg parity, no rules for name and date | the Python path's fast writer when installed |
| **delta-rs** | the Rust and Python Delta library | Delta only | the Python path's Delta writer; the interop oracle |
| **delta-kernel, iceberg-core** | the official protocol libraries | they are the protocol, not a product: no loader, configuration or cross-format behaviour | **what every byte goes through** |

What a second customer gets: one lake file per lake (tables, partitions, promoted columns, retention); a concurrent Java loader and one
Python path with identical results; an inbox with `.ready` hand-over, undo, delete-by-date, relocation and maintenance; the same on local
disk, S3 and `mem://`; a contract check before anything is written; a kit that proves a new storage back-end or store behaves.

## Rules of the design

| Rule | Meaning |
|---|---|
| One interface, three stores | `TableStore` is implemented by Delta, Iceberg and files; every operation exists on every store, and `tck/capabilities.yaml` declares no gap. A difference is a defect. Speed is not a parity feature: the files store gives the same answers through the same calls and is measured, not held to Parquet's number; it has no binary sidecar for vectors (one way per job) |
| No library type in the API | rows, columns and expressions are the product's own types; Delta Kernel and Iceberg types stay in each store's `internal` package, and `ApiSurfaceTest` fails the build on one in a public signature |
| Per-instance registries | stores and storage providers are registered on a `LakeRuntime`; no static registry, no `ServiceLoader`, no system property in the core |
| One loader per language | one concurrent Java loader and one Python path, both under the parity kit; there is no loader-engine SPI |
| The Delta engine | Delta Kernel's native engine only; the Hadoop engine is not in the product (object stores arrive as `StorageProvider`s) |
| Read-only configuration | the runtime reads configuration and never writes a configuration file; paths are absolute or anchored to a named root |
| Errors | `LakeException` with `LK-` codes; the host maps them once at its edge |
| Host seams | `LakeCatalog` (name to lake file), `RowSource` (rows for a memory table's replenish), `ReadyRule` (may this hand-over load now), `ContractChecker` (rows and a table declaration in, problems out, before anything is written) |
| One way to read | `TableStore.open` gives a `LakeReader`, then `TableReader`, then an immutable `TableSnapshot`; every read is a method of the snapshot; whole-row scans stay `TableStore.scan` with pushdown. A read that ignores deletes on one store and refuses on another is a parity defect: the product applies deletion vectors and delete files |
| Parallel vector decoding in the product | asked for by the request (`decoders`, `pieceBytes`), delivered in file order; a vector row is delivered into one reused buffer per decoding piece |
| Problems recorded inside reads | a read serves what it can and lists what it could not (`ReadProblems`); the classification is written once |
| One stamp rule | the cache stamp is the same on every store (hash of `path:size`); one id-map file format (`IdMaps`); folder, TTL and cap of its cache are the host's |
| Iceberg partitioning | identity fields after the business date (`source.partition-by`), no spec evolution: an existing table with another spec is refused |

What a host calls for each need (the public packages are those of `java/api-surface.txt`; everything else is `internal` and may change):

| Need | Public API |
|---|---|
| map errors at the edge | `com.ash.lake.LakeException` (`error()`), `com.ash.lake.LakeError` (`code()`, `status()`) |
| a read that fails | `LakeException` with `LakeError.UNREADABLE` (`LK-1006`): says what cannot be read and what to do, without a folder |
| a host's own store loader reading loader rows by the load rules | `com.ash.lake.load.RowFeed` |
| write rows into a Delta or Iceberg table | the loader, `com.ash.lake.load.LakeLoad.run(runtime, args, out)` with `source.format: loader-rows` and `target.mode` (`append`, `overwrite-dates`, `overwrite-partitions`); `target.properties` (`lake.append-only`, `lake.retention-hours`) map to each format's own; the summary carries `version` (one meaning on every store) and `nativeVersion` |
| files beside the tables on local disk, S3 or `mem://` | `com.ash.lake.load.LakeStorage` (`storage(path)`) |

## Data contracts

The contract **schema**, reader, checker (rows against a contract, a contract against a table) and page generator are the product's
(`lake-contracts`, `drishti_lake.contracts`). The contract **files** and their generated pages belong to the host and its domain. A
host's own keys in a contract are declared as a contract profile (extra keys the reader accepts and hands back untouched), so the
product names no host concept. The check runs once, in the loader, before the first write.

## Shaped reads of foreign tables

A table another engine wrote (Spark, Flink, Trino, Snowflake, Glue, dbt, pyiceberg, delta-rs, plain JSON Lines) has its own layout: no
`id`, `business_date` or `doc` columns. A **shape** says how its rows are a kind's documents; the layout readers are untouched, and a
shaped table answers through the same `TableReader` and `TableSnapshot`: `LakeReader.foreign(name, shape)`.

`TableShape` (built from `tables.<kind>.*` settings by `TableShape.of(kind, settings)`) holds `table`, `id` (columns; empty: the table's
identifier fields), `idSeparator` (`:`), `date` (a date column, or a timestamp with `date-from: day`; null: the partition spec's day),
`dateZone` (default UTC), `document` (`row`, or `column:<name>` holding the JSON), `exclude`, `rename`, `links`, `depth` (nested structs
flattened into searchable paths, default 2) and `values` (decimals as numbers or text, binary as base64). A shape the table cannot
answer is refused in words with `LK-1001` (no id, a date column that is not a date or timestamp, a timestamp without `date-from: day`).

One rule, written once (`lake-io`, package `com.ash.lake.io.read`: `ShapedTable`, `ShapedDocument`, `ShapedSnapshot`,
`ForeignTableReader`): a file's dates are the partition when it is the date, else the date column's bounds (one day: exact; a span under
400 days: every day inexact, its rows checked), else the column read; the document is the row's columns (renamed, excluded, nested
structs as objects, lists as arrays, timestamps ISO); the id is its columns joined by the separator, pruned by partition, bounds and
row-group statistics. Each store gives a `ForeignSource` only: `IcebergForeign` (planning, bounds, delete files), `DeltaForeign` (the
Kernel, partition values and statistics, deletion vectors) and `FilesForeign` (`<domain>/<table>/*.jsonl[.gz]`, the date from the name
by the loader's rule, `document: row` only). The files store has no time travel here, and its text values that are ISO dates or instants
with a zone read as dates and timestamps. A memory lake holds no other engine's tables.

## Repository and coordinates

| | |
|---|---|
| Java | groupId `com.ash.lake`, artifacts `lake-api`, `lake-io`, ... (no product prefix), package `com.ash.lake` (`.spi`, `.read`, `.config`, `.load`, `.inbox`, `.contract`; `internal` packages closed by the module boundary), Java 21 |
| Python | distribution `drishti-lake`, import `drishti_lake` |
| Third-party versions | pinned in the parent POM and exported to hosts by `lake-bom` |
| Dependency allow-list | `lake-api`: the JDK and `tools-settings`; `lake-io`: Parquet, AWS SDK, codecs; `lake-delta`: delta-kernel; `lake-iceberg`: iceberg-core; no Spring, no host (a guard) |
| Consumption | a consumer builds against a release tag, never a branch; the reactor is in [java/README.md](../java/README.md) |
