# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The input of a load (the Java loader's ``Inputs`` and ``Archives``): ``source.files`` resolved to data files in path order, each read
as lines whatever its container.

A pattern is a file, a folder (its data files; ``recursive`` for subfolders) or a glob (``*``, ``?``, ``**``). Plain JSON Lines
(``.jsonl``, ``.ndjson``, ``.json``), gzip (every member), zstd, a zip (its JSON Lines files loose, in name order, dated by the
archive's name) and a tar (``<table>/<YYYY-MM-DD>/<file>.jsonl``, every member dated by its folder); both under the archive rule and
its guards and caps (``archives``). A ``.json`` file or member whose text starts with ``[`` is a JSON array: its elements are the rows
(``json_array``). Local files only: an object store's input is the Java loader's."""
from __future__ import annotations

import io
import json
import os
import pathlib
import re
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
            plan = archives.plan(p, dataset=dataset, limits=limits, today=today)
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
        if plan.folders and dataset and dataset.strip():                 # a tar's one table this load is for (the others: other loads)
            members = plan.datasets.get(dataset, [])
        else:
            if plan.folders and table is not None:
                plan.require([table])
            members = plan.members()
        out += [InputFile(str(plan.archive), m.name.rsplit("/", 1)[-1], str(plan.archive), m.raw, m.day, m.dataset if plan.folders else None,
                          m.tally) for m in members]
    return sorted(out, key=lambda f: f.path)


def _member(raw, f: InputFile):
    """A member's JSON Lines held to the archive's caps (``archives.capped``)."""
    if f.tally is None:
        return archives.decompressed(raw, f.member)
    m = archives.Member(pathlib.Path(f.archive), "/".join(archives.safe_parts(f.member)), f.folder_table or "", 0, f.member)
    return archives.capped(raw, m, f.tally)


def lines(f: InputFile):
    """The lines of one data file, decoded as UTF-8 (a bad byte replaced, as the Java reader does), without their line ends; blank
    lines skipped. An archive's member is held to its caps while it is read (``archives.ArchiveError``). A JSON array (``json_array``)
    gives its elements as the lines."""
    if f.archive and archives.kind_of(f.archive) == "zip":
        with zipfile.ZipFile(f.archive) as z, z.open(f.member) as raw:
            yield from _rows(_member(raw, f), f.member)
    elif f.archive:
        t, outer = archives.open_tar(f.archive, archives.kind_of(f.archive))
        try:
            yield from _rows(_member(t.extractfile(f.member), f), f.member)
        finally:
            t.close()
            if outer is not None:
                outer.close()
    else:
        with open(f.path, "rb") as raw:
            yield from _rows(archives.decompressed(raw, f.name), f.name)


def is_json_named(name: str) -> bool:
    """Whether a file or member may be a JSON array: ``.json``, plain or ``.gz`` / ``.zst`` (Java: JsonArrays.named)."""
    return name.lower().endswith((".json", ".json.gz", ".json.zst"))


def _rows(stream, name: str):
    """JSON Lines, or a JSON array's elements (``json_array``) when ``name`` may be one and the text starts with ``[``."""
    if not is_json_named(name):
        yield from _text(stream)
        return
    buffered = stream if isinstance(stream, io.BufferedReader) else io.BufferedReader(stream, 1 << 16)
    head = buffered.peek(4096)[:4096]
    if head.startswith(b"\xef\xbb\xbf"):
        head = head[3:]
    if head.lstrip(b" \t\r\n")[:1] == b"[":
        yield from json_array(io.TextIOWrapper(buffered, encoding="utf-8-sig", errors="replace", newline=""), os.path.basename(name))
    else:
        yield from _text(buffered)


def json_array(text, name: str, chunk: int = 1 << 16):
    """A JSON array's elements as JSON Lines lines (each compact, one line), read in chunks (never the whole file in memory): the Java
    loader's ``JsonArrays``. An array that breaks off, or text after its closing ``]``, fails the load naming the file and the element."""
    dec = json.JSONDecoder()
    buf, at, n, started, eof = "", 0, 0, False, False

    def more():
        nonlocal buf, at, eof
        got = text.read(chunk)
        if not got:
            eof = True
        buf = buf[at:] + got
        at = 0

    def skip():
        nonlocal at
        while True:
            while at < len(buf) and buf[at] in " \t\r\n":
                at += 1
            if at < len(buf) or eof:
                return
            more()

    more()
    skip()
    if buf[at:at + 1] != "[":
        raise InputError(f"{name}: not a JSON array")
    at += 1
    while True:
        skip()
        if at >= len(buf):
            raise InputError(f"{name}: the JSON array ends without its closing ] after element {n}: nothing was loaded")
        if buf[at] == "]":
            at += 1
            skip()
            if at < len(buf):
                raise InputError(f"{name}: text after the JSON array's closing ]: nothing was loaded")
            return
        if started:
            if buf[at] != ",":
                raise InputError(f"{name}: element {n + 1} of the JSON array is not JSON (a ',' or ']' expected): nothing was loaded")
            at += 1
            skip()
        while True:
            try:
                value, end = dec.raw_decode(buf, at)
                if end >= len(buf) and not eof:           # a number may go on in the next chunk
                    raise ValueError("more")
                break
            except ValueError as e:
                if eof:
                    raise InputError(f"{name}: element {n + 1} of the JSON array is not JSON ({e}): nothing was loaded") from None
                more()
                skip()
        at = end
        n += 1
        started = True
        yield json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _text(stream):
    for b in io.TextIOWrapper(stream, encoding="utf-8", errors="replace", newline=None):
        s = b.rstrip("\n").rstrip("\r")
        if s.strip():
            yield s
