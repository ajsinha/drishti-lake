# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The input of a load (the Java loader's ``Inputs`` and ``Archives``): ``source.files`` resolved to data files in path order, each read
as lines whatever its container.

A pattern is a file, a folder (its data files; ``recursive`` for subfolders) or a glob (``*``, ``?``, ``**``). Plain JSON Lines
(``.jsonl``, ``.ndjson``, ``.json``), gzip (every member), zstd, a zip (its JSON Lines files loose, in name order, dated by the
archive's name) and a tar (``<table>/<YYYY-MM-DD>/<file>.jsonl``, every member dated by its folder); both under the archive rule and
its guards and caps (``archives``). Local files only: an object store's input is the Java loader's."""
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

from . import archives, filedate

SUFFIXES = (".jsonl", ".ndjson", ".json", ".gz", ".zst", ".zip", ".tgz", ".tar")


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
    tally: object = field(default=None, compare=False, hash=False)    # the archive's running counts (archives.Tally) while read

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


def resolve(patterns: list[str], recursive: bool, today: date, limits: archives.Limits | None = None, dataset: str | None = None,
            table: str | None = None, as_of: date | None = None, notes: list | None = None) -> list[InputFile]:
    """The data files of ``source.files``, in path order; an archive expanded to its members (module doc). ``limits``: the caps
    (``source.archive``); ``dataset``: a folderless archive's dataset, or the one tar table of this load; ``table``: the load's table
    (a tar's other tables refused); ``notes``: the archives' notes are added to it."""
    limits = limits or archives.Limits.defaults()
    paths = []
    for pattern in patterns:
        if "://" in pattern and not pattern.startswith("file:"):
            raise InputError(f"source.files {pattern}: the Python path reads local files only (load object-store input with the Java loader)")
        found = _local(pattern, recursive)
        if not found:
            raise InputError(f"source.files {pattern}: no input file matches")
        paths += found
    out, plans = [], []
    for p in sorted(paths):
        name = os.path.basename(p)
        if archives.is_archive(name):
            plan = archives.plan(p, dataset, limits, today)
            if notes is not None:
                notes += [f"{plan.archive_name}: {n}" for n in plan.notes]
            plans.append(plan)
        else:
            out.append(InputFile(p, name))
    for plan in plans:
        if plan.kind != "zip" and as_of is not None:
            raise archives.ArchiveError(f"{plan.archive_name}: a .tar archive names its own tables and dates (its <table>/<YYYY-MM-DD>/ "
                                        "folders): source.as-of is refused for it")
    for plan in plans:
        tally = archives.Tally(plan.archive_name, limits.cap(plan.size))
        if plan.folders and dataset and dataset.strip():                 # a tar's one table this load is for (the others: other loads)
            members = plan.datasets.get(dataset, [])
        else:
            if plan.folders and table is not None:
                plan.require([table])
            members = plan.members()
        out += [InputFile(plan.archive, m.name.rsplit("/", 1)[-1], plan.archive, m.raw, m.day, m.dataset if plan.folders else None, tally)
                for m in members]
    return sorted(out, key=lambda f: f.path)


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


def _member(raw, f: InputFile):
    """A member's JSON Lines held to the archive's caps: the bytes it expands to, and apart the text of a compressed member."""
    if f.tally is None:
        return _decompress(raw, f.member)
    entry = io.BufferedReader(archives.Capped(raw, f.tally, "expanded", f.tally.archive_name))
    low = f.member.lower()
    if low.endswith((".gz", ".zst")):
        what = f"{f.tally.archive_name}!/{archives.safe_parts(f.member) and '/'.join(archives.safe_parts(f.member))}'s JSON Lines"
        return io.BufferedReader(archives.Capped(_decompress(entry, f.member), f.tally, "text", what))
    return entry


def lines(f: InputFile):
    """The lines of one data file, decoded as UTF-8 (a bad byte replaced, as the Java reader does), without their line ends; blank
    lines skipped. An archive's member is held to its caps while it is read (``archives.ArchiveError``)."""
    if f.archive and f.archive.lower().endswith(".zip"):
        with zipfile.ZipFile(f.archive) as z, z.open(f.member) as raw:
            yield from _text(_member(raw, f))
    elif f.archive:
        with tarfile.open(f.archive, "r:*") as t:
            raw = t.extractfile(f.member)
            yield from _text(_member(raw, f))
    else:
        with open(f.path, "rb") as raw:
            yield from _text(_decompress(raw, f.name))


def _text(stream):
    for b in io.TextIOWrapper(stream, encoding="utf-8", errors="replace", newline=None):
        s = b.rstrip("\n").rstrip("\r")
        if s.strip():
            yield s
