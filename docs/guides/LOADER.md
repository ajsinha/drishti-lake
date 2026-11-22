<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# The loader, the inbox and the upkeep tools

JSON Lines in, tables out. **One** Java loader (concurrent: the default) and **one** Python path ([PYTHON.md](PYTHON.md)) take the same
configuration file, apply the same rules and write the same summary; the parity kit holds them equal. Rows arrive as JSON Lines, plain or
`gz` / `zst`, or inside a `zip` / `tar` archive; nothing else is a row format.

## Run a load

```text
java -Xmx8g -cp <class path> com.ash.lake.load.LakeLoad --config load.yaml [options]
```

The class path is the `lake-load` module's run-time class path (`./mvnw package dependency:build-classpath -pl lake-load -am -DskipTests
-Dmdep.outputFile=target/cp.txt`, then `lake-load/target/classes:$(cat lake-load/target/cp.txt)`), or your own application's. As a library:
`LakeLoad.run(runtime, args, printStream)` returns the exit code.

| Option | Meaning |
|---|---|
| `--config FILE` | the load's YAML (below); `--set key.path=value` overrides any key (the value is read as YAML; repeatable) |
| `--as-of YYYY-MM-DD` | the business date of every file that does not carry its own |
| `--table T`, `--kind K`, `--pack DIR`, `--key FIELD`, `--mode append\|replace`, `--date-from auto\|name`, `--dataset NAME` | the keys `target.table`, `target.kind`, `source.pack`, `source.id`, `target.mode`, `source.date-from`, `source.dataset` |
| `--dry-run`, `--plan` | read and check everything, write nothing |
| `--summary FILE`, `--progress FILE`, `--no-lock`, `--no-run-log`, `--run-id ID`, `--json`, `-q` | reporting and locking |

**Exit codes:** 0 loaded; 2 loaded with rejects; 1 failed (no date that was not committed changed); 3 another run holds the table's lock.

## The configuration

A mapping of `source`, `target`, `parallel`, `memory`, `rejects`, `report`, `lock`, `datasets`. An unknown key is refused. A value may be
`${NAME}` (an environment variable); keys and secrets are never taken from the file (S3 credentials come from the AWS default chain).

| Section | Keys |
|---|---|
| `source` | `files` (a file, folder or glob `*` `?` `**`; a list), `recursive`, `storage-options`, `format` (documents, or `loader-rows`), `id` (the id field), `date`, `as-of`, `date-from`, `promote` (columns beside the document), `vectors`, `flatten`, `partition-by`, `latest-by`, `pack`, `connector`, `dataset`, `archive` |
| `source.archive` | `max-mb`, `max-ratio`, `max-members` (zip-bomb caps), `validate` (`rows`, the default, or `structure`) |
| `target` | `format` (`delta`, `iceberg`, `files`), `lake` (a lake file's name, or a folder), `path`, `domain`, `kind`, `table`, `storage-options`, `mode`, `default-mode`, `batch-id`, `properties`, `iceberg`, `layout`, `contract` |
| `target.layout` | `sort-by`, `file-size-mb`, `row-group-mb`, `compression`, `vector-compression`, `byte-stream-split`, `shard-aligned`, `vector-type` |
| `parallel` | `readers`, `parsers`, `writers`, `queue-mb`, `split-mb` |
| `memory` | `budget-mb` (default half the heap, shared by the reader queue, the parsers' sort buffers and the writers), `sort-buffer-mb`, `spill-dir`, `check-spill-space` |
| `rejects` | `file`, `max-pct` (the share of rejected rows a load may have and still commit), `keep` |
| `report` | `summary`, `manifest`, `progress`, `run-log`, `dry-run` |

When `target.lake` names a lake file ([STORAGE.md](STORAGE.md)), the table is that file's entry for `target.kind` (location, format,
partitioning, promoted columns, vectors, id, date, latest-by, sort, layout, load mode); a load key that says otherwise is refused with both
values named; a kind the lake file lacks is refused.

## Rules that apply to every load

- **Business date of a file**, in order: the date given (`--as-of`, a `.ready` file's `business-date`), the date in the file name, a tar's
  folder, the business today. A file name is `<table>.<date>.<format>[.<compression>]` (`trades.2026-11-20.jsonl.gz`); anything else is refused
  with the reason, never guessed. **No date after today** (the business zone's, `lake.business.zone`) is ever loaded.
- **Archives.** A `zip` is one table and one business date, its JSON Lines files loose, dated by the archive's name. A tar
  (`.tar`, `.tar.gz`, `.tgz`) is `<table>/<YYYY-MM-DD>/<file>.jsonl[.gz|.zst]`: every member dated by its folder, each table loaded, and any
  other layout refuses the whole archive listing every problem. Absolute or `..` names, links, devices, encrypted members and nested archives
  are refused; the caps are checked from the listing before a byte is decompressed.
- **Modes.** `replace` (also `replace-date`, `overwrite-dates`): each business date of the input replaces that date's rows in one commit;
  `append`: rows are added, and with `target.batch-id` each batch is committed once per date (a retry is skipped). Where the mode came from
  (given, the lake file, the inbox default, the rules' default) is in the summary's `modeFrom`.
- **Rows.** One line, one row: JSON, an object, the id, the date, the vectors, then `target.contract` ([CONTRACTS.md](CONTRACTS.md)); a line
  with two faults is rejected for the first. Rejects are counted by reason; over `rejects.max-pct` nothing commits. Several rows of one id on
  one date: `latest-by` decides, else the last read.
- **Commits.** One commit per business date. A load never leaves a date half written.
- **Loads at once.** A load's base is the table as it found it when it started. A replace is optimistic on every store (Delta, Iceberg,
  files; local disk, S3, memory): when another writer changed the date (this load's slice of it) after that, the load commits nothing for
  the date, says `<domain>/<table>: <date> was replaced or changed by another load while this one ran ... run it again` and exits 1; the
  other load's rows stand. Appends of one date at once all land. The lock (`--no-lock` turns it off) only makes loads of one lake on one
  machine wait for each other.
- **The newest load.** Where a date holds several loads' copies of an id (appends), the reader keeps the copy of the **later commit**
  (the table version, the data sequence number, the files log's order), whatever the loaders' clocks said; the run's time orders only
  copies of one commit.
- **Output.** A local lake gets `_drishti-lake/` beside its tables: `runs/<run>.json` (the summary), `runs/<run>.manifest.json` (every file
  written, with its row counts and key bounds), `runs.log`, `last-run.json`, and the lock.

## Undo, delete, retention, check

Every row carries the `_load_id` of its load (`<run-id>.<n>`, listed in the summary's `loads`).

```text
LakeUndo      --lake ROOT --load-id ID [--table domain/table ...] [--dry-run] [--json]
LakeRetention --lake ROOT --format delta|iceberg|files --table domain/table --dates 2026-11-20[,2026-11-01..2026-11-10] [--dry-run] [--json]
LakeRetention --lake ROOT --format delta|iceberg|files --table domain/table [--ttl 8h] [--on FIELD] [--max-rows N] [--dry-run] [--json]
LakeCheck     check [--dir LAKEFILES]
```

All in `com.ash.lake.load`, run like `LakeLoad`. **Undo** removes exactly a load's rows, one commit per table and date; a load that replaced a date
is undone by restoring the date as it was (rows appended by later loads kept). **Retention** removes whole business dates (`--dates`) in one
commit, or the rows older than `--ttl` on a field (default the `arrived_at` column, else the business date) or beyond `--max-rows`. **Check**
reads the lake files and the tables on disk and reports findings.
The same commands are `python -m drishti_lake undo|retention|check|maintain|lakes` ([PYTHON.md](PYTHON.md)).

## Maintenance: `lake maintain`

One table's upkeep, the same options and the same order on every store (Delta, Iceberg, files; local disk, S3, memory), through the
official libraries (Delta Kernel, iceberg-core), each step an ordinary commit:

```text
LakeMaintain --lake ROOT --format delta|iceberg|files --table domain/table
    [--keep-days N [--as-of D] [--zone Z] [--max-drop-share F] [--force-drop]] [--floor-days D]
    [--ttl 8h [--on FIELD] [--max-rows N]] [--compact [--target-file-mb 128]] [--checkpoint] [--vacuum-hours H] [--dry-run] [--json]
