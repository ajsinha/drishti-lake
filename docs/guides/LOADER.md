<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# The loader, the inbox and the upkeep tools

JSON Lines in, tables out. **One** Java loader (concurrent: the default) and **one** Python path ([PYTHON.md](PYTHON.md)) take the same
configuration file, apply the same rules and write the same summary; the parity kit holds them equal. Rows arrive as JSON Lines, or as a
JSON array (below), plain or `gz` / `zst`, or inside a `zip` / `tar` archive; nothing else is a row format.

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
| `--future-days N`, `--zone Z`, `--max-drop-share F`, `--force-drop` | the guards: `guard.future-days`, `guard.zone`, `guard.max-drop-share`, `guard.force-drop` (below) |
| `--dry-run`, `--plan` | read and check everything, write nothing |
| `--summary FILE`, `--progress FILE`, `--no-lock`, `--no-run-log`, `--run-id ID`, `--json`, `-q` | reporting and locking |

**Exit codes:** 0 loaded; 2 loaded with rejects; 1 failed (no date that was not committed changed); 3 another run holds the table's lock.

## The configuration

A mapping of `source`, `target`, `parallel`, `memory`, `rejects`, `report`, `guard`, `lock`, `datasets`. An unknown key is refused. A value may be
`${NAME}` (an environment variable); keys and secrets are never taken from the file (S3 credentials come from the AWS default chain).

