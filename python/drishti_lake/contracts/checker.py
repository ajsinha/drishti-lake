# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The checks of a data contract: the contract against its schema (``schema_problems``), one record against the contract (``record``,
the loader's check before the first write: the same words as Java's ``com.ash.lake.contract.ContractChecker``, no value in them), and
the contract against the data a kind holds (``drift``: fields, required, enums, types, links, id patterns)."""
from __future__ import annotations

import json
import math
import re
from collections import Counter, OrderedDict

from .reader import CORE, Contract, Profile

NUMBER = {"integer", "amount", "rate"}
MAX_DEPTH = 3             # nested levels described
_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
_TIMESTAMP = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})")
RATE_WORDS = {"UTILISATION", "UTILIZATION", "RATE", "VOL", "YIELD", "SPREAD", "HAIRCUT", "MULTIPLIER", "ALPHA", "CONFIDENCE", "RHO",
              "CORRELATION", "WEIGHT", "PCT", "SHARE", "RATIO", "GROWTH", "INFLATION", "AVERAGE", "USE", "BETA", "PD", "LGD",
              "RECOVERY", "MARGIN", "COUPON", "STRIKE"}
AMOUNT_WORDS = {"MTM", "NOTIONAL", "PNL", "VAR", "ES", "AMOUNT", "VALUE", "EXPOSURE", "BALANCE", "EAD", "PFE", "EE", "EPE", "EEPE",
                "ENE", "CVA", "DVA", "FVA", "KVA", "COLLATERAL", "CHARGE", "PV", "IM", "LIMIT", "USED", "HELD", "POSTED", "RECEIVED",
                "NET", "TOTAL", "COST", "RWA", "REVENUE", "INCOME", "PRICE", "CS01", "DV01", "ADD", "USD", "BN"}


# -- the contract against its schema --------------------------------------------------------------------------------------------

def schema_problems(doc: dict, profile: Profile = CORE) -> list[str]:
    """Where the contract breaks its schema (the core's with the profile's keys), by path; empty: valid."""
    import jsonschema
    v = jsonschema.Draft202012Validator(profile.schema())
    return [f"{'/'.join(str(p) for p in e.absolute_path) or '(top)'}: {e.message}"
            for e in sorted(v.iter_errors(doc), key=lambda e: list(e.absolute_path))]


# -- one record against the contract --------------------------------------------------------------------------------------------

def _number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def type_error(t: str, v) -> str | None:
    """Why ``v`` is not of the contract type ``t`` (None: it is); an unknown or a plain list type takes anything."""
    if t in ("text", "id"):
        return None if isinstance(v, str) else "not text"
    if t in ("amount", "rate"):
        return None if _number(v) else "not a finite number"
    if t == "record":
        return None if isinstance(v, dict) else "not a record"
    if t == "record list":
        return None if isinstance(v, list) else "not a list of records"
    if t == "text list":
        return None if isinstance(v, list) and all(isinstance(x, str) for x in v) else "not a list of text"
    if t == "integer":
        return None if isinstance(v, int) and not isinstance(v, bool) else "not an integer"
    if t == "date":
        return None if isinstance(v, str) and _DATE.fullmatch(v) else "not a date (YYYY-MM-DD)"
    if t == "timestamp":
        return None if isinstance(v, str) and _TIMESTAMP.fullmatch(v) else "not an ISO-8601 timestamp"
    if t == "boolean":
        return None if isinstance(v, bool) else "not true or false"
    if t == "vector":
        if not isinstance(v, list) or not v:
            return "not a list of numbers"
        bad = [i for i, x in enumerate(v) if not _number(x)]
        return None if not bad else f"{len(bad)} value(s) not finite numbers (first at index {bad[0]}); never filled with zero"
    if t == "id list":
        return None if isinstance(v, list) and all(isinstance(x, str) and x for x in v) else "not a list of ids (text)"
    if t == "date list":
        return None if isinstance(v, list) and all(isinstance(x, str) and _DATE.fullmatch(x) for x in v) else "not a list of dates (YYYY-MM-DD)"
    return None


def same(a, b) -> bool:
    """Two JSON values equal within their kind: texts, booleans, finite numbers by value (1 is 1.0); never across kinds (Python's
    True == 1 is not taken)."""
    if isinstance(a, str) and isinstance(b, str):
        return a == b
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a == b
    return _number(a) and _number(b) and a == b


def _value_problems(shown: str, f: dict, v, enums: list | None, pattern: str | None) -> list[str]:
    why = type_error(str(f.get("type", "unknown")), v)
    if why:
        return [f"{shown}: {why}"]
    out = []
    if enums is not None and not any(same(e, v) for e in enums):
        out.append(f"{shown}: not one of the contract's values")
    if pattern and isinstance(v, str) and not re.search(pattern, v):
        out.append(f"{shown}: does not match {pattern}")
    return out


def record(c: Contract, r: dict) -> list[str]:
    """What is wrong with one record (empty: nothing): an attribute the contract lacks, a required field missing (the field that dates the
    kind is not: a row's date may come from its file), a value of another type, outside its enum or pattern, a derived field (never
    loaded), and the same in nested records and lists of records the contract details."""
    bad = [f"{k}: not a field of the contract" for k in r if k not in c.fields]
    for name in c.required:
        if name != c.dated_by and (r.get(name) is None or r.get(name) == ""):
            bad.append(f"{name}: required, missing")
    for name, v in r.items():
        f = c.fields.get(name)
        if f is None or v is None or v == "":             # an empty value: a required one is missing (above), an optional one absent
            continue
        if name in c.derived:
            bad.append(f"{name}: derived, never loaded")
            continue
        probs = _value_problems(name, f, v, c.enums.get(name), c.patterns.get(name))
        bad += probs
        if probs:
            continue
        t = f.get("type")
        if t == "record" and isinstance(v, dict):
            bad += _nested(c, name, v, name)
        elif t == "record list" and isinstance(v, list):
            for i, x in enumerate(v):
                bad += _nested(c, f"{name}[]", x, f"{name}[{i}]") if isinstance(x, dict) else [f"{name}[{i}]: not a record"]
    return bad


def _nested(c: Contract, path: str, r: dict, shown: str) -> list[str]:
    """One nested record against the contract's ``nested:`` fields of ``path``; a path the contract does not detail is accepted."""
    fields = c.nested.get(path)
    if fields is None:
        return []
    bad = [f"{shown}.{k}: not a field of the contract" for k in r if k not in fields]
    for name, f in fields.items():
        v = r.get(name)
        if v is None or v == "":
            if f.get("presence") == "required":
                bad.append(f"{shown}.{name}: required, missing")
            continue
        if f.get("presence") == "derived":
            bad.append(f"{shown}.{name}: derived, never loaded")
            continue
        bad += _value_problems(f"{shown}.{name}", f, v, f.get("enum"), f.get("pattern"))
    return bad


