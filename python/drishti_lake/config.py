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
    "": {"source", "target", "parallel", "memory", "rejects", "report", "lock", "datasets", "guard"},
    "guard": {"future-days", "zone", "max-drop-share", "force-drop"},
    "source": {"files", "recursive", "storage-options", "format", "id", "date", "as-of", "date-from", "promote", "vectors", "vector", "flatten",
               "partition-by", "pack", "connector", "latest-by", "dataset", "archive"},
    "source.archive": {"max-mb", "max-ratio", "max-members", "validate"},
    "target": {"format", "path", "lake", "domain", "kind", "table", "storage-options", "mode", "properties", "iceberg", "layout", "batch-id",
               "default-mode", "contract"},
    "target.layout": {"sort-by", "file-size-mb", "row-group-mb", "compression", "vector-compression", "byte-stream-split", "shard-aligned",
                      "vector-type"},
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
    float32: bool = False
    contract: object = None    # contracts.Contract (target.contract): every row checked against it before the first write
    limits: object = None      # archives.Limits (source.archive, else lake.archive.*)
    dataset: str | None = None
    future_days: int = 0       # guard.future-days: a business date up to today plus this many days (the date guard)
    zone: str | None = None    # guard.zone: the business zone whose today it is (default lake.business.zone)
    max_drop_share: float | None = None   # guard.max-drop-share: the drop guard (off when None)
    force_drop: bool = False   # guard.force-drop


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
    for sec in ("source", "target", "parallel", "memory", "rejects", "report", "guard"):
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
    lake, path = _text(t, "lake"), _text(t, "path")
    e = _entry(t, lake) if lake is not None and is_lake_name(lake) else None
    key, day, latest = _text(s, "id"), _text(s, "date"), _text(s, "latest-by")
    promote, vectors = _list(s, "promote"), _list(s, "vectors") + _list(s, "vector")
    flatten = {str(k): str(_env(v)) for k, v in (s.get("flatten") or {}).items()}
    partition_by, sort_by = _list(s, "partition-by"), _list(t.get("layout") or {}, "sort-by")
    fmt = _text(t, "format", e["format"] if e else "delta").lower()
    if e:                                             # the lake file's entry: everything of the table from there, nothing contradicted here
        day = _agree(e, "date", day, e.get("date"))
        if "partition-by" in s:
            _agree(e, "partition-by", partition_by, e.get("partition-by"))
        partition_by = list(e.get("partition-by") or [])
        promote = _agree(e, "promote", promote, e.get("promote")) if "promote" in s else list(e.get("promote") or [])
        vectors = _agree(e, "vectors", vectors, e.get("vectors")) if "vectors" in s or "vector" in s else list(e.get("vectors") or [])
        if "flatten" in s:
            _agree(e, "flatten", flatten, e.get("flatten") or {})
        flatten = dict(e.get("flatten") or {})
        key = _agree(e, "id", key, e.get("id") or key) if key else e.get("id")
        latest = _agree(e, "latest-by", latest, e.get("latest-by") or latest) if latest else e.get("latest-by")
        _agree(e, "format", fmt, e["format"])
        if sort_by:
            _agree(e, "sort-by", sort_by, e.get("sort-by") or [])
        sort_by = sort_by or list(e.get("sort-by") or [])
        if path is not None:
            _agree(e, "path", path, e["location"])
        if e.get("properties") or (e.get("maintenance") or {}).get("checkpoint-interval"):
            raise ConfigError(f"target.lake {lake}: table {e['name']} sets table properties; {JAVA}")
    if fmt != "delta":
        raise ConfigError(f"target.format {fmt}: the Python path writes Delta tables; {JAVA}")
    if e is None and path is None and (lake is None or _text(t, "domain") is None or (_text(t, "kind") is None and _text(t, "table") is None)):
        raise ConfigError("target: give path, or lake, domain and kind (or table)")
    if e:
        table_path = _trim(e["location"])
    else:
        table_path = _trim(path) if path else _trim(lake) + "/" + _text(t, "domain") + "/" + (_text(t, "table") or _text(t, "kind"))
    if "://" in table_path and not table_path.startswith("file:"):
        raise ConfigError(f"target {table_path}: the Python path writes local tables; {JAVA}")
    a = table_path.rfind("/")
    b = table_path.rfind("/", 0, a) if a > 0 else -1
    table = table_path[a + 1:]
    domain = _text(t, "domain") or (e["domain"] if e else None) or (table_path[b + 1:a] if b >= 0 else "")
    lake_root = _trim(e["root"]) if e else _trim(lake) if lake and not path else (table_path[:b] if b > 0 else table_path)
    given, fallback = _text(t, "mode"), _text(t, "default-mode")
    mode_from = "given" if given else "lake" if e else "default" if fallback else "rules"
    lake_mode = None if e is None else "append" if str(e.get("load-mode") or "").lower() == "append" else "replace-date"
    mode = (given or lake_mode or fallback or "append").lower()
    mode = "replace-date" if mode in ("overwrite-dates", "replace") else mode
    if mode not in ("replace-date", "append"):
        raise ConfigError(f"target.mode {mode}: append (the default: each business date's rows are added, one commit per date) or "
                          "replace-date (each business date of the input replaced by one commit; also replace, overwrite-dates)")
    rj, rp, lay = tree.get("rejects") or {}, tree.get("report") or {}, t.get("layout") or {}
    return LoadConfig(
        files=files, recursive=_bool(s, "recursive", False), key=key or "id", date=day, as_of=as_of,
        date_from=date_from, promote=promote, vectors=vectors, flatten=flatten, partition_by=partition_by,
        latest_by=(latest or DEFAULT_LATEST_BY).strip(), fmt=fmt, table_path=table_path, lake_root=lake_root, domain=domain, table=table,
        mode=mode, mode_from=mode_from, batch_id=_text(t, "batch-id"), max_pct=float(rj.get("max-pct", 100.0)), keep=int(rj.get("keep", 50)),
        summary=_text(rp, "summary"), progress=_text(rp, "progress"), run_log=_bool(rp, "run-log", True), dry_run=_bool(rp, "dry-run", False),
        lock=_bool(tree, "lock", True), sort_by=sort_by or list(DEFAULT_SORT),
        loader_rows_of=loader_rows_of, float32=_vector_type(_text(lay, "vector-type", "double")), contract=_contract(_text(t, "contract")),
        limits=_limits(s.get("archive") or {}), dataset=_dataset(_text(s, "dataset")), **_guard(tree.get("guard") or {}))


