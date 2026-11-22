# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The Python path's Delta writer (the Java loader's ``DeltaTarget``): one commit per business date through delta-rs.

The table: ``id``, ``doc`` (text), ``business_date`` (date), the partition columns (text), the promoted columns (``double``, ``array<string>``
or text), the vector columns (``array<double>``), ``_load_id`` and ``_source_file``; partitioned by ``business_date`` then the partition
columns. An existing table keeps its schema and its partitioning (another partitioning is refused in the Java loader's words; a column
it lacks is left out and reported). A replace takes out the date's files and adds the new ones in one commit; an append adds; a batch's
append of a date records ``drishti-batch:<batch>:<date>`` (version 1) and is never committed twice.

**One mechanism, Polars when installed.** A date's rows, in the layout's order (``sort-by``, then the id), become one Arrow table:
built by Polars when it is installed, by pyarrow otherwise. Both give the same table (a test holds them equal), and the same delta-rs
call writes it."""
from __future__ import annotations

import os
from datetime import date

import pyarrow as pa

from . import props, rowrule

LOAD_ID, SOURCE_FILE = "_load_id", "_source_file"


def polars_installed() -> bool:
    if os.environ.get("LAKE_PYTHON_NO_POLARS") == "1":       # the plain path, asked for (its parity test)
        return False
    try:
        import polars  # noqa: F401
        return True
    except ImportError:
        return False


def rows_per_date(path: str, dates: set) -> dict:
    """The rows the Delta table at ``path`` holds on each of ``dates`` (counted, the table read through delta-rs); none when absent."""
    from deltalake import DeltaTable
    if not DeltaTable.is_deltatable(path):
        return {}
    import pyarrow.compute as pc
    t = DeltaTable(path).to_pyarrow_table(columns=["business_date"], partitions=[("business_date", "in", [d.isoformat() for d in sorted(dates)])])
    out = {}
    for d, n in zip(*[c.to_pylist() for c in pc.value_counts(t.column("business_date")).flatten()]):
        out[d] = n
    return out


class TableError(Exception):
    """The table refuses the load (its partitioning, its state): the Java loader's words."""


def _type(kind: str):
    return {"double": pa.float64(), "list": pa.list_(pa.string()), "vector": pa.list_(pa.float64()),
            "vector32": pa.list_(pa.float32())}.get(kind, pa.string())


def _kind_of(t: pa.DataType) -> str:
    if pa.types.is_list(t) or pa.types.is_large_list(t):
        if pa.types.is_string(t.value_type) or pa.types.is_large_string(t.value_type):
            return "list"
        return "vector32" if pa.types.is_float32(t.value_type) else "vector"
    if pa.types.is_floating(t):
        return "double"
    if pa.types.is_date(t):
        return "date"
    return "string"


