# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The archive rule on the shared fixtures (tck/fixtures/archives/expected.json), as the Java loader's ArchivesTest runs them: the
loader reads feeds only (a tar is <table>/<YYYY-MM-DD>/, a zip one table with its files loose), so the shared rule's other layouts are
refused here too; every refusal in the Java loader's words; the caps from the listing and while reading."""
import json
from datetime import date

import pytest
from conftest import ROOT

from drishti_lake import archives, filedate, inputs

FIX = ROOT / "tck" / "fixtures" / "archives"
DOC = json.loads((FIX / "expected.json").read_text(encoding="utf-8"))
TODAY = date(2026, 11, 21)


def limits(c) -> archives.Limits:
    d, l = archives.Limits(), c.get("limits") or {}
    return archives.Limits(int(l["max_mb"] * 1048576) if "max_mb" in l else d.max_bytes, l.get("max_ratio", d.max_ratio),
                           l.get("max_members", d.max_members))


def plan(c):
    p = archives.plan(str(FIX / c["file"]), c.get("dataset"), limits=limits(c), today=TODAY)
    if "configured" in c:
        p.require(c["configured"])
    return p


def rows(p, lim):
    """The plan's datasets' rows as the loader reads them (non-blank lines), held to the caps."""
    out = {d: 0 for d in p.datasets}
    tally = archives.Tally(p.archive_name, lim.cap(p.size))
    for m in p.members():
        f = inputs.InputFile(str(p.archive), m.name.rsplit("/", 1)[-1], str(p.archive), m.raw, m.day, None, tally)
        out[m.dataset] += sum(1 for _ in inputs.lines(f))
    return out


@pytest.mark.parametrize("c", DOC["cases"], ids=lambda c: c["case"])
def test_the_shared_cases(c):
    want = c.get("expect")
    folders = bool(want) and len(want["datasets"]) > 0 and (c.get("dataset") or archives.stem(c["file"])) not in want["datasets"]
    if "refused" not in c and (filedate.is_tar(c["file"]) or folders):
        with pytest.raises(archives.ArchiveError):
            plan(c)
        return
    if "refused" in c:
        with pytest.raises(archives.ArchiveError) as e:
            rows(plan(c), limits(c))
        assert any(w in str(e.value) for w in (c["refused"], "refused, nothing was loaded", "a .zip is one table and one business date"))
        return
    p = plan(c)
    assert sorted(p.datasets) == sorted(want["datasets"])
    for d, w in want["datasets"].items():
        assert [m.name for m in p.datasets[d]] == w["members"]
    assert rows(p, limits(c)) == {d: w["rows"] for d, w in want["datasets"].items()}
    assert len(p.notes) == want["notes"], p.notes


def test_the_defaults_are_the_shared_ones():
    d = DOC["defaults"]
    assert archives.Limits() == archives.Limits(d["max_mb"] * 1048576, d["max_ratio"], d["max_members"])
    assert archives.FLOOR == d["floor_bytes"]
    env = {"lake.archive.max-mb": "5", "LAKE_ARCHIVE_MAX_RATIO": "7", "lake.archive.max-members": "9"}
    assert archives.Limits.defaults(env) == archives.Limits(5 << 20, 7.0, 9)
    with pytest.raises(ValueError, match="are numbers"):
        archives.Limits.defaults({"lake.archive.max-mb": "x"})


def test_the_refusals_are_the_java_loaders_words():
    lim = archives.Limits(1024, 200, 100000)
    with pytest.raises(archives.ArchiveError) as e:
        archives.plan(str(FIX / "bomb.zip"), limits=lim, today=TODAY)
    assert str(e.value).startswith("bomb.zip: its entries expand to ") and str(e.value).endswith(
        " bytes, more than max-mb 9.765625E-4 (a zip bomb?); nothing was read")
    with pytest.raises(archives.ArchiveError) as e:
        archives.plan(str(FIX / "bundle.zip"), limits=archives.Limits(max_members=3), today=TODAY)
    assert str(e.value) == "bundle.zip: 9 entries, more than the cap of 3 (max-members)"
    with pytest.raises(archives.ArchiveError, match=r"^member '\.\./"):
        archives.plan(str(FIX / "slip.zip"), limits=archives.Limits(), today=TODAY)
    assert archives.safe_parts("./a/./b.jsonl") == ["a", "b.jsonl"]
    with pytest.raises(archives.ArchiveError, match="an absolute path"):
        archives.safe_parts("C:/x.jsonl")


@pytest.mark.parametrize("c", DOC["cases"], ids=lambda c: c["case"])
def test_the_shared_cases_as_rows(c):
    """The ``rows`` layout (a host's own rows files: folders are datasets) gives every shared case's expected answer, read through
    ``Archive`` / ``row_counts`` (one reader for both layouts)."""
    lim = limits(c)
    want = c.get("expect")

    def go():
        p = archives.plan(FIX / c["file"], c.get("dataset"), c.get("configured"), lim, layout="rows")
        return p, archives.row_counts(p, lim)
    if "refused" in c:
        with pytest.raises(archives.ArchiveError) as e:
            go()
        assert c["refused"] in str(e.value)
        return
    p, counts = go()
    assert sorted(p.datasets) == sorted(want["datasets"])
    for d, w in want["datasets"].items():
        assert [m.name for m in p.datasets[d]] == w["members"]
    assert counts == {d: w["rows"] for d, w in want["datasets"].items()}
    assert len(p.notes) == want["notes"], p.notes


def test_the_api_a_host_reads_through():
    """What a host's tools use (a lake build, a convert, a feed reader): the caps from the environment with overrides, the stem, a
    member's label and its date, a feed's tar dated by its folders and the read helpers."""
    lim = archives.Limits.from_env({"LAKE_ARCHIVE_MAX_MB": "5"}, max_ratio=9)
    assert (lim.max_mb, lim.max_ratio, lim.max_members) == (5.0, 9.0, archives.DEFAULT_MAX_MEMBERS)
    with pytest.raises(ValueError, match="more than 0"):
        archives.Limits.from_env({}, max_members=0)
    assert archives.archive_stem("trade.tar.gz") == archives.stem("trade.tgz") == "trade"
    p = archives.plan(FIX / "trade.zip", layout="rows")
    m = p.members()[0]
    assert m.label == "trade.zip!/a.jsonl" and m.stat().st_size == m.size and str(m).endswith("trade.zip!/a.jsonl")
    with archives.Archive(p) as a:
        assert sum(1 for line in a.lines(m) if line.strip()) > 0
    assert sum(1 for _ in archives.read_lines(p)) >= sum(archives.row_counts(p).values())
