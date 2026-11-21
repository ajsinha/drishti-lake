# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""Data contracts (design section 5): the shape of what may be loaded into a kind's table. The schema (``schema.json``), the reader
(``reader``: ``Contract``, ``Profile`` for a host's own keys), the checks (``checker``: against the schema, a record, the data) and the
pages (``pages``: a page per group and an index, a host's words through a ``PageProfile``). The contract files are the host's.

    python -m drishti_lake contracts check FILE...  [--profile PROFILE.json]       the files against the schema
    python -m drishti_lake contracts pages FILE... --out DIR [--check]              a page per folder of contracts and README.md

A profile file is ``{"properties": {...}, "required": [...]}`` (JSON schema of the host's keys). The loader checks every row against
``target.contract`` (a contract file) before the first write (the Java loader: ``com.ash.lake.contract``)."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .checker import drift, reason, record, schema_problems
from .pages import Entry, PageProfile, render, write
from .reader import CORE, Contract, Profile, core_schema, load

__all__ = ["CORE", "Contract", "Entry", "PageProfile", "Profile", "core_schema", "drift", "load", "reason", "record", "render",
           "schema_problems", "write", "main"]


def _profile(path) -> Profile:
    if not path:
        return CORE
    p = json.loads(Path(path).read_text(encoding="utf-8"))
    return Profile(properties=p.get("properties") or {}, required=tuple(p.get("required") or ()), title=p.get("title"))


def main(argv=None, out=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m drishti_lake contracts", description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check", help="every file against the contract schema (with --profile: the host's keys too)")
    c.add_argument("files", nargs="+", type=Path)
    c.add_argument("--profile", help="a JSON file: the host's keys (properties, required)")
    p = sub.add_parser("pages", help="a page per folder of contracts (its group: the folder above contracts/) and README.md")
    p.add_argument("files", nargs="+", type=Path)
    p.add_argument("--out", required=True, type=Path)
    p.add_argument("--check", action="store_true", help="write nothing: fail when a page is stale")
    a = ap.parse_args(argv)
    if a.cmd == "check":
        prof, bad = _profile(a.profile), 0
        for f in a.files:
            for problem in schema_problems(load(f), prof):
                print(f"{f}: {problem}", file=out)
                bad += 1
        print(f"{len(a.files)} contract(s), {bad} problem(s)", file=out)
        return 1 if bad else 0
    entries = []
    for f in sorted(a.files):
        doc = load(f)
        group = f.resolve().parent.parent.name if f.resolve().parent.name == "contracts" else f.resolve().parent.name
        entries.append(Entry(doc.get("kind") or f.name, doc.get("kind") or f.name, group, f, doc))
    stale, extra = write(render(PageProfile(), [e for e in entries if e.contract.get("status") != "planned"],
                                [e for e in entries if e.contract.get("status") == "planned"]), a.out, a.check)
    for n in stale + extra:
        print(("stale: " if a.check else "wrote or removed: ") + n, file=out)
    return 1 if a.check and (stale or extra) else 0
