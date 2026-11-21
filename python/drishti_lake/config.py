# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""A load's configuration: the Java loader's file (``LoadConfig``), read the same way, so one file drives either path.

The keys are the Java loader's (an unknown key is refused in its words); ``--set key.path=value`` overrides any key (the value read as
YAML); ``${NAME}`` in a value is the environment variable. What the Python path does not do yet is refused before a row is read, naming
the Java loader: a lake named by its lake file, a format other than Delta, an object-store table, ``datasets:``, ``source.format`` other
than documents, a pack."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from datetime import date

import yaml

KEYS = {
    "": {"source", "target", "parallel", "memory", "rejects", "report", "lock", "datasets"},
    "source": {"files", "recursive", "storage-options", "format", "id", "date", "as-of", "date-from", "promote", "vectors", "vector", "flatten",
               "partition-by", "pack", "connector", "latest-by", "dataset", "archive"},
    "source.archive": {"max-mb", "max-ratio", "max-members", "validate"},
    "target": {"format", "path", "lake", "domain", "kind", "table", "storage-options", "mode", "properties", "iceberg", "layout", "batch-id",
               "default-mode"},
    "target.layout": {"sort-by", "file-size-mb", "row-group-mb", "compression", "vector-compression", "byte-stream-split", "shard-aligned"},
    "parallel": {"readers", "parsers", "writers", "queue-mb", "split-mb"},
    "memory": {"budget-mb", "sort-buffer-mb", "spill-dir", "check-spill-space"},
    "rejects": {"file", "max-pct", "keep"},
    "report": {"summary", "manifest", "progress", "run-log", "dry-run"},
}
DEFAULT_LATEST_BY = "COMPUTED_AT"
DEFAULT_SORT = ["LEGAL_ENTITY", "DESK", "id"]
JAVA = "load it with the Java loader (lake-cli load)"
_REF = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_.]*)\}")


class ConfigError(ValueError):
    """A configuration refused (the words the Java loader prints after ``lakeload: the configuration: ``)."""


@dataclass
class LoadConfig:
    files: list
    recursive: bool
    key: str
    date: str | None
    as_of: date | None
    date_from: str
    promote: list
    vectors: list
    flatten: dict
    partition_by: list
    latest_by: str
    fmt: str
    table_path: str
    lake_root: str
    domain: str
    table: str
    mode: str                  # append | replace-date
    mode_from: str
    batch_id: str | None
    max_pct: float
    keep: int
    summary: str | None
    progress: str | None
    run_log: bool
    dry_run: bool
    lock: bool
    sort_by: list = field(default_factory=lambda: list(DEFAULT_SORT))
    loader_rows_of: str | None = None


def _check(section: str, n):
    if n is None:
        return
    if not isinstance(n, dict):
        raise ConfigError(f"{section or 'the file'}: a mapping of keys")
    known = KEYS[section]
    for k in n:
        if k not in known:
            raise ConfigError(f"{section + '.' if section else ''}{k} is not a key of the loader ({', '.join(sorted(known))}); "
                              "docs/guides/CLI_REFERENCE.md section 4.13 lists them")


def _env(v):
    if isinstance(v, str):
        return _REF.sub(lambda m: os.environ.get(m.group(1), m.group(0)), v)
    return v


def _text(n, key, default=None):
    v = (n or {}).get(key)
    if v is None:
        return default
    v = _env(v if isinstance(v, str) else str(v).lower() if isinstance(v, bool) else str(v))
    return v.strip() if v.strip() else default


def _list(n, key) -> list:
    v = (n or {}).get(key)
    if v is None:
        return []
    if isinstance(v, list):
        return [str(_env(x)).strip() for x in v if x is not None and str(x).strip()]
    return [x.strip() for x in str(_env(v)).split(",") if x.strip()]


def _bool(n, key, default: bool) -> bool:
    v = (n or {}).get(key)
    if v is None:
        return default
    return v if isinstance(v, bool) else str(v).strip().lower() == "true"


def set_value(tree: dict, kv: str):
    """``a.b.c=value``: the value read as YAML (``[A, B]`` is a list, ``8`` a number)."""
    key, eq, value = kv.partition("=")
    if not eq or not key.strip():
        raise ConfigError(f"--set {kv}: give KEY.PATH=VALUE")
    path = key.strip().split(".")
    v = None if value == "" else yaml.safe_load("v: " + value)["v"]
    at = tree
    for p in path[:-1]:
        if not isinstance(at.get(p), dict):
            at[p] = {}
        at = at[p]
    at[path[-1]] = v


def read(file: str | None, sets: list[str]) -> LoadConfig:
    """The configuration of ``file`` (YAML or JSON) with ``sets`` applied, checked (module doc)."""
    tree = {}
    if file is not None:
        with open(file, encoding="utf-8") as f:
            tree = yaml.safe_load(f) or {}
        if not isinstance(tree, dict):
            raise ConfigError(f"{file}: a load configuration is a mapping (source:, target:, ...)")
    for s in sets:
        set_value(tree, s)
    return of(tree)


