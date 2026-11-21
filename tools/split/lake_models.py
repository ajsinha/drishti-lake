#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The lake-file model check of step L5: the host's lake folders (read only) read by this code and by the host's jars at the freeze
commit (the reference class path make_reference.py --keep built) must give the same model, lake by lake.

    python3 tools/split/lake_models.py --ref-classpath SCRATCH/ref-classpath.txt DIR [DIR ...]

Exit 0 when equal. JDK 21 (LAKE_JAVA_HOME)."""
import argparse, os, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
from make_reference import to_host  # noqa: E402
sys.path.insert(0, str(ROOT / "tools" / "tck"))
from tck import product_classpath  # noqa: E402

JAVA = Path(os.environ.get("LAKE_JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin" / "java"


def RENAMED(line):
    """The settings the product renamed (step L4), as a refusal of the host's names them."""
    return line.replace("drishti.settings.file", "lake.settings.file")


def dump(cp, source, dirs, home, host):
    # the lake files' placeholders name the host's data folder (configuration text, the host's to set): both processes are given it
    props = [f"-Ddrishti.home={home}", f"-Ddrishti.data.dir={home}/data"] if host else [f"-Dlake.home={home}", f"-Dlake.data.dir={home}/data",
                                                                                     f"-Ddrishti.data.dir={home}/data"]
    r = subprocess.run([str(JAVA), *props, "-cp", cp, str(source), *dirs], capture_output=True, text=True, cwd=home)
    if r.returncode != 0:
        sys.exit(r.stdout + r.stderr)
    return r.stdout.splitlines()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--ref-classpath", required=True)
    ap.add_argument("dirs", nargs="+")
    a = ap.parse_args(argv)
    with tempfile.TemporaryDirectory(prefix="lake-models-", dir=os.environ.get("LAKE_TCK_TMP")) as t:
        tool = ROOT / "tools" / "split" / "LakeModelDump.java"
        host_tool = Path(t) / "LakeModelDump.java"
        host_tool.write_text(to_host(tool.read_text(encoding="utf-8")), encoding="utf-8")
        ours = dump(product_classpath(), tool, a.dirs, t, False)
        ref = [RENAMED(x) for x in dump(Path(a.ref_classpath).read_text().strip(), host_tool, a.dirs, t, True)]
    # the lakes the host's jars ship come through its LakeCatalog now (not on this class path): left out of the reference's list
    ref = [x for x in ref if not x.startswith("{") or '"file":"file:' in x]
    ours = [x.replace(t, "${tmp}") for x in ours]
    ref = [x.replace(t, "${tmp}") for x in ref]
    same = ours == ref
    print(f"{len(ours)} lakes here, {len(ref)} in the reference: " + ("identical" if same else "DIFFERENT"))
    if not same:
        for x, y in zip(ours, ref):
            if x != y:
                print("here:      " + x + "\nreference: " + y)
                break
    return 0 if same else 1


if __name__ == "__main__":
    sys.exit(main())
