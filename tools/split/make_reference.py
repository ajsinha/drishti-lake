#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The split's reference (design section 4, step L2): the parity kit run over the host's own jars at the freeze commit, so that
compare_results.py can prove each step. The host is read, never changed: a scratch clone (git clone --no-local) at the commit, built
there with ./mvnw clean package -DskipTests (never install), and the kit's runner with its imports mapped back to the host's packages.

    python3 tools/split/make_reference.py --drishti /path/to/drishti --commit 9ce7c3170 --out ref.jsonl [--keep SCRATCH]
    python3 tools/tck/tck.py --out lake.jsonl && python3 tools/split/compare_results.py ref.jsonl lake.jsonl
"""
import argparse, os, re, shutil, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
from l3_rename_packages import PACKAGES  # noqa: E402  (host package -> this repository's)

JAVA_HOME = os.environ.get("LAKE_JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
MODULE = "lake/drishti-lakeload"


def to_host(src):
    """The runner's imports mapped back to the host's packages (the reverse of the L3 rename; longest first)."""
    for old, new in sorted(PACKAGES, key=lambda p: -len(p[1])):
        if new == "com.ash.lake.tck":
            continue
        src = re.sub(r"(import\s+)" + re.escape(new) + r"\b", lambda m: m.group(1) + old, src)
    # the host at the freeze has no LakeRuntime (step L6): its readers take no runtime argument
    src = "\n".join(l for l in src.split("\n") if not l.rstrip().endswith("// lake-only"))
    return src.replace("(RT, ", "(").replace("(RT)", "()")


def sh(cmd, **kw):
    print("+", " ".join(map(str, cmd)), flush=True)
    return subprocess.run(cmd, check=True, **kw)


def build(drishti, commit, scratch):
    """The host at the commit, built in a scratch clone; returns the run-time class path of its loader module."""
    wt = scratch / "drishti"
    if not (wt / ".git").exists():
        sh(["git", "clone", "-q", "--no-local", "--no-checkout", drishti, str(wt)])
    sh(["git", "-C", str(wt), "checkout", "-q", "--detach", commit])
    cpf = scratch / "cp.txt"
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    sh(["./mvnw", "-q", "clean", "package", "dependency:build-classpath", "-DskipTests", "-pl", MODULE, "-am",
        f"-Dmdep.outputFile={cpf}", "-Dmdep.includeScope=runtime"], cwd=wt, env=env)
    own = next(j for j in sorted((wt / MODULE / "target").glob("*.jar")) if not j.name.endswith(("-sources.jar", "-tests.jar")))
    cp = [str(own)] + [p for p in cpf.read_text().strip().split(os.pathsep) if not p.endswith("-tests.jar")]
    return os.pathsep.join(cp)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--drishti", required=True, help="the host's repository (read only: it is cloned, never changed)")
    ap.add_argument("--commit", default="9ce7c3170", help="the freeze commit")
    ap.add_argument("--out", required=True)
    ap.add_argument("--keep", help="a scratch folder kept between runs (the clone and its build are reused)")
    ap.add_argument("--no-build", action="store_true", help="with --keep: reuse the class path built before")
    a = ap.parse_args(argv)
    scratch = Path(a.keep) if a.keep else Path(tempfile.mkdtemp(prefix="lake-ref-"))
    scratch.mkdir(parents=True, exist_ok=True)
    try:
        cpf = scratch / "ref-classpath.txt"
        if not (a.no_build and cpf.exists()):
            cpf.write_text(build(a.drishti, a.commit, scratch))
        runner = scratch / "TckRunner.java"
        runner.write_text(to_host((ROOT / "tools" / "tck" / "TckRunner.java").read_text(encoding="utf-8")), encoding="utf-8")
        return subprocess.run([sys.executable, str(ROOT / "tools" / "tck" / "tck.py"), "--classpath-file", str(cpf),
                               "--runner", str(runner), "--out", a.out,
                               "--stores", "delta,iceberg", "--storages", "local", "--loaders", "java"]).returncode   # the host's stores, local, its Java loader
    finally:
        if not a.keep:
            shutil.rmtree(scratch, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
