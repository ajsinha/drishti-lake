# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The shared cases of names and dates (tck/fixtures/file-dates/names.json): the Java FileDatesTest reads the same file, so the two
halves of the rule cannot drift; the business zone's today."""
import json
from datetime import date, datetime

import pytest
from conftest import ROOT

from drishti_lake import filedate

CASES = json.loads((ROOT / "tck" / "fixtures" / "file-dates" / "names.json").read_text(encoding="utf-8"))
TODAY = date.fromisoformat(CASES["today"])


def d(v):
    return None if v is None else date.fromisoformat(v)


@pytest.mark.parametrize("n", CASES["names"], ids=lambda n: n["name"])
def test_names(n):
    got = filedate.parse(n["name"], TODAY)
    assert got.tar == n.get("tar", False)
    if n.get("error"):
        assert got.error and n["error"] in got.error
    else:
        assert got.error is None
        assert (got.table, got.date) == (n.get("table"), d(n.get("date")))


@pytest.mark.parametrize("n", CASES["decide"], ids=lambda n: json.dumps(n)[:60])
def test_decide(n):
    r = filedate.decide(d(n.get("given")), d(n.get("name")), n.get("dateFrom", "auto"), TODAY, n.get("file", "f.jsonl"))
    if n.get("error"):
        assert r.error and n["error"] in r.error
    else:
        assert [r.date.isoformat(), r.source, filedate.words(r)] == [n["date"], n["source"], n["words"]]


def test_business_today():
    for n in CASES["businessToday"]:
        assert filedate.business_today(d(n["today"])) == d(n["business"])


@pytest.mark.parametrize("t", CASES["tars"], ids=lambda t: t["case"])
def test_tars(t):
    lay = filedate.tar_layout([tuple(e) for e in t["entries"]], TODAY, t.get("known"))
    tables = {tb: {day.isoformat(): fs for day, fs in days.items()} for tb, days in lay.tables.items()}
    assert (tables, lay.errors, lay.ignored) == (t["tables"], t["errors"], t["ignored"])


def test_today_is_the_business_zones(monkeypatch):
    """Etc/GMT+12 is always a day or two behind Pacific/Kiritimati: the day after today in the one is today (or before) in the other."""
    from zoneinfo import ZoneInfo
    day = datetime.now(ZoneInfo("Etc/GMT+12")).date()
    tomorrow = date.fromordinal(day.toordinal() + 1)
    name = f"pv.{tomorrow.isoformat()}.jsonl"
    monkeypatch.setenv("LAKE_BUSINESS_ZONE", "Etc/GMT+12")
    assert "in the future" in filedate.parse(name).error
    monkeypatch.setenv("LAKE_BUSINESS_ZONE", "Pacific/Kiritimati")
    assert filedate.parse(name).date == tomorrow
    monkeypatch.setenv("LAKE_BUSINESS_ZONE", "Mars/Olympus")
    with pytest.raises(ValueError, match="lake.business.zone"):
        filedate.today()
