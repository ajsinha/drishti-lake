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
| L4 to L14 | untangle, TCK, Python, contracts, API, docs, release | not started | |

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

## Next steps

1. **L1, the rest**: per-module dependency allow-list (design section 6), the public-surface dump in report mode, the
   official-libraries-only pattern scan, explicit paths and writes-no-configuration guards; a CI entry point and a release script
   skeleton (no tag).
2. **L2, the bridge**: a results-file runner over Drishti's own jars at `9ce7c3170` (a scratch worktree, read only, as Rachana's
   `make_reference.py`), and the first 60% of `tck/cases` harvested from the parity and interop tests (the staying adapter tests
   included); stable over two runs.
3. **L3's check, completed**: TCK results over this code equal the bridge's (`compare_results.py`, `known-differences.txt`).
4. **L4**: `LakeOptions`, TLS and proxy records, `LakeException` and `LK-` codes; the L4 rows of hostcompat leave; the host words
   and the `drishti.*` settings go down; `StartOnFewCoresTest` moved.
5. **L5 to L8** as the design orders them (configuration divided behind `LakeCatalog`; per-instance `LakeRuntime`; table layer and
   `TableStore`, with the delta opener's move; files store and memory lake with their own allow-lists); then L9 to L14.
6. The owner confirms LK-9 (nobody uses `abfs://`, `gs://` or HDFS through the Hadoop engine) and names when `v0.9.0` may be tagged.