def reason(c: Contract, problems: list[str]) -> str:
    """A rejected row's reason (the loader's words, counted by reason): the first problem and how many more."""
    more = len(problems) - 1
    return f"the contract of {c.kind} (version {c.version}): {problems[0]}" + (f"; and {more} more" if more > 0 else "")


# -- the contract against the data ----------------------------------------------------------------------------------------------

def json_type(v) -> str:
    """A value's JSON type in words: boolean, integer, number, date, timestamp, string, null, object, list, list of objects, list of
    <type>s."""
    if isinstance(v, bool):
        return "boolean"
    if isinstance(v, int):
        return "integer"
    if isinstance(v, float):
        return "number"
    if isinstance(v, str):
        if not v:
            return "null"
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", v):
            return "date"
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}T[0-9:.]+(Z|[+-]\d\d:?\d\d)?", v):
            return "timestamp"
        return "string"
    if isinstance(v, list):
        if v and all(isinstance(x, dict) for x in v):
            return "list of objects"
        return "list of " + json_type(v[0]) + "s" if v else "list"
    if isinstance(v, dict):
        return "object"
    return "null"


def _empty(v) -> bool:
    return v is None or v == ""


class Stat:
    """What one field held over the documents read: how often it was non-empty, its JSON types, a few values."""

    def __init__(self):
        self.present = 0
        self.types: Counter = Counter()
        self.values: Counter = Counter()
        self.overflow = False
        self.example = None
        self.lengths: set[int] = set()
        self.min = self.max = None
        self.floats = False

    def add(self, v) -> None:
        if _empty(v):
            return
        self.present += 1
        self.types[json_type(v)] += 1
        if isinstance(v, str):
            if not self.overflow:
                self.values[v] += 1
                self.overflow = len(self.values) > 50
            if self.example is None:
                self.example = v if len(v) <= 60 else v[:59] + "…"
        elif isinstance(v, (int, float)) and not isinstance(v, bool):
            self.floats |= isinstance(v, float) and not float(v).is_integer()
            self.min = v if self.min is None else min(self.min, v)
            self.max = v if self.max is None else max(self.max, v)
            if self.example is None:
                self.example = v
        elif isinstance(v, bool):
            self.values[v] += 1
            if self.example is None:
                self.example = v
        elif isinstance(v, list) and v and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v):
            self.lengths.add(len(v))


class Shape:
    """Fields seen over a kind's documents: path ("" for the top, "TRADES[]", "KYC", "LEGS[].INDEX") to field to Stat, and how many
    objects each path held (the denominator of a field's coverage). A top-level ``_meta`` is not a field."""

    def __init__(self, docs=()):
        self.n = 0
        self.fields: "OrderedDict[str, OrderedDict[str, Stat]]" = OrderedDict()
        self.items: Counter = Counter()
        for d in docs:
            self.add(d)

    def add(self, doc: dict) -> None:
        self.n += 1
        self._walk(doc, "", 0)

    def _walk(self, obj: dict, path: str, depth: int) -> None:
        self.items[path] += 1
        into = self.fields.setdefault(path, OrderedDict())
        for k, v in obj.items():
            if k == "_meta" and not path:
                continue
            into.setdefault(k, Stat()).add(v)
            if depth >= MAX_DEPTH:
                continue
            sub = (path + "." if path else "") + k
            if isinstance(v, list) and v and all(isinstance(x, dict) for x in v):
                for item in v[:50]:
                    self._walk(item, sub + "[]", depth + 1)
            elif isinstance(v, dict) and v:
                self._walk(v, sub, depth + 1)


