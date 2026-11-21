#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The read API's performance proof (READ_API.md section 7): one scenario-P&L table (10,000 positions x 509 scenarios, about 4 files,
6 promoted columns, one date) loaded once by this build's loader into Delta and Iceberg, then read by tools/bench/ReadBench.java over
each side: `ref` (Drishti's jars at the freeze, through the same-package shims of tools/split/bench mapped to the host's packages),
`internal` (the same shims over this build's table classes) and `api` (the read API, tools/bench/ApiReads.java, once it exists).
One heavy job at a time; not part of check.py; scratch removed on exit unless --keep.

    python3 tools/bench/bench.py --ref-cp REFCP.txt [--sides ref,internal,api] [--stores delta,iceberg] [--keep DIR] [--out bench.jsonl]
"""
import argparse, json, os, re, shutil, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "tck"))
import tck  # noqa: E402

JAVA_HOME = tck.JAVA_HOME
DATE = "2026-10-27"
TO_HOST = [("package com.ash.lake.delta.table;", "package com.ash.drishti.plugin.delta;"),
           ("package com.ash.lake.iceberg.table;", "package com.ash.drishti.plugin.iceberg;"),
           ("com.ash.lake.rows.", "com.ash.drishti.api.")]
SIDES = {"delta": "com.ash.lake.delta.table.DeltaReads", "iceberg": "com.ash.lake.iceberg.table.IcebergReads"}
HOST_SIDES = {"delta": "com.ash.drishti.plugin.delta.DeltaReads", "iceberg": "com.ash.drishti.plugin.iceberg.IcebergReads"}
API = "bench.ApiReads"


def to_host(src):
    src = "\n".join(l for l in src.split("\n") if not l.rstrip().endswith("// lake-only"))
    for a, b in TO_HOST:
        src = src.replace(a, b)
    return src.replace("(RT, ", "(").replace("(RT)", "()")


def javac(cp, sources, out):
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run([f"{JAVA_HOME}/bin/javac", "-nowarn", "-proc:none", "-d", str(out), "-cp", cp] + [str(s) for s in sources], check=True)


def load(cp, work, store, n, scenarios):
    """The bench table, written once per store by this build's loader."""
    inp = work / "in"
    if not inp.exists():
        inp.mkdir(parents=True)
        (inp / f"scenario-pv.{DATE}.jsonl").write_text(
            "".join(l + "\n" for l in tck.pv_lines(n, scenarios, 1, 5, DATE)), encoding="utf-8")
    lake = work / f"lake-{store}"
    if lake.exists():
        return lake
    cfg = work / f"load-{store}.yaml"
    cfg.write_text(f"""source:
  files: '{inp}/*'
  id: POSITION_ID
  promote: [LEGAL_ENTITY, DESK, TT_BOOK, TRADE_ID, QUANTITY, BASE_PV, CCY]
  vectors: [SCENARIO_PV]
target:
  format: {store}
  lake: '{lake}'
  domain: risk
  kind: scenario-pv
  layout:
    file-size-mb: 10
""", encoding="utf-8")
    subprocess.run([f"{JAVA_HOME}/bin/java", "-Xmx4g", "-cp", cp, "com.ash.lake.load.LakeLoad", "--config", str(cfg)], check=True,
                   stdout=subprocess.DEVNULL)
    return lake


def root_of(lake, store):
    return str(lake / "delta") if store == "delta" and (lake / "delta").exists() else str(lake)


def run(cp, classes, side, root, warm, runs):
    cmd = [f"{JAVA_HOME}/bin/java", "-Xmx4g", "-Xms4g", "-XX:+UseG1GC", "-cp", f"{classes}{os.pathsep}{cp}", "bench.ReadBench", side, root,
           "risk", "scenario-pv", DATE, str(warm), str(runs)]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"{side} on {root} failed:\n" + "\n".join(p.stderr.splitlines()[-30:]))
    out = p.stdout
    return [json.loads(l) for l in out.splitlines() if l.startswith("{")]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--ref-cp", help="the reference class path file (Drishti's plugins at the freeze, built in a scratch folder)")
    ap.add_argument("--sides", default="ref,internal,api")
    ap.add_argument("--stores", default="delta,iceberg")
    ap.add_argument("--n", type=int, default=10_000)
    ap.add_argument("--scenarios", type=int, default=509)
    ap.add_argument("--warmups", type=int, default=3)
    ap.add_argument("--runs", type=int, default=7)
    ap.add_argument("--keep", help="a scratch folder kept between runs (the lakes are reused)")
    ap.add_argument("--out", help="append the results here (JSON lines)")
    a = ap.parse_args(argv)
    work = Path(a.keep) if a.keep else Path(tempfile.mkdtemp(prefix="lake-bench-", dir=os.environ.get("LAKE_TCK_TMP")))
    work.mkdir(parents=True, exist_ok=True)
    try:
        lake_cp = tck.product_classpath()
        here = Path(__file__).parent
        shims = sorted((ROOT / "tools" / "split" / "bench").glob("*.java"))
        sides = a.sides.split(",")
        compiled = {}
        if "ref" in sides:
            ref_cp = Path(a.ref_cp).read_text().strip()
            src = work / "ref-src"
            src.mkdir(exist_ok=True)
            mapped = []
            for s in shims:
                t = src / s.name
                t.write_text(to_host(s.read_text(encoding="utf-8")), encoding="utf-8")
                mapped.append(t)
            javac(ref_cp, [here / "ReadBench.java"] + mapped, work / "classes-ref")
            compiled["ref"] = (ref_cp, work / "classes-ref")
        if "internal" in sides or "api" in sides:
            extra = [here / "ApiReads.java"] if "api" in sides else []
            javac(lake_cp, [here / "ReadBench.java"] + shims + extra, work / "classes-lake")
            compiled["internal"] = compiled["api"] = (lake_cp, work / "classes-lake")
        results = []
        for store in a.stores.split(","):
            root = root_of(load(lake_cp, work, store, a.n, a.scenarios), store)
            for side in sides:
                if store not in SIDES and side != "api":
                    continue                                     # the files store has only the read API (published, RA-10)
                cp, classes = compiled[side]
                cls = API + ":" + store if side == "api" else (HOST_SIDES if side == "ref" else SIDES)[store]
                for r in run(cp, classes, cls, root, a.warmups, a.runs):
                    r.update(store=store, side=side)
                    results.append(r)
                    print(json.dumps(r), flush=True)
        if a.out:
            with open(a.out, "a", encoding="utf-8") as f:
                for r in results:
                    f.write(json.dumps(r) + "\n")
        return 0
    finally:
        if not a.keep:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
