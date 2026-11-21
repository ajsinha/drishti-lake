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
| L4 | own settings and errors | **done** | part 1: `LakeException`/`LK-` codes, TLS and proxy records in `com.ash.lake.io.net`; part 2: `ChannelIo`, `SingleFlight`, `LoadGuard` in `com.ash.lake.common`; part 3: `LakeOptions` (`lake-api`), `LakeRoots`, `Placeholders`, `SettingsFile`, `RowFeed` own, `StartOnFewCoresTest` moved (one carrier, eight loads) (hostcompat 41 → 18, host-word files 79 → 73); kit 30/30 equal each time. G3: those copies (21 files) deleted for Drishti Tools 1.0.0 (`com.ash.tools.settings`, `.net`, `.concurrent`, `.time`, `.testkit`; `lake-bom` pins `drishti.tools.version`) |
| L5 | configuration divided | **done** | `LakeCatalog` SPI (`com.ash.lake.spi`, in `lake-api`); named lake settings the product's own (`NamedLakeSettings`); the 12 pack and module types of hostcompat gone (41 → 6), `lake-config` no longer needs hostcompat; one parked check returned (2 → 1); every lake file of the host's `config/lakes` and `config/lakes.examples` reads to the same model as the reference (`tools/split/lake_models.py`: 15 of 15, the host's shipped lakes now its catalog's); kit 30/30 |
| L6 | per-instance runtime | **done** | `LakeRuntime` replaces `LakeOptions.current()`, `LakeCatalog.current()`, `StorageProviders` (`ServiceLoader`), `Lakes.useProperties`, `NamedLakeSettings.useView` and the static caches; no static registry, `ServiceLoader` or hidden system-property read left in main code; `TwoRuntimesTest`; `StartOnFewCoresTest` green; kit 30/30 |
| L7 | table layer | **done** | `LakeCheck` (now `lake-load`) and `LakeUndo` read Delta through the Kernel (snapshot, commit range) and Iceberg through iceberg-core: official-libraries list empty; hostcompat 6 → 0 (`com.ash.lake.rows`, module gone); persisted names written `lake.*`, read both; TCK 15 → 16 cases, kit 32/32. part 2: `LakeStore`, `NativeLake`, `NamedTables`, `NewTables` moved with history, native path only (`engine: hadoop` refused, LK-9), `LakeStoreTest`; Hadoop on the class path reviewed (until L12). part 3: the `TableStore` SPI (`com.ash.lake.spi`, product types only), the loader writes through `Stores.of(runtime, format)`; `java/api-surface.txt` + `ApiSurfaceTest` (no library type in a public signature; any API change rewrites the file in the same commit) |
| L8 | stores | **done** | part 1: the files store moved (`JsonlDay`, `JsonlFormat`, `JsonlLoader`, 10 commits) into `lake-files`; `FilesStore` a full `TableStore`. Part 2: the memory lake moved with its history (`tools/split/l8b-paths.txt`, 28 commits; `MemoryBinding` stays in the host) into `lake-memory` (`com.ash.lake.memory`, `.store`): the host's connectors behind `RowSource` (`lake-api`: documents, fetch by ids, mark, memory-only connectors) for `ReplenishSource` and `Retention` (`LK-1005`); `MemoryStores` per runtime, held by the `MemoryStorageProvider` the host registers (`.storage(..)`; no static store, no `ServiceLoader`); `MemoryRowSourceTest` (two runtimes, two sets of stores); the parity kit's memory row (`<case>@<store>~mem`, Delta and Iceberg on `mem://`) equal to the reference's local results (32 of 32); undo and retention find tables through the runtime's storage |
| L11 | contracts | **done** | `drishti_lake.contracts` (schema, reader with a host's `Profile`, schema/record/data checks, pages with a host's `PageProfile`) and `lake-contracts` (`Contract`, `ContractChecker`: the same words, `tck/fixtures/contracts/records.json`); the loader's `target.contract` on both paths (case `load/contract-rejects-rows`, every row); `tools/split/contracts_proof.py` over Drishti at the freeze: 139 contracts read and valid with its profile (merged schema = its own), 20 of 20 pages byte-identical. The host keeps its contract files, its pack folders and its words (its adapter: the proof's `HOST_PROFILE` and `HostPages`) |
| L9 to L14 | TCK, Python, contracts, API, docs, release | L9 begun: the S3 simulator decided (S3Mock stays, test support only, optional in `lake-tck`), the Kotlin bill and Testcontainers gone, no skip for a missing simulator; the gate holds every store (Iceberg, files, the memory row) to its Delta case, no gap declared (it found and fixed the Iceberg command-line undo and its REPLACE commit). The S3 row (`~s3`, S3Mock in its own runner): every Delta and Iceberg case on `s3://` equals the reference's local result; the runtime carries the S3 store (`lake.s3.*`), so undo, retention and the readers reach a custom endpoint. L12 begun: the host's bill of materials replaced by the product's own pinned versions (resolved dependencies unchanged); `lake-bom` published for hosts (the reactor's parent; test support's pins stay out of it); L9 parts 3-5: the S3 row, concurrent writers (files store's commit fixed), the coverage and additive gates (`tools/ci/coverage.py`, `tck/coverage-baseline.txt` only shrinks, `tck/cases.txt` only grows; 59 of 103 items); the rest not started. L10 begun: `python/drishti_lake` (the wheel), the Python row of the kit equal to the Java row (25 of 25) | |

