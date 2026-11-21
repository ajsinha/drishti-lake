# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The Python path end to end on small lakes: one mechanism, Polars when installed, the plain writer otherwise, the same table either
way; the row rule's words; the business date rules. The parity with the Java loader is the kit's Python row (tools/tck/tck.py)."""
import json
import re

import pytest
from conftest import PYTHON

from drishti_lake import delta, load, rowrule

LINES = [
    {"POSITION_ID": "P-1", "DESK": "D1", "BASE_PV": 1.5, "QTY": 3, "TAGS": ["a", 2, None], "SCENARIO_PV": [1, None, 2.5],
     "LEGS": [{"RATE": 0.25}], "NOTE": 'café "q"\nü'},
    {"POSITION_ID": 1000008, "DESK": None, "BASE_PV": "n/a", "SCENARIO_PV": []},
    {"POSITION_ID": "P-3", "DESK": "D2", "BASE_PV": 1e-300, "QTY": 2.0, "SCENARIO_PV": None},
]


def run(tmp_path, name, lines, extra="", args=()):
    d = tmp_path / name
    (d / "in").mkdir(parents=True)
    (d / "in" / "scenario-pv.2026-10-27.jsonl").write_text("".join(json.dumps(x, ensure_ascii=False) + "\n" for x in lines), encoding="utf-8")
    (d / "c.yaml").write_text(f"source: {{files: '{d}/in/*', id: POSITION_ID, promote: [DESK, BASE_PV, QTY, TAGS], vectors: [SCENARIO_PV], "
                              f"flatten: {{LEG_RATE: 'LEGS[0].RATE'}}{extra}}}\n"
                              f"target: {{format: delta, lake: '{d}/lake', domain: risk, kind: scenario-pv, mode: replace}}\n")
    code = load.main(["--config", str(d / "c.yaml"), "--summary", str(d / "s.json"), "-q"] + list(args), out=open(d / "said.txt", "w"))
    return code, json.loads((d / "s.json").read_text()), d


def dump(path):
    from deltalake import DeltaTable
    t = DeltaTable(str(path)).to_pyarrow_table()
    rows = sorted(t.drop_columns(["_load_id"]).to_pylist(), key=lambda r: r["id"])
    return [{k: (v.isoformat() if hasattr(v, "isoformat") else v) for k, v in r.items()} for r in rows], str(t.schema)


@pytest.mark.skipif(not delta.polars_installed(), reason="the plain writer's parity needs Polars at hand to compare with")
def test_polars_and_the_plain_writer_give_the_same_table(tmp_path, monkeypatch):
    code, s, a = run(tmp_path, "polars", LINES)
    assert code == 0 and s["engineWhy"].endswith("Polars)")
    monkeypatch.setenv("LAKE_PYTHON_NO_POLARS", "1")
    code, s2, b = run(tmp_path, "plain", LINES)
    assert code == 0 and s2["engineWhy"] == "the Python path (drishti_lake)"
    assert dump(a / "lake/risk/scenario-pv") == dump(b / "lake/risk/scenario-pv")


def test_the_rows(tmp_path):
    code, s, d = run(tmp_path, "rows", LINES)
    assert code == 0, (d / "said.txt").read_text()
    assert s["rows"] == {"2026-10-27": 3} and s["commit"]["commits"] == 1
    assert s["columns"]["BASE_PV"] == "string" and s["columns"]["QTY"] == "double" and s["columns"]["TAGS"] == "list"
    rows, _ = dump(d / "lake/risk/scenario-pv")
    one = rows[0]
    assert one["id"] == "1000008"
    assert one["doc"] == '{"POSITION_ID":"1000008","DESK":null,"BASE_PV":"n/a","SCENARIO_PV":{"$vector":"SCENARIO_PV"}}'
    p1 = rows[1]
    assert p1["doc"].startswith('{"POSITION_ID":"P-1","DESK":"D1","BASE_PV":1.5,"QTY":3,"TAGS":["a",2,null],'
                                '"SCENARIO_PV":{"$vector":"SCENARIO_PV"}')
    assert p1["doc"].endswith(',"NOTE":"café \\"q\\"\\nü","LEG_RATE":0.25}')
    assert (p1["BASE_PV"], p1["TAGS"], p1["SCENARIO_PV"], p1["LEG_RATE"]) == ("1.5", ["a", "2"], [1.0, None, 2.5], 0.25)
    assert rows[2]["BASE_PV"] == "1e-300" and rows[2]["SCENARIO_PV"] is None
    assert p1["_source_file"] == "scenario-pv.2026-10-27.jsonl"


def test_rejects_and_the_share(tmp_path):
    bad = LINES[:1] + [{"DESK": "x"}, {"POSITION_ID": "X", "SCENARIO_PV": [1, "two"]}, {"POSITION_ID": {"a": 1}}]
    code, s, _ = run(tmp_path, "rejects", bad)
    assert code == 2 and s["status"] == "partial"
    assert s["rejects"]["byReason"] == {"no id (field 'POSITION_ID' is missing or empty)": 1,
                                        "the id field 'POSITION_ID' is not a single value": 1,
                                        "the vector field 'SCENARIO_PV' is not a list of numbers": 1}
    code, s, d = run(tmp_path, "share", bad, args=["--set", "rejects.max-pct=10"])
    assert code == 1 and s["status"] == "failed"
    assert s["error"] == "3 of 4 documents rejected (75.00%), more than rejects.max-pct 10.0: nothing was written"
    assert not (d / "lake/risk/scenario-pv").exists()


def test_a_future_date_is_refused(tmp_path):
    code, s, d = run(tmp_path, "future", LINES, args=["--as-of", "2999-01-04"])
    assert code == 1 and "business date 2999-01-04 given" in s["error"] and "is in the future" in s["error"]
    assert not (d / "lake").exists() or not (d / "lake/risk/scenario-pv").exists()


def test_the_row_rule_order_and_words():
    plan = rowrule.Plan(key="ID", vectors=("V",))
    assert rowrule.row(plan, "{not json", 0, 0, False).startswith("not JSON (")
    assert rowrule.row(plan, "[1,2]", 0, 0, False) == "not a JSON object"
    assert rowrule.row(plan, '{"ID":NaN}', 0, 0, False).startswith("not JSON (")
    assert rowrule.row(plan, '{"ID":"a"} x', 0, 0, False) == "not JSON (Extra data)"
    assert rowrule.row(plan, '{"V":[1,"x"]}', 0, 0, False) == "no id (field 'ID' is missing or empty)"
    r = rowrule.row(plan, '{"ID":12.0,"V":[1]}', 5, 0, False)
    assert (r.ident, r.doc, r.day) == ("12", '{"ID":"12","V":{"$vector":"V"}}', 5)


def test_no_host_word_in_the_package():
    """The package names no host (the Java guard NamesNoHostTest's words), as the moved Java code must not."""
    host = re.compile(r"DRS-\d|\bdrishti\.[a-z]|DRISHTI_|(?i:\b(endur|calypso|murex|swapone)\b)|Drishti(?! Lake)")
    found = [str(p.relative_to(PYTHON)) for p in PYTHON.rglob("*.py") if host.search(p.read_text(encoding="utf-8"))
             and p.name != "test_load.py"]
    assert found == []
