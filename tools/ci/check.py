#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The repository's one check (the CI entry point, run locally; there is no hosted CI): delta-rs at hand (uv with the pins of
tools/ci/interop-pins.txt), the build with every test and guard, the Delta interop tests ran with none skipped (both directions with
delta-rs), the Python path's tests, then the parity kit twice (with its Python row) (its results must be stable), its coverage and additive gates (tools/ci/coverage.py), and against the reference results of the freeze commit (the split's
proof).

    python3 tools/ci/check.py [--skip-build] [--reference tck/results/reference-9ce7c3170.jsonl.gz] [--scratch DIR]

Exit 0 when all of it holds. JDK 21 (LAKE_JAVA_HOME). Scratch files go under --scratch (default: java/target/ci) and are removed."""
import argparse, os, shutil, subprocess, sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA_HOME = os.environ.get("LAKE_JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
REFERENCE = ROOT / "tck" / "results" / "reference-9ce7c3170.jsonl.gz"
PINS = ROOT / "tools" / "ci" / "interop-pins.txt"
# the tests that run delta-rs (com.ash.lake.tck.DeltaRs): each must have run, with none skipped
INTEROP = {"lake-load": ["com.ash.lake.load.DeltaInteropTest"],
           "lake-memory": ["com.ash.lake.memory.DeleteDatesParityTest", "com.ash.lake.memory.RetentionParityTest"]}


def pins():
    return [l.strip() for l in PINS.read_text(encoding="utf-8").splitlines() if l.strip() and not l.strip().startswith("#")]


def delta_rs_at_hand():
    """uv runs the pinned deltalake and pyarrow (from its cache, else fetched)."""
    uv = shutil.which("uv") or str(Path.home() / ".local" / "bin" / "uv")
    cmd = [uv, "run", "--quiet", "--no-project"] + [x for p in pins() for x in ("--with", p)] + \
          ["python", "-I", "-c", "import deltalake, pyarrow; print('deltalake', deltalake.__version__, 'pyarrow', pyarrow.__version__)"]
    for extra in (["--offline"], []):
        try:
            if subprocess.run(cmd[:2] + extra + cmd[2:]).returncode == 0:
                return True
        except FileNotFoundError:
            print("== uv is not installed: the Delta interop tests need it (https://docs.astral.sh/uv/)")
            return False
    return False


def interop_ran():
    """The interop tests' surefire reports: each ran, none skipped, none failed."""
    ok = True
    for module, classes in INTEROP.items():
        for c in classes:
            f = ROOT / "java" / module / "target" / "surefire-reports" / f"TEST-{c}.xml"
            if not f.exists():
                print(f"== {c}: no report (it did not run)")
                ok = False
                continue
            t = ET.parse(f).getroot()
            n, skipped, bad = int(t.get("tests")), int(t.get("skipped")), int(t.get("failures")) + int(t.get("errors"))
            print(f"== {c}: {n} run, {skipped} skipped, {bad} failed")
            ok &= n > 0 and skipped == 0 and bad == 0
    return ok


def python_tests():
    """pytest over python/tests with the pins (delta-rs, pyarrow), Polars and PyYAML, through uv (offline first: the cache)."""
    uv = shutil.which("uv") or str(Path.home() / ".local" / "bin" / "uv")
    withs = [x for p in pins() + ["polars==2.1.0", "pyyaml", "pytest==8.4.2"] for x in ("--with", p)]
    return [uv, "run", "--offline", "--quiet", "--no-project"] + withs + ["python", "-I", "-m", "pytest", "-q", "-p", "no:cacheprovider", "tests"]


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
        print("== delta-rs at hand (" + ", ".join(pins()) + ")", flush=True)
        ok &= delta_rs_at_hand()
        if not a.skip_build:
            ok &= step("build, tests and guards", ["./mvnw", "-q", "-o", "clean", "verify"], cwd=ROOT / "java", env=env)
        print("== Delta interop with delta-rs, both directions", flush=True)
        ok &= interop_ran()
        ok &= step("the Python path's tests (python/tests: the shared name and date cases, Polars and the plain writer equal)",
                   python_tests(), cwd=ROOT / "python")
        scratch.mkdir(parents=True, exist_ok=True)               # after the build: its clean removes java/target
        one, two = scratch / "tck-1.jsonl", scratch / "tck-2.jsonl"
        tck = [sys.executable, str(ROOT / "tools" / "tck" / "tck.py")]
        ok &= step("parity kit, run 1", tck + ["--out", str(one)], env=env)
        ok &= step("parity kit, run 2", tck + ["--out", str(two)], env=env)
        if ok:
            stable = one.read_bytes() == two.read_bytes()
            print("== parity kit stable over two runs: " + ("yes" if stable else "NO"))
            ok &= stable
        if ok:
            ok &= step("coverage (only shrinks) and additive (cases only added) gates",
                       [sys.executable, str(ROOT / "tools" / "ci" / "coverage.py"), str(one)])
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
