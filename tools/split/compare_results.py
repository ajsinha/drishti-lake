#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The proof of each split step: the parity kit's results over this repository's code against the reference (the host's jars at the
freeze commit, make_reference.py).

    python3 tools/split/compare_results.py REFERENCE.jsonl LAKE.jsonl

Every case must give the same line, byte for byte, except those listed in known-differences.txt beside this script (each with the step
that made it and why). Exit 0 when that holds and every case here ran (status ok); 1 otherwise. A listed case that no longer differs is
reported, so the list stays true. A case of a store the host never had (BEYOND: its jars cannot run it, make_reference.py --stores) has no
reference line: it must run here (status ok); check.py holds it stable over two runs. A case of the memory row (`<case>@<store>~mem`, its
lake on mem://) must give the same line as the reference's local case, its name aside."""
import gzip, json, sys
from pathlib import Path


def lines(p):
    """A results file, plain or gzipped (the stored reference is .jsonl.gz)."""
    raw = Path(p).read_bytes()
    text = (gzip.decompress(raw) if p.endswith(".gz") else raw).decode("utf-8")
    return {json.loads(l)["case"]: l for l in text.splitlines() if l.strip()}


BEYOND = {"files"}   # stores the host's jars at the freeze commit do not have
MEM = "~mem"         # the memory row: compared with the local case of the reference


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
    beyond = sorted(c for c in set(new) - set(ref) if c.rpartition("@")[2] in BEYOND)
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
    failed = sorted(c for c in new if json.loads(new[c]).get("status") != "ok")
    if failed:
        print(f"not ok here: {failed}")
        bad += 1
    print(f"{same} identical ({len(mem)} of them the memory row), {differ} different, {len(known)} known differences listed; "
          f"{len(beyond)} beyond the reference ({', '.join(sorted(BEYOND))})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
