<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Changelog

Entries are written per release; unreleased work is listed first.

## 0.9.0 (2026-11-21): the move from Drishti

- **One table maintenance for every store (`lake maintain`, G4 lake gap)**: `LakeMaintain` (and `python -m drishti_lake maintain`) keeps
  a Delta, Iceberg or files table by the same options, in one order: keep-days under the drop guard (`--max-drop-share`, `--force-drop`)
  and the retention floor (`--floor-days`), row retention, compaction, checkpoint and vacuum. Delta through Delta Kernel alone (no delta-rs):
  compaction rewrites each date held in two or more small files (OPTIMIZE), a checkpoint of the latest version, a vacuum that deletes the
  data files no version of the last `--vacuum-hours` references (a file no commit names: after 72 hours too; nothing deleted unless every
  file of the latest version is matched). Iceberg through iceberg-core: compaction (`NativeMaintenance.compactDates`: a date in more files
  than its rows need, every row kept, a table laid out by more than the id kept in that order; `SortedRuns` takes an order and keeps every
  row on request), the manifests regrouped, snapshots expired then orphan files. Files: the log's content files of older versions vacuumed
  (a version whose content went is refused by name); compaction and checkpoint have nothing to do there. The printed line is the same on
  every store; kit cases `maintain/compact-checkpoint-vacuum` and `maintain/keep-days-and-drop-guard`; the runner's `read-files` verb.
  LOADER.md "Maintenance".
- **Drishti Tools 1.1.0**: `drishti.tools.version` moves from 1.0.0 to the 1.1.0 tag (its jars name an `Automatic-Module-Name`), the
  version every product of the family pins.
- **The files store on S3 and in memory (G3 F4, decision O3: built, not declared)**: the files store reads and writes through the
  `Storage` interface (`Place`), so a files lake lives on local disk, S3 or a memory store as Delta and Iceberg do; the parity kit's
  memory and S3 rows run the files store too (`<case>@files~mem`, `@files~s3`). The log needs no lock on any storage: a commit is the
  exclusive creation of `<n>.json` (the loser reads the log again and checks what it built on), its content a file of its own
  (`<uuid>.jsonl`), and the day file is published whole after the commit (`Storage.publish`: a link and a rename on local disk) until it
  is the newest content. The writer's part files are local temporary files (`TempFiles`). `Storage` gains `files(dir)` and
  `publish(from, to)`; `FilesStore.tables`, `dayDates`, `dayLines` serve undo and row retention on any storage.