def of(tree: dict) -> LoadConfig:
    _check("", tree)
    for sec in ("source", "target", "parallel", "memory", "rejects", "report"):
        _check(sec, tree.get(sec))
    s, t = tree.get("source") or {}, tree.get("target") or {}
    _check("source.archive", s.get("archive"))
    _check("target.layout", t.get("layout"))
    if tree.get("datasets") is not None:
        raise ConfigError(f"datasets: the Python path loads one table a run; {JAVA}")
    sf = _text(s, "format", "documents").lower()
    if sf not in ("documents", "jsonl", "loader-rows"):
        raise ConfigError(f"source.format {sf}: documents (one entity a line) or loader-rows ({{domain, kind, id, date, doc, columns}} a line)")
    loader_rows_of = None
    if sf == "loader-rows":
        if _text(t, "domain") is None or _text(t, "kind") is None:
            raise ConfigError("source.format loader-rows: give target.domain and target.kind (the table the rows must name)")
        loader_rows_of = f"{_text(t, 'domain')}/{_text(t, 'kind')}"
    if _text(s, "pack") is not None:
        raise ConfigError(f"source.pack: a pack is read by the Java loader; {JAVA}")
    files = _list(s, "files")
    if not files:
        raise ConfigError("source.files: name the input (a file, a folder or a glob)")
    as_of_text = _text(s, "as-of")
    try:
        as_of = date.fromisoformat(as_of_text) if as_of_text else None
    except ValueError:
        raise ConfigError(f"source.as-of {as_of_text}: not YYYY-MM-DD") from None
    date_from = _text(s, "date-from", "auto").lower()
    if date_from not in ("auto", "name"):
        raise ConfigError(f"source.date-from {date_from}: auto or name")
    fmt = _text(t, "format", "delta").lower()
    if fmt != "delta":
        raise ConfigError(f"target.format {fmt}: the Python path writes Delta tables; {JAVA}")
    lake, path = _text(t, "lake"), _text(t, "path")
    if path is None and lake is not None and "/" not in lake and "\\" not in lake:
        raise ConfigError(f"target.lake {lake}: a lake named by its lake file is read by the Java loader; {JAVA}")
    if path is None and (lake is None or _text(t, "domain") is None or (_text(t, "kind") is None and _text(t, "table") is None)):
        raise ConfigError("target: give path, or lake, domain and kind (or table)")
    table_path = _trim(path) if path else _trim(lake) + "/" + _text(t, "domain") + "/" + (_text(t, "table") or _text(t, "kind"))
    if "://" in table_path and not table_path.startswith("file:"):
        raise ConfigError(f"target {table_path}: the Python path writes local tables; {JAVA}")
    a = table_path.rfind("/")
    b = table_path.rfind("/", 0, a) if a > 0 else -1
    table = table_path[a + 1:]
    domain = _text(t, "domain") or (table_path[b + 1:a] if b >= 0 else "")
    lake_root = _trim(lake) if lake and not path else (table_path[:b] if b > 0 else table_path)
    given, fallback = _text(t, "mode"), _text(t, "default-mode")
    mode_from = "given" if given else "default" if fallback else "rules"
    mode = (given or fallback or "append").lower()
    mode = "replace-date" if mode in ("overwrite-dates", "replace") else mode
    if mode not in ("replace-date", "append"):
        raise ConfigError(f"target.mode {mode}: append (the default: each business date's rows are added, one commit per date) or "
                          "replace-date (each business date of the input replaced by one commit; also replace, overwrite-dates)")
    vectors = _list(s, "vectors") + _list(s, "vector")
    flatten = {str(k): str(_env(v)) for k, v in (s.get("flatten") or {}).items()}
    rj, rp, lay = tree.get("rejects") or {}, tree.get("report") or {}, t.get("layout") or {}
    return LoadConfig(
        files=files, recursive=_bool(s, "recursive", False), key=_text(s, "id", "id"), date=_text(s, "date"), as_of=as_of,
        date_from=date_from, promote=_list(s, "promote"), vectors=vectors, flatten=flatten, partition_by=_list(s, "partition-by"),
        latest_by=_text(s, "latest-by", DEFAULT_LATEST_BY), fmt=fmt, table_path=table_path, lake_root=lake_root, domain=domain, table=table,
        mode=mode, mode_from=mode_from, batch_id=_text(t, "batch-id"), max_pct=float(rj.get("max-pct", 100.0)), keep=int(rj.get("keep", 50)),
        summary=_text(rp, "summary"), progress=_text(rp, "progress"), run_log=_bool(rp, "run-log", True), dry_run=_bool(rp, "dry-run", False),
        lock=_bool(tree, "lock", True), sort_by=_list(lay, "sort-by") or list(DEFAULT_SORT),
        loader_rows_of=loader_rows_of)


def _trim(p: str) -> str:
    s = p.replace("\\", "/")
    return s[:-1] if len(s) > 1 and s.endswith("/") else s
