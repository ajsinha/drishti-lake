<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Changelog

Entries are written per release; unreleased work is listed first.

## Unreleased (0.9.0): the move from Drishti

- **The read API, designed** ([docs/design/READ_API.md](docs/design/READ_API.md); nothing built): `com.ash.lake.read` entered through
  `TableStore.open`, own types only, the same on Delta, Iceberg, files and `mem://`; every call of the host's source adapters mapped;
  the stores' defects to close (deletes in column and vector reads, files statistics and identity, Iceberg `partition-by`); 20 parity
  cases; the performance proof; steps RA-S0 to RA-S10; decisions RA-1 to RA-12 open.

- **IDE launchers**: `java/run-configs/` (build all, test, install release for a tag only, the loader and check programs), `tools/run-configs/` (the TCK, the repository check) and `drishti-lake.code-workspace` for VS Code; README, Build and run in the IDE.

- **L0, the allow-list.** [docs/MOVE_ALLOWLIST.md](docs/MOVE_ALLOWLIST.md): every file of the lake modules, the three table plug-ins,
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
- Not moved yet, on purpose (each with its step): the memory lake (L8, part 2), the Python path (L10), the contracts tool (L11).
