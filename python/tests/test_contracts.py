# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""Data contracts with the sample contracts of tck/fixtures/contracts: the reader (a host's keys untouched), the schema (core, and with
a host's profile), the record check (records.json: the Java checker's words too), the data check (drift) and the pages (pages/)."""
import json

import pytest
from conftest import ROOT

from drishti_lake import contracts as K
from drishti_lake.contracts import checker, pages

FIX = ROOT / "tck" / "fixtures" / "contracts"
POSITION = FIX / "desk" / "contracts" / "position.contract.yaml"
PLANNED = FIX / "desk" / "contracts" / "limit.contract.yaml"
HOST = K.Profile(properties={"owner-team": {"type": "string", "minLength": 1}}, required=("owner-team",))


def test_reader_reads_every_key_and_hands_the_hosts_back():
    c = K.Contract.read(POSITION, HOST)
    assert c.kind == "position" and c.version == "1.2" and c.dated_by == "BUSINESS_DATE"
    assert c.required == ["POSITION_ID", "BUSINESS_DATE", "DESK"]             # the validation rule, not the presence marks
    assert c.single_key() == "POSITION_ID" and c.key() == ["POSITION_ID"]
    assert c.derived == {"NET_PV"} and c.enums["DESK"] == ["D1", "D2"] and c.enums["TIER"] == [1, 2, 3]
    assert set(c.nested) == {"KYC", "LEGS[]"} and c.required_in("KYC") == ["STATUS"]
    assert c.field("LEGS[].CCY")["pattern"] == "^[A-Z]{3}$" and c.field("NOPE") is None
    assert c.host == {"owner-team": "market-risk"}
    assert K.Contract.read(POSITION).host == {}


def test_schema_core_refuses_a_host_key_the_profile_accepts():
    doc = K.load(POSITION)
    assert K.schema_problems(doc) == ["(top): Additional properties are not allowed ('owner-team' was unexpected)"]
    assert K.schema_problems(doc, HOST) == []
    assert K.schema_problems(K.load(PLANNED)) == []
    assert K.schema_problems(K.load(PLANNED), HOST) == ["(top): 'owner-team' is a required property"]
    bad = dict(doc, fields=[{"name": "x y", "type": "money", "presence": "required"}])
    assert K.schema_problems(bad, HOST) == ["fields/0/name: 'x y' does not match '^[A-Za-z_][A-Za-z0-9_]*$'",
                                            "fields/0/type: 'money' is not one of ['text', 'id', 'integer', 'amount', 'rate', 'date', "
                                            "'timestamp', 'boolean', 'vector', 'record', 'record list', 'text list', 'id list', "
                                            "'date list', 'list', 'unknown']"]


def test_a_profile_replaces_a_core_key_definition():
    strict = K.Profile(properties={"owner-team": {"type": "string"}, "title": {"type": "string", "pattern": "^Desk "}})
    assert K.schema_problems(dict(K.load(POSITION), title="A position"), strict) == ["title: 'A position' does not match '^Desk '"]
    assert K.core_schema()["properties"]["title"] == {"type": "string", "minLength": 1}


@pytest.mark.parametrize("case", json.loads((FIX / "records.json").read_text(encoding="utf-8")), ids=lambda c: str(c["record"].get("POSITION_ID")))
def test_record_problems_are_the_shared_words(case):
    assert K.record(K.Contract.read(POSITION), case["record"]) == case["problems"]


def test_reason_names_the_contract_and_counts_the_rest():
    c = K.Contract.read(POSITION)
    assert K.reason(c, ["A: x"]) == "the contract of position (version 1.2): A: x"
    assert K.reason(c, ["A: x", "B: y", "C: z"]) == "the contract of position (version 1.2): A: x; and 2 more"


def test_drift_finds_what_the_data_no_longer_matches():
    doc = K.load(POSITION)
    good = [{"POSITION_ID": "P-1", "BUSINESS_DATE": "2026-10-27", "DESK": "D1", "BASE_PV": 1.5},
            {"POSITION_ID": "P-2", "BUSINESS_DATE": "2026-10-27", "DESK": "D2", "BASE_PV": 2}]
    assert K.drift(doc, good) == []
    assert K.drift(doc, good, id_patterns=["^P-[0-9]+$"]) == []
    bad = good + [{"POSITION_ID": "P-3", "BUSINESS_DATE": "2026-10-27", "DESK": "D7", "BASE_PV": "x", "COLOUR": "red"},
                  {"POSITION_ID": "P-4", "BUSINESS_DATE": "2026-10-27", "BASE_PV": 1}]
    assert K.drift(doc, bad, links={"DESK": "book", "POSITION_ID": "position"}, id_patterns=None) == [
        "id-pattern ['^P-[0-9]+$'] differs from the link graph's None",
        "DESK is required but empty in 1 of 4 records",
        "DESK has values outside its enum: ['D7']",
        "BASE_PV is amount in the contract but text in the data",
        "COLOUR is in the data but not in the contract",
        "DESK links to book in the link graph but not in the contract"]
    linked = dict(doc, fields=[dict(f, link="desk") if f["name"] == "DESK" else f for f in doc["fields"]])
    assert K.drift(linked, good, links={"DESK": "book"}) == ["DESK links to desk but the link graph says book"]


def test_pages_of_the_samples_are_the_committed_ones(tmp_path):
    import importlib.util
    spec = importlib.util.spec_from_file_location("make_contract_fixtures", FIX / "make_fixtures.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    got = m.pages()
    assert sorted(got) == ["README.md", "desk.md"]
    stale, extra = pages.write(got, FIX / "pages", check=True)
    assert (stale, extra) == ([], []), "tck/fixtures/contracts/pages is stale: run tck/fixtures/contracts/make_fixtures.py and review"
    pages.write(got, tmp_path)
    (tmp_path / "old.md").write_text("x", encoding="utf-8")
    assert pages.write(got, tmp_path) == ([], ["old.md"]) and not (tmp_path / "old.md").exists()


def test_a_host_profile_changes_only_the_words_around_the_sections():
    class Host(pages.PageProfile):
        generator = "host docs"

        def heading(self, c):
            return f"## {c['owner-team']}: {c['title']}"

        def title(self, c):
            return f"{c['owner-team']}: {c['title']}"

    doc = K.load(POSITION)
    e = pages.Entry("position", "position", "desk", POSITION, doc)
    text = pages.render_group(Host(), "desk", [e])
    assert text.startswith("<!-- Generated by host docs. Edit the contracts, not this file. -->\n")
    assert "## market-risk: Desk position\n" in text and "(#market-risk-desk-position)" in text
    assert pages.section(Host(), doc, POSITION, "desk")[4:] == pages.section(pages.PageProfile(), doc, POSITION, "desk")[4:]


def test_command_line_checks_and_writes_pages(tmp_path, capsys):
    from drishti_lake.__main__ import main
    files = [str(POSITION), str(PLANNED)]
    assert main(["contracts", "check"] + files) == 1
    prof = tmp_path / "profile.json"
    prof.write_text(json.dumps({"properties": {"owner-team": {"type": "string"}}}), encoding="utf-8")
    assert main(["contracts", "check", "--profile", str(prof)] + files) == 0
    assert main(["contracts", "pages", "--out", str(tmp_path / "p")] + files) == 0
    assert main(["contracts", "pages", "--check", "--out", str(FIX / "pages")] + files) == 0
    assert "2 contract(s), 0 problem(s)" in capsys.readouterr().out


def test_json_type_and_contract_type_of_values():
    st = checker.Stat()
    for v in (1, 2.5, None, ""):
        st.add(v)
    assert checker.contract_type("BASE_PV", st, False) == ("amount", True, [])
    assert checker.json_type([{"a": 1}]) == "list of objects" and checker.json_type("2026-01-01") == "date"
