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
| L7 | table layer | **done** | `LakeCheck` (now `lake-load`) and `LakeUndo` read Delta through the Kernel (snapshot, commit range) and Iceberg through iceberg-core: official-libraries list empty; hostcompat 6 → 0 (`com.ash.lake.rows`, module gone); persisted names written `lake.*`, read both; TCK 15 → 16 cases, kit 32/32. part 2: `LakeStore`, `NativeLake`, `NamedTables`, `NewTables` moved with history, native path only (`engine: hadoop` refused, LK-9), `LakeStoreTest`; Hadoop on the class path reviewed (until L12). part 3: the `TableStore` SPI (`com.ash.lake.spi`, product types only), the loader writes through `Stores.of(runtime, format)`; `java/api-surface.txt` + `ApiSurfaceTest` (no library type in a public signature; any API change rewrites the file in the same commit) |
| L8 | stores | **done** | part 1: the files store moved (`JsonlDay`, `JsonlFormat`, `JsonlLoader`, 10 commits) into `lake-files`; `FilesStore` a full `TableStore`. Part 2: the memory lake moved with its history (`tools/split/l8b-paths.txt`, 28 commits; `MemoryBinding` stays in the host) into `lake-memory` (`com.ash.lake.memory`, `.store`): the host's connectors behind `RowSource` (`lake-api`: documents, fetch by ids, mark, memory-only connectors) for `ReplenishSource` and `Retention` (`LK-1005`); `MemoryStores` per runtime, held by the `MemoryStorageProvider` the host registers (`.storage(..)`; no static store, no `ServiceLoader`); `MemoryRowSourceTest` (two runtimes, two sets of stores); the parity kit's memory row (`<case>@<store>~mem`, Delta and Iceberg on `mem://`) equal to the reference's local results (32 of 32); undo and retention find tables through the runtime's storage |
| L9 to L14 | TCK, Python, contracts, API, docs, release | L12 begun: the host's bill of materials replaced by the product's own pinned versions (resolved dependencies unchanged); the rest not started | |

## What the move left for later (each listed with its step)

- **Not moved yet** (MOVE_ALLOWLIST section 3): `tools/lake` (L10); `tools/contracts.py` (L11);
  `LoaderRows`, `MinioServer`, `ConditionalWrites` and the Python path's Delta-feature fixtures (with the TCK cases that use them).
- **Moved but owing the host** ([SPLIT_EXEMPTIONS](../java/SPLIT_EXEMPTIONS.md)): 41 host types in `hostcompat` (L4: settings, TLS,
  proxy, errors, small utilities; L5: packs and modules; L7: row and column types); 79 files with a host word; 2 parked checks; the
  host's Spring Boot bill of materials for versions only (gone at L12: the product pins its own). Now: 0 host types, 68 host-word
  files, 1 parked check.
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

1. Every declared gap is closed: time travel (`TableStore.versions`, `ScanRequest.asOf`) on all three stores with its parity case
   (`time-travel/versions-and-earlier-rows`); then L9 to L14. The files store's other five gaps are closed (owner, 2026-11-21: Delta, Iceberg and files feature-identical): the files store keeps a log per table
   (`<root>/<domain>/_log/<table>/`: `columns.json`, `<n>.json` per commit, `<n>.jsonl` the date's content at that version; the day file
   published from it), its lines carry the typed columns (promoted, `list<string>`, vectors, load columns; one rule with Delta's,
   `PromotedValues`), an append's batch is committed once, undo goes through the store (`LakeUndo` finds files tables), deletes and row
   retention commit to the log; every parity case runs on all three stores and the files line must equal the Delta line
   (`compare_results.py`). `JsonlLoader` is folded into the loader (`source.format: loader-rows`; parity case `load/loader-rows`).
   Delta interop with delta-rs runs both ways in every check (`DeltaInteropTest`, pins in `tools/ci/interop-pins.txt`). `IcebergLoader` is
   folded the same way (one writer per store: the loader; keep-days is lake maintain's).
2. The parity kit grows toward the 60% harvest as each step goes (child tables, list columns, promoted columns, flatten, latest-by,
   `.ready`, relocate, maintenance).
3. The owner names when `v0.9.0` may be tagged.
