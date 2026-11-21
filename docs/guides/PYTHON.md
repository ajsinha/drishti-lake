<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# The Python package: `drishti_lake`

The Python path of the lake: the Java loader's configuration, rules and summary, writing **Delta** tables through delta-rs (Polars is used when
it is installed, the same table either way), plus the data-contract tools and a front for the Java upkeep tools. Distribution `drishti-lake`,
import `drishti_lake`, Python 3.11 or later, built from `python/` (`pyproject.toml`: `deltalake==1.6.6`, `pyarrow==26.0.1`, `pyyaml`,
`jsonschema==4.26.0`; optional `polars==2.1.0`, `zstandard`). The package never imports a host.

## Install

```text
cd python && pip install -e .                 # or: uv build --wheel python   then   pip install dist/drishti_lake-*.whl
python -m drishti_lake --help
```

## Commands

```text
python -m drishti_lake load --config FILE [--set key.path=value ...] [--as-of YYYY-MM-DD] [--table T] [--kind K] [--mode append|replace]
        [--key FIELD] [--date-from auto|name] [--plan] [--summary FILE] [--progress FILE] [--no-lock] [--no-run-log] [--dry-run] [--run-id ID] [--json] [-q]
python -m drishti_lake contracts check|pages ...            (CONTRACTS.md)
python -m drishti_lake undo|retention|relocate|check|maintain|lakes ...    the Java tools, below
```

`load` takes **the same configuration file and flags as the Java loader** ([LOADER.md](LOADER.md)), applies the same rules in the same order (the
configuration checked; each file's business date decided; every line through the row rule; the rows' dates and the reject share checked; one
id's copies on a date resolved; one commit per business date), prints the same summary keys and exits with the same codes (0 loaded, 2 loaded
with rejects, 1 failed, 3 locked). It writes Delta on the local disk (`target.path` or a lake folder); what it does not do is refused **before a row
is read**, naming the Java loader: Iceberg and files, `s3://` and `mem://` tables, `datasets:`, `source.pack`, a lake named by a lake file.

The upkeep commands run the Java tools of the product (`LakeUndo`, `LakeRetention`, `LakeRelocate`, `LakeCheck`, `IcebergMaintenance`, `LakesCli`) so there is
one implementation of each job. Point them at the product's class path with `LAKE_CLASSPATH` (the `lake-load` module's run-time class path, or one
runnable jar); the Java binary is `LAKE_JAVA`, else `$JAVA_HOME/bin/java`, else `java` (JDK 21 or later). Without them the command says what to set.

## Modules

| Module | Job |
|---|---|
| `filedate` | table and business date from a file name or a tar's folders; the business today (`LAKE_BUSINESS_ZONE`, default `America/New_York`); no date after today |
| `rowrule` | one line to one row: the checks in their fixed order, the id, constants, flattened fields, vectors, the document text (the same bytes the Java loader writes), partition and promoted values |
| `inputs`, `archives` | `source.files` resolved to files and lines: plain, gzip, zstd, zip, tar, with the archive guards and caps (local files only) |
| `config` | the loader's configuration file, read and checked as the Java loader does |
| `delta` | the writer: one commit per business date; the table has `id`, `doc`, `business_date`, the partition and promoted columns, vectors, `_load_id`, `_source_file` |
| `load` | the load itself |
| `contracts` | schema, reader, record and data checks, pages |
| `java` | the front for the Java tools |

## Tutorial: load Delta from Python and read it back

```text
cd /work                                   # in/trades.2026-11-20.jsonl as in LOADER.md
cat > load.yaml <<'YAML'
source: {files: /work/in/*.jsonl, id: TRADE_ID, promote: [DESK, BOOK, NOTIONAL]}
target: {format: delta, path: /work/lake/risk/trade, domain: risk, kind: trade}
YAML
python -m drishti_lake load --config load.yaml
loader: delta table /work/lake/risk/trade
  2026-11-20  3 rows
documents read: 3, rows written: 3, rejected: 0
... lake-build ok exit=0 run=<run id> domain=risk kind=trade engine=python mode=append rows=3 rejected=0 dates=1

python -m drishti_lake load --config load.yaml --mode replace -q          # the date is replaced, not doubled
python -c "from deltalake import DeltaTable; t=DeltaTable('/work/lake/risk/trade'); print(t.version(), t.to_pyarrow_table().schema.names)"
1 ['id', 'doc', 'business_date', 'DESK', 'BOOK', 'NOTIONAL', '_load_id', '_source_file']
```

The table is a standard Delta table: delta-rs, Spark and the Java read API ([READ.md](READ.md)) all read it. The tests (`python -m pytest` in
`python/`) and the parity kit's Python row hold this path equal to the Java loader ([TCK.md](TCK.md)).