- **A lost concurrent replace fails on every store (G3 F5, decision O5a)**: a load's base is the table as it found it when it started
  (the writer is made before the input is read). A replace of a date that another writer changed after that commits nothing and fails
  with `TableWriter.LostReplace` (exit 1, "... was replaced or changed by another load while this one ran ... run it again"); the winner's
  rows alone stand. Delta compares the date's live files at the base with the transaction's read version and maps Kernel's
  `ConcurrentWriteException`; Iceberg's replace is iceberg-core's overwrite validated from the base snapshot with a conflict filter on the
  date (and slice) (`validateNoConflictingData`, `validateNoConflictingDeletes`; it used to keep all four writers' rows); the files log
  refuses a replace when the date was committed after the base (it used to keep the last). Appends at once all land, as before. Kit
  cases `concurrent/one-date-four-replaces` and `concurrent/one-date-four-appends` (four loaders started together, local and memory rows;
  S3 waits for a simulator with atomic conditional writes), with the runner's `race:` step (results sorted: the winner is not known).
  `last-run.json` is written through a temporary file per load, so loads of one process at once no longer collide.
- **The newest load is the later commit (G3 F10, decision O5)**: `LoadOrder` orders loads by commit (table version, data sequence
  number, the files log's order) first, the run's time only within one commit; clocks that disagree no longer decide which copy wins.
- **Lake leftovers removed (G3 F6, decision O4)**: the read-both of the host's `drishti.*` persisted names and lake-file settings is
  gone: `PersistedNames` reads `lake.*` only and refuses a table that carries the older names ("rebuild the lake"; no migration code), a
  lake file's `${drishti.x}` is refused naming `${lake.x}`, the default lake roots are `${lake.data.dir:./data}/delta` and `/iceberg`
  (`PersistedNames.alias` removed, `earlier`/`product` added). `LakeRelocate` (the one-time move of the older layout) is deleted, with
  `python -m drishti_lake relocate`. The loader's spill folder and Iceberg maintenance's rewrite runs come from `TempFiles` (the
  runtime's temporary folder, or `TempFiles.under` an operator's `--spill-dir`), not `java.io.tmpdir` directly.
- **A shared table's slice in the files store**: a replace takes out only the load's slice of the date (by its promoted or partition
  columns, as Iceberg), so the files store no longer refuses a shared table.
- **Migration code removed (G4 shims)**: no customers, so no compatibility code. `LakesCli migrate-config` and `LakeFiles.migrate` /
  `adoptDemo` / `demoRoot` (the rewrite of old connector files into lake files), `ConnectorLake.legacy` and its `LakeCheck` note are gone;
  `api-surface.txt` shrinks by the same names. A connector's explicit form (`root`, `domain`, `table.<kind>`) stays: it is a way to name tables, not a leftover.
- **Duplicate leftovers (i7b audit T10, E-tools)**: the guards' header, line-cap and API-surface checks are `drishti-tools`' published
  `tools-guards` test jar (`GuardFiles`, `HeaderAndLineCap`, `ApiSurface`), not copies in `lake-guards`; the files store's two temporary
  files (`columns-*.part`, `content-*.part`) come from a `tools-files` `TempFolder` made inside the table's own log folder (the same file system as
  the place they are moved to, so the atomic move holds), removed when the JVM stops cleanly.
- **Foreign tables: the account of a missing table and the documents' fields (G4 closure)**: `TableReader.absence()` says why a
  table has no snapshot (a foreign table: what its catalog looked for and what is there instead, a Delta table's missing log, a missing
  folder); `TableSnapshot.documentFields()` lists the documents' fields of a shaped table with their nested fields and the id and date
  roles (the date also when it is the table's day partition), as `DocumentField`. Additive: `api-surface.txt` extended.
- **Duplicates folded (i7b duplicate audit, G3)**: hashes through `drishti-tools` `Hashes` (five copies of SHA-256 and hex), the
  temporary folder through `tools-files` `TempFolder` (`TempFiles` keeps only where the runtime's base is); one `StorageRoutes` in
  `lake-io` for the Delta and Iceberg engines' local/S3/provider routing (the stale "set the engine to hadoop" hint dropped); `Columns.promoted`
  and `Columns.vectors` for the three stores' snapshots; Delta's own `IdMap`/`HeapIdMap` replaced by the read API's; one counting stream
  for the two Delta file writers; `NativeMaintenance` names a day's files once; the unused `Lz4RoutingEngine` (it routed to the retired
  hadoop engine; the LZ4 page reader stays) and Iceberg's unused `withVectors` copy deleted.
- **Shaped reads of foreign tables (RA-S8)**: `TableShape` (from a connector's `tables.<kind>.*` settings) and `LakeReader.foreign`
  read a table other engines wrote (Spark, delta-rs, pyiceberg, a folder of JSON Lines) as a kind's documents, through the same
  `TableSnapshot` on Delta, Iceberg and files; the shape rule (dates from partitions, a zone's day, bounds or the column; ids; documents;
  refusals `LK-1001`) is written once in `lake-io`. Case `read/foreign-table-shaped` (tables written by each format's other writer, the
  same JSON on every store). The moved `iceberg.external` copies and `JsonValues` are deleted.
- **G4 rehearsal items R1, R3, R4**: `TableReader.snapshot` at a time before the first commit is empty on every store (no problem
  recorded; a version the table does not hold is still refused), case `read/as-of-before-first-commit`; the data inbox starts the main
  the runtime's catalog names (`LakeCatalog.loaderMain`, default `LakeLoad`), so a host's child loads read with its catalog;
  `LakesCli.run(runtime, args, out)` is public (a host's main runs the lake-file commands with its runtime).
- **The Python path, L10 rest**: the archive rule with the Java loader's guards, caps and words (`drishti_lake.archives`; the shared
  fixtures in `test_archives.py`; case `inputs/archive-guards-and-dataset`, `--dataset` picking a tar's one table); the Java tools from
  Python (`python -m drishti_lake undo|retention|relocate|check|maintain|lakes`, class path `LAKE_CLASSPATH`): one implementation per
  job, and the kit's Python row now runs its undo, retention and delete steps through them (equal to the Java row); a named lake
  (`target.lake: <name>`) read through `LakesCli table` and applied with the Java loader's agree rule.
- **Data contracts (L11)**: the contract schema, reader, checks and page generator are the product's. Python `drishti_lake.contracts`
  (`schema.json` without the host's keys; `Profile` adds a host's keys, handed back untouched; `schema_problems`, `record`, `drift`;
  `pages` with a host's words through a `PageProfile`; `python -m drishti_lake contracts check|pages`). Java `lake-contracts`
  (`com.ash.lake.contract`: `Contract`, `ContractChecker.standard()`, the same words as Python's `record`, `records.json`); the loader's
  `target.contract` (Java and Python) rejects a row that breaks its contract, after the row rules and before the first write (case
  `load/contract-rejects-rows`); `PackEntry.contractKey` and the inbox's contract check read through `Contract`. Proof
  (`tools/split/contracts_proof.py`, Drishti at the freeze, read only): its 139 contracts read and validate identically with its profile
  (the merged schema is its own) and its 20 contract pages are byte-identical.
- **The old Delta entry points removed**: `NewTables` deleted, `LakeStore` package-private (the Delta reader's own); a host lists, opens
  and finds new tables through the read API (`LakeReader.tables`, `newTables`, `health`). `LakeStoreTest` is `DeltaLakeOpenTest` on
  the read API.
- **float32 vectors on every store**: `target.layout.vector-type: float32` (default `double`; Java and Python loaders) makes a new
  table's vectors `list<float>` (Delta, Iceberg; the files store's column type `float_list`, its values rounded to float as Parquet holds
  them); an existing table keeps its own element type. `ColumnType.FLOAT_LIST` (and `ColumnType.vector()`) in the SPI: Delta and Iceberg
  report a float32 vector as such. Case `read/float32-and-null-vector` (exact widening, a null and an empty vector, a null element, an
  append keeping float32) equal on every store and row: RA-S9's 20 cases complete.
- **The newest load, one rule (`com.ash.lake.rows.LoadOrder`)**: two loads are ordered by their run's time; runs that started in the
  same millisecond by their commit (Delta: the version that added the file, read through the Kernel's commit range only then; Iceberg:
  the file's data sequence number; files: the line's place), never by the process id's text (`-77-9` sorted after `-77-10`); then by
  the id. Delta, Iceberg and the files store follow it (the files store took the last line whatever the run's time). Case
  `read/newest-wins-same-millisecond` (three runs of one millisecond, text order the reverse of the commits'), 30 runs on every row
  identical; `LoadOrderTest`.
- **Drishti Tools adopted (G3)**: Lake's copies of the shared utilities are deleted (21 files: the 13 TLS and proxy classes of
  `com.ash.lake.io.net`, `Placeholders`, `SettingsFile`, `SingleFlight`, `LoadGuard`, `FakeProxy`, `TlsFixture`, `TestPki`) and
  `com.ash.tools.{settings,net,concurrent,time,testkit}` used instead, at one version through `lake-bom` (`drishti.tools.version`,
  `tools-bom` imported). Public API changed on purpose before the tag: `com.ash.lake.Placeholders`/`SettingsFile` and
  `LakeRoots.isAbsolute`/`anchorValue` are gone (`com.ash.tools.settings.PathAnchors` is the one path rule); `LakeOptions.SETTINGS_FILE`
  (`lake.settings.file`, passed to `SettingsFile.applyFrom`) and `LakeOptions.ALLOW_INSECURE_TLS` (`LAKE_ALLOW_INSECURE_TLS`, passed
  to `TlsContexts.build`) name Lake's own words. The test client certificate is `CN=client`.
- **Read API: I2 and part of RA-S9**: Iceberg vector reads cut a date's files into row-group pieces for their decoding threads, as
  Delta's do (`ParquetRowReader`/`TaskReader` read a row-group range; positions stay the file's, so position deletes apply). Kit
  verbs `read-at`, `read-keep`, `read-vectors-of` and cases `read/as-of-version-and-time`, `keep-prunes-files`, `mapped-equals-heap`,
  `of-ids-in-one-file`, `partition-column-filled` (every store and row). `TwoRuntimesTest` (two readers of one lake in two runtimes:
  no shared snapshot or footer cache) and `StartOnFewCoresTest` (eight read-API vector reads with four decoders each on one carrier)
  extended. `docs/BENCHMARK.md`: the interleaved A/B run (Iceberg: the API at or below Drishti's jars, same allocation per row).
- **Read API, accuracy and speed first, then RA-S6 and RA-S7** ([READ_API](docs/design/READ_API.md)):
  - *Newest load wins, made deterministic (Delta)*: a date's files were ordered by their largest `_load_id` from the log's statistics,
    and a value of 32 characters or more was "not stated", so it sorted first (oldest). A load id is `<time>-<pid>-<n>.<file>`: with a
    7-digit process id the tenth run in one process makes it 32 characters, and the older load's copy won (the kit's one-off
    `ids-and-file-of-id` difference: the runner is one process). The load order now uses every stated value (a cut value keeps its
    first characters, the run's time). Case `read/newest-wins-long-load-ids` (31 then 32 characters, every store); `NewestLoadWinsTest`.
  - *Files store, read by id*: the date's id index (sorted ids, each live line's offset and length; `JsonlDay`, the one index of a
    JSON-lines day) is built once per snapshot and date; `document` and `vectorsOf` are a binary search and one positioned read each
    (were a pass over the date per id); `ids` from the same index; released when the reader drops or purges the snapshot.
  - *RA-S6, Iceberg `partition-by` (P1)*: the spec is the business date then an identity field per partition-by field; the loader
    and lake maintain write one file run per partition tuple; a table with another partition-by is refused in Delta's words; an
    empty value is null (tuple and column, as Delta; files store too). Case `layout/partition-by-beside-the-date` (every store);
    `config:source.partition-by` left the coverage baseline; iceberg-core reads such a table (`IcebergSpecInteropTest`).
  - *RA-S7, pushdown*: one rule (`com.ash.lake.io.read.Pruning`): a scan's `where` prunes dates, partition values and text bounds
    before a file is read (Delta's log statistics, Iceberg's manifests, the files store's commit statistics); every row is still
    tested. `RowScan.stats()` reports files read and skipped, rows read and returned. Case `read/scan-skips-files`.
  - `tools/bench/bench.py --repeat N`: interleaved rounds (one JVM per side and round, order reversed every other round), a summary
    of medians, spread and the ratio to the first side.
- **The read API, built through RA-S5** ([READ_API](docs/design/READ_API.md)): `com.ash.lake.read` (`lake-api`) entered through
  `TableStore.open`, own types only (in `api-surface.txt`), on Delta, Iceberg and files (and the memory rows): snapshots, days and files
  with rows, bounds and one stamp rule, id maps and one mapped id-map format, documents, promoted columns as arrays, vectors into a
  reused buffer (1 or more decoders, file order), a page of vectors, file scans, identity, health, problems (`LK-1006`), purges.
  Defects closed: D1 (Delta deletion vectors applied in columns and vectors), D2 (Delta identity from the snapshot's metadata), F1 (files
  commits carry rows and text bounds), F2 (files table id); I1 proven (`IcebergReadDeletesTest`). Kit: 11 `read/*` cases equal on every
  store. `tools/bench` (`ReadBench` over Drishti's jars at the freeze and over the API): `tck/bench/`. Not yet: I2, P1 (Iceberg
  `partition-by`), pushdown and `ScanStats` (RA-S7), foreign-table shapes (RA-S8), the rest of RA-S9.
- **The read API, designed** ([docs/design/READ_API.md](docs/design/READ_API.md); nothing built): `com.ash.lake.read` entered through
  `TableStore.open`, own types only, the same on Delta, Iceberg, files and `mem://`; every call of the host's source adapters mapped;
  the stores' defects to close (deletes in column and vector reads, files statistics and identity, Iceberg `partition-by`); 20 parity
  cases; the performance proof; steps RA-S0 to RA-S10; decisions RA-1 to RA-12 open.

- **IDE launchers**: `java/run-configs/` (build all, test, install release for a tag only, the loader and check programs), `tools/run-configs/` (the TCK, the repository check) and `drishti-lake.code-workspace` for VS Code; README, Build and run in the IDE.

- **L0, the allow-list.** docs/MOVE_ALLOWLIST.md (since removed): every file of the lake modules, the three table plug-ins,
  the files store, `tools/lake` and `tools/contracts.py` at the freeze commit (Drishti `main` `9ce7c3170`), with its verdict (moves at
  L3, moves later, divides, stays) and destination; `tools/split/l3-paths.txt` is the same list for `git filter-repo`. Scan of the
  listed paths over all their history: no file over 1 MB, no key or token, no real trading-system value.
- **L3, the move with history.** 587 files and their 283 commits, extracted with `git filter-repo` from a fresh clone and merged with
  `--allow-unrelated-histories`; the packages renamed in one commit (`com.ash.drishti.{lakeio, lakeconfig, deltalake, iceberg,
  lakeload, dataload}` and the plug-ins' table layer to `com.ash.lake.{io, config, delta, iceberg, load, inbox, delta.table,
  iceberg.table, iceberg.external}`; `FileDates`, `RowRetention`, `TempFiles`, `JvmTempDir` taken into `com.ash.lake.common`; the test
  support into `com.ash.lake.tck`; the loaders' shared fixtures into `tck/fixtures`).
- **The reactor.** `java/pom.xml`, groupId `com.ash.lake`, version `0.9.0-SNAPSHOT`, Java 21, `mvnw`; the host's third-party versions.
- **hostcompat and the guards.** 41 host types copied with their package changed into `com.ash.lake.hostcompat`
  ([java/SPLIT_EXEMPTIONS.md](java/SPLIT_EXEMPTIONS.md)); `lake-guards`: names no host (no host package; host-word files 79, only
  down), exemptions only shrink (host types and parked checks listed, each still needed), header, the 2,500-line cap.
- **Green on its own.** `./mvnw clean verify`: 348 tests, 0 failures, 1 skipped (a host-reading check parked); two host-reading checks
  parked in all, each listed with the step that brings it back.
- **L1, the rest of the guards and the local CI.** `ModuleRulesTest`: the per-module dependency allow-list and official libraries only
  (no Delta log, Parquet footer or Iceberg metadata handled by hand; two listed leftovers, L7) fail the build; the public surface,
  relative paths and configuration writes are reported (`lake-guards/target/guard-reports`). `tools/ci/check.py` (build, guards, the
  parity kit twice and against the reference); `tools/release/release.py`, a skeleton that changes nothing and tags nothing.
- **L2, the bridge and the first parity cases.** `tools/tck/TckRunner.java` + `tck.py` run `tck/cases` (15 cases, Delta and Iceberg:
  load modes, two dates, input forms, archives, rejects, special characters, delete dates, retention, undo); `tools/split/make_reference.py`
  runs the same runner, imports mapped back, over Drishti's own jars at `9ce7c3170` (a scratch clone, `./mvnw clean package`); the
  reference results are stored (`tck/results/reference-9ce7c3170.jsonl.gz`), stable over two runs, and this code's results equal them
  (`compare_results.py`: 30 of 30 identical).
- **L4, part 1: own errors, TLS and proxy.** `LakeException` and `LakeError` (`LK-1001` bad request, `LK-1002` not found, `LK-1003`
  too large, `LK-1004` ready refused; each with its HTTP status for a host to map) replace the host's `DrishtiException` and
  `ErrorCode`; the TLS and proxy records (13 types) are the product's own, `com.ash.lake.io.net`; the insecure-TLS switch is
  `LAKE_ALLOW_INSECURE_TLS`. hostcompat 41 → 26. Parity kit equal to the reference (30/30).
- **L4, part 2: small utilities.** `ChannelIo`, `SingleFlight` and `LoadGuard` are the product's own (`com.ash.lake.common`); the
  quarantine's business zone variable is `LAKE_BUSINESS_ZONE`; `Extensions` stays listed until L5 (`Vocabularies` needs it).
  hostcompat 26 → 23. Parity kit equal to the reference (30/30).
- **L4, part 3: own settings.** `LakeOptions` (new module `lake-api`, the JDK only) is the one place the product reads a setting:
  `lake.*` names and their `LAKE_*` variables (home, configuration, data, lake-file, connectors and temp folders; Parquet row group,
  page, page index, footer cache; Iceberg delete and manifest caches; archive caps; loader trace); built options read nothing from the
  process. `LakeRoots`, `Placeholders` and `SettingsFile` (`lake.settings.file`) are the product's own (`com.ash.lake`), `RowFeed`
  `com.ash.lake.common`; `DataDir` folded into `LakeRoots`. The loader's child process gets the `lake.*` settings. `StartOnFewCoresTest`:
  eight loads at once, Delta and Iceberg, on one carrier thread. Names persisted into tables keep the host's prefix until L7.
  hostcompat 23 → 18, host-word files 79 → 73. Parity kit equal to the reference (30/30).
- **L5: the configuration divided.** `LakeCatalog` (`com.ash.lake.spi`): what a host adds to the lake configuration (shipped lake
  files, pack files, connector templates, named lake writers, extensions such as a `ReadyRule`, the child loader's settings); with no
  host, `LakeCatalog.NONE` (a pack folder read as it lies). The named `[lake.<name>]` settings are the product's (`NamedLakeSettings`).
  The 12 pack and module types leave hostcompat (18 → 6); one parked check returns (2 → 1). `tools/split/lake_models.py`: the host's
  lake files read to the same model here and in the reference (15 of 15). Parity kit equal to the reference (30/30).
- **L6: the per-instance runtime.** `LakeRuntime` (`lake-api`) holds an instance's options, the host's catalog, the settings its lake
  files and named lakes are resolved with, its storage providers and the parts it keeps for its life (the Parquet footer cache, the
  Iceberg delete-file cache and manifest purge list, the temporary folder); built with `LakeRuntime.builder()` (a host: everything
  registered explicitly) or `LakeRuntime.fromProcess()` (a command line: `-D`, then the environment). `LakeOptions.current()`,
  `LakeCatalog.current()`, the `ServiceLoader`-filled `StorageProviders`, `Lakes.useProperties` and `NamedLakeSettings.useView` are
  gone; `StorageProvider`, `Storage`, `FileEntry` and `RangeReader` move to `com.ash.lake.spi`. Every reader, writer, loader, inbox
  and maintenance entry takes its runtime (`LoadConfig`, `DataLoadSettings` and `FileAccess`/`NativeFileIO` carry it); a lake file's
  reader reads only the lookup it is given (no system property behind it). The REST catalog's tables get their file IO from Iceberg's
  session catalog's builder (no `io-impl` class name). `JvmTempDir` and the process-wide temp folder stay with the host's server.
  `TwoRuntimesTest`: two runtimes, two loads at once, each sees only its own storages, shipped lakes, caches and temp folder.
  Host-word files 73 → 72. Parity kit equal to the reference (30/30).
- **L7, part 1: official libraries, own row types, persisted names.** `LakeCheck` moves to `lake-load` (the lake-file model in
  `lake-config` reads no table format) and reads a table's shape through the Delta Kernel's snapshot and Iceberg's current metadata;
  its command line is `com.ash.lake.load.LakeCheck check` (`LakesCli` keeps dump, table, migrate-config, add-entry, pack-tables).
  `LakeUndo` finds the commit that replaced a date through the Kernel's commit range (add and remove actions), in object storage too;
  the reflective calls of the Iceberg maintenance are direct calls. The official-libraries list is empty. The last six host types
  (`ColumnSet`, `IdText`, `ListValues`, `UnreadableData`, `VectorReads`, `VectorSink`) are the product's own (`com.ash.lake.rows`) and
  `lake-hostcompat` is gone (hostcompat 6 → 0). Persisted names (`PersistedNames`): written `lake.<name>` (snapshot summaries, table
  properties, the writer's per-column options `lake.parquet.*`), read `lake.<name>` then `drishti.<name>`; a lake file's `${drishti.x}`
  and `${lake.x}` stand in for each other; the Delta commits' engine info names the product. TCK case `undo/undo-a-replace-restores-the-date`
  (reference regenerated over Drishti's jars at `9ce7c3170`: the 30 earlier results unchanged). Host-word files 72 → 70. Parity kit
  equal to the reference (32/32).
- **L7, part 2: the Delta lake opener, native path only.** `LakeStore`, `NativeLake`, `NamedTables` and `NewTables` moved with their
  history (7 commits, `tools/split/l7-paths.txt`, the L3 filename callback) into `com.ash.lake.delta.table`; `LakeStore.of(runtime,
  root, domain, settings)` opens a lake through the runtime's native engine (local disk, S3, a registered provider's scheme);
  `engine: hadoop` is refused naming LK-9 (`EngineKind` and `HadoopLake` stay in Drishti's adapter until G4). `LakeStoreTest`. The
  Hadoop client on the class path of `lake-io` and `lake-delta` reviewed: `parquet-hadoop` and the Kernel's default engine need
  Hadoop's `Configuration`; no Hadoop file system is used; until L12. Parity kit equal to the reference (32/32).
- **L7, part 3: the `TableStore` SPI.** `com.ash.lake.spi` (`lake-api`, the JDK only): `TableStore` (format, declared
  `Capabilities`, exists, columns, dates, scan, the loader's `TableWriter`, delete dates, undo) over the product's own `TableRef`,
  `Columns`/`Column`/`ColumnType`, `Row`, `Expr`, `ScanRequest`, `RowScan`, `DateChange`, `WriteSpec`, `LoadRow`;
  `LakeRuntime.Builder.store(..)` registers a store per format. The loader's package-private `TableTarget` is gone: `DeltaTarget` and
  `IcebergTarget` are `TableWriter`s built from a `WriteSpec`, the loader asks `Stores.of(runtime, format)` (the runtime's store, else
  the built-in `DeltaStore`/`IcebergStore`), its row is a `LoadRow`. `ApiSurfaceTest` (as Rachana's CG5): `java/api-surface.txt` is the
  public API of `com.ash.lake`, `.spi`, `.config`, `.load`, `.inbox`, any change fails until the file is rewritten in the same commit,
  and no line may name a Delta Kernel, Iceberg, Parquet or Hadoop type (none does). `TableStoreTest`. Parity kit equal (32/32).
- **L8, part 1: the files store, a full `TableStore` (LK-8).** `JsonlDay`, `JsonlFormat` and `JsonlLoader` moved with their history
  (10 commits, `tools/split/l8-paths.txt`, `l8_rename.py`) into the new module `lake-files` (`com.ash.lake.files`; the file connector's
  `SourcePlugin` half and its document tree stay in the host). `FilesStore` (`<root>/<domain>/<date>/<table>.jsonl`, rows `id`,
  `business_date`, `doc`): exists, columns, dates, scan, delete dates (the lake's delete of a files table now goes through it), and
  the loader's `FilesWriter` (part files joined and moved onto the day in one atomic rename; replace, or append without a batch). Its
  gaps are declared with their reasons (`APPEND_BATCH_ONCE`, `UNDO`, `TIME_TRAVEL`, `SCHEMA_EVOLUTION`, `PROMOTED_COLUMNS`,
  `TYPED_LIST_COLUMNS`) and listed in `tck/capabilities.yaml` (`StoreCapabilitiesTest`: equal to the stores' declarations, only
  shrinks). `files` is a built-in format beside `delta` and `iceberg` (`Stores`). Parity kit: five capability cases
  (`tck/cases/capabilities`, store `files`: load and read, append without a batch, a batch refused with its reason, undo refused, a
  date deleted); the runner's `store` step calls the SPI; `make_reference.py` runs the reference's stores only (`--stores
  delta,iceberg`) and `compare_results.py` accepts cases of a store the host never had (they must run, stable over two runs): 32
  identical, 5 beyond the reference.
- **Time travel, the last declared gap closed.** `TableStore.versions` (a table's versions oldest first: `TableVersion`, the store's
  number and commit time) and `ScanRequest.asOf` (`AsOf`: the newest, a version, or the newest committed at or before a time) on all
  three stores: Delta through the Kernel's snapshot at a version or timestamp and its commit range, Iceberg through its snapshot log
  (snapshot id or time), files through its log (each commit now records when it was committed). `tck/capabilities.yaml` is empty for
  every store; parity case `time-travel/versions-and-earlier-rows` equal on Delta, Iceberg and files (and the memory row).
- **Delta interop with delta-rs runs, both ways, never skipped.** `com.ash.lake.tck.DeltaRs` runs `tools/ci/delta_rs.py` under
  `uv run --with` the pins of `tools/ci/interop-pins.txt` (`deltalake==1.6.6`, `pyarrow==26.0.1`; from uv's cache, else fetched);
  `DeltaInteropTest` (47 partition values of every special-character class × 2 dates): our lake writer writes, delta-rs (`DeltaTable`
  and `pyarrow.dataset`) reads every row, each folder is Spark's Hive escaping, each `add.path` decodes to a file; our loader's ids and
  texts read by delta-rs; delta-rs writes, the Delta store reads. Found and fixed at the cause: the Delta store read a partition
  value recorded as `""` as an empty string; the protocol (and Spark, delta-rs) read it as null. The memory lake's delete and retention
  parity tests read through delta-rs the same way (they skipped before). `tools/ci/check.py`: delta-rs at hand first, then the interop
  tests' reports must show them run with none skipped.
- **One loader: `JsonlLoader` folded in.** The loader reads rows already wrapped with their table (`source.format: loader-rows`,
  `{domain, kind, id, date, doc, columns}` a line, into `target.domain`/`target.kind`, on every store): the document is `doc` (JSON
  text or an object), the id the wrapper's, the row's own date only counted against its file's date, a promoted value the document
  lacks taken from `columns`; a row of another table or without its document is rejected with its reason. `JsonlLoader` (the files
  store's second writer) is deleted; parity case `load/loader-rows` equal on Delta, Iceberg, files and the memory row. For the host's
  adapter (G4): `data load --store files` becomes the loader with `format: loader-rows`, one table a load (a tar's
  `<kind>/<date>/` members as the loader's archives).
- **One loader: `IcebergLoader` folded in.** The Iceberg tables' second writer of loader rows is deleted: the one loader writes the
  same layout (`IcebergTarget`: id, doc, business_date, promoted and vector columns, identity partition, sorted by id) with
  `format: loader-rows`, one table a load, and its own external sort and memory budget; keeping the newest N days is
  `IcebergMaintenance --keep-days` (lake maintain, guarded by `LoadGuard`: counted from `--as-of`, the drop share capped), not part of a
  load. The words of a failure move with it: `LakeLoad.oneLine` names a missing bucket, its endpoint and what to check, and an
  unreachable REST catalog or object store in words (`LoadFailureWordsTest`, ported from `IcebergLoaderFailureTest`). For the host's
  adapter (G4): `data load --store iceberg` becomes the loader with `format: loader-rows`, as `--store files` did. Host-word files
  68 -> 66.
- **L12, first part: the host's bill of materials gone.** `spring-boot-dependencies` is no longer imported; `java/pom.xml` pins the
  product's own versions (39 libraries, and 25 for the S3 mock alone, test support, leaving with it at L9), each the version the build
  resolved with the bill, so every module's resolved dependencies are unchanged (checked: `dependency:list` before and after equal).
  The exemption is removed from `SPLIT_EXEMPTIONS.md`.
- **L9, first part: the S3 simulator decided, the build's test support trimmed.** S3Mock stays as the S3 simulator (maintained,
  in-process, no Docker or native binary: it runs on Windows), test support only: optional in `lake-tck`, so only a module that
  declares it (`lake-load`, test scope) gets it; Spring Boot and Kotlin are gone from every other module's test class path. The host's
  Kotlin bill of materials removed; Testcontainers (declared, never used) removed; `commons-compress` 1.28.0 and `commons-io` 2.21.0
  pinned so every run-time class path is unchanged (`dependency:list` of every module before and after: compile and run-time scopes
  equal). `S3LoadTest` fails when the simulator does not start (it skipped before: a missing tool never skips).
- **L9, second part: every store equals Delta, case by case.** The parity kit's gate (`compare_results.py`) now holds Iceberg and the
  memory row to their Delta case as it held the files store (the dump's date form aside); only a gap declared in `tck/capabilities.yaml`
  with its case may differ, and none is. It found two Iceberg faults, both fixed: the command-line undo (`LakeUndo`) reached Delta and
  files tables only (Iceberg tables now go through their store, as files do; `undoAll` is the same walk with each store's format, and
  `IcebergMaintenance.undoLake` is gone), and the Iceberg undo of a replace committed a REPLACE, which iceberg-core refuses once rows are
  restored (now an overwrite, `Operations.overwriteFiles`, with the undo's snapshot summary; its version is the table's commit number
  from 0, as Delta's). Known differences from the reference: the two undo cases of Iceberg (local and memory row), each with its reason.
- **No business date in the future, for every store.** The check `IcebergLoader` had (its `LoadGuard`) is kept by the one loader for
  Delta, Iceberg, files and the memory row: a date given (`--as-of`, a `.ready` file's `business-date`) or taken from the rows (also a
  loader row's own date) that is after today is refused with the same words as a name's or a folder's, before anything is written
  (`FileDates.futureError`, one rule for every source). Parity case `load/future-date-refused`; shared case in
  `tck/fixtures/file-dates/names.json`.
- **L9, third part: the S3 row of the matrix.** Every Delta and Iceberg case also runs with its lake on `s3://` (S3Mock in the
  runner's JVM, from `lake-tck`, in a runner of its own so the simulator is on no other class path), `<case>@<store>~s3`, and must
  equal the reference's local result. The runtime now carries the S3 store (`lake.s3.endpoint`, `lake.s3.region`, `lake.s3.path-style`,
  `lake.s3.read-block-kb`; credentials from the AWS chain): every reader and writer is built with `S3Settings.from(runtime, own)`, so
  undo, retention, the table walks and the dumps reach a custom endpoint as the loader does (they passed no settings before). Found
  and fixed: Kernel names `_last_checkpoint` with size 0, which an object store read as an empty file and Kernel retried for three
  seconds a snapshot (now the store is asked, a missing object is not found, as on local disk); the undo's Iceberg table root on S3.
- **L9, fourth part: concurrent writers.** The kit's runner takes `parallel:` steps (writers started together); case
  `concurrent/two-writers-two-dates`: two loaders (`--no-lock`) write one table at once, both commits land, on Delta, Iceberg, files and
  the memory row. Not on the S3 row: S3Mock does not make a conditional PUT atomic between two requests in flight (both won 48 of 50
  races, measured), which S3 and MinIO do; the case says so (`not-on`). Found and fixed in the files store: a writer moved its content
  over the winner's `<n>.jsonl` before the number was won (rows lost or doubled), an append was built on the published day file, and two
  writers shared `columns.json.tmp`; commits to one table now go one at a time under an operating-system lock (`commit.lock`), an append
  is built on the date's newest version and rebuilt if another writer moved it, and the day file never goes back to an older version.
- **L12: the product's bill of materials, `lake-bom`.** One place for every version a host pins: each Drishti Lake module and the
  third-party libraries the product runs on (Delta Kernel, Iceberg, Parquet, the AWS SDK, Hadoop, codecs, Jackson, Netty, the HTTP
  clients); a host imports it (`<scope>import</scope>`) and keeps no lake version of its own. It is the reactor's parent, so the build
  uses exactly it; test support's libraries (S3Mock and its Spring Boot, JUnit, AssertJ, Logback) stay pinned in the reactor and never
  reach a host. Resolved dependencies of every module unchanged (dependency:list before and after); no Spring Boot bill of materials is
  needed by the product.
- **L9, fifth part: the kit's coverage and additive gates.** `tools/ci/coverage.py` (run by `check.py`): every loader setting,
  command-line flag, `.ready` key and `LK-` code is used or shown by a case, measured from the cases and a run; what none covers is in
  `tck/coverage-baseline.txt`, which only shrinks; `tck/cases.txt` lists every case, and cases are only added. New cases: file layout
  and flatten, dry run / date-from / mode / progress, a `.ready` file's date: 59 of 103 items covered. Found: Iceberg refuses
  `partition-by` beside the business date (a parity gap; its case comes with the fix). The host's read API needs written in
  SPLIT_STATUS (G4 rehearsal).
- **Today is the business zone's.** The loaders' today (a business date after it is refused; a file with no date gets its business
  today) is the calendar date in the runtime's business zone, `lake.business.zone` / `LAKE_BUSINESS_ZONE` (default
  `America/New_York`, `LakeOptions.businessZone()`), taken once per load; it was the JVM's default zone. The same zone for the inbox's
  routes and checks, the memory lake's `today` dates and lake maintain's guard (`--zone` still wins there). `BusinessZoneTest`: a date
  that is tomorrow in `Etc/GMT+12` and today in `Pacific/Kiritimati` is refused in the one and loaded in the other.
- **L10, first part: the Python path, `drishti_lake` (LK-7).** `python/` builds the wheel `drishti-lake` (`uv build`; deltalake 1.6.6,
  pyarrow 26.0.1, PyYAML; Polars 2.1.0 optional). One command, the Java loader's: `python -m drishti_lake load --config FILE` with
  `LakeLoad`'s flags, the same configuration file (the same keys, refused in the same words), the same rules (`filedate`: names, dates,
  the `.ready` file, tars, the business zone's today, no future date; `rowrule`: rejects in the Java loader's words, the id as text, the
  document text, flatten, vectors, promoted types; loader rows; the rows' own date in a replace; `latest-by`; `rejects.max-pct`; a
  batch appended once), the same summary keys and exit codes, the same locks; one commit per business date through delta-rs. Polars
  builds the Arrow table when installed, pyarrow otherwise: the same table (a test). Delta on the local disk only for now; anything else
  is refused naming the Java loader. The kit's **Python row** (`<case>@delta^python`, `tck.py --loaders java,python`): every Delta case's
  loads by the Python path must equal the Java line of the same run (`compare_results.py`; `tck/python-differences.txt` only shrinks:
  empty) — 25 of 25 equal, incl. undo, retention, time travel and concurrent writers over Python-written tables. `python/tests`
  (pytest, in `check.py`): the shared `names.json` cases, Polars and the plain writer equal, the business zone, no host word. The host's
  Python callers' mapping in SPLIT_STATUS.
- Not moved yet, on purpose (each with its step): the contracts tool (L11); the Python path's lake files, maintenance and archive guards (L10, later parts).