```

| Step | Delta | Iceberg | Files |
|---|---|---|---|
| `--keep-days N` | the business dates older than the newest N on or before `--as-of` (the business today in `--zone`) removed in one commit; **refused, nothing removed, over `--max-drop-share` (0.5) of the table's rows** unless `--force-drop`; never a date within `--floor-days` of `--as-of` | same | same |
| `--ttl` / `--max-rows` | the row retention above | same | same |
| `--compact` | each date in two or more files under `--target-file-mb` rewritten (OPTIMIZE), one commit a date, every row kept | each date in more files than its rows need (or with delete files, or drifted from its id order) rewritten into files of the table's `file-rows`, every row kept; a table laid out by more than the id keeps that order | nothing to do: a date is one day file |
| `--checkpoint` | a checkpoint of the latest version | the manifests regrouped one per month | nothing to do: the day files are the current state |
| `--vacuum-hours H` | the data files no version of the last H hours references deleted (VACUUM) | the snapshots older than H expired, then orphan files | the log's content files of versions older than H deleted |

Vacuum keeps every version committed in the last H hours and the version current at the cut, so time travel reaches back H hours; an older
version is then refused by name. A file no commit names (a load still running, or one that died) goes only when it is older than 72 hours
as well. `--floor-days D` raises H to D days. A dry run changes nothing and says what would change. The printed line is the same on every
store (the table's dates and rows before and after, the steps run); `--json` gives each step's detail (dates compacted, files and bytes
vacuumed). The run fails (exit 1) for an absent table, a negative `--vacuum-hours`, or the drop guard; a Delta vacuum that cannot match
every file of the latest version to the table's folder deletes nothing. The parity kit's cases: `maintain/compact-checkpoint-vacuum`,
`maintain/keep-days-and-drop-guard`. `IcebergMaintenance` (an Iceberg lake's domains at once: relayout, `--columns`, undo) stays the Iceberg
store's own tool.

## The inbox: hand-over by `.ready` files

A host that wants files to arrive by dropping them in a folder runs the **data inbox** (`com.ash.lake.inbox`: `DataInbox`, `Cycle`; its
settings are `DataLoadSettings`, bound by the host). A cycle, on the owner node only: finds the ready, stable files (a file is ready when its
`<file>.ready` marker sits beside it, or `ready.all` is in the folder, and it is taken only once its size and time have not changed for `stableSeconds`; a file with no marker is never touched), claims the whole batch into `inprocess/<run>/`, skips
files whose SHA-256 was loaded before (the **ledger**), routes each file to a table (`Routes`, rules supplied by the host), loads the files of one
table in one loader run, records the outcome, moves the files with their reports to `outbox/` (or `failed/`), and finally signals the host. Every
step is written to the ledger before it is acted on, so a stopped run is finished by `Recovery`.

A `.ready` file is empty or a YAML (or JSON) mapping of these keys only: `business-date`, `table`, `pack`, `kind`, `connector`, `mode`, `new-table`, `key`,
`folders`, `sha256` (checked against the file), `rows` (checked), `hold`, `lake`. A host may add rules (`ReadyRule`) that decide whether a hand-over
may load now.

## Tutorial: load, look, undo, delete

Run these in an empty folder with JDK 21 and the class path of the loader in `$CP` (see "Run a load").

**1. Data.** `in/trades.2026-11-20.jsonl`:

```json
{"TRADE_ID":"T-1","DESK":"FX","BOOK":"B1","NOTIONAL":1000000.5,"LEGS":[{"LEG":1,"RATE":0.012},{"LEG":2,"RATE":0.0131}]}
{"TRADE_ID":"T-2","DESK":"RATES","BOOK":"B2","NOTIONAL":250000,"LEGS":[]}
{"TRADE_ID":"T-3","DESK":"FX","BOOK":"B1","NOTIONAL":75000}
```

**2. Configuration.** `load.yaml` (use absolute paths; one file, the format is the only difference between the three stores):

```yaml
source:
  files: /work/in/*.jsonl
  id: TRADE_ID
  promote: [DESK, BOOK, NOTIONAL]
target:
  format: delta                 # or iceberg, or files
  lake: /work/lake
  domain: risk
  kind: trade
```

**3. Load.**

```text
java -Xmx2g -cp "$CP" com.ash.lake.load.LakeLoad --config load.yaml
documents read: 3, rows written: 3, rejected: 0
  business date 2026-11-20 from the name of trades.2026-11-20.jsonl
  ...
... lake-build ok exit=0 run=<run id> domain=risk kind=trade engine=java-loader mode=append rows=3 rejected=0 dates=1 partitions=1 seconds=1.5
```

The table is `/work/lake/risk/trade` (Delta: `_delta_log/` and `business_date=2026-11-20/`); for `files` it is `/work/lake/risk/2026-11-20/trade.jsonl`. The
libraries print their own log lines to the console too. Read it back with the read API ([READ.md](READ.md)).

**4. Undo, as a dry run, then for real.** The load id is the key in the summary's `loads` (`/work/lake/_drishti-lake/runs/<run>.json`):

```text
java -cp "$CP" com.ash.lake.load.LakeUndo --lake /work/lake --load-id <run id>.1 --dry-run --json
[ { "table" : "risk/trade", "date" : "2026-11-20", "removed" : 3, "restored" : 0, "replaced" : false, "version" : -1 } ]
```

**5. Delete a whole date, on any store.**

```text
java -cp "$CP" com.ash.lake.load.LakeRetention --lake /work/lake --format delta --table risk/trade --dates 2026-11-20 --dry-run --json
{"table":"risk/trade","format":"delta","dryRun":true,"held":["2026-11-20"],"dropped":3,"dates":[{"date":"2026-11-20","rows":3,"dropped":3}]}
```

The same lines, with `--format iceberg` or `files` on a lake loaded with that `target.format`, give the same answer. The same load runs from Python with
`python -m drishti_lake load --config load.yaml` for Delta ([PYTHON.md](PYTHON.md)).
