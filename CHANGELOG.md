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
- Not moved yet, on purpose (each with its step): the memory lake (L8), the Delta lake opener that chooses the Hadoop engine (L7;
  the engine itself stays in Drishti, LK-9), the files store (L8), the Python path (L10), the contracts tool (L11).