def _guard(g: dict) -> dict:
    """``guard:`` (Java: LoadConfig.guard): the date guard (``future-days``, ``zone``) and the drop guard (``max-drop-share``,
    ``force-drop``), in the Java loader's words."""
    text = _text(g, "future-days", "0")
    try:
        future = int(text)
    except ValueError:
        raise ConfigError(f"guard.future-days {text}: a whole number of days, 0 or more") from None
    if future < 0:
        raise ConfigError(f"guard.future-days {future}: 0 or more (0: no business date after today)")
    zone = _text(g, "zone")
    if zone is not None:
        from . import filedate
        try:
            filedate.business_zone(zone)
        except ValueError:
            raise ConfigError(f"guard.zone {zone}: not a time zone (America/New_York, Europe/London, UTC)") from None
    share_text = _text(g, "max-drop-share")
    share = None
    if share_text is not None:
        try:
            share = float(share_text)
        except ValueError:
            share = float("nan")
        if not 0 <= share <= 1:
            raise ConfigError(f"guard.max-drop-share {share_text}: a share between 0 and 1 (0.5: half a date's rows)")
    return {"future_days": future, "zone": zone, "max_drop_share": share, "force_drop": _bool(g, "force-drop", False)}


_LAKE_NAME = re.compile(r"[a-z0-9][a-z0-9_-]*")


def is_lake_name(ref: str) -> bool:
    """Whether ``target.lake`` names a lake by its lake file (a plain name) rather than a folder or a URL (Java: LoadConfig.isLakeName)."""
    return _LAKE_NAME.fullmatch(ref) is not None and not os.path.isdir(ref)


