<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Changelog

Entries are written per release; unreleased work is listed first.

## Unreleased (0.9.0): the move from Drishti

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
- Not moved yet, on purpose (each with its step): the memory lake (L8), the files store (L8), the Python path (L10), the contracts tool (L11).
