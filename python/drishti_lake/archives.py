# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""Archives as loader input, read in place (nothing unpacked to disk): ONE rule for every loader, Java (``Archives``) and Python, in
the Java loader's words. The shared fixtures: tck/fixtures/archives (``expected.json``).

Two layouts (``plan(..., layout=...)``):

* ``feed`` (the default; the loaders' and the inbox's): a tar (``.tar``, ``.tar.gz``, ``.tgz``, ``.tar.zip``) is
  ``<table>/<YYYY-MM-DD>/<file>.jsonl`` and nothing else (``filedate.tar_layout``: every problem listed, the whole archive refused), each
  member dated by its folder; a ``.zip`` is one table and one business date, its JSON Lines files loose (folders refused).
* ``rows`` (a host's own rows files): no folders, the archive is one dataset named after the file (``trade.tar.gz`` -> ``trade``) unless
  ``dataset`` names it; folders exactly one level deep, each a dataset (a name that cannot be a table's, or that ``configured`` lacks, is
  refused, every one listed); JSON Lines both loose and in folders, a file deeper than one folder, no JSON Lines member: refused.

Members: ``*.jsonl``, ``*.ndjson``, ``*.json``, plain or ``.gz`` / ``.zst``, in name order; any other file is skipped with a note, and so
are hidden files and ``__MACOSX/``. The guards: absolute names, drive letters and ``..`` parts, links and devices, encrypted members and
archives inside the archive are refused; the caps (``Limits``: entries, the declared sizes at most ``max-mb`` and ``max-ratio`` times the
archive's size, 1 MB always allowed) are checked from the listing before a byte is decompressed, and while reading, the bytes the archive
expands to and, apart, the JSON Lines text of its compressed members are held to the same caps (``Capped``). ``Archive`` reads the members
of a plan through one handle; ``read_lines`` and ``row_counts`` walk them. ``LAKE_ARCHIVE_MAX_MB``, ``LAKE_ARCHIVE_MAX_RATIO`` and
``LAKE_ARCHIVE_MAX_MEMBERS`` (or ``lake.archive.*``) change the caps' defaults."""
from __future__ import annotations

import gzip
import io
import os
import pathlib
import re
import tarfile
import zipfile
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Iterable, Iterator

from . import filedate

FLOOR = 1 << 20
DEFAULT_MAX_MB = 102400
DEFAULT_MAX_RATIO = 200.0
DEFAULT_MAX_MEMBERS = 100000
JSONL_SUFFIXES = (".jsonl", ".ndjson", ".json")
NESTED_SUFFIXES = (".zip", ".tar", ".tgz", ".tar.gz", ".tar.zst", ".tar.bz2", ".tar.xz", ".7z", ".rar")
ARCHIVE_SUFFIXES = (".tar.gz", ".tar.zst", ".tar.zip", ".tgz", ".tar", ".zip")
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
LAYOUTS = ("feed", "rows")


class ArchiveError(ValueError):
    """An archive the rule refuses; the message says which member and why (printed after ``lakeload: `` as it is)."""


def _num(v: str | None, dflt: float) -> float:
    return dflt if v is None or not str(v).strip() else float(str(v).strip())


@dataclass(frozen=True)
class Limits:
    """The zip-bomb caps: the declared and the expanded bytes at most ``max_bytes`` and ``max_ratio`` times the archive."""
    max_bytes: int = DEFAULT_MAX_MB * 1048576
    max_ratio: float = DEFAULT_MAX_RATIO
    max_members: int = DEFAULT_MAX_MEMBERS

    @property
    def max_mb(self) -> float:
        return self.max_bytes / 1048576.0

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

    @classmethod
    def from_env(cls, env=None, max_mb: float | None = None, max_ratio: float | None = None, max_members: int | None = None) -> "Limits":
        """The defaults (``defaults(env)``), each overridden when given (None: no override); every cap more than 0."""
        d = cls.defaults(env)
        out = cls(int(max_mb * 1048576) if max_mb is not None else d.max_bytes, float(max_ratio) if max_ratio is not None else d.max_ratio,
                  int(max_members) if max_members is not None else d.max_members)
        if out.max_bytes <= 0 or out.max_ratio <= 0 or out.max_members <= 0:
            raise ValueError("the archive caps (max-mb, max-ratio, max-members) must be more than 0")
        return out

    def cap(self, archive_bytes: int) -> int:
        """The most bytes one stage may expand to: max-mb, and max-ratio times the archive's size (at least FLOOR)."""
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


def is_archive(name) -> bool:
    return str(name).lower().endswith(ARCHIVE_SUFFIXES)


def stem(name) -> str:
    """``trade.tar.gz`` -> ``trade`` (the dataset of an archive without folders)."""
    base = os.path.basename(str(name))
    for s in ARCHIVE_SUFFIXES:
        if base.lower().endswith(s):
            return base[:-len(s)]
    return base


archive_stem = stem


def valid_name(n: str | None) -> bool:
    """A dataset name that can be a table's folder: letters, digits, '.', '_' and '-', starting with a letter or digit."""
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


class Tally:
    """One archive's running counts while it is read: the bytes it expands to, and the text of its compressed members."""

    def __init__(self, archive_name: str, cap: int):
        self.archive_name, self.cap, self.expanded, self.text = archive_name, cap, 0, 0


@dataclass
class Member:
    """One JSON Lines member: its archive, its normalised name, its dataset, its declared size, its name as written and (a feed's tar)
    its folder's business date; ``tally`` is the archive's counts, shared by its members."""
    archive: pathlib.Path
    name: str
    dataset: str
    size: int
    raw: str = ""
    day: date | None = None
    tally: Tally | None = field(default=None, repr=False, compare=False)

    @property
    def date(self) -> date | None:
        return self.day

    @property
    def label(self) -> str:
        """How a reject names it: ``trade.zip!/trade/a.jsonl``."""
        return f"{self.archive.name}!/{self.name}"

    def stat(self):
        """As a file's: ``st_size`` is the member's declared (uncompressed) size."""
        import types
        return types.SimpleNamespace(st_size=self.size)

    def __str__(self) -> str:
        return f"{self.archive}!/{self.name}"


@dataclass
class Plan:
    """What an archive holds: its datasets (name to members in name order), whether they are folders, the notes, its size and kind
    (``zip``, ``tar``, ``tgz``, ``tarzip``)."""
    archive: pathlib.Path
    datasets: dict
    folders: bool
    notes: list = field(default_factory=list)
    size: int = 0
    kind: str = ""

    @property
    def archive_name(self) -> str:
        return self.archive.name

    def require(self, configured) -> None:
        """Refuses every dataset not in ``configured``, listing them all."""
        configured = list(configured)
        unknown = sorted((d for d in self.datasets if d not in configured))
        if unknown:
            what = "folder" if self.folders else "dataset"
            conf = ", ".join(configured) if configured else "none"
            raise ArchiveError(f"{self.archive_name}: {what}{'s ' if len(unknown) > 1 else ' '}{', '.join(unknown)} match"
                               f"{'' if len(unknown) > 1 else 'es'} no configured dataset ({conf}); nothing was loaded")

    def members(self) -> list:
        return [m for d in sorted(self.datasets) for m in self.datasets[d]]


def kind_of(name) -> str:
    """``zip``, ``tarzip``, ``tgz`` or ``tar``, by the name."""
    low = str(name).lower()
    if low.endswith(".tar.zip"):
        return "tarzip"
    if low.endswith(".zip"):
        return "zip"
    return "tgz" if low.endswith((".tar.gz", ".tgz")) else "tar"


def _tar_in_zip(z: zipfile.ZipFile, name: str) -> zipfile.ZipInfo:
    """The one .tar member of a .tar.zip."""
    files = [i for i in z.infolist() if not i.is_dir()]
    if len(files) != 1 or not files[0].filename.lower().endswith(".tar"):
        raise ArchiveError(f"{name}: a .tar.zip holds exactly one .tar file")
    return files[0]


def open_tar(path, kind: str):
    """(the tar, the zip holding it or None)."""
    if kind == "tarzip":
        z = zipfile.ZipFile(path)
        try:
            return tarfile.open(fileobj=z.open(_tar_in_zip(z, os.path.basename(str(path)))), mode="r:"), z
        except BaseException:
            z.close()
            raise
    return tarfile.open(path, "r:*"), None


def _entries_zip(path, name: str, limits: Limits) -> list[tuple]:
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


def _entries_tar(path, name: str, kind: str, limits: Limits, size: int) -> list[tuple]:
    out, declared, cap = [], 0, limits.cap(size)
    t, z = open_tar(path, kind)
    try:
        for m in t:
            if len(out) >= limits.max_members:
                raise ArchiveError(f"{name}: more than the cap of {_n(limits.max_members)} entries (max-members)")
            typ = "file" if m.isfile() else "dir" if m.isdir() else "a link" if m.issym() or m.islnk() else "a device or special file"
            declared += m.size
            if declared > cap:
                break                                    # the declared total is refused below, before more is decompressed
            out.append((m.name, m.size, typ))
    finally:
        t.close()
        if z:
            z.close()
    if declared > cap:
        out.append(("(more)", declared - sum(e[1] for e in out), "file"))
    return out


def plan(path, dataset: str | None = None, configured: Iterable[str] | None = None, limits: Limits | None = None, layout: str = "feed",
         today: date | None = None) -> Plan:
    """The archive's datasets by the rule (module doc), every check that needs no reading made (Java: ``Archives.plan``). ``dataset``:
    the dataset of an archive without folders (the one table of a zip); ``configured`` (when given): the datasets the loader knows;
    ``layout``: ``feed`` or ``rows``; ``today``: the latest business date a tar's folder may carry (default the business today)."""
    if layout not in LAYOUTS:
        raise ValueError(f"layout {layout}: feed or rows")
    path = pathlib.Path(path)
    name = path.name
    limits = limits or Limits.defaults()
    if not is_archive(name):
        raise ArchiveError(f"{name}: not an archive (.zip, .tar, .tar.gz, .tgz, .tar.zip)")
    kind = kind_of(name)
    size = path.stat().st_size
    try:
        entries = _entries_zip(path, name, limits) if kind == "zip" else _entries_tar(path, name, kind, limits, size)
    except ArchiveError:
        raise
    except (OSError, zipfile.BadZipFile, tarfile.TarError, EOFError, ValueError) as e:
        raise ArchiveError(f"{name}: not a readable {'zip' if kind == 'zip' else 'tar'} archive ({e})") from None
    declared = sum(e[1] for e in entries)
    if declared > limits.cap(size):
        why = (f"more than max-mb {_fmt(limits.max_bytes / 1048576.0)}" if declared > limits.max_bytes
               else f"more than max-ratio {_fmt(limits.max_ratio)} times the archive's {_n(size)} bytes")
        raise ArchiveError(f"{name}: its entries expand to {_n(declared)} bytes, {why} (a zip bomb?); nothing was read")
    tally = Tally(name, limits.cap(size))
    if kind != "zip" and layout == "feed":
        p = _tar_feed(path, size, kind, entries, today, configured, tally)
    else:
        p = _loose_or_folders(path, size, kind, entries, dataset, layout, tally)
    if configured is not None:
        p.require(configured)
    return p


def _loose_or_folders(path: pathlib.Path, size: int, kind: str, entries: list, dataset: str | None, layout: str, tally: Tally) -> Plan:
    """A zip (either layout), or a tar of the ``rows`` layout: loose JSON Lines, or one folder level of datasets."""
    name = path.name
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
        m = Member(path, n, "" if len(parts) == 1 else parts[0], sz, raw, tally=tally)
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
    if foldered and layout == "feed":
        raise ArchiveError(f"{name}: a .zip is one table and one business date, its JSON Lines files loose; this one has folders ("
                           f"{', '.join(sorted(foldered))}): several tables or dates go in a .tar of "
                           "<table>/<YYYY-MM-DD>/ folders")
    if foldered:
        if dataset and dataset.strip():
            raise ArchiveError(f"{name}: --dataset names the one dataset of an archive without folders; this one has folders "
                               f"({', '.join(sorted(foldered))}), each a dataset")
        bad = sorted(f for f in foldered if not valid_name(f))
        if bad:
            raise ArchiveError(f"{name}: folder{'s' if len(bad) > 1 else ''} {', '.join(bad)} cannot name a table "
                               "(letters, digits, '.', '_' and '-')")
        datasets = {d: sorted(ms, key=lambda m: m.name) for d, ms in sorted(foldered.items())}
        return Plan(path, datasets, True, notes, size, kind)
    ds = dataset if dataset and dataset.strip() else stem(name)
    members = sorted((Member(path, m.name, ds, m.size, m.raw, tally=tally) for m in loose), key=lambda m: m.name)
    return Plan(path, {ds: members}, False, notes, size, kind)


def _tar_feed(path: pathlib.Path, size: int, kind: str, entries: list, today, configured, tally: Tally) -> Plan:
    """A feed's tar: one dataset per table, each member dated by its folder (``filedate.tar_layout``); any problem refuses the archive."""
    name = path.name
    listed, by_name = [], {}
    for raw, sz, typ in entries:
        parts = safe_parts(raw)                       # the guards first: an unsafe member refuses the archive before anything else
        listed.append((raw, typ))
        if typ == "file":
            by_name["/".join(parts)] = (raw, sz)
    lay = filedate.tar_layout(listed, today, None if configured is None else list(configured))
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
                ms.append(Member(path, n, table, sz, raw, day, tally))
        datasets[table] = ms
    return Plan(path, datasets, True, [f"{r}: skipped (litter)" for r in lay.ignored], size, kind)


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

    def close(self):
        try:
            self.raw.close()
        finally:
            super().close()


def decompressed(raw, member_name: str):
    """A member's bytes decompressed by its name (``.gz``, ``.zst``), else as they are."""
    low = member_name.lower()
    if low.endswith(".gz"):
        return gzip.GzipFile(fileobj=raw, mode="rb")
    if low.endswith(".zst"):
        try:
            from compression import zstd              # Python 3.14
            return zstd.ZstdFile(raw)
        except ImportError:
            try:
                import zstandard                       # earlier Pythons: the zstandard package
            except ImportError:
                raise ArchiveError(f"{member_name}: a .zst member needs the zstandard package (pip install zstandard), "
                                   "or load it with the Java loader") from None
            return zstandard.ZstdDecompressor().stream_reader(raw, read_across_frames=True)
    return raw


def capped(raw, m: Member, tally: Tally):
    """A member's JSON Lines bytes held to the caps: the bytes it expands to, and apart the text of a compressed member."""
    entry = io.BufferedReader(Capped(raw, tally, "expanded", tally.archive_name), 1 << 16)
    if not m.name.lower().endswith((".gz", ".zst")):
        return entry
    return io.BufferedReader(Capped(decompressed(entry, m.name), tally, "text", f"{m.label}'s JSON Lines"), 1 << 16)


class Archive:
    """An archive open for reading its members in turn (one handle, so a .tar.gz is not decompressed once per member)."""

    def __init__(self, plan_: Plan, limits: Limits | None = None):
        self.plan = plan_
        self.tally = Tally(plan_.archive_name, (limits or Limits.defaults()).cap(plan_.size))
        self._z = self._t = self._outer = None

    def __enter__(self):
        if self.plan.kind == "zip":
            self._z = zipfile.ZipFile(self.plan.archive)
        else:
            self._t, self._outer = open_tar(self.plan.archive, self.plan.kind)
        return self

    def __exit__(self, *exc):
        (self._z or self._t).close()
        if self._outer:
            self._outer.close()

    def open(self, m: Member):
        """The member's bytes as JSON Lines (decompressed when it is .gz or .zst), held to the caps."""
        try:
            raw = self._z.open(m.raw) if self._z else self._t.extractfile(m.raw)
        except (KeyError, RuntimeError, NotImplementedError) as e:
            raise ArchiveError(f"{m.label}: cannot be read ({e})") from None
        return capped(raw, m, m.tally or self.tally)

    def lines(self, m: Member) -> Iterator[str]:
        """The member's lines as text (UTF-8, a byte-order mark dropped), newline kept off."""
        with io.TextIOWrapper(self.open(m), encoding="utf-8-sig", newline="") as fh:
            for line in fh:
                yield line.rstrip("\r\n")


def read_lines(plan_: Plan, limits: Limits | None = None) -> Iterator[tuple[str, Member, int, str]]:
    """Every line of every dataset, datasets by name and members in name order: (dataset, member, line number, text)."""
    with Archive(plan_, limits) as a:
        for d in sorted(plan_.datasets):
            for m in plan_.datasets[d]:
                for n, line in enumerate(a.lines(m), 1):
                    yield d, m, n, line


def row_counts(plan_: Plan, limits: Limits | None = None) -> dict[str, int]:
    """Non-blank lines per dataset (what a loader reads as rows)."""
    out = {d: 0 for d in plan_.datasets}
    for d, _m, _n, line in read_lines(plan_, limits):
        if line.strip():
            out[d] += 1
    return out
