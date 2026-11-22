# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The table and the business date a file's name carries, the business date of a file, and a tar archive's layout: the Python half of
one rule. ``com.ash.lake.common.FileDates`` is the Java half; both are tested against ``tck/fixtures/file-dates/names.json``.

**Names.** ``<table>.<date>.<extensions>`` or ``<table>.<extensions>``: the table is everything before the first ``.`` (letters, digits,
``_`` and ``-``); the date, when there is one, is the second segment, ``YYYY-MM-DD`` (or ``YYYYMMDD``), a calendar date from 1900, not
in the future; the extensions are a format (``jsonl``, ``ndjson``, ``json``) and/or a compression (``gz``, ``zip``, ``zst``), in that
order, read without case. Anything else is refused with the reason: never guessed.

**Tar archives** (``.tar``, ``.tgz``, ``.tar.gz``, ``.tar.zip``) name their tables and dates in their folders: every member is
``<table>/<YYYY-MM-DD>/<file>.jsonl[.gz|.zst]``; OS litter is ignored; anything else refuses the whole archive, every problem listed.

**The business date of a plain file** (``decide``): the date given for the load (``--as-of``, a ``.ready`` file's ``business-date``),
else the name's date, else the business today; ``date-from: name`` refuses a name without a date. No date after today is ever taken,
whatever gives it (``future_error``).

**Today** is the calendar date in the business zone (``today``: ``LAKE_BUSINESS_ZONE``, default ``America/New_York``), never the
machine's own zone; the Java loader reads the same setting (``lake.business.zone``)."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

FORMATS = ("jsonl", "ndjson", "json")
COMPRESSIONS = ("gz", "zip", "zst")
TAR_SUFFIXES = (".tar.gz", ".tar.zip", ".tgz", ".tar")
ARCHIVE_SUFFIXES = (".tar.gz", ".tgz", ".tar", ".zip")
DATE_FROM = ("auto", "name")
LITTER = ("__MACOSX", "Thumbs.db")
FIRST_YEAR = 1900
EXTENSIONS_TEXT = "(.jsonl, .ndjson, .json, .gz, .zip, .zst; an archive .tar, .tgz, .tar.gz, .tar.zip)"
#: the business zone when ``LAKE_BUSINESS_ZONE`` names none (the Java loader's ``LakeOptions.DEFAULT_BUSINESS_ZONE``)
DEFAULT_BUSINESS_ZONE = "America/New_York"
_DATEISH = re.compile(r"^(\d{8}|\d{4}-\d{2}-\d{2})$", re.ASCII)
_PART = re.compile(r"^part-?\d{1,6}$", re.ASCII | re.IGNORECASE)
_TABLE = re.compile(r"^[A-Za-z0-9_-]+$", re.ASCII)
_JSONL_MEMBER = re.compile(r"\.(jsonl|ndjson|json)(\.gz|\.zst)?$", re.IGNORECASE)


def business_zone(zone: str | None = None):
    """The business zone: ``zone``, else ``LAKE_BUSINESS_ZONE``, else ``America/New_York``; an unknown zone is refused with its key."""
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
    name = (zone or os.environ.get("LAKE_BUSINESS_ZONE") or "").strip() or DEFAULT_BUSINESS_ZONE
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as e:
        raise ValueError(f"lake.business.zone (LAKE_BUSINESS_ZONE): '{name}' is not a time zone id (for example America/New_York)") from e


def today(zone: str | None = None) -> date:
    """Today: the calendar date in the business zone (``business_zone``), never the machine's zone."""
    return datetime.now(business_zone(zone)).date()


_zone_today = today        # the functions below take a ``today`` argument (the host's names), which hides this one


def business_today(today: date | None = None) -> date:
    """The business today: ``today`` (default the business zone's), or the Friday before on a Saturday or a Sunday."""
    d = today or _zone_today()
    return d - timedelta(days=max(0, d.weekday() - 4))


def base_name(name) -> str:
    """The last part of a path (after '/', '\\' or an archive's '!/')."""
    return str(name).replace("\\", "/").rsplit("/", 1)[-1]


def _date(text: str, day: date) -> tuple[date | None, str | None]:
    """(the date, None) or (None, why it is not one)."""
    d = text.replace("-", "")
    try:
        got = date(int(d[:4]), int(d[4:6]), int(d[6:8]))
    except ValueError:
        return None, f"{text} is not a calendar date"
    if got.year < FIRST_YEAR:
        return None, f"{text} is before {FIRST_YEAR}"
    if got > day:
        return None, f"{text} is in the future"
    return got, None


def is_tar(name) -> bool:
    return base_name(name).lower().endswith(TAR_SUFFIXES)


def is_archive(name) -> bool:
    """Whether ``name`` is an archive the loaders read members of (.zip, .tar, .tar.gz, .tgz, .tar.zip)."""
    return base_name(name).lower().endswith(ARCHIVE_SUFFIXES + (".tar.zip",))


@dataclass(frozen=True)
class Name:
    """What a file's name says: its table (None: none), its date (None: none), whether it is a tar archive, or why it is refused."""
    table: str | None
    date: date | None
    tar: bool = False
    error: str | None = None


def parse(name, today: date | None = None) -> Name:
    """The table and date ``name`` carries (module doc), or why it is refused; ``today`` default the business zone's."""
    day = today or _zone_today()
    base = base_name(name)
    low = base.lower()
    for s in TAR_SUFFIXES:
        if low.endswith(s):
            label = base[: len(base) - len(s)]
            if not _TABLE.match(label):
                return Name(None, None, True, f"{base}: a .tar archive names its tables and dates in its folders "
                                              "(<table>/<YYYY-MM-DD>/), never in its file name (letters, digits, _ and - before .tar)")
            return Name(label.lower(), None, True)
    tokens = base.split(".")
    table, rest = tokens[0], tokens[1:]
    if not _TABLE.match(table):
        return Name(None, None, False, f"{base}: '{table}' is not a table name (letters, digits, _ and -, before the first '.')")
    if not rest:
        return Name(None, None, False, f"{base}: no extension {EXTENSIONS_TEXT}")
    got = None
    if _DATEISH.match(rest[0]):
        got, why = _date(rest[0], day)
        if why:
            return Name(None, None, False, f"{base}: {why}")
        rest = rest[1:]
    if got and rest and _PART.match(rest[0]):        # one part of a day split into several files: <table>.<date>.part-0001.jsonl
        rest = rest[1:]
    shape = ""
    for t in rest:
        lt = t.lower()
        if lt in FORMATS:
            shape += "F"
        elif lt in COMPRESSIONS:
            shape += "C"
        else:
            return Name(None, None, False, f"{base}: '{t}' is neither the date (YYYY-MM-DD, right after the table name) nor an extension "
                                           + EXTENSIONS_TEXT)
    if shape not in ("F", "FC", "C"):
        return Name(None, None, False, f"{base}: not <table>[.<YYYY-MM-DD>].<format>[.<compression>] {EXTENSIONS_TEXT}")
    return Name(table.lower(), got, False)


def of(name, today: date | None = None) -> date | None:
    """The date ``name`` carries; None when it carries none, is refused or is a tar."""
    n = parse(name, today)
    return None if n.error or n.tar else n.date


@dataclass(frozen=True)
class Decided:
    """A file's business date and where it comes from (given, name, folder, ready, rows, today), or why it is refused."""
    date: date | None
    source: str
    error: str | None = None


def date_error(name, today: date | None = None) -> str | None:
    """Why the date part of a plain file's ``name`` is refused (not a calendar date, before 1900, in the future), None when it has none
    or a good one: such a name is refused, never filed under another date."""
    base = base_name(name)
    if is_tar(base):
        return None
    tokens = base.split(".")
    if len(tokens) > 1 and _DATEISH.match(tokens[1]):
        _got, why = _date(tokens[1], today or _zone_today())
        if why:
            return f"{base}: {why}"
    return None


def future_error(day: date, now: date, source: str | None = None) -> str | None:
    """Why a load for ``day`` is refused: a business date after ``now`` (the same rule for every source of the date), None otherwise."""
    if day <= now:
        return None
    return f"business date {day.isoformat()}" + ("" if source is None else " " + source) + " is in the future: a load for a future date is refused"


def decide(given: date | None, name_date: date | None, date_from: str = "auto", today: date | None = None, name: str = "",
           latest: date | None = None) -> Decided:
    """The business date of one plain file (module doc). ``name``: the name the date was read from; a bad date part in it refuses the
    file whatever else is given (a wrong date is never replaced by --as-of, the .ready file or today). ``latest``: the latest date
    allowed (today plus the loader's ``guard.future-days``; default today); a file without a date still goes under today."""
    day = today or _zone_today()
    last = latest or day
    why = date_error(name, last) if name else None
    if why:
        return Decided(None, "name", why)
    if given is not None:
        if given > last:
            return Decided(None, "given", future_error(given, last, "given (--as-of, the .ready file's business-date)"))
        return Decided(given, "given")
    if name_date is not None:
        return Decided(name_date, "name")
    if date_from == "name":
        return Decided(None, "name", f"{base_name(name)}: date-from name, but the name carries no date (YYYY-MM-DD)")
    return Decided(business_today(day), "today")


def words(d: Decided, given_by: str = "the command line") -> str:
    """Where a file's date comes from, in words (the Java loader's words are the same)."""
    if d.source == "given":
        return f"{d.date.isoformat()} from {given_by}"
    if d.source == "name":
        return f"{d.date.isoformat()} from the file name"
    if d.source == "folder":
        return f"{d.date.isoformat()} from the archive's folder"
    if d.source == "ready":
        return f"{d.date.isoformat()} from the .ready file"
    if d.source == "rows":
        return f"{d.date.isoformat()} from the rows' own business date (no date given or in the name)"
    return f"{d.date.isoformat()} (today: no date in the name)"


def ready_date(path) -> date | None:
    """The business date a ``.ready`` file beside a local data file gives (``<file>.ready``, or its name without the data extension
    ``.ready``): its ``business-date``, None without one (``FileDates.readyDate``)."""
    import pathlib
    import yaml
    text = str(path)
    if "://" in text or "!/" in text:
        return None
    p = pathlib.Path(text)
    name, short = p.name, p.name
    for e in (".tar.gz", ".tar.zip", ".jsonl.gz", ".jsonl.zip", ".jsonl.zst", ".ndjson.gz", ".ndjson.zst", ".json.gz", ".tgz", ".tar",
              ".jsonl", ".ndjson", ".json", ".gz", ".zip", ".zst"):
        if name.lower().endswith(e) and len(name) > len(e):
            short = name[: len(name) - len(e)]
            break
    for m in (name + ".ready", short + ".ready"):
        r = p.with_name(m)
        if r.is_file():
            try:
                n = yaml.safe_load(r.read_text(encoding="utf-8"))
                d = n.get("business-date") if isinstance(n, dict) else None
                if d is None or str(d).strip() == "":
                    return None
                return d if isinstance(d, date) else date.fromisoformat(str(d).strip())
            except (yaml.YAMLError, ValueError) as e:
                raise ValueError(f"{r.name}: its business-date is not YYYY-MM-DD ({e})") from e
    return None


def other_date_note(rows: int, label: str, field_name: str | None, day: date) -> str:
    """The words for rows whose own date field differs from their file's date (``FileDates.otherDateNote``)."""
    return f"{rows} row{'' if rows == 1 else 's'} of {label} carried another date in {field_name}; loaded under {day.isoformat()}"


@dataclass
class Layout:
    """A tar archive's layout: table -> date -> member names (in name order), the problems (any refuses the archive), the ignored litter."""
    tables: dict = field(default_factory=dict)
    errors: list = field(default_factory=list)
    ignored: list = field(default_factory=list)


def tar_layout(entries, today: date | None = None, known=None) -> Layout:
    """The layout of a tar archive's entries ``[(raw name, type)]`` (type ``file``, ``dir`` or what else it is). ``known``: the tables
    the load knows (a name compared without case: the known spelling is kept), None: any table name."""
    day = today or _zone_today()
    out = Layout()
    canon = {k.lower(): k for k in known} if known is not None else None
    unknown = []
    for raw, typ in entries:
        n = str(raw).replace("\\", "/")
        if n.startswith("/") or re.match(r"^[A-Za-z]:", n) or "\x00" in n:
            out.errors.append(f"{raw}: an absolute path")
            continue
        parts = [p for p in n.split("/") if p not in ("", ".")]
        if ".." in parts:
            out.errors.append(f"{raw}: a '..' in its path")
            continue
        if not parts or typ == "dir":
            continue
        if any(p.startswith(".") or p in LITTER for p in parts):
            out.ignored.append(raw)
            continue
        if typ != "file":
            out.errors.append(f"{raw}: {typ}, not a file")
            continue
        if len(parts) != 3:
            where = "at the top" if len(parts) == 1 else f"{len(parts) - 1} folder{'s' if len(parts) > 2 else ''} deep"
            out.errors.append(f"{raw}: {where}; every file is <table>/<YYYY-MM-DD>/<file>.jsonl")
            continue
        table, folder, file = parts
        if not _JSONL_MEMBER.search(file):
            out.errors.append(f"{raw}: not JSON Lines (.jsonl, .ndjson, .json; plain, .gz or .zst)")
            continue
        if not _DATEISH.match(folder):
            out.errors.append(f"{raw}: the folder {folder} is not a date (YYYY-MM-DD)")
            continue
        got, why = _date(folder, day)
        if why:
            out.errors.append(f"{raw}: {why}")
            continue
        if not _TABLE.match(table):
            out.errors.append(f"{raw}: the folder {table} is not a table name (letters, digits, _ and -)")
            continue
        if canon is not None:
            if table.lower() not in canon:
                if table not in unknown:
                    unknown.append(table)
                continue
            table = canon[table.lower()]
        else:
            table = table.lower()
        out.tables.setdefault(table, {}).setdefault(got, []).append("/".join(parts))
    for t in unknown:
        out.errors.append(f"{t}/: no such table (known: {', '.join(sorted(known, key=str.lower)) or 'none'})")
    for t in out.tables.values():
        for d in t:
            t[d].sort()
    return out