def number_type(name: str, st: Stat) -> tuple[str, bool]:
    """(integer | amount | rate, whether the field's name decided it)."""
    w = set(name.upper().split("_"))
    if w & RATE_WORDS:
        return "rate", True
    if w & AMOUNT_WORDS:
        return "amount", True
    return ("amount" if st.floats or "number" in st.types else "integer"), False


def contract_type(name: str, st: Stat, id_like: bool) -> tuple[str, bool, list[str]]:
    """(contract type, decided by name, the JSON types seen when they disagree)."""
    ts = {t for t in st.types if t != "null"}
    if not ts:
        return "unknown", False, []
    if len(ts) > 1 and "list" in ts:
        ts.discard("list")
    if ts <= {"integer", "number"}:
        t, by_name = number_type(name, st)
        return t, by_name, []
    if ts <= {"date"}:
        return "date", False, []
    if ts <= {"timestamp", "date"}:
        return "timestamp", False, []
    if ts == {"boolean"}:
        return "boolean", False, []
    if ts == {"object"}:
        return "record", False, []
    if ts == {"list of objects"}:
        return "record list", False, []
    if ts <= {"list of integers", "list of numbers"}:
        return "vector", False, []
    if ts == {"list of strings"}:
        return ("id list" if name.endswith("IDS") or id_like else "text list"), False, []
    if ts == {"list of dates"}:
        return "date list", False, []
    if all(t.startswith("list") for t in ts):
        return "list", False, sorted(ts) if len(ts) > 1 else []
    if ts <= {"string", "date", "timestamp"}:
        return ("id" if id_like else "text"), False, sorted(ts) if len(ts) > 1 else []
    return "text", False, sorted(ts)


def _family(t: str) -> str:
    return "number" if t in NUMBER else {"id": "text", "timestamp": "date"}.get(t, t)


def _numeric(v) -> bool:
    try:
        float(v)
        return isinstance(v, str)
    except (TypeError, ValueError):
        return False


def drift(contract: dict, documents, links: dict | None = None, id_patterns: list | None = ..., ) -> list[str]:
    """What in the data (``documents``) and the host's link graph (``links``: field name to the kind it links to; None: not checked) no
    longer matches the contract: id pattern (``id_patterns``: the graph's patterns of the kind; left out: not checked), links, fields,
    required, enums, types."""
    out = []
    kind = contract["kind"]
    if id_patterns is not ...:
        if (contract.get("id-pattern") or None) != id_patterns:
            out.append(f"id-pattern {contract.get('id-pattern')} differs from the link graph's {id_patterns}")
    graph = links if links is not None else {}
    shape = documents if isinstance(documents, Shape) else Shape(documents)
    for path, fs in [("", contract.get("fields") or [])] + list((contract.get("nested") or {}).items()):
        seen = shape.fields.get(path)
        names = {f["name"] for f in fs}
        for f in fs:
            full = f"{path}.{f['name']}" if path else f["name"]
            if links is not None and f.get("link") and graph.get(f["name"]) != f["link"]:
                out.append(f"{full} links to {f['link']} but the link graph says {graph.get(f['name'])}")
            if seen is None:
                continue
            st = seen.get(f["name"])
            if st is None:
                if f["presence"] == "required":      # an optional field may be rare
                    out.append(f"{full} is in the contract but not in the data")
                continue
            if f["presence"] == "required" and st.present < shape.items[path]:
                out.append(f"{full} is required but empty in {shape.items[path] - st.present} of {shape.items[path]} records")
            if f.get("enum") and not st.overflow and set(st.values) - set(f["enum"]):
                out.append(f"{full} has values outside its enum: {sorted(map(str, set(st.values) - set(f['enum'])))[:5]}")
            data_t = contract_type(f["name"], st, f["type"] in ("id", "id list"))[0]
            if f["type"] in NUMBER and set(st.types) - {"null"} <= {"integer", "number", "string"} \
                    and all(_numeric(v) for v in st.values):
                data_t = f["type"]                   # numbers, some written as numeric text by an extract
            if f["type"] != "unknown" and data_t != "unknown" and _family(data_t) != _family(f["type"]) and not (
                    f["type"] == "text" and data_t in ("date", "timestamp")):
                out.append(f"{full} is {f['type']} in the contract but {data_t} in the data")
        if seen is not None:
            out += [f"{(path + '.') if path else ''}{n} is in the data but not in the contract" for n in seen if n not in names]
            if links is not None:
                for n in seen:
                    if n in graph and n in names and not next(f for f in fs if f["name"] == n).get("link") and graph[n] != kind:
                        out.append(f"{(path + '.') if path else ''}{n} links to {graph[n]} in the link graph but not in the contract")
    return out


def records_of(lines) -> list[dict]:
    """JSON Lines text (an iterable of lines) as records; blank lines skipped."""
    return [json.loads(t) for t in lines if t.strip()]
