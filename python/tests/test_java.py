# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The Java tools from the Python path (drishti_lake.java): the command each tool runs, the words when the class path or Java is
missing, and a named lake's entry applied to a load as the Java loader applies it (the entry from the Java reader, stubbed here; the
kit's Python row runs the real tools for undo and retention)."""
import io
import sys

import pytest

from drishti_lake import config, java
from drishti_lake.__main__ import main

ENTRY = {"name": "risk/scenario-pv", "domain": "risk", "table": "scenario_pv", "kind": "scenario-pv", "format": "delta",
         "location": "/data/lakes/risk/scenario_pv", "root": "/data/lakes", "partition-by": [], "promote": ["DESK", "QUANTITY"],
         "vectors": ["SCENARIO_PV"], "flatten": {}, "id": "POSITION_ID", "date": None, "latest-by": None, "load-mode": "replace",
         "sort-by": ["DESK", "id"], "layout": {}, "properties": {}, "maintenance": {}}


def test_each_tool_runs_its_java_class_with_the_product_class_path():
    env = {"LAKE_CLASSPATH": "/cp/a.jar:/cp/b.jar", "LAKE_JAVA": "/jdk/bin/java"}
    assert java.command("undo", ["--lake", "/l"], env) == ["/jdk/bin/java", "-cp", "/cp/a.jar:/cp/b.jar", "com.ash.lake.load.LakeUndo", "--lake", "/l"]
    assert java.command("maintain", [], env)[3] == "com.ash.lake.load.LakeMaintain"
    assert java.command("lakes", [], {"LAKE_CLASSPATH": "x", "JAVA_HOME": "/j"})[0] == "/j/bin/java"
    with pytest.raises(java.JavaError, match="set LAKE_CLASSPATH"):
        java.command("retention", [], {})
    with pytest.raises(java.JavaError, match="unknown tool"):
        java.command("nope", [], env)


def test_a_tool_without_its_class_path_says_so(monkeypatch):
    monkeypatch.delenv("LAKE_CLASSPATH", raising=False)
    out = io.StringIO()
    assert java.run("undo", ["--help"], out) == 2
    assert out.getvalue().startswith("lake undo: the Java tools: set LAKE_CLASSPATH")


def test_a_tool_runs_and_its_output_and_code_come_back(monkeypatch, tmp_path):
    fake = tmp_path / "fakejava.py"
    fake.write_text("import sys\nprint('ran', sys.argv[2], ' '.join(sys.argv[3:]))\nsys.exit(3)\n", encoding="utf-8")
    monkeypatch.setattr(java, "java", lambda env=None: sys.executable)
    monkeypatch.setattr(java, "command", lambda tool, args, env=None: [sys.executable, str(fake), "cp", java.TOOLS[tool]] + list(args))
    out = io.StringIO()
    assert java.run("undo", ["--load-id", "x"], out) == 3
    assert out.getvalue() == "ran com.ash.lake.load.LakeUndo --load-id x\n"


def test_a_named_lake_takes_the_entry(monkeypatch):
    asked = {}

    def entry(lake, kind, domain=None, table=None, lakes_dir=None, env=None):
        asked.update(lake=lake, kind=kind, domain=domain)
        return dict(ENTRY)
    monkeypatch.setattr(java, "lake_table", entry)
    cfg = config.of({"source": {"files": "/in/*.jsonl"}, "target": {"lake": "risklake", "kind": "scenario-pv"}})
    assert asked == {"lake": "risklake", "kind": "scenario-pv", "domain": None}
    assert (cfg.table_path, cfg.lake_root, cfg.domain, cfg.table) == ("/data/lakes/risk/scenario_pv", "/data/lakes", "risk", "scenario_pv")
    assert (cfg.key, cfg.promote, cfg.vectors, cfg.sort_by) == ("POSITION_ID", ["DESK", "QUANTITY"], ["SCENARIO_PV"], ["DESK", "id"])
    assert (cfg.mode, cfg.mode_from) == ("replace-date", "lake")
    given = config.of({"source": {"files": "/in/*.jsonl", "id": "POSITION_ID"}, "target": {"lake": "risklake", "kind": "scenario-pv",
                                                                                         "mode": "append"}})
    assert (given.mode, given.mode_from) == ("append", "given")


def test_a_named_lake_refuses_what_contradicts_its_entry(monkeypatch):
    monkeypatch.setattr(java, "lake_table", lambda *a, **k: dict(ENTRY))
    with pytest.raises(config.ConfigError) as e:
        config.of({"source": {"files": "/in/*.jsonl", "promote": ["DESK"]}, "target": {"lake": "risklake", "kind": "scenario-pv"}})
    assert str(e.value) == ("promote [DESK] differs from [DESK, QUANTITY], the promote of table risk/scenario-pv in its lake file: the lake "
                            "file decides (docs/guides/DATA_LAKE_GUIDE.md); remove promote from the load or correct the lake file")
    monkeypatch.setattr(java, "lake_table", lambda *a, **k: dict(ENTRY, format="iceberg"))
    with pytest.raises(config.ConfigError, match="the Python path writes Delta tables"):
        config.of({"source": {"files": "/in/*.jsonl"}, "target": {"lake": "risklake", "kind": "scenario-pv"}})
    monkeypatch.setattr(java, "lake_table", lambda *a, **k: dict(ENTRY, properties={"delta.appendOnly": "true"}))
    with pytest.raises(config.ConfigError, match="sets table properties"):
        config.of({"source": {"files": "/in/*.jsonl"}, "target": {"lake": "risklake", "kind": "scenario-pv"}})
    with pytest.raises(config.ConfigError, match="target.kind: name the kind"):
        config.of({"source": {"files": "/in/*.jsonl"}, "target": {"lake": "risklake"}})


def test_a_folder_is_not_a_lake_name(tmp_path):
    assert config.is_lake_name("risklake") and not config.is_lake_name("/data/lake") and not config.is_lake_name("Risk")
    assert not config.is_lake_name(str(tmp_path))


def test_the_command_line_lists_the_tools(capsys):
    assert main(["--help"]) == 0
    assert "undo|retention|check|maintain|lakes" in capsys.readouterr().out
    assert main(["nope"]) == 1
