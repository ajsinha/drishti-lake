# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The Java tools of the product, run from the Python path (design 3.3: one implementation of each job; the Python path reaches the
Java one through the product's class path, never a host's jar). The class path is ``LAKE_CLASSPATH`` (the lake-load module's run-time
class path, or one runnable jar), the Java binary ``LAKE_JAVA`` (else ``$JAVA_HOME/bin/java``, else ``java``); JDK 21 or later.

The tools: ``undo`` (LakeUndo), ``retention`` (LakeRetention: delete dates, keep days or rows),
``check`` (LakeCheck), ``maintain`` (LakeMaintain: one table's upkeep on every store: keep-days under the drop guard, row retention,
compaction, checkpoint, vacuum), ``lakes`` (LakesCli: a lake file's model).
Each runs with the arguments given and prints what the Java tool prints; its exit code is the tool's."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys

TOOLS = {"undo": "com.ash.lake.load.LakeUndo", "retention": "com.ash.lake.load.LakeRetention",
         "check": "com.ash.lake.load.LakeCheck", "maintain": "com.ash.lake.load.LakeMaintain",
         "lakes": "com.ash.lake.config.LakesCli"}


class JavaError(Exception):
    """The Java tools cannot be reached (no class path, no Java), in words."""


def classpath(env=None) -> str:
    env = os.environ if env is None else env
    cp = env.get("LAKE_CLASSPATH")
    if not cp or not cp.strip():
        raise JavaError("the Java tools: set LAKE_CLASSPATH to the product's class path (the lake-load module's run-time class path, or "
                        "its runnable jar)")
    return cp.strip()


def java(env=None) -> str:
    env = os.environ if env is None else env
    if env.get("LAKE_JAVA"):
        return env["LAKE_JAVA"]
    if env.get("JAVA_HOME"):
        return os.path.join(env["JAVA_HOME"], "bin", "java")
    found = shutil.which("java")
    if not found:
        raise JavaError("the Java tools: no Java (set LAKE_JAVA or JAVA_HOME; JDK 21 or later)")
    return found


def command(tool: str, args: list[str], env=None) -> list[str]:
    if tool not in TOOLS:
        raise JavaError(f"unknown tool {tool} ({', '.join(TOOLS)})")
    return [java(env), "-cp", classpath(env), TOOLS[tool]] + list(args)


def run(tool: str, args: list[str], out=None, env=None) -> int:
    """Runs a tool with ``args``, its standard output to ``out`` (default: this process's), its errors to this process's; the exit code."""
    out = out or sys.stdout
    try:
        cmd = command(tool, args, env)
    except JavaError as e:
        print(f"lake {tool}: {e}", file=out)
        return 2
    p = subprocess.run(cmd, stdout=subprocess.PIPE, env=env if env is not None else None)
    out.write(p.stdout.decode("utf-8", errors="replace"))
    out.flush()
    return p.returncode


def lake_table(lake: str, kind: str, domain: str | None = None, table: str | None = None, lakes_dir: str | None = None, env=None) -> dict:
    """A named lake's entry for a kind (``LakesCli table``): its location, format and settings as the Java loader reads them."""
    args = ["table", "--lake", lake, "--kind", kind] + (["--domain", domain] if domain else []) + (["--table", table] if table else []) \
        + (["--dir", lakes_dir] if lakes_dir else [])
    p = subprocess.run(command("lakes", args, env), stdout=subprocess.PIPE, env=env if env is not None else None)
    text = p.stdout.decode("utf-8", errors="replace").strip()
    try:
        got = json.loads(text) if text else {}
    except json.JSONDecodeError:
        raise JavaError(f"the lake file reader said: {text[:300]}") from None
    if p.returncode != 0 or "error" in got:
        raise ValueError(got.get("error") or f"the lake file reader failed ({p.returncode})")
    return got
