#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The release script, skeleton (design section 6, step L1; completed at L14): it checks and prints the plan, and changes nothing.

    python3 tools/release/release.py --version 0.9.1

A release is tagged only with the owner's word (no tag before the G3 gate). The full script will: refuse a dirty tree or a branch other
than main; run tools/ci/check.py (the parity kit green, the guards green); set the version in every POM; tag v<version>; build from
the tag and install the jars to ~/.m2 (decision R-1); build the wheel (L10); write the checksums the host's pin records (LK-15)."""
import argparse, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def git(*a):
    return subprocess.run(["git", "-C", str(ROOT), *a], capture_output=True, text=True, check=True).stdout.strip()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--version", required=True)
    a = ap.parse_args(argv)
    if not re.fullmatch(r"\d+\.\d+\.\d+", a.version):
        print("the version is MAJOR.MINOR.PATCH")
        return 2
    problems = []
    if git("status", "--porcelain"):
        problems.append("the working tree is not clean")
    if git("rev-parse", "--abbrev-ref", "HEAD") != "main":
        problems.append("not on main")
    if git("tag", "--list", "v" + a.version):
        problems.append(f"v{a.version} exists already")
    pom = (ROOT / "java" / "pom.xml").read_text(encoding="utf-8")
    current = re.search(r"<artifactId>lake-parent</artifactId>\s*<version>([^<]+)</version>", pom).group(1)
    print(f"plan for v{a.version} (the POMs say {current}):")
    for s in ("tools/ci/check.py (build, guards, parity kit equal to the reference)", f"set {a.version} in every POM, commit",
              f"tag v{a.version} (only with the owner's word)", "build from the tag; install the jars to ~/.m2 (R-1)",
              "the wheel (L10)", "checksums for the host's pin (LK-15)"):
        print("  - " + s)
    for p in problems:
        print("refused: " + p)
    print("skeleton: nothing was changed")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
