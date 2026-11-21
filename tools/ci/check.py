#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The repository's one check (the CI entry point, run locally; there is no hosted CI): the build with every test and guard, then the
parity kit twice (its results must be stable) and against the reference results of the freeze commit (the split's proof).

    python3 tools/ci/check.py [--skip-build] [--reference tck/results/reference-9ce7c3170.jsonl.gz] [--scratch DIR]

Exit 0 when all of it holds. JDK 21 (LAKE_JAVA_HOME). Scratch files go under --scratch (default: java/target/ci) and are removed."""
import argparse, os, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA_HOME = os.environ.get("LAKE_JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
REFERENCE = ROOT / "tck" / "results" / "reference-9ce7c3170.jsonl.gz"


def step(name, cmd, **kw):
    print(f"== {name}", flush=True)
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        print(f"== {name}: FAILED ({r.returncode})", flush=True)
    return r.returncode == 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--skip-build", action="store_true", help="the modules are built already (./mvnw clean verify ran)")
    ap.add_argument("--reference", default=str(REFERENCE))
    ap.add_argument("--scratch", default=str(ROOT / "java" / "target" / "ci"))
    a = ap.parse_args(argv)
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    scratch = Path(a.scratch)
    env["LAKE_TCK_TMP"] = str(scratch)
    ok = True
    try:
        if not a.skip_build:
            ok &= step("build, tests and guards", ["./mvnw", "-q", "-o", "clean", "verify"], cwd=ROOT / "java", env=env)
        scratch.mkdir(parents=True, exist_ok=True)               # after the build: its clean removes java/target
        one, two = scratch / "tck-1.jsonl", scratch / "tck-2.jsonl"
        tck = [sys.executable, str(ROOT / "tools" / "tck" / "tck.py")]
        ok &= step("parity kit, run 1", tck + ["--out", str(one)], env=env)
        ok &= step("parity kit, run 2", tck + ["--out", str(two)], env=env)
        if ok:
            stable = one.read_bytes() == two.read_bytes()
            print("== parity kit stable over two runs: " + ("yes" if stable else "NO"))
            ok &= stable
        if ok and Path(a.reference).exists():
            ok &= step("equal to the reference (" + Path(a.reference).name + ")",
                       [sys.executable, str(ROOT / "tools" / "split" / "compare_results.py"), a.reference, str(one)])
        elif not Path(a.reference).exists():
            print(f"== no reference at {a.reference}: tools/split/make_reference.py writes it")
            ok = False
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    print("== " + ("all green" if ok else "NOT green"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
