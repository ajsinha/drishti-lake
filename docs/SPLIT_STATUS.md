<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Split status

The steps of the design's section 7 ([DRISHTI_LAKE_DESIGN](design/DRISHTI_LAKE_DESIGN.md)), with their state and proof. Method as
Drishti Rachana's re-order of 2026-11-19: **move first, untangle here; Drishti is not modified until G4.** Freeze commit: Drishti
`main` `9ce7c3170` (2026-11-21). Decisions LK-1 to LK-16 accepted; LK-9 confirmed by the owner (2026-11-21): the Delta `hadoop` engine is not in the product and is
retired from Drishti in G4, so `LakeStore` comes here with the native path only (L7).

| Step | What | State | Proof |
|---|---|---|---|
| L0 | allow-list, file by file | **done** | [MOVE_ALLOWLIST](MOVE_ALLOWLIST.md); size and private-data scan of all history of the listed paths |
| L1 | bootstrap: reactor, headers, licence, guards | **done** | guards: names no host, exemptions only shrink, header, file cap, dependency allow-list, official libraries only (failing); public surface, relative paths, configuration writes (report mode); `tools/ci/check.py` (local, no hosted CI); `tools/release/release.py` skeleton |
| L2 | bridge and first TCK cases | **done** | `make_reference.py` over Drishti's jars at `9ce7c3170`; 15 cases × Delta, Iceberg = 30 results, stable over two runs; this code's results equal the reference (30/30) |
| L3 | move with history, rename, hostcompat | **done** (untagged: no tag before the owner says) | 283 commits, 587 files; `./mvnw clean verify` green: 348 tests, 0 failures, 1 skipped (parked); the guards green |
| L4 | own settings and errors | **done** | part 1: `LakeException`/`LK-` codes, TLS and proxy records in `com.ash.lake.io.net`; part 2: `ChannelIo`, `SingleFlight`, `LoadGuard` in `com.ash.lake.common`; part 3: `LakeOptions` (`lake-api`), `LakeRoots`, `Placeholders`, `SettingsFile`, `RowFeed` own, `StartOnFewCoresTest` moved (one carrier, eight loads) (hostcompat 41 → 18, host-word files 79 → 73); kit 30/30 equal each time |
| L5 | configuration divided | **done** | `LakeCatalog` SPI (`com.ash.lake.spi`, in `lake-api`); named lake settings the product's own (`NamedLakeSettings`); the 12 pack and module types of hostcompat gone (41 → 6), `lake-config` no longer needs hostcompat; one parked check returned (2 → 1); every lake file of the host's `config/lakes` and `config/lakes.examples` reads to the same model as the reference (`tools/split/lake_models.py`: 15 of 15, the host's shipped lakes now its catalog's); kit 30/30 |
| L6 | per-instance runtime | **done** | `LakeRuntime` replaces `LakeOptions.current()`, `LakeCatalog.current()`, `StorageProviders` (`ServiceLoader`), `Lakes.useProperties`, `NamedLakeSettings.useView` and the static caches; no static registry, `ServiceLoader` or hidden system-property read left in main code; `TwoRuntimesTest`; `StartOnFewCoresTest` green; kit 30/30 |
| L7 | table layer | **part 1 done** | `LakeCheck` (now `lake-load`) and `LakeUndo` read Delta through the Kernel (snapshot, commit range) and Iceberg through iceberg-core: official-libraries list empty; hostcompat 6 → 0 (`com.ash.lake.rows`, module gone); persisted names written `lake.*`, read both; TCK 15 → 16 cases, kit 32/32. Part 2 open: `LakeStore`/`NativeLake`/`NamedTables`/`NewTables` (native path only), `TableStore` SPI, API surface, Hadoop on the class path |
| L8 to L14 | stores, TCK, Python, contracts, API, docs, release | not started | |

## What the move left for later (each listed with its step)

- **Not moved yet** (MOVE_ALLOWLIST section 3): the memory lake (`memlake`, `memlake.store`: L8, behind `RowSource`; `MemoryLake`
  takes a `ReplenishSource` over the host's connector registry); `LakeStore`, `NativeLake`, `NamedTables`, `NewTables` (L7: `LakeStore`
  chooses the Hadoop engine, which stays in Drishti, LK-9); the files store (L8); `tools/lake` (L10); `tools/contracts.py` (L11);
  `LoaderRows`, `MinioServer`, `ConditionalWrites` and the Python path's Delta-feature fixtures (with the TCK cases that use them).
- **Moved but owing the host** ([SPLIT_EXEMPTIONS](../java/SPLIT_EXEMPTIONS.md)): 41 host types in `hostcompat` (L4: settings, TLS,
  proxy, errors, small utilities; L5: packs and modules; L7: row and column types); 79 files with a host word; 2 parked checks; the
  host's Spring Boot bill of materials for versions only (L12).
- **Tests that stayed in Drishti** (they test its adapters or read through its `SourcePlugin`s): their table-layer cases are harvested
  into the TCK at L2 and L9, so nothing they prove is lost.

## The host's catalog (L5, for Drishti's adapter at G4; not built here)

Drishti implements `LakeCatalog` once and registers it with the one `LakeRuntime` its server builds (`LakeRuntime.builder().catalog(..)`), with what its types
gave the moved code before: `shippedLakes`/`shippedLake` from its kernel's and live modules' `drishti/lakes/shipped.txt` indexes
(`Lakes.shippedIn` reads one); `packFiles` from its `ConfigResolution` (the site folder's packs, else `PackFolders`);
`connectorTemplates` from `ConfigResolution.templatesForKinds`; `namedLakeWriters` from its `DomainVocabulary` union; `extensions`
from `Extensions.of` (a module's `ReadyRule`); `childProcessSettings` the module boot's `drishti.module.homes`, `drishti.module.dir.*`
and `drishti.modules.dev`; and its environment's view as the runtime's settings (`.settings(lookup, names)`: the lake files'
placeholders and the named lakes). It builds the runtime's `LakeOptions` from its roots (`lake.home`, `lake.data.dir`, ...), registers its
memory lake's `StorageProvider` (`mem://`) with `.storage(..)`, keeps `drishti.data.dir` for its lake files' placeholders, and hands the
runtime to every call (`LakeLoad.run(runtime, ..)`, `DataLoadSettings`, `Catalogs.open`, `NativeEngine.create`, `LakeUndo`, ...).

## Next steps

1. **L7 and L8** as the design orders them (table layer and `TableStore`, `LakeStore` with the native path only (LK-9 confirmed), `LakeCheck` and
   `LakeUndo` onto the official Delta library; files store and memory lake with their own allow-lists); then L9 to L14.
2. The parity kit grows toward the 60% harvest as each step goes (child tables, list columns, promoted columns, flatten, latest-by,
   `.ready`, relocate, maintenance).
3. The owner names when `v0.9.0` may be tagged.
