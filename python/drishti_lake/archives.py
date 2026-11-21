# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""Archives as loader input, the Java loader's rule (``Archives``) in its words: a ``.zip`` is one table and one business date, its JSON
Lines files loose; a tar (``.tar``, ``.tar.gz``, ``.tgz``) is ``<table>/<YYYY-MM-DD>/<file>.jsonl`` (``filedate.tar_layout``). The guards:
absolute names, drive letters and ``..`` parts, links and devices, encrypted members and archives inside the archive are refused; the
caps (``Limits``: entries, the declared sizes at most ``max-mb`` and ``max-ratio`` times the archive's size, 1 MB always allowed) are
checked from the listing before a byte is decompressed, and while reading, the bytes the archive expands to and, apart, the JSON Lines
text of its compressed members are held to the same caps (``Capped``). The shared fixtures: tck/fixtures/archives."""
from __future__ import annotations

import io
import os
import re
import tarfile
import zipfile
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from . import filedate

FLOOR = 1 << 20
DEFAULT_MAX_MB = 102400
DEFAULT_MAX_RATIO = 200.0
DEFAULT_MAX_MEMBERS = 100000
JSONL_SUFFIXES = (".jsonl", ".ndjson", ".json")
NESTED_SUFFIXES = (".zip", ".tar", ".tgz", ".tar.gz", ".tar.zst", ".tar.bz2", ".tar.xz", ".7z", ".rar")
ARCHIVE_SUFFIXES = (".tar.gz", ".tar.zst", ".tar.zip", ".tgz", ".tar", ".zip")
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


class ArchiveError(Exception):
    """An archive the rule refuses; the message says which member and why (printed after ``lakeload: `` as it is)."""


def _num(v: str | None, dflt: float) -> float:
    return dflt if v is None or not str(v).strip() else float(str(v).strip())


@dataclass(frozen=True)
class Limits:
    """The zip-bomb caps: the declared and the expanded bytes at most ``max_bytes`` and ``max_ratio`` times the archive."""
    max_bytes: int = DEFAULT_MAX_MB * 1048576
    max_ratio: float = DEFAULT_MAX_RATIO
    max_members: int = DEFAULT_MAX_MEMBERS

    @classmethod
    def defaults(cls, env=None) -> "Limits":
        """From ``lake.archive.max-mb``, ``max-ratio``, ``max-members`` (or ``LAKE_ARCHIVE_MAX_MB``, ...), else the product's."""
        env = os.environ if env is None else env

        def get(key):
            v = env.get(key)
            return v if v not in (None, "") else env.get(key.upper().replace(".", "_").replace("-", "_"))
        try:
            return cls(int(_num(get("lake.archive.max-mb"), DEFAULT_MAX_MB) * 1048576), _num(get("lake.archive.max-ratio"), DEFAULT_MAX_RATIO),
                       int(_num(get("lake.archive.max-members"), DEFAULT_MAX_MEMBERS)))
        except ValueError:
            raise ValueError("lake.archive.max-mb, max-ratio and max-members (LAKE_ARCHIVE_MAX_MB, ...) are numbers") from None

    def cap(self, archive_bytes: int) -> int:
        return int(min(self.max_bytes, self.max_ratio * max(archive_bytes, FLOOR)))


def _fmt(v: float) -> str:
    """A number as the Java loader writes it: a whole number without its fraction, else Java's Double.toString (the shortest digits;
    scientific, ``9.765625E-4``, below 10^-3 and from 10^7)."""
    v = float(v)
    if v.is_integer():
        return str(int(v))
    sign, digits, exp = Decimal(repr(v)).as_tuple()
    d = "".join(map(str, digits)).rstrip("0") or "0"
    e10 = exp + len(digits) - 1                        # the exponent of the first digit
    if 1e-3 <= abs(v) < 1e7:
        return repr(v)
    return ("-" if sign else "") + d[0] + "." + (d[1:] or "0") + "E" + str(e10)


def _n(v: int) -> str:
    return f"{v:,}"


def is_archive(name: str) -> bool:
    return name.lower().endswith(ARCHIVE_SUFFIXES)


def stem(name: str) -> str:
    """``trade.tar.gz`` -> ``trade``."""
    base = os.path.basename(name)
    for s in ARCHIVE_SUFFIXES:
        if base.lower().endswith(s):
            return base[:-len(s)]
    return base


def valid_name(n: str | None) -> bool:
    return n is not None and NAME.fullmatch(n) is not None


def is_jsonl(name: str) -> bool:
    n = name.lower()
    return any(n.endswith(j + c) for c in ("", ".gz", ".zst") for j in JSONL_SUFFIXES)


def is_nested(name: str) -> bool:
    return name.lower().endswith(NESTED_SUFFIXES)


def safe_parts(raw: str) -> list[str]:
    """The member's path as parts ('\\' read as '/', '.' parts and a leading './' dropped); refuses an unsafe name."""
    n = raw.replace("\\", "/")
    if "\0" in n or n.startswith("/") or re.match(r"[A-Za-z]:", n):
        raise ArchiveError(f"member '{raw}': an absolute path (unsafe; nothing was read)")
    parts = [p for p in n.split("/") if p and p != "."]
    if ".." in parts:
        raise ArchiveError(f"member '{raw}': a '..' in its path (unsafe; nothing was read)")
    return parts


@dataclass(frozen=True)
class Member:
    name: str                    # normalised path in the archive
    raw: str                     # as written
    dataset: str
    size: int
    day: date | None = None      # a tar member's folder date


@dataclass
class Plan:
    """What an archive holds: its datasets (name to members in name order), whether they are folders, and the notes."""
    archive: str
    archive_name: str
    kind: str                    # zip | tar
    size: int
    folders: bool
    datasets: dict
    notes: list = field(default_factory=list)

    def require(self, configured) -> None:
        """Refuses every dataset not in ``configured``, listing them all."""
        unknown = sorted((d for d in self.datasets if d not in configured))
        if unknown:
            what = "folder" if self.folders else "dataset"
            conf = ", ".join(configured) if configured else "none"
            raise ArchiveError(f"{self.archive_name}: {what}{'s ' if len(unknown) > 1 else ' '}{', '.join(unknown)} match"
                               f"{'' if len(unknown) > 1 else 'es'} no configured dataset ({conf}); nothing was loaded")

    def members(self) -> list:
        return [m for ms in self.datasets.values() for m in ms]


def _entries_zip(path: str, name: str, limits: Limits) -> list[tuple]:
    with zipfile.ZipFile(path) as z:
        infos = z.infolist()
    if len(infos) > limits.max_members:
        raise ArchiveError(f"{name}: {_n(len(infos))} entries, more than the cap of {_n(limits.max_members)} (max-members)")
    out = []
    for i in infos:
        mode = (i.external_attr >> 16) & 0xF000
        typ = "dir" if i.filename.endswith("/") else "a link" if mode == 0xA000 else "encrypted" if i.flag_bits & 1 else "file"
        out.append((i.filename, 0 if i.filename.endswith("/") else i.file_size, typ))
    return out


def _entries_tar(path: str, name: str, limits: Limits, size: int) -> list[tuple]:
    out, declared, cap = [], 0, limits.cap(size)
    with tarfile.open(path, "r:*") as t:
        for m in t:
            if len(out) >= limits.max_members:
                raise ArchiveError(f"{name}: more than the cap of {_n(limits.max_members)} entries (max-members)")
            typ = "file" if m.isfile() else "dir" if m.isdir() else "a link" if m.issym() or m.islnk() else "a device or special file"
            declared += m.size
            if declared > cap:
                break                                    # the declared total is refused below, before more is decompressed
            out.append((m.name, m.size, typ))
    if declared > cap:
        out.append(("(more)", declared - sum(e[1] for e in out), "file"))
    return out


def plan(path: str, dataset: str | None, limits: Limits, today: date) -> Plan:
    """The archive's datasets by the rule, every check that needs no reading made (Java: Archives.plan)."""
    name = os.path.basename(path)
    kind = "zip" if name.lower().endswith(".zip") and not name.lower().endswith(".tar.zip") else "tar"
    if name.lower().endswith(".tar.zip"):
        raise ArchiveError(f"{name}: a .tar.zip is read by the Java loader")
    size = os.path.getsize(path)
    try:
        entries = _entries_zip(path, name, limits) if kind == "zip" else _entries_tar(path, name, limits, size)
    except ArchiveError:
        raise
    except (OSError, zipfile.BadZipFile, tarfile.TarError, EOFError, ValueError) as e:
        raise ArchiveError(f"{name}: not a readable {kind} archive ({e})") from None
    declared = sum(e[1] for e in entries)
    if declared > limits.cap(size):
        why = (f"more than max-mb {_fmt(limits.max_bytes / 1048576.0)}" if declared > limits.max_bytes
               else f"more than max-ratio {_fmt(limits.max_ratio)} times the archive's {_n(size)} bytes")
        raise ArchiveError(f"{name}: its entries expand to {_n(declared)} bytes, {why} (a zip bomb?); nothing was read")
    if kind == "tar":
        return _tar_feed(path, name, size, entries, today)
    notes, loose, foldered, other = [], [], {}, set()
    for raw, sz, typ in entries:
        parts = safe_parts(raw)
        if typ == "dir" or not parts:
            continue
        if typ != "file":
            raise ArchiveError(f"{name}: member '{raw}' is {typ} (unsafe; nothing was read)")
        if any(p.startswith(".") or p == "__MACOSX" for p in parts):
            notes.append(f"{raw}: skipped (hidden)")
            continue
        if len(parts) > 2:
            raise ArchiveError(f"{name}: member '{raw}' is {len(parts) - 1} folders deep; folders are datasets, exactly one level deep")
        n = "/".join(parts)
        if is_nested(n):
            raise ArchiveError(f"{name}: member '{raw}' is an archive inside the archive; archives are read one level deep (unpack it first)")
        if not is_jsonl(n):
            notes.append(f"{n}: skipped (not JSON Lines)")
            if len(parts) == 2:
                other.add(parts[0])
            continue
        m = Member(n, raw, "" if len(parts) == 1 else parts[0], sz)
        if len(parts) == 1:
            loose.append(m)
        else:
            foldered.setdefault(parts[0], []).append(m)
    if loose and foldered:
        some = ", ".join(sorted((m.name for m in loose))[:3])
        raise ArchiveError(f"{name}: JSON Lines both loose ({some}) and in folders ({', '.join(sorted(foldered))}): "
                           "a mixed archive is refused; put every file in a folder (one per dataset) or none")
    if not loose and not foldered:
        raise ArchiveError(f"{name}: no JSON Lines member (*.jsonl, *.ndjson, *.json, plain or .gz/.zst)")
    for f in sorted(other):
        if f not in foldered:
            notes.append(f"{f}/: holds no JSON Lines member, not a dataset")
    if foldered:
        raise ArchiveError(f"{name}: a .zip is one table and one business date, its JSON Lines files loose; this one has folders ("
                           f"{', '.join(sorted(foldered))}): several tables or dates go in a .tar of "
                           "<table>/<YYYY-MM-DD>/ folders")
    ds = dataset if dataset and dataset.strip() else stem(name)
    members = sorted((Member(m.name, m.raw, ds, m.size) for m in loose), key=lambda m: m.name)
    return Plan(path, name, "zip", size, False, {ds: members}, notes)


def _tar_feed(path: str, name: str, size: int, entries: list, today: date) -> Plan:
    listed, by_name = [], {}
    for raw, sz, typ in entries:
        parts = safe_parts(raw)
        listed.append((raw, typ))
        if typ == "file":
            by_name["/".join(parts)] = (raw, sz)
    lay = filedate.tar_layout(listed, today)
    if lay.errors:
        n = len(lay.errors)
        raise ArchiveError(f"{name}: refused, nothing was loaded ({n} problem{'s' if n > 1 else ''}): " + "; ".join(lay.errors))
    datasets = {}
    for table in sorted(lay.tables):
        ms = []
        for day, names in sorted(lay.tables[table].items()):
            for n in names:
                raw, sz = by_name[n]
                if is_nested(n):
                    raise ArchiveError(f"{name}: member '{raw}' is an archive inside the archive; archives are read one level deep")
                ms.append(Member(n, raw, table, sz, day))
        datasets[table] = ms
    return Plan(path, name, "tar", size, True, datasets, [f"{r}: skipped (litter)" for r in lay.ignored])


class Tally:
    """One archive's running counts while it is read: the bytes it expands to, and the text of its compressed members."""

    def __init__(self, archive_name: str, cap: int):
        self.archive_name, self.cap, self.expanded, self.text = archive_name, cap, 0, 0


class Capped(io.RawIOBase):
    """A stream counted into a tally (``expanded`` or ``text``), stopped the moment the tally passes its cap."""

    def __init__(self, raw, tally: Tally, which: str, what: str):
        super().__init__()
        self.raw, self.tally, self.which, self.what = raw, tally, which, what

    def readable(self) -> bool:
        return True

    def readinto(self, b) -> int:
        data = self.raw.read(len(b))
        n = len(data)
        b[:n] = data
        if n:
            setattr(self.tally, self.which, getattr(self.tally, self.which) + n)
            if getattr(self.tally, self.which) > self.tally.cap:
                raise ArchiveError(f"{self.what} expands past its cap of {_n(self.tally.cap)} bytes (max-mb, max-ratio): a zip bomb? "
                                   "Reading stopped")
        return n