## What the move left for later (each listed with its step)

- **Not moved yet** (MOVE_ALLOWLIST section 3): `tools/lake` (L10); `tools/contracts.py`'s drafting half (`bootstrap`: reads the host's packs, about.yaml, Sutras; stays host);
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

## The Python path and the host's Python callers (L10, for Drishti's D5 at G4; not done here)

`python/drishti_lake` (wheel `drishti-lake`, built by `uv build`; pins deltalake 1.6.6, pyarrow 26.0.1, PyYAML; Polars 2.1.0 optional:
used when installed, the plain writer otherwise, the same table either way). One command, the Java loader's:
`python -m drishti_lake load --config FILE [the LakeLoad flags]`, the same configuration file, the same summary keys and exit codes.
Today it writes Delta on the local disk (a lake folder, `target.path`, or a named lake's Delta entry read through the Java tools); Iceberg, files, `s3://`/`mem://`,
`datasets:` and `source.pack` are refused before a row is read, naming the Java loader. Its proof is the kit's Python row
(`<case>@delta^python`, every Delta case, equal to the Java line of the same run; `tck/python-differences.txt` only shrinks).

The host's callers (about 66 Python files outside `tools/lake` import it at the freeze commit) switch by import:

| Drishti `tools/lake` | Callers | Drishti Lake | Note |
|---|---|---|---|
| `filedate` | 9 | `drishti_lake.filedate` | same names and arguments (`parse`, `of`, `decide`, `words`, `tar_layout`, `business_today`, `date_error`, `base_name`, `is_tar`, `other_date_note`); today is the business zone's (`LAKE_BUSINESS_ZONE`), and a date given after today is refused as in Java; `local_day` and `of_input` stay host |
| `build`, `javaload`, `writers`, `icebergwrite`, `stage` | 5 + 3 + 2 + 1 | `drishti_lake.load.main([...])` / `python -m drishti_lake load` | the host's front writes the loader configuration (javaload's `loader.json` is that document) and calls the one command; Iceberg, S3 and the memory lake: the Java loader (`lake-cli`) |
| `jsonstream` | 2 | `drishti_lake.inputs` (`resolve`, `lines`) | JSON Lines only (`jsonl-only`); a JSON array is the host's to convert |
| `archives` | 6 | `drishti_lake.archives` (`plan`, `Limits`, `safe_parts`) through `drishti_lake.inputs` | the Java loader's rule and words: guards (absolute, `..`, links, encrypted, nested), caps from the listing and while reading (`source.archive.*`, `lake.archive.*`), a zip one table, a tar's other tables refused unless `source.dataset` names one; shared fixtures `tck/fixtures/archives` (`test_archives.py`), case `inputs/archive-guards-and-dataset` |
| `lakeconfig` | 16 | `target.lake: <name>` in the load configuration; `python -m drishti_lake lakes ...` | the lake-file reader is Java's (`LakesCli table`), reached through the product's class path (`LAKE_CLASSPATH`, `drishti_lake.java`); the entry applied with the Java loader's agree rule; a Delta entry only, table properties refused naming the Java loader |
| `maintain*`, `delete`, `undo`, `relocate`, `laketables` (check) | few | `python -m drishti_lake maintain|retention|undo|relocate|check` | the Java tools themselves (`IcebergMaintenance`, `LakeRetention` incl. delete dates, `LakeUndo`, `LakeRelocate`, `LakeCheck`) through `LAKE_CLASSPATH`: one way per job; the kit's Python row runs its undo, retention and delete steps this way (equal to the Java row) |
| `scan`, `deltaread`, `ownership`, `compare_lakes`, `memory_*` | few | not yet | reads: the read API (Java) when a host needs them from Python |
| `feedfiles`, `feedroute`, `stores`, `demo`, `publish`, `s3sim` | 19 + 4 + 2 + 2 | stay in Drishti | feeds, source systems and the demo (design 2) |

