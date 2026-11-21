<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Storage: Delta, Iceberg and plain files as one lake

Drishti Lake reads and writes three table formats through **one** interface, `TableStore` (`com.ash.lake.spi`), and keeps them
**feature-identical**: every operation exists on every store, and a parity kit ([TCK.md](TCK.md)) holds each store's answer equal to the
others' for every case. A difference is a defect, never a documented limit (`tck/capabilities.yaml` declares none: `delta: {}`,
`iceberg: {}`, `files: {}`).

| Store | Format | Built on | Where it can live |
|---|---|---|---|
| `delta` (`DeltaStore`) | Delta Lake tables, with deletion vectors and column mapping on read | Delta Kernel's native engine (the Hadoop engine is not in the product) | local disk, `s3://`, `mem://` |
| `iceberg` (`IcebergStore`) | Iceberg tables, catalogs `none` (path tables), REST, Glue, S3 Tables | iceberg-core and Parquet | local disk, `s3://`, `mem://` |
| `files` (`FilesStore`) | plain JSON Lines day files `<root>/<domain>/<date>/<kind>.jsonl` with a commit log | the JDK | local disk |

Storage is separate from format. A **storage provider** (`StorageProvider`, scheme `file`, `s3`, `mem`, or your own) lists, reads (ranged),
creates, create-if-absent and deletes; S3 reaches a custom endpoint through the `lake.s3.*` options, credentials come from the AWS default
chain and are never in a file. The **memory lake** (`lake-memory`, root `mem://<store>`) holds Delta or Iceberg tables in memory with a
hard cap (`memory.max-mb`), retention and a replenish source supplied by the host; it runs every case of the parity kit, as a storage.

## What every store does

Load whole business dates (each date replaced in one commit, or appended with a batch recorded so a retry commits once); read rows with
projection and pushdown; the table's columns and dates; delete whole dates; undo one load by its id (a replace is restored); time travel
(versions, read as of a version or time); retention by age and by row count; atomic one-commit-per-date; schema evolution; vector
(`list<double>`) and list columns as typed columns; promoted columns beside the document; concurrent writers (two loaders, one table:
one wins or both commit, none corrupts). Interop is proven both ways with the official tools: delta-rs reads our Delta tables (47 partition
values of every special-character class) and our reader reads delta-rs's, with Spark's Hive escaping for folder names.

Every table has the same shape: `id` (text), `doc` (the JSON document), `business_date` (date, the first partition column), the partition
columns, the promoted columns as typed columns, the vector columns, `_load_id` and `_source_file`.

## The lake file

A **lake file** (YAML, `<lakes dir>/<name>.yaml`; `lake.lakes.dir`, default `<config>/lakes`) describes a lake once: where it is and every
table in it. A load names it with `target.lake`, and what the file says wins (a loader key that contradicts it is refused, naming both).
A writer never creates a table its lake file does not describe.

```yaml
lake: risk                      # the name (the file's name)
format: delta                   # delta | iceberg; a table may say otherwise
root: s3://bucket/prefix        # a folder, s3://, or mem://<store>; none: <data>/delta/<lake>
storage: {region: ${AWS_REGION:us-east-1}}     # how to reach the root; secrets are never in the file
catalog: {type: none}           # Iceberg: none | rest | glue | s3tables
defaults: {load-mode: replace}  # any table key
tables:
  risk.trade:                   # <domain>.<kind>   (a second table of one kind: risk.trade_b with kind: trade)
    partition-by: [DESK]        # after business_date, always first
    promote: [DESK, BOOK, NOTIONAL]
    vectors: [SCENARIO_PV]
    id: TRADE_ID
    retention: 520d             # business dates kept (520d, 7y); or a map for rows, below
  risk.quote:
    retention: {ttl: 8h, on: UPDATED_AT, max-rows: 100000}   # rows dropped for their age or count (keys: ttl, on, max-rows, dates)
```

The top keys are `lake`, `format`, `root`, `storage`, `catalog`, `defaults`, `tables`, `memory` (for `mem://`: `max-mb`, `chunk-kb`, `contract`,
`owner`, `idle-trim-minutes`), `description`. A table takes `kind`, `path`, `format`, `partition-by`, `promote`, `vectors`, `flatten`, `id`, `date`,
`latest-by`, `load-mode` (`replace` or `append`), `sort-by`, the layout (`file-rows`, `row-group-rows`, `file-size-mb`, `row-group-mb`, `compression`,
`vector-compression`), `retention` (a period, or the row map above), `maintenance`, `time-travel`, `properties`; an unknown key is refused. `LakesCli dump --dir D` prints every lake
of a folder as the code reads it, and `LakeCheck` checks the files against the tables on disk.

## Options of the runtime

Settings are read per instance (`LakeRuntime`), never from a global: a `-D` system property, then the environment variable of the same name
(`lake.data.dir` is `LAKE_DATA_DIR`), then `lake.settings.file`. The folders are `lake.home`, `lake.config.dir`, `lake.data.dir`,
`lake.lakes.dir`, `lake.tmp.dir`; Parquet: `lake.parquet.row-group-kb` / `-mb`, `page-kb`, `page-index`, `footer-cache-mb`; Iceberg:
`lake.iceberg.delete-cache-rows`, `manifest-cache-bytes`; archives: `lake.archive.max-mb`, `max-ratio`, `max-members`; S3: `lake.s3.endpoint`,
`region`, `path-style`, `read-block-kb`; `lake.business.zone` (default `America/New_York`: the loaders' "today"); `lake.loader.trace`.
The authoritative list is `com.ash.lake.LakeOptions`.

## Tutorial: the same data on three stores

Load the same JSON Lines into Delta, Iceberg and files with one configuration that differs only in `target.format`
([LOADER.md](LOADER.md) has the whole walk-through), then read each back through the one read API ([READ.md](READ.md)): the table list,
the dates, the promoted columns, the rows and the documents come out identical, which is what the parity kit proves for every case.