class Writer:
    """The table of one load (module doc)."""

    def __init__(self, path: str, plan: rowrule.Plan, kinds: list[str], vectors: list[str], batch_id: str | None, appends: bool,
                 float32: bool = False, properties: dict | None = None, partitions_only: bool = False, name: str = ""):
        from deltalake import DeltaTable
        self.path = path
        self.properties = dict(properties or {})       # a new table's (props.for_delta)
        self.partitions_only = partitions_only and not appends
        self.plan = plan
        self.batch_id = batch_id
        self.appends = appends
        self.parts = ["business_date"] + [rowrule.column_name(p) for p in plan.partition_by]
        wanted = [("id", "string"), ("doc", "string"), ("business_date", "date")]
        wanted += [(rowrule.column_name(p), "string") for p in plan.partition_by]
        wanted += [(rowrule.column_name(p), k) for p, k in zip(plan.promote, kinds)]
        wanted += [(rowrule.column_name(v), "vector32" if float32 else "vector") for v in vectors]   # target.layout.vector-type
        wanted += [(LOAD_ID, "string"), (SOURCE_FILE, "string")]
        self.table = DeltaTable(path) if DeltaTable.is_deltatable(path) else None
        self.left_out = []
        if self.table is None:
            self.columns = wanted
        else:
            if not appends:
                try:
                    props.refuse_replace(self.table.metadata().configuration, name, partitions_only)
                except ValueError as e:
                    raise TableError(str(e)) from None
            have = list(self.table.metadata().partition_columns)
            if have != self.parts:
                raise TableError(f"{path} is partitioned by [{', '.join(have)}], this load by [{', '.join(self.parts)}] (give the table's "
                                 "partition-by, or write a new table)")
            schema = pa.schema(self.table.schema().to_arrow())
            self.columns = [(f.name, _kind_of(f.type)) for f in schema]
            names = {n for n, _ in self.columns}
            self.left_out = [n for n, _ in wanted if n not in names]
        self.kinds = dict(zip([rowrule.column_name(p) for p in plan.promote], kinds))
        self.schema = pa.schema([pa.field(n, _type(k) if k != "date" else pa.date32(), True) for n, k in self.columns])

    def column_types(self) -> dict:
        return {n: "vector" if k == "vector32" else k for n, k in self.columns}

    def appended(self, day: date) -> bool:
        """Whether this batch's append of ``day`` is committed already."""
        if not (self.appends and self.batch_id and self.table is not None):
            return False
        return self.table.transaction_version(self._app(day)) is not None

    def _app(self, day: date) -> str:
        return f"drishti-batch:{self.batch_id}:{day.isoformat()}"

    def frame(self, day: date, rows: list, load_ids: dict, sources: dict, sort: list) -> pa.Table:
        """One date's rows as the table's Arrow table, in the layout's order (module doc)."""
        promote_index = {rowrule.column_name(p): i for i, p in enumerate(self.plan.promote)}
        part_index = {rowrule.column_name(p): i for i, p in enumerate(self.plan.partition_by)}
        vector_index = {rowrule.column_name(v): i for i, v in enumerate(self.plan.vectors)}
        ordered = order(rows, sort, self.plan)
        data = {}
        for name, kind in self.columns:
            if name == "id":
                data[name] = [r.ident for r in ordered]
            elif name == "doc":
                data[name] = [r.doc for r in ordered]
            elif name == "business_date":
                data[name] = [day] * len(ordered)
            elif name == LOAD_ID:
                data[name] = [load_ids[r.file] for r in ordered]
            elif name == SOURCE_FILE:
                data[name] = [sources[r.file] for r in ordered]
            elif name in part_index:
                i = part_index[name]
                data[name] = [None if r.partitions[i] in (None, "") else r.partitions[i] for r in ordered]
            elif name in promote_index:
                i = promote_index[name]
                is_id = rowrule.is_id_field(self.plan.promote[i], self.plan.key)
                data[name] = [rowrule.cell(r.promoted[i], kind if kind not in ("vector", "vector32") else "none", is_id) for r in ordered]
            elif name in vector_index:
                i = vector_index[name]
                data[name] = [r.vectors[i] for r in ordered] if kind in ("vector", "vector32") else [None] * len(ordered)
            else:
                data[name] = [None] * len(ordered)                 # a column of the table this load does not give
        if polars_installed():
            import polars as pl
            return pl.DataFrame(data, schema={f.name: pl.from_arrow(pa.array([], f.type)).dtype for f in self.schema},
                                strict=False).to_arrow().cast(self.schema)
        return pa.Table.from_pydict(data, schema=self.schema)

    def commit(self, day: date, table: pa.Table) -> int:
        """One commit of ``day``: replace (the date's earlier files taken out) or append; the table's new version."""
        from deltalake import CommitProperties, DeltaTable, Transaction, write_deltalake
        props = None
        if self.appends and self.batch_id:
            props = CommitProperties(app_transactions=[Transaction(app_id=self._app(day), version=1)])
        if self.table is None:
            write_deltalake(self.path, table, partition_by=self.parts, mode="append", commit_properties=props,
                            configuration=self.properties or None)
            self.table = DeltaTable(self.path)
        elif self.appends:
            write_deltalake(self.table, table, partition_by=self.parts, mode="append", commit_properties=props)
        else:
            write_deltalake(self.table, table, partition_by=self.parts, mode="overwrite", predicate=self._replaced(day, table),
                            commit_properties=props)
        self.table.update_incremental()
        return self.table.version()

    def _replaced(self, day: date, table: pa.Table) -> str:
        """What a replace of ``day`` takes out: the date; with overwrite-partitions only the partition values the rows bring (the Java
        loader's partitionsOnly), an empty value as null."""
        where = f"business_date = '{day.isoformat()}'"
        cols = self.parts[1:]
        if not self.partitions_only or not cols:
            return where
        tuples = sorted({tuple(None if v in (None, "") else v for v in t) for t in zip(*[table.column(c).to_pylist() for c in cols])},
                        key=lambda t: [(x is not None, x or "") for x in t])
        each = [" AND ".join(f"{_ident(c)} IS NULL" if v is None else f"{_ident(c)} = {_quote(v)}" for c, v in zip(cols, t)) for t in tuples]
        return where + " AND (" + " OR ".join(f"({e})" for e in each) + ")"


def _quote(v: str) -> str:
    return "'" + v.replace("'", "''") + "'"


def _ident(c: str) -> str:
    return '"' + c.replace('"', '""') + '"'


def order(rows: list, sort: list, plan: rowrule.Plan) -> list:
    """The rows of a date in the layout's order: the partition values, then ``sort`` (promoted fields, partition fields, ``id``), then
    the id; a missing value first, a number before text, numbers by value, text by its UTF-8 bytes."""
    keys = []
    for name in sort:
        if name in ("id", plan.key):
            keys.append(("i", 0))
            continue
        hit = None
        for i, p in enumerate(plan.partition_by):
            if name in (p, rowrule.column_name(p)):
                hit = ("p", i)
        for i, p in enumerate(plan.promote):
            if hit is None and name in (p, rowrule.column_name(p)):
                hit = ("c", i)
        if hit is not None:
            keys.append(hit)
    if ("i", 0) not in keys:
        keys.append(("i", 0))

    def comp(v, is_id=False):
        if v is None:
            return (0, b"")
        if rowrule.is_number(v) and not is_id:
            return (1, float(v))
        return (2, (rowrule.id_text(v) if rowrule.is_number(v) else v if isinstance(v, str) else rowrule.text(v)).encode("utf-8"))

    def key(r):
        out = [comp(v) for v in r.partitions]
        for k, i in keys:
            if k == "i":
                out.append((2, r.ident.encode("utf-8")))
            elif k == "p":
                out.append(comp(r.partitions[i]))
            else:
                out.append(comp(r.promoted[i], rowrule.is_id_field(plan.promote[i], plan.key)))
        return out
    return sorted(rows, key=key)