| Section | Keys |
|---|---|
| `source` | `files` (a file, folder or glob `*` `?` `**`; a list), `recursive`, `storage-options`, `format` (documents, or `loader-rows`: `{domain, kind, id, date, doc, columns}` a line, a row as a lake keeps it; a promoted value the document lacks is the `columns` value, and a vector the document holds in its stored form `{"$vector": "<column>"}` takes its numbers from the `columns` list, a null there no vector, a missing or other value rejected), `id` (the id field), `date`, `as-of`, `date-from`, `promote` (columns beside the document), `vectors`, `flatten`, `partition-by`, `latest-by`, `pack`, `connector`, `dataset`, `archive` |
| `source.archive` | `max-mb`, `max-ratio`, `max-members` (zip-bomb caps), `validate` (`rows`, the default, or `structure`) |
| `target` | `format` (`delta`, `iceberg`, `files`), `lake` (a lake file's name), `path` (the table's folder), `domain`, `kind`, `table`, `storage-options`, `mode`, `default-mode`, `batch-id`, `properties`, `iceberg`, `layout`, `contract` |
| `target.layout` | `sort-by`, `file-size-mb`, `row-group-mb`, `compression`, `vector-compression`, `byte-stream-split`, `shard-aligned`, `vector-type` |
| `parallel` | `readers`, `parsers`, `writers`, `queue-mb`, `split-mb` |
| `memory` | `budget-mb` (default half the heap, shared by the reader queue, the parsers' sort buffers and the writers), `sort-buffer-mb`, `spill-dir`, `check-spill-space` |
| `rejects` | `file`, `max-pct` (the share of rejected rows a load may have and still commit), `keep` |
| `report` | `summary`, `manifest`, `progress`, `run-log`, `dry-run` |
| `guard` | `future-days` (0), `zone`, `max-drop-share` (off unless set), `force-drop` (false): the accuracy guards below |

A table's place has two forms: `target.lake` names a lake file ([STORAGE.md](STORAGE.md)) and the table is that file's entry for
`target.kind` (location, format, partitioning, promoted columns, vectors, id, date, latest-by, sort, layout, load mode; a load key that says
otherwise is refused with both values named; a kind the lake file lacks is refused), or `target.path` is the table's own folder
(`<root>/<domain>/<table>`, its layout given in the load). A folder given as `target.lake` is refused, naming both forms (kit case
`load/lake-folder-refused`); `datasets:` loads into a lake file's tables.

## Rules that apply to every load

- **Business date of a file**, in order: the date given (`--as-of`, a `.ready` file's `business-date`), the date in the file name, a tar's
  folder, the business today. A file name is `<table>.<date>.<format>[.<compression>]` (`trades.2026-11-20.jsonl.gz`); anything else is refused
  with the reason, never guessed. **No date after today** (the business zone's, `lake.business.zone`) is ever loaded.
- **Guards** (`guard:`, the same on every store and on the Python path). The **date guard**: a business date after today plus
  `guard.future-days` (0) is refused, whatever gave it (the name, a tar's folder, `--as-of`, a `.ready` file, the rows), before anything is
  written; `guard.zone` names the zone whose today it is (default `lake.business.zone`). The **drop guard**: with `guard.max-drop-share F`,
  a replace that would leave a date (this load's slice of a shared table) with more than F fewer rows than the table holds for it now is
  refused before anything is written, every such date named (`<date> holds N rows, the load brings M (P% fewer)`), a dry run too;
  `guard.force-drop` (or `--force-drop`) lets it through. An append, and a date the table does not hold yet, drop nothing. Off unless a
  share is set: set it for a feed whose dates must not shrink by accident (0.5: half a date). Retention's own guard is
  `lake maintain --keep-days` (below). Kit cases: `load/date-guard-future-days-and-zone`, `load/drop-guard-replace`.
- **JSON arrays.** A `.json` file or archive member (plain, `.gz`, `.zst`) whose text starts with `[` (after a byte-order mark and white
  space) is a JSON array whose elements are the rows: read as a stream, each element taken as the line a JSON Lines file would hold, so
  the same rows load alike either way (an element that is not an object, or lacks its id, is rejected as such a line is, numbered by
  element). A `.json` file that does not start with `[` is JSON Lines. An array that breaks off, or text after its closing `]`, fails the
  load naming the file and the element. Kit case `inputs/json-array`.
- **Archives.** A `zip` is one table and one business date, its JSON Lines files loose, dated by the archive's name. A tar
  (`.tar`, `.tar.gz`, `.tgz`) is `<table>/<YYYY-MM-DD>/<file>.jsonl[.gz|.zst]`: every member dated by its folder, each table loaded, and any
  other layout refuses the whole archive listing every problem. Absolute or `..` names, links, devices, encrypted members and nested archives
  are refused; the caps are checked from the listing before a byte is decompressed.
- **Modes.** `replace` (also `replace-date`, `overwrite-dates`): each business date of the input replaces that date's rows in one commit;
  `overwrite-partitions`: of each business date only the partitions the input brings (its `partition-by` values after the date, an empty
  value as null) are replaced, in one commit, and the date's other partitions stay (a feed re-sending one legal entity's day, systems that
  each write their own partition of a shared table); without `partition-by` it replaces the date; with the drop guard (`guard.max-drop-share`,
  which counts whole dates) it is refused, so give one of the two; `append`: rows are added, and with `target.batch-id` each batch is
  committed once per date (a retry is skipped). The same on Delta, Iceberg, files and the Python path (kit case
  `load/overwrite-partitions`). Where the mode came from (given, the lake file, the inbox default, the rules' default) is in the summary's
  `modeFrom`, the mode in `mode`.
- **Table properties.** `target.properties` are set when the load creates the table (an existing table keeps its own). Two mean the same
  on every store and are written as the format's own property:

  | Product name | Delta | Iceberg | files |
  |---|---|---|---|
  | `lake.append-only: true` | `delta.appendOnly` | `lake.append-only` (Iceberg has none) | `lake.append-only` |
  | `lake.retention-hours: H` | `delta.deletedFileRetentionDuration` and `delta.logRetentionDuration` (`interval N days`, or hours) | `history.expire.max-snapshot-age-ms` | `lake.retention-hours` |

  Any format's native name of the two is taken too (Delta's on an Iceberg table, say) and written as the target's; two names of one
  property with different values, a value that is not one, and a key with the prefix of tables written before Drishti Lake are refused.
  Every other key is the format's own and is written as given (the files store keeps them in `_log/<table>/properties.json`). An
  **append-only** table takes appends only: a replace (`overwrite-dates`, `overwrite-partitions`), a delete of dates, row retention or
  keep-days that would remove rows, and an undo that would remove rows are refused with `<domain>/<table> is append-only (lake.append-only):
  ... is refused; the table takes appends only`; compaction, relayout, checkpoints and vacuum change no row and run (on Delta their removes
  carry `dataChange: false`, as OPTIMIZE's). The **retention** is a floor for `lake maintain --vacuum-hours` (below): time travel always
  reaches back that far. Kit case `properties/retention-and-append-only`; `TableStore.properties` and `TableProperties.effective` read
  them back.
- **Rows.** One line, one row: JSON, an object, the id, the date, the vectors, then `target.contract` ([CONTRACTS.md](CONTRACTS.md)); a line
  with two faults is rejected for the first. Rejects are counted by reason; over `rejects.max-pct` nothing commits. Several rows of one id on
  one date: `latest-by` decides, else the last read.
- **Commits.** One commit per business date. A load never leaves a date half written. The summary's `commit.dates.<date>` gives
  `version`, one meaning on every store: a number that grows with every commit of the table (Delta's table version, the Iceberg
  snapshot's sequence number, the files store's log number), so a later commit always has a larger version; and `nativeVersion`, the
  store's own id of that commit, the one a read at a version takes (`TableStore.versions`, `AsOf`): Delta's and the files store's version
  itself, Iceberg's snapshot id. The manifest's dates carry both.
- **Loads at once.** A load's base is the table as it found it when it started. A replace is optimistic on every store (Delta, Iceberg,
  files; local disk, S3, memory): when another writer changed the date (this load's slice of it) after that, the load commits nothing for
  the date, says `<domain>/<table>: <date> was replaced or changed by another load while this one ran ... run it again` and exits 1; the
  other load's rows stand. Appends of one date at once all land. The lock (`--no-lock` turns it off) only makes loads of one lake on one
  machine wait for each other.
- **The newest load.** Where a date holds several loads' copies of an id (appends), the reader keeps the copy of the **later commit**
  (the table version, the data sequence number, the files log's order), whatever the loaders' clocks said: a run id (`--run-id`, or the
  loader's start time) is a name, never an order, and no value in the data decides between two commits. Every rewrite of a date keeps
  that answer: compaction, an undo and row retention write the rows readers see into files of their own and every older copy (by id,
  in the readers' order) into files marked as **files of older copies** (on Delta the add action's tag `lake.copies: older`, on Iceberg
  `-older-` in the file's name), which readers put before the commit's other files. Kit cases `maintain/compact-commit-order` (loads
  committed in the order opposite to their run ids, then compaction and undo) and `retention/ttl-keeps-commit-order`.
- **New columns.** A promoted or vector column first sent by a later load of an existing table is **added** to the table, nullable, on
  every store (Delta: one metadata commit, as merge-schema writes it; Iceberg: a schema update; files: the table's column list; the Python
  path: delta-rs's add-columns); no file is rewritten, so the rows written before read it as **null** (never filled from their documents,
  by the load or by maintenance). A Delta table under column mapping or in-commit timestamps (written by another engine) is not widened
  by the loader: such a load is **refused**, naming the column, nothing written. A column is never accepted and dropped. Kit case
  `load/new-promoted-column`.
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
    [--ttl 8h [--on FIELD] [--max-rows N]] [--relayout [--file-rows N] [--dates D,D|D..D] [--force]] [--compact [--target-file-mb 128]] [--checkpoint] [--vacuum-hours H] [--dry-run] [--json]
```

| Step | Delta | Iceberg | Files |
|---|---|---|---|
| `--keep-days N` | the business dates before the day N business days (Monday to Friday) before `--as-of` (the business today in `--zone`) removed in one commit: `--as-of` and the N business days before it stay, counted on the calendar, never from the newest date the table holds (Monday with 2 keeps from the Thursday before); **refused, nothing removed, over `--max-drop-share` (0.5) of the table's rows** unless `--force-drop`; never a date within `--floor-days` of `--as-of` | same | same |
| `--relayout` | a table laid out by id: each date whose files drifted from it (two files sharing an id range, a file over the table's `lake.layout.file-rows`, 250,000, more files than its rows need) rewritten as readers see it (each id's newest copy), sorted by id into files of `file-rows` (`--file-rows N`: N instead, a host's own layout; `--dates` only those dates; `--force` even a date in the layout: another codec, smaller row groups), each file its own id range, one commit a date (Delta Kernel); a table laid out by more than the id (`lake.layout.sort-by`) is left in its order | the same, by iceberg-core | a day file that holds an id twice rewritten with the last copy of each, sorted by id |
| `--ttl` / `--max-rows` | the row retention above | same | same |
| `--compact` | each date in two or more files under `--target-file-mb` rewritten (OPTIMIZE), one commit a date, every row kept: the rows readers see in the table's order (by id, or its `sort-by` then the id), every older copy an append left in one more file of older copies (by id, in the readers' order: the commit, never the run id), so the newest copy is still the one read and an undo still finds the older; a date already in that form is left | each date in more files than its rows need (or with delete files, or drifted from its id order) rewritten into files of the table's `file-rows`, every row kept, the older copies as on Delta; a table laid out by more than the id keeps that order | nothing to do: a date is one day file |
| `--checkpoint` | a checkpoint of the latest version | the manifests regrouped one per month | nothing to do: the day files are the current state |
| `--vacuum-hours H` | the data files no version of the last H hours references deleted (VACUUM) | the snapshots older than H expired, then orphan files | the log's content files of versions older than H deleted |

Vacuum keeps every version committed in the last H hours and the version current at the cut, so time travel reaches back H hours; an older
version is then refused by name. A file no commit names (a load still running, or one that died) goes only when it is older than 72 hours
as well. `--floor-days D` raises H to D days, and the table's own retention (`lake.retention-hours` or its native name, above) raises it
to that (the line says `vacuum Nh (the table's lake.retention-hours)`). A dry run changes nothing and says what would change. The printed line is the same on every
store (the table's dates and rows before and after, the steps run); `--json` gives each step's detail (dates compacted, files and bytes
vacuumed). The run fails (exit 1) for an absent table, a negative `--vacuum-hours`, or the drop guard; a Delta vacuum that cannot match
every file of the latest version to the table's folder deletes nothing. The parity kit's cases: `maintain/compact-checkpoint-vacuum`,
`maintain/keep-days-and-drop-guard`, `maintain/sorted-layout`. `IcebergMaintenance` (an Iceberg lake's domains at once: relayout, `--columns`, undo) stays the Iceberg
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
  path: /work/lake/risk/trade   # the table's folder (or lake: <name>, a lake file's entry)
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