def _entry(t: dict, lake: str) -> dict:
    """The lake file's entry for ``target.kind`` (the Java reader's answer, through the product's class path: ``java.lake_table``)."""
    from . import java
    kind = _text(t, "kind")
    if kind is None:
        raise ConfigError(f"target.kind: name the kind whose table of lake {lake} this load writes")
    try:
        return java.lake_table(lake, kind, _text(t, "domain"), _text(t, "table"),
                               os.environ.get("LAKE_LAKES_DIR") or os.environ.get("lake.lakes.dir"))
    except java.JavaError as e:
        raise ConfigError(f"target.lake {lake}: a lake named by its lake file is read through the Java tools: {e}") from None
    except ValueError as e:
        raise ConfigError(str(e)) from None


def _java_text(v) -> str:
    """A value as Java's toString writes it in the refusal (a list [a, b], a map {k=v})."""
    if isinstance(v, list):
        return "[" + ", ".join(_java_text(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{" + ", ".join(f"{k}={_java_text(x)}" for k, x in v.items()) + "}"
    return str(v)


def _agree(e: dict, key: str, mine, lakes):
    """A value of the load file that the lake file also gives: the same, else refused naming both (Java: LoadConfig.agree)."""
    if mine is not None and lakes is not None and mine != lakes:
        raise ConfigError(f"{key} {_java_text(mine)} differs from {_java_text(lakes)}, the {key} of table {e['name']} in its lake file: the lake "
                          f"file decides (docs/guides/DATA_LAKE_GUIDE.md); remove {key} from the load or correct the lake file")
    return mine if mine is not None else lakes


def _mb(v, key: str) -> int:
    mb = float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else float(str(_env(str(v))))
    if mb <= 0:
        raise ConfigError(f"{key} must be more than 0")
    return int(mb * 1024 * 1024)


def _limits(a: dict):
    """source.archive.max-mb, max-ratio, max-members over lake.archive.* (Java: LoadConfig.archive); validate: rows or structure."""
    from . import archives
    try:
        d = archives.Limits.defaults()
    except ValueError as e:
        raise ConfigError(str(e)) from None
    max_bytes = _mb(a["max-mb"], "max-mb") if a.get("max-mb") is not None else d.max_bytes
    ratio = float(_text(a, "max-ratio", "0")) if "max-ratio" in a else d.max_ratio
    members = int(_text(a, "max-members", "0")) if "max-members" in a else d.max_members
    if ratio <= 0 or members <= 0 or max_bytes <= 0:
        raise ConfigError("source.archive: max-mb, max-ratio and max-members must be more than 0")
    validate = _text(a, "validate", "rows").lower()
    if validate not in ("rows", "structure"):
        raise ConfigError(f"source.archive.validate {validate}: rows (every dataset read and checked before the first commit) or structure "
                          "(the archives' listing only)")
    return archives.Limits(max_bytes, ratio, members)


def _dataset(d: str | None):
    from . import archives
    if d is not None and not archives.valid_name(d):
        raise ConfigError(f"source.dataset {d}: a table's folder name (letters, digits, '.', '_' and '-')")
    return d


def _contract(file: str | None):
    """target.contract: the data contract file (an absolute path), read now (Java: LoadConfig.contract)."""
    if file is None or not file.strip():
        return None
    if not os.path.isabs(file.strip()):
        raise ConfigError(f"target.contract {file}: give the contract file's absolute path")
    from .contracts import Contract
    try:
        return Contract.read(file.strip())
    except (OSError, ValueError, yaml.YAMLError) as e:
        raise ConfigError(f"target.contract {file}: cannot be read ({e})") from None


def _vector_type(v: str) -> bool:
    """target.layout.vector-type: double (the default) or float32; whether a new table's vectors are float32 (as Java's)."""
    k = v.strip().lower()
    if k in ("double", "float64"):
        return False
    if k in ("float32", "float"):
        return True
    raise ConfigError(f"target.layout.vector-type {v}: give double (the default) or float32")


def _trim(p: str) -> str:
    s = p.replace("\\", "/")
    return s[:-1] if len(s) > 1 and s.endswith("/") else s
