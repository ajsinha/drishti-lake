# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The input of a load (the Java loader's ``Inputs`` and ``Archives``): ``source.files`` resolved to data files in path order, each read
as lines whatever its container.

A pattern is a file, a folder (its data files; ``recursive`` for subfolders) or a glob (``*``, ``?``, ``**``). Plain JSON Lines
(``.jsonl``, ``.ndjson``, ``.json``), gzip (every member), zstd, a zip (its JSON Lines members, in name order, dated by the archive's
name) and a tar (``<table>/<YYYY-MM-DD>/<file>.jsonl``, every member dated by its folder; a bad layout refuses the archive with every
problem listed). Local files only: an object store's input is the Java loader's."""
from __future__ import annotations

import gzip
import io
import os
import pathlib
import re
import tarfile
import zipfile
from dataclasses import dataclass
from datetime import date

from . import filedate

SUFFIXES = (".jsonl", ".ndjson", ".json", ".gz", ".zst", ".zip", ".tgz", ".tar")
_JSONL = re.compile(r"\.(jsonl|ndjson|json)(\.gz|\.zst)?$", re.IGNORECASE)


class InputError(Exception):
    """An input refused before a row is read (the words the Java loader prints after ``lakeload: ``)."""


@dataclass(frozen=True)
class InputFile:
    """One data file of a load: its path, its label (``_source_file``: the file's name, a member's name), the archive it is a member of
    (None: none), a tar member's folder date and table."""
    path: str
    name: str
    archive: str | None = None
    member: str | None = None
    folder_date: date | None = None
    folder_table: str | None = None

    @property
    def top(self) -> str:
        """The file a load id is given to: the archive for its members."""
        return self.archive or self.path

    @property
    def dated_name(self) -> str:
        """The name the date is read from: the archive's for a member."""
        return os.path.basename(self.archive) if self.archive else self.name


def _wanted(name: str) -> bool:
    return name.lower().endswith(SUFFIXES)


def _glob_regex(rest: str) -> re.Pattern:
    out, i = [], 0
    while i < len(rest):
        c = rest[i]
        if rest.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
            continue
        if rest.startswith("**", i):
            out.append(".*")
            i += 2
            continue
        out.append("[^/]*" if c == "*" else "[^/]" if c == "?" else re.escape(c))
        i += 1
    return re.compile("".join(out) + r"\Z")


def _local(pattern: str, recursive: bool) -> list[str]:
    p = pattern[len("file:"):] if pattern.startswith("file:") else pattern
    slashed = p.replace("\\", "/")
    first = next((i for i, ch in enumerate(slashed) if ch in "*?["), -1)
    if first < 0:
        path = pathlib.Path(p)
        if path.is_file():
            return [str(path.absolute())]
        if path.is_dir():
            walk = path.rglob("*") if recursive else path.iterdir()
            return sorted(str(f.absolute()) for f in walk if f.is_file() and _wanted(f.name))
        raise InputError(f"source.files {pattern}: no such file or folder")
    base = slashed.rfind("/", 0, first)
    folder = pathlib.Path("." if base < 0 else "/" if base == 0 else p[:base])
    rest = slashed[base + 1:]
    glob = _glob_regex(rest)
    deep = recursive or "/" in rest or "**" in rest
    if not folder.is_dir():
        raise InputError(f"source.files {pattern}: no such folder {folder}")
    walk = folder.rglob("*") if deep else folder.iterdir()
    return sorted(str(f.absolute()) for f in walk if f.is_file() and glob.match(f.relative_to(folder).as_posix()))


def resolve(patterns: list[str], recursive: bool, today: date) -> list[InputFile]:
    """The data files of ``source.files``, in path order; an archive expanded to its members (module doc)."""
    paths = []
    for pattern in patterns:
        if "://" in pattern and not pattern.startswith("file:"):
            raise InputError(f"source.files {pattern}: the Python path reads local files only (load object-store input with the Java loader)")
        found = _local(pattern, recursive)
        if not found:
            raise InputError(f"source.files {pattern}: no input file matches")
        paths += found
    out = []
    for p in sorted(paths):
        name = os.path.basename(p)
        low = name.lower()
        if filedate.is_tar(name):
            out += _tar(p, name, today)
        elif low.endswith(".zip") and zipfile.is_zipfile(p):
            out += _zip(p, name)
        else:
            out.append(InputFile(p, name))
    return out


def _tar(path: str, name: str, today: date) -> list[InputFile]:
    if name.lower().endswith(".tar.zip"):
        raise InputError(f"{name}: a .tar.zip is read by the Java loader")
    with tarfile.open(path, "r:*") as t:
        entries = [(m.name, "file" if m.isfile() else "dir" if m.isdir() else "link" if m.issym() or m.islnk() else "other") for m in t]
    lay = filedate.tar_layout(entries, today)
    if lay.errors:
        n = len(lay.errors)
        raise InputError(f"{name}: refused, nothing was loaded ({n} problem{'s' if n > 1 else ''}): " + "; ".join(lay.errors))
    out = []
    for table, days in lay.tables.items():
        for day, members in sorted(days.items()):
            for m in members:
                out.append(InputFile(path, m.rsplit("/", 1)[-1], path, m, day, table))
    return out


def _zip(path: str, name: str) -> list[InputFile]:
    with zipfile.ZipFile(path) as z:
        members = sorted(i.filename for i in z.infolist() if not i.is_dir() and _JSONL.search(i.filename)
                         and not any(part.startswith(".") or part in filedate.LITTER for part in i.filename.split("/")))
    return [InputFile(path, m.rsplit("/", 1)[-1], path, m) for m in members]


def _decompress(raw: io.BufferedIOBase, name: str):
    low = name.lower()
    if low.endswith(".gz"):
        return gzip.GzipFile(fileobj=raw)
    if low.endswith(".zst"):
        try:
            from compression import zstd              # Python 3.14
            return zstd.ZstdFile(raw)
        except ImportError:
            import zstandard                           # earlier Pythons: the zstandard package
            return zstandard.ZstdDecompressor().stream_reader(raw, read_across_frames=True)
    return raw


def lines(f: InputFile):
    """The lines of one data file, decoded as UTF-8 (a bad byte replaced, as the Java reader does), without their line ends; blank
    lines skipped."""
    if f.archive and f.archive.lower().endswith(".zip"):
        with zipfile.ZipFile(f.archive) as z, z.open(f.member) as raw:
            yield from _text(_decompress(raw, f.member))
    elif f.archive:
        with tarfile.open(f.archive, "r:*") as t:
            raw = t.extractfile(f.member)
            yield from _text(_decompress(raw, f.member))
    else:
        with open(f.path, "rb") as raw:
            yield from _text(_decompress(raw, f.name))


def _text(stream):
    for b in io.TextIOWrapper(stream, encoding="utf-8", errors="replace", newline=None):
        s = b.rstrip("\n").rstrip("\r")
        if s.strip():
            yield s
