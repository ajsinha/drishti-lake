#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The parity kit's two gates (design section 4), run by check.py after the kit:

* coverage: every loader setting (LoadConfig's keys), every loader command-line flag (LakeLoad), every .ready key (ReadyNote.KEYS) and
  every LK- error code (LakeError) is used or shown by some case, measured from the cases and one run's results. What no case covers is
  listed in tck/coverage-baseline.txt, which ONLY SHRINKS: a new uncovered item fails, an item now covered (or gone) must leave the list.
* additive: tck/cases.txt lists every case; cases are only ever added (a listed case that is gone fails; a new case must be listed).

    python3 tools/ci/coverage.py RESULTS.jsonl
"""
import json, re, sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CASES = ROOT / "tck" / "cases"
JAVA = ROOT / "java"
BASELINE = ROOT / "tck" / "coverage-baseline.txt"
LISTED = ROOT / "tck" / "cases.txt"


def src(rel):
    return (JAVA / rel).read_text(encoding="utf-8")


def items():
    """Every item the kit must cover, from the product's own sources."""
    out = set()
    cfg = src("lake-load/src/main/java/com/ash/lake/load/LoadConfig.java")
    block = cfg[cfg.index("Map<String, Set<String>> KEYS = Map.of("):]
    block = block[:block.index(");\n")]
    for section, keys in re.findall(r'"([a-z.\-]*)",\s*Set\.of\(([^)]*)\)', block):
        for k in re.findall(r'"([^"]+)"', keys):
            out.add("config:" + (section + "." if section else "") + k)
    for flag in re.findall(r'case "(--[a-z][a-z\-]*)"', src("lake-load/src/main/java/com/ash/lake/load/LakeLoad.java")):
        out.add("flag:" + flag)
    ready = src("lake-load/src/main/java/com/ash/lake/inbox/ReadyNote.java")
    keys = ready[ready.index("KEYS = List.of("):]
    out |= {"ready:" + k for k in re.findall(r'"([^"]+)"', keys[:keys.index(");")])}
    out |= {"code:" + c for c in re.findall(r'"(LK-\d+)"', src("lake-api/src/main/java/com/ash/lake/LakeError.java"))}
    return out


def walk(node, prefix, out):
    if isinstance(node, dict):
        for k, v in node.items():
            key = f"{prefix}.{k}" if prefix else str(k)
            out.add("config:" + key)
            if k not in ("storage-options", "properties", "iceberg", "datasets"):   # free-form maps: their keys are not settings
                walk(v, key, out)


def covered(results):
    """What the cases use (their configurations, arguments, .ready files) and what one run of them showed (the codes in its words)."""
    out = {"flag:--config", "flag:--summary", "config:report", "config:report.summary"}   # every load step: tck.py and the runner pass them
    for f in sorted(CASES.rglob("*.yaml")):
        c = yaml.safe_load(f.read_text(encoding="utf-8"))
        for y in (c.get("configs") or {"main": c.get("config", "")}).values():
            try:
                walk(yaml.safe_load(y.replace("${", "{")) or {}, "", out)
            except yaml.YAMLError:
                pass
        text = json.dumps(c.get("steps"))
        out |= {"flag:" + m for m in re.findall(r'"(--[a-z][a-z\-]*)"', text)}
        for m in re.findall(r'"--set",\s*"([a-z.\-]+)=', text):
            out.add("config:" + m)
        for name, spec in (c.get("inputs") or {}).items():
            if name.endswith(".ready") and isinstance(spec, str):
                out |= {"ready:" + k for k in (yaml.safe_load(spec) or {})}
    out |= {"code:" + c for c in re.findall(r"LK-\d+", Path(results).read_text(encoding="utf-8"))}
    return out


def main(argv):
    if len(argv) != 2:
        sys.exit(__doc__)
    bad = 0
    want = items()
    missing = want - covered(argv[1])
    baseline = {l.split()[0] for l in BASELINE.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")}
    for i in sorted(missing - baseline):
        print(f"NOT COVERED (no case uses it, and it is not in tck/coverage-baseline.txt): {i}")
        bad += 1
    for i in sorted(baseline - missing):
        print(f"covered or gone now (remove it from tck/coverage-baseline.txt): {i}")
        bad += 1
    cases = {str(f.relative_to(CASES).with_suffix("")) for f in CASES.rglob("*.yaml")}
    listed = {l.strip() for l in LISTED.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")}
    for c in sorted(listed - cases):
        print(f"CASE GONE (cases are only added): {c}")
        bad += 1
    for c in sorted(cases - listed):
        print(f"new case not listed in tck/cases.txt: {c}")
        bad += 1
    print(f"coverage: {len(want) - len(missing)} of {len(want)} items covered ({len(missing)} in the baseline, which only shrinks); "
          f"{len(cases)} cases, none removed")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
