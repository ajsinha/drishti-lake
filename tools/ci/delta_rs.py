#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""delta-rs (the ``deltalake`` package) on a table, for the Delta interop tests (com.ash.lake.tck.DeltaRs runs it under uv with the
pins of tools/ci/interop-pins.txt; never skipped):

    delta_rs.py read TABLE [COLUMNS]        # one JSON object on stdout: rows (DeltaTable and pyarrow.dataset), the add paths, missing files
    delta_rs.py write TABLE ROWS.json PART  # delta-rs writes ROWS.json ({columns: {name: type}, rows: [...]}) partitioned by PART (a,b)

Dates print as ISO text. Ends with os._exit: delta-rs's runtime can hold the interpreter open at exit."""
import datetime
import json
import os
import sys
import urllib.parse

import pyarrow as pa
import pyarrow.dataset as pads
from deltalake import DeltaTable, write_deltalake

TYPES = {"string": pa.string(), "date": pa.date32(), "double": pa.float64(), "long": pa.int64()}


def plain(v):
    return v.isoformat() if isinstance(v, (datetime.date, datetime.datetime)) else v


def read(table, columns=None):
    dt = DeltaTable(table)
    cols = columns.split(",") if columns else None
    rows = [{k: plain(v) for k, v in r.items()} for r in dt.to_pyarrow_table(columns=cols).to_pylist()]
    viaset = [{k: plain(v) for k, v in r.items()} for r in dt.to_pyarrow_dataset().to_table(columns=cols).to_pylist()]
    adds = pa.table(dt.get_add_actions(flatten=True)).column("path").to_pylist()
    missing = [u for u in dt.file_uris() if not os.path.isfile(urllib.parse.unquote(u[len("file://"):]) if u.startswith("file://") else u)]
    return {"rows": rows, "dataset": viaset, "adds": adds, "missing": missing}


def write(table, rows_file, part):
    spec = json.load(open(rows_file, encoding="utf-8"))
    cols = {}
    for name, t in spec["columns"].items():
        vals = [r.get(name) for r in spec["rows"]]
        if t == "date":
            vals = [None if v is None else datetime.date.fromisoformat(v) for v in vals]
        cols[name] = pa.array(vals, TYPES[t])
    write_deltalake(table, pa.table(cols), partition_by=part.split(",") if part else None, mode="append")
    return {"written": len(spec["rows"])}


def main(argv):
    out = read(*argv[1:]) if argv[0] == "read" else write(*argv[1:]) if argv[0] == "write" else None
    if out is None:
        print(__doc__, file=sys.stderr)
        os._exit(2)
    sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    main(sys.argv[1:])
