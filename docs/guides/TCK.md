<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# The parity kit (TCK): for backend authors and maintainers

One conformance kit holds every store and storage to the same behaviour. A **case** is data (`tck/cases/<area>/<name>.yaml`); the kit runs it on
each store, in a fresh folder, and writes one canonical result line per case; **a store is conformant when its answer equals the others'** for
every case. A difference is declared (`tck/capabilities.yaml`, which today declares none) or it is a failure, never a skip.

## A case

```yaml
# rows already wrapped with their table load through the one loader on every store (tck/cases/load/loader-rows.yaml)
inputs:                      # files written into ${in}: literal text, a generator {pv: {n, scenarios, dates, seed}}, or {tar: {...}} / {zip: {...}}
  rows.2026-10-27.jsonl: |
    {"domain":"risk","kind":"scenario-pv","id":"P-1","date":"2026-10-27","doc":{"POSITION_ID":"P-1","DESK":"DESK-FX"},"columns":{}}
config: |                    # the loader's configuration; ${store}, ${lake}, ${in} are filled by the kit (several: configs: {main: ..., second: ...})
  source: {files: '${in}/*', format: loader-rows, promote: [DESK, QUANTITY]}
  target: {format: ${store}, lake: '${lake}', domain: risk, kind: scenario-pv}
steps:                       # load | undo | retention | store (a store operation: read-doc, scan-at, delete-row, ...) | parallel: [steps]
  - load: []
tables: [risk/scenario-pv]   # read back and compared (default risk/scenario-pv)
stores: [delta, iceberg, files]    # default: all three
```

Other keys: `local-only` (not on the memory or S3 rows), `not-on` (a storage the case cannot run on, with the reason), `foreign` (tables written by
another engine: delta-rs, pyiceberg or plain JSON Lines, to read back). The areas: `concurrent`, `delete`, `inputs` (archives, codecs, member and
size guards), `layout`, `load` (modes, batches, vectors, loader rows, contracts), `modes`, `names`, `read` (the read API), `rejects`, `retention`,
`time-travel`, `undo`. `tck/cases.txt` lists every case.

## The matrix

| Row | What changes | Name of the result |
|---|---|---|
| store | delta, iceberg, files | `<case>@<store>` |
| memory | the case's lake on `mem://` (Delta and Iceberg) | `<case>@<store>~mem` |
| S3 | the lake on `s3://` against an in-process S3 simulator (`lake-tck`), reached through the runtime's `lake.s3.*` options | `<case>@<store>~s3` |
| Python | the case's loads by the Python path ([PYTHON.md](PYTHON.md)), Delta | `<case>@delta^python` |

## Run it

```text
python3 tools/tck/tck.py --out results.jsonl                                  # every case, every row (builds the loader first; JDK 21)
python3 tools/tck/tck.py --out r.jsonl --only load/two-dates --storages local --loaders java     # one case, three stores
python3 tools/ci/check.py                                                    # the whole check, below
```

`tck.py` options: `--only PREFIX`, `--stores a,b`, `--storages local,mem,s3`, `--loaders java,python`, `--classpath-file FILE` (run over another class path),
`--runner FILE` (another runner). The runner (`tools/tck/TckRunner.java`) uses only the loader's command-line entry points and the stores' readers. A result
holds no time, id, path or size, so two runs compare byte for byte.

## The gates (`tools/ci/check.py`)

1. The build with every test and guard. 2. The Delta interop tests, both ways with **delta-rs** at the pinned versions (`tools/ci/interop-pins.txt`; run by
`uv`; **never skipped**: without `uv` the check fails). 3. The Python tests. 4. The kit twice: the results must be identical. 5. `tools/ci/coverage.py`: every loader
setting, loader flag, `.ready` key and `LK-` error code is used by some case; what no case covers is in `tck/coverage-baseline.txt`, which only shrinks; and
`tck/cases.txt` only grows (a case is never removed or renamed). 6. `tools/split/compare_results.py`: every case equals its **Delta** case (the stores are
feature-identical; a declared gap in `tck/capabilities.yaml` is the only exception), the memory and S3 rows equal the local row's, the Python row equals the Java
row (`tck/python-differences.txt` only shrinks), and the results over the product equal the stored reference results (`tck/results/reference-*.jsonl.gz`,
the kit's results over the code before it was moved here; differences are listed with their reason in `tools/split/known-differences.txt`).

## For a backend author

The SPI is in `com.ash.lake.spi` (module `lake-api`, which depends on the JDK and the settings library only):

| Implement | When | Contract |
|---|---|---|
| `StorageProvider` / `Storage` | a new place to keep tables (Azure, GCS, a vendor's object store) | a scheme; list, status, ranged read, create, `createIfAbsent` (the commit primitive), delete |
| `TableStore` | a new table format | `format()`, `capabilities()`, `exists`, `columns`, `dates`, `versions`, `scan` (with pushdown), `writer` (one commit per date), `deleteDates`, `undo`, `open` (the read API) |

Declare what the store cannot do with `Capabilities.allBut(capability, reason, ...)` (`Capability`: `LOAD_REPLACE`, `LOAD_APPEND`, `APPEND_BATCH_ONCE`, `SCAN`,
`COLUMNS`, `DATES`, `DELETE_DATES`, `UNDO`, `TIME_TRAVEL`, `ATOMIC_COMMIT`, `SCHEMA_EVOLUTION`, `TYPED_LIST_COLUMNS`, `PROMOTED_COLUMNS`); a case of a missing
capability must then be a refusal, not an absence. Register your implementations on the runtime that runs the loader (`LakeRuntime.builder().storage(...).store(...)`;
nothing is found on the class path), which is how the runner's `RT` registers the memory storage. The certificate is the kit's: your store's result for every case
equal to the built-in stores'. The matrix is fixed in `tools/tck/tck.py` (`jobs`): a new store joins by being listed in the cases' `stores:`, a new storage by a row
there (as `~mem` and `~s3` are), and the runner is replaceable with `--runner`.

## Tutorial: run one case and read its result

```text
python3 tools/tck/tck.py --out /tmp/r.jsonl --only load/two-dates --storages local --loaders java
3 results -> /tmp/r.jsonl
python3 - <<'PY'
import json
for l in open('/tmp/r.jsonl'):
    r = json.loads(l); print(r['case'], r['status'], r['steps'][0]['summary']['rows'])
PY
load/two-dates@delta ok {'2026-10-27': 40, '2026-10-28': 40}
load/two-dates@files ok {'2026-10-27': 40, '2026-10-28': 40}
load/two-dates@iceberg ok {'2026-10-27': 40, '2026-10-28': 40}
```

The three store lines carry the same `tables` content apart from their name: that equality is the whole test.
