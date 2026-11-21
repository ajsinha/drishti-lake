#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The proof of each split step: the parity kit's results over this repository's code against the reference (the host's jars at the
freeze commit, make_reference.py).

    python3 tools/split/compare_results.py REFERENCE.jsonl LAKE.jsonl

Every case must give the same line, byte for byte, except those listed in known-differences.txt beside this script (each with the step
that made it and why). Exit 0 when that holds and every case here ran (status ok); 1 otherwise. A listed case that no longer differs is
reported, so the list stays true."""
import gzip, json, sys
from pathlib import Path


def lines(p):
    """A results file, plain or gzipped (the stored reference is .jsonl.gz)."""
    raw = Path(p).read_bytes()
    text = (gzip.decompress(raw) if p.endswith(".gz") else raw).decode("utf-8")
    return {json.loads(l)["case"]: l for l in text.splitlines() if l.strip()}


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
    if set(ref) != set(new):
        print(f"case sets differ: only in the reference {sorted(set(ref) - set(new))[:5]}, only here {sorted(set(new) - set(ref))[:5]}")
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
    print(f"{same} identical, {differ} different, {len(known)} known differences listed")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
