#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The proof of each split step: the parity kit's results over this repository's code against the reference (the host's jars at the
freeze commit, make_reference.py).

    python3 tools/split/compare_results.py REFERENCE.jsonl LAKE.jsonl

Every case must give the same line, byte for byte, except those listed in known-differences.txt beside this script (each with the step
that made it and why). Exit 0 when that holds and every case here ran (status ok); 1 otherwise. A listed case that no longer differs is
reported, so the list stays true. A case of a store the host never had (BEYOND: its jars cannot run it, make_reference.py --stores) has no
reference line, nor does a case added here: each must run here (status ok); check.py holds them stable over two runs. A case of the memory row (`<case>@<store>~mem`, its
lake on mem://) must give the same line as the reference's local case, its name aside. A case of every other store (files, Iceberg, and their memory rows) must give the same
answer as its Delta case here (the stores are feature-identical), its name and the dump's date form aside, unless tck/capabilities.yaml
declares the gap and names the case."""
import gzip, json, sys
from pathlib import Path


def lines(p):
    """A results file, plain or gzipped (the stored reference is .jsonl.gz)."""
    raw = Path(p).read_bytes()
    text = (gzip.decompress(raw) if p.endswith(".gz") else raw).decode("utf-8")
    return {json.loads(l)["case"]: l for l in text.splitlines() if l.strip()}


BEYOND = {"files"}   # stores the host's jars at the freeze commit do not have
MEM = "~mem"         # the memory row: compared with the local case of the reference


def declared_differences():
    """The cases tck/capabilities.yaml names for a store's declared gap (<case>@<store>): those alone may differ from their Delta case."""
    import yaml
    caps = yaml.safe_load((Path(__file__).resolve().parents[2] / "tck" / "capabilities.yaml").read_text(encoding="utf-8")) or {}
    out = set()
    for store, gaps in caps.items():
        for gap in (gaps or {}).values():
            if isinstance(gap, dict) and gap.get("case"):
                out.add(f"{gap['case']}@{store}")
    return out


def same_rows(line, case):
    """A result line in the form every store shares: its case name taken out, and a business date the table dump gives as days since
    1970 (the Iceberg dump: a date column read as its number) written as the date, in the row keys and the business_date values."""
    import datetime as dt
    r = json.loads(line)
    r.pop("case", None)

    def day(v):
        return str(dt.date(1970, 1, 1) + dt.timedelta(days=int(v))) if isinstance(v, (int, float)) and not isinstance(v, bool) else v

    for t, rows in (r.get("tables") or {}).items():
        out = {}
        for k, row in rows.items():
            first, sep, rest = k.partition("|")
            try:
                first = day(float(first)) if sep and first.replace(".", "", 1).lstrip("-").isdigit() else first
            except ValueError:
                pass
            if isinstance(row, dict) and "business_date" in row:
                row = dict(row, business_date=day(row["business_date"]))
            out[first + sep + rest] = row
        r["tables"][t] = out
    return json.dumps(r, sort_keys=True, ensure_ascii=False)


def main(argv):
    if len(argv) != 3:
        sys.exit(__doc__)
    ref, new = lines(argv[1]), lines(argv[2])
    known = {}
    for l in (Path(__file__).parent / "known-differences.txt").read_text(encoding="utf-8").splitlines():
        if l.strip() and not l.startswith("#"):
            case, _, why = l.partition(" ")
            known[case] = why.strip()
    bad = 0
    mem = {c: c[:-len(MEM)] for c in new if c.endswith(MEM)}
    for c, local in mem.items():                                 # the memory row: the reference's local line under this name
        if local in ref:
            ref[c] = ref[local].replace(json.dumps(local)[1:-1], json.dumps(c)[1:-1], 1)
    cases_ref = {c.rpartition("@")[0] for c in ref}
    beyond = sorted(c for c in set(new) - set(ref)                 # a store, or a case, the host's kit never had
                    if c.rpartition("@")[2] in BEYOND or c.split(MEM)[0].rpartition("@")[0] not in cases_ref)
    only_here = sorted(set(new) - set(ref) - set(beyond))
    if set(ref) - set(new) or only_here:
        print(f"case sets differ: only in the reference {sorted(set(ref) - set(new))[:5]}, only here {only_here[:5]}")
        bad += 1
    same = differ = 0
    for c in sorted(set(ref) & set(new)):
        if ref[c] == new[c]:
            same += 1
            if c in known:
                print(f"listed but now identical (remove it from known-differences.txt): {c}")
                bad += 1
            continue
        differ += 1
        if c in known:
            print(f"known difference: {c}: {known[c]}")
        else:
            print(f"DIFFERS: {c}\n  reference: {ref[c][:400]}\n  lake:      {new[c][:400]}")
            bad += 1
    declared = declared_differences()
    for c in sorted(new):                                         # every store equals the Delta store, case by case (the memory row too)
        name, _, store = c.partition("@")
        base = store.split(MEM)[0]
        if base == "delta" or c in declared:
            continue
        d = f"{name}@delta" + (MEM if store.endswith(MEM) else "")
        if d in new and same_rows(new[d], d) != same_rows(new[c], c):
            print(f"DIFFERS from its Delta case (undeclared in tck/capabilities.yaml): {c}\n  delta: {new[d][:400]}\n  {base}: {new[c][:400]}")
            bad += 1
    failed = sorted(c for c in new if json.loads(new[c]).get("status") != "ok")
    if failed:
        print(f"not ok here: {failed}")
        bad += 1
    print(f"{same} identical ({len(mem)} of them the memory row), {differ} different, {len(known)} known differences listed; "
          f"{len(beyond)} beyond the reference (the {', '.join(sorted(BEYOND))} store, and cases added here)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
