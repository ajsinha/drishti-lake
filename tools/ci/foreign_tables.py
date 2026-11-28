#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""Foreign tables for the shaped-read case (docs/DESIGN.md, shaped reads): tables other engines write, in their own layout, by each format's own
writer (com.ash.lake.tck.DeltaRs.foreign runs it under uv with tools/ci/interop-pins.txt and foreign-pins.txt):

    foreign_tables.py FORMAT LAKE DOMAIN SPEC.json     # FORMAT delta (delta-rs), iceberg (pyiceberg), files (plain JSON Lines)

SPEC: {"tables": {name: {"columns": [[name, type], ...], "partition": [column], "appends": [[row, ...], ...],
"delete": {"column": c, "equals": v}}}}; a type is string, long, double, date, timestamptz, {"struct": [[name, type], ...]} or
{"list": type}. Rows are JSON objects (dates and instants as ISO text). Each append is one commit (delta-rs, pyiceberg) or, for files,
one file per date of the partition column (<table>.<date>.part-<n>.jsonl; an unpartitioned append: <table>.part-<n>.jsonl, no date in
the name). The delete removes the rows whose column equals the value (delta-rs and pyiceberg delete; files: the lines rewritten).
Prints one JSON object. Ends with os._exit: delta-rs's runtime can hold the interpreter open at exit."""
import datetime
import json
import os
import sys

import pyarrow as pa


def arrow_type(t):
    if isinstance(t, dict) and "struct" in t:
        return pa.struct([pa.field(n, arrow_type(x)) for n, x in t["struct"]])
    if isinstance(t, dict) and "list" in t:
        return pa.list_(arrow_type(t["list"]))
    return {"string": pa.string(), "long": pa.int64(), "double": pa.float64(), "date": pa.date32(),
            "timestamptz": pa.timestamp("us", tz="UTC")}[t]


def py_value(t, v):
    if v is None:
        return None
    if isinstance(t, dict) and "struct" in t:
        return {n: py_value(x, v.get(n)) for n, x in t["struct"]}
    if isinstance(t, dict) and "list" in t:
        return [py_value(t["list"], x) for x in v]
    if t == "date":
        return datetime.date.fromisoformat(v)
    if t == "timestamptz":
        return datetime.datetime.fromisoformat(v.replace("Z", "+00:00"))
    return v


def arrow_table(spec, rows):
    schema = pa.schema([pa.field(n, arrow_type(t)) for n, t in spec["columns"]])
    cols = {n: pa.array([py_value(t, r.get(n)) for r in rows], arrow_type(t)) for n, t in spec["columns"]}
    return pa.table(cols, schema=schema)


def delta(lake, domain, name, spec):
    from deltalake import DeltaTable, write_deltalake
    path = os.path.join(lake, domain, name)
    for rows in spec["appends"]:
        write_deltalake(path, arrow_table(spec, rows), partition_by=spec.get("partition") or None, mode="append")
    d = spec.get("delete")
    if d:
        v = d["equals"]
        DeltaTable(path).delete(f"{d['column']} = " + (f"'{v}'" if isinstance(v, str) else str(v)))
    return DeltaTable(path).version()


def iceberg(lake, domain, name, spec, catalogs):
    from pyiceberg.catalog.sql import SqlCatalog
    from pyiceberg.expressions import EqualTo
    from pyiceberg.transforms import IdentityTransform
    db = os.path.join(catalogs, "pyiceberg.db")
    cat = SqlCatalog("tck", uri=f"sqlite:///{db}", warehouse=f"file://{catalogs}")
    if (domain,) not in cat.list_namespaces():
        cat.create_namespace(domain)
    t = cat.create_table(f"{domain}.{name}", schema=arrow_table(spec, []).schema, location=f"file://{os.path.join(lake, domain, name)}")
    if spec.get("partition"):
        with t.update_spec() as u:
            for p in spec["partition"]:
                u.add_field(p, IdentityTransform(), p)
    for rows in spec["appends"]:
        t = cat.load_table(f"{domain}.{name}")
        t.append(arrow_table(spec, rows))
    d = spec.get("delete")
    if d:
        t = cat.load_table(f"{domain}.{name}")
        t.delete(EqualTo(d["column"], d["equals"]))
    return cat.load_table(f"{domain}.{name}").current_snapshot().snapshot_id


def files(lake, domain, name, spec):
    folder = os.path.join(lake, domain, name)
    os.makedirs(folder, exist_ok=True)
    part = (spec.get("partition") or [None])[0]
    names = [n for n, _ in spec["columns"]]
    written = []
    for i, rows in enumerate(spec["appends"], 1):
        groups = {}
        for r in rows:
            groups.setdefault(r.get(part) if part else None, []).append(r)
        for key, group in sorted(groups.items(), key=lambda kv: str(kv[0])):
            f = os.path.join(folder, f"{name}.{key}.part-{i:04d}.jsonl" if key else f"{name}.part-{i:04d}.jsonl")
            with open(f, "w", encoding="utf-8") as out:
                for r in group:
                    out.write(json.dumps({n: r.get(n) for n in names}, ensure_ascii=False) + "\n")
            written.append(f)
    d = spec.get("delete")
    if d:
        for f in written:
            with open(f, encoding="utf-8") as src:
                keep = [l for l in src if json.loads(l).get(d["column"]) != d["equals"]]
            with open(f, "w", encoding="utf-8") as out:
                out.writelines(keep)
    return len(written)


def main(argv):
    fmt, lake, domain, spec_file = argv
    spec = json.load(open(spec_file, encoding="utf-8"))
    catalogs = os.path.join(os.path.dirname(os.path.abspath(lake)), "foreign-catalog")
    os.makedirs(catalogs, exist_ok=True)
    out = {}
    for name, t in spec["tables"].items():
        out[name] = (delta(lake, domain, name, t) if fmt == "delta" else iceberg(lake, domain, name, t, catalogs) if fmt == "iceberg"
                     else files(lake, domain, name, t))
    sys.stdout.write(json.dumps({"written": sorted(out)}) + "\n")
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except Exception as e:  # noqa: BLE001 - the runner reports the words
        sys.stderr.write(f"{type(e).__name__}: {e}\n")
        sys.stderr.flush()
        os._exit(1)