## The host's read API (G4 rehearsal L1; built through RA-S7 on 2026-11-21)

Built (decisions RA-1 to RA-12 accepted): RA-S0 (section 10 checked, today's numbers recorded), RA-S1 (`com.ash.lake.read`,
`TableStore.open`, `Problems` in `lake-io`), RA-S2 Delta, RA-S3 Iceberg, RA-S4 files (F1, F2), RA-S5 deletes (D1 in the kit, I1 in
`IcebergReadDeletesTest`), RA-S6 Iceberg `partition-by` (P1), RA-S7 pushdown and `ScanStats` (`Pruning`, one rule); the files store's
read by id through a per-snapshot id index; Delta's newest-load order no longer depends on the load id's length, and loads of one millisecond go by commit order on every store (`LoadOrder`). 14 `read/*` cases and
`layout/partition-by-beside-the-date` equal on every store and the memory rows; I2 (Iceberg row-group pieces) and of RA-S9 the cases
(20 of 20; `float32-and-null-vector` with the loader's `target.layout.vector-type: float32` on every store), `TwoRuntimesTest` and
`StartOnFewCoresTest`, `docs/BENCHMARK.md`. The old entry points are gone (G3): `NewTables` deleted, `LakeStore` package-private (the Delta reader's own), their test on the read API (`DeltaLakeOpenTest`). Open: Delta's and the files store's bench rows on a quiet machine (load under 6); RA-S8 last (RA-9, its half-page design first).


Designed in [READ_API](design/READ_API.md): the API, every host call mapped to it, the defects each store closes, the cases, the
performance proof, the steps RA-S0 to RA-S10 and the decisions RA-1 to RA-12 (open, for the owner).

Drishti's Delta and Iceberg source adapters read through the table layer's internal types (`DeltaTable`, `TableProblems`,
`VectorBatches`, `LakeStore`, `NewTables`, `LakeTables`, `KindTable`, `IcebergVectors`, Kernel's `Row`/`StructType`/`Engine`,
Iceberg's `FileScanTask`). The public read SPI (`TableStore`: `exists`, `columns`, `dates`, `versions`, `scan` with `ScanRequest` of
dates, columns, a per-row `Expr` and `AsOf`) covers only whole-row scans, row counts per date, versions and the schema. What the
adapters need and the public API lacks (each to be added deliberately, own types only, LK-13, with parity cases on every store):
1. table discovery and open (list tables, tables loaded after start, the S3 Tables catalog, relocation); 2. health (reachable,
describe, engine, TLS and proxy notes, close) and per-table read problems; 3. a snapshot handle: per date its files (path, size,
format, statistics), version, time, a cache stamp (files stamp, fingerprint, sequence number); 4. deletes present (deletion vectors,
delete files); 5. id maps (per date, id to file, all ids, a memory-mapped form); 6. one document by id in a known file; 7. promoted
columns as primitive arrays and the path-to-physical-column map; 8. file pruning by text bounds and file-level scans; 9. vector
columns as `double[]` per id, batched; 10. bytes and uncounted files per date; 11. table identity (Delta log id, Iceberg uuid);
12. the runtime's cache purges; 13. no Kernel or Iceberg type to the host; 14. filter pushdown (today `Expr` is tested per row).

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
