# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The row rule: what one line of a load becomes, as one function (the Java loader's ``RowParser``, rule by rule).

``row(plan, line, file_day, today_day)`` decides, for one line, whether it is rejected and why (in the Java loader's words), its id, the
constants and flattened fields it gains, its vectors, its document text, its partition values and its promoted values. It reads no file and
writes none; the business date of the file is the caller's (``file_day``: rule of the file, not of the row).

The order of the checks is part of the rule (a line with two faults is rejected for the first): JSON, an object, the id, the slice, the
date, the vectors, then (``target.contract``) the document as sent against its data contract (``contracts.checker``).

**The document text** is the document written again as Python's ``json.dumps(ensure_ascii=False, separators=(",", ":"))`` writes it
(the Java loader's ``PyJson`` writes the same bytes): key order kept, a float as ``repr``, a top-level numeric id as its text, a vector
field as ``{"$vector": "<column>"}``, the ``set`` constants and the flattened fields a document lacks appended at its end."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import date

MISSING = object()
_YMD = re.compile(r"^(\d{4})(\d{2})(\d{2})$")
_STEP = re.compile(r"([^.\[\]]+)|\[(\d+)\]")
_ID_FIELD = re.compile(r"(?i)(^|_)(id|ids|ref|refs|key)$")


def text(v) -> str:
    """A JSON value as compact text (``PyJson``)."""
    return json.dumps(v, ensure_ascii=False, separators=(",", ":"))


def get(doc, path: str):
    """The value at a dotted path (``index.group``), MISSING when absent."""
    cur = doc
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return MISSING
    return cur


def get_indexed(doc, path: str):
    """The value at a path that may index lists (``LEGS[0].NOTIONAL``), MISSING when absent."""
    cur = doc
    for name, index in _STEP.findall(path):
        if name:
            if isinstance(cur, dict) and name in cur:
                cur = cur[name]
            else:
                return MISSING
        else:
            i = int(index)
            if isinstance(cur, list) and i < len(cur):
                cur = cur[i]
            else:
                return MISSING
    return cur


def column_name(path: str) -> str:
    """The physical column of a promoted path or partition: counterparty.id -> counterparty__id."""
    return path.replace(".", "__")


def is_number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def is_id_field(path: str, key: str) -> bool:
    """A field named like an id or key (ID, TRADE_ID, TRADE_IDS, DEAL_REF), or the key: its column is text, whatever the values."""
    return path == key or bool(_ID_FIELD.search(path.replace("__", ".").rsplit(".", 1)[-1]))


def id_text(v) -> str:
    """An id as its exact text: a whole float as an integer (``PyJson.idOfFloat``), an integer as written, text unchanged."""
    if isinstance(v, float) and v.is_integer() and abs(v) < 1e18:
        return str(int(v))
    if isinstance(v, bool):
        return "true" if v else "false"
    return v if isinstance(v, str) else text(v)


def scalar_text(v) -> str | None:
    """A partition value as text: None for missing or null, a number as Python writes it, a container as its JSON text."""
    if v is MISSING or v is None:
        return None
    if isinstance(v, str):
        return v
    if is_id and is_number(v):
        return id_text(v)                                   # an id is text: 1000008, never 1000008.0
    return text(v)


def parse_day(v) -> date | None:
    """A business date from a field (``RowParser.day``): an ISO date or date-time text, yyyymmdd as text or an integer."""
    if isinstance(v, bool) or not isinstance(v, (str, int)):
        return None
    s = v.strip() if isinstance(v, str) else str(v)
    try:
        m = _YMD.match(s)
        if m:
            return date(int(m[1]), int(m[2]), int(m[3]))
        return date.fromisoformat(s[:10]) if len(s) >= 10 else None
    except ValueError:
        return None


def is_simple_list(v) -> bool:
    return isinstance(v, list) and not any(isinstance(x, (list, dict)) for x in v)


@dataclass(frozen=True)
class Plan:
    """What the row rule needs of a load (``RowPlan``)."""
    key: str
    date: str | None = None
    partition_by: tuple = ()
    promote: tuple = ()                    # promoted paths (flattened names among them), partition columns excluded
    flatten: dict = field(default_factory=dict)
    vectors: tuple = ()
    set: dict = field(default_factory=dict)
    slice: dict = field(default_factory=dict)      # the set constants that split a shared table, by name
    latest_by: str | None = None


@dataclass
class Row:
    """One accepted line: id, business date (epoch day), document text, partition texts, promoted values (Python values), vectors,
    the row's own date (epoch day or None) and its latest-by text."""
    ident: str
    day: int
    doc: str
    partitions: tuple
    promoted: tuple
    vectors: tuple
    own: int | None
    latest: str | None
    file: int = -1                       # the index of its data file (the caller's: load id and source file)


def _not_json(e: json.JSONDecodeError, line: str) -> str:
    """A parse fault in the Java loader's words where Python's parser meets the same fault (its parser's message, Jackson's), else
    Python's own (tck/python-differences.txt names the cases)."""
    if e.msg == "Extra data":
        return "not JSON (Extra data)"
    if e.msg == "Expecting property name enclosed in double quotes" and e.pos < len(line):
        ch = line[e.pos]
        return f"not JSON (Unexpected character ('{ch}' (code {ord(ch)})): was expecting double-quote to start field name)"
    return f"not JSON ({e.msg})"


def row(plan: Plan, line: str, file_day: int | None, today_day: int, rows_or_today: bool, wrapped: str | None = None,
        contract=None) -> Row | str:
    """One line's outcome: a ``Row`` or the reason it is rejected. ``file_day``: the file's business date (epoch day) when it has one;
    ``rows_or_today``: no date given or named, the row's own date decides (else today). ``wrapped``: ``<domain>/<kind>`` for loader
    rows (``{domain, kind, id, date, doc, columns}`` a line: the document ``doc``, the id and the row's own date the wrapper's, a
    promoted value the document lacks the wrapper's ``columns`` value). ``contract``: a ``contracts.Contract`` the document must keep."""
    try:
        doc = json.loads(line, parse_constant=_no_constant)
    except json.JSONDecodeError as e:
        return _not_json(e, line)
    if not isinstance(doc, dict):
        return "not a JSON object"
    wrapper = None
    if wrapped is not None:
        wrapper = doc
        table = f"{_wtext(wrapper.get('domain'))}/{_wtext(wrapper.get('kind'))}"
        if table != wrapped:
            return f"a loader row of {table}, not this load's {wrapped}"
        d = wrapper.get("doc")
        if d is None:
            return "a loader row without its document (doc)"
        if isinstance(d, str):
            try:
                doc = json.loads(d, parse_constant=_no_constant)
            except json.JSONDecodeError as e:
                return _not_json(e, d)
        else:
            doc = d
        if not isinstance(doc, dict):
            return "not a JSON object"
    sent = None
    if contract is not None:                           # the document as sent: the rule below rewrites ids, vectors and constants
        sent = json.loads(d if wrapper is not None and isinstance(d, str) else line, parse_constant=_no_constant)
        sent = sent.get("doc") if wrapper is not None and not isinstance(d, str) else sent
    top = set(doc)
    if wrapper is not None:
        kv = wrapper.get("id", MISSING)
    else:
        kv = doc.get(plan.key, MISSING) if "." not in plan.key else get(doc, plan.key)
    if kv is MISSING or kv is None or kv == "":
        return f"no id (field '{plan.key}' is missing or empty)"
    if isinstance(kv, (dict, list)):
        return f"the id field '{plan.key}' is not a single value"
    ident = id_text(kv) if not isinstance(kv, str) else kv
    if is_number(kv) and plan.key in doc and wrapper is None:   # the document carries a top-level numeric id as its text
        doc[plan.key] = ident
    for name, value in plan.set.items():                   # a row that lacks the field takes the constant
        if name not in top:
            doc[name] = value
    for name, value in plan.slice.items():
        own = scalar_text(get(doc, name))
        if own is not None and own != value:
            return (f"{name} is {own}, not this load's {value} (a load into a shared table writes and replaces only its own {name} "
                    "slice)")
    own_day = None
    if wrapper is not None or plan.date:
        d = parse_day(wrapper.get("date") if wrapper is not None else get(doc, plan.date))
        own_day = None if d is None else d.toordinal() - _EPOCH
    if file_day is not None:
        day = file_day
    elif rows_or_today:
        day = own_day if own_day is not None else today_day
    else:
        return "no business date"
    vectors = []
    bad = None
    for p in plan.vectors:
        v = get(doc, p)
        if v is MISSING or v is None:
            vectors.append(None)
            continue
        if not isinstance(v, list) or not all(is_number(x) or x is None for x in v):
            bad = bad or p
            vectors.append(None)
            continue
        vectors.append([None if x is None else float(x) for x in v])
        if "." not in p:
            doc[p] = {"$vector": column_name(p)}
    if bad is not None:
        return f"the vector field '{bad}' is not a list of numbers"
    if contract is not None:
        from .contracts import checker
        problems = checker.record(contract, sent)
        if problems:
            return checker.reason(contract, problems)
    for name, path in plan.flatten.items():
        if name not in top:
            v = get_indexed(doc, path)
            if v is not MISSING:
                doc[name] = v
    partitions = tuple(scalar_text(get(doc, p)) for p in plan.partition_by)
    promoted = tuple(None if (v := get(doc, p)) is MISSING else v for p in plan.promote)
    if wrapper is not None:                            # a promoted value the document lacks: the wrapper's columns (a scalar)
        cols = wrapper.get("columns") if isinstance(wrapper.get("columns"), dict) else {}
        promoted = tuple(v if get(doc, p) is not MISSING else _scalar(cols.get(column_name(p), cols.get(p)))
                         for p, v in zip(plan.promote, promoted))
    latest = None
    if plan.latest_by:
        lv = get(doc, plan.latest_by)
        latest = lv if isinstance(lv, str) else None
    return Row(ident, day, text(doc), partitions, promoted, tuple(vectors), own_day, latest)


_EPOCH = date(1970, 1, 1).toordinal()


def _wtext(v) -> str:
    """A wrapper's field as Jackson's asText gives it ("" for none)."""
    return "" if v is None or isinstance(v, (dict, list)) else v if isinstance(v, str) else id_text(v)


def _scalar(v):
    return None if isinstance(v, (dict, list)) else v


def _no_constant(name):
    """NaN and Infinity are not JSON (the Java parser refuses them too)."""
    raise json.JSONDecodeError(f"Non-standard token '{name}'", name, 0)


def epoch_day(d: date) -> int:
    return d.toordinal() - _EPOCH


def day_of(epoch: int) -> date:
    return date.fromordinal(epoch + _EPOCH)


class Types:
    """What the accepted rows showed of each promoted column: seen, not all numbers, a list of simple values, not a list
    (``Loader.write``): a column is ``double`` when every value is a number (or none was seen) and it is not an id field, ``list``
    (array<string>) when every value is a list of simple values, else ``string``."""

    def __init__(self, plan: Plan):
        self.plan = plan
        n = len(plan.promote)
        self.not_numeric = [is_id_field(p, plan.key) for p in plan.promote]
        self.list_seen = [False] * n
        self.not_list = [False] * n

    def add(self, r: Row):
        for i, v in enumerate(r.promoted):
            if v is None:
                continue
            if not is_number(v):
                self.not_numeric[i] = True
            if isinstance(v, list) and is_simple_list(v):
                self.list_seen[i] = True
            else:
                self.not_list[i] = True

    def kinds(self) -> list[str]:
        out = []
        for i in range(len(self.plan.promote)):
            out.append("list" if self.list_seen[i] and not self.not_list[i] else "string" if self.not_numeric[i] else "double")
        return out


def number_text(v) -> str:
    """A number as Python writes it (an integer as written, a float as ``repr``)."""
    return text(v)


def cell(v, kind: str, is_id: bool = False):
    """A promoted value as its column takes it (``TargetRules``): a ``double`` column a number (text that reads as one, else None), a
    ``list`` column the texts of a list of simple values (a single value a list of one, a null left out), a ``string`` column the text
    (a number as Python writes it, a boolean ``true``/``false``, a container its JSON text)."""
    if v is None:
        return None
    if kind == "double":
        if is_number(v):
            return float(v)
        if isinstance(v, str):
            try:
                return float(v)
            except ValueError:
                return None
        return None
    if kind == "list":
        items = v if isinstance(v, list) else [v]
        out = []
        for x in items:
            if x is None:
                continue
            if isinstance(x, bool):
                out.append("true" if x else "false")
            elif isinstance(x, float) and x.is_integer() and abs(x) < 1e18:
                out.append(str(int(x)))
            elif isinstance(x, str):
                out.append(x)
            else:
                out.append(text(x))
        return out
    if isinstance(v, str):
        return v
    if is_id and is_number(v):
        return id_text(v)                                   # an id is text: 1000008, never 1000008.0
    return text(v)
