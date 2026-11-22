# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""A load's ``target.properties`` (the Java ``com.ash.lake.spi.TableProperties``, the same words): ``lake.append-only`` and
``lake.retention-hours`` written as the format's native property (the Python path writes Delta: ``delta.appendOnly``,
``delta.deletedFileRetentionDuration`` and ``delta.logRetentionDuration``); any format's native name of the two is taken too; every
other key is written as given. Set when the load creates the table; an append-only table refuses a replace."""
from __future__ import annotations

import re

APPEND_ONLY, RETENTION_HOURS = "lake.append-only", "lake.retention-hours"
DELTA_APPEND_ONLY, DELTA_FILE_RETENTION, DELTA_LOG_RETENTION = "delta.appendOnly", "delta.deletedFileRetentionDuration", "delta.logRetentionDuration"
ICEBERG_MAX_AGE = "history.expire.max-snapshot-age-ms"
EARLIER = "dri" + "shti."   # the prefix of tables written before Drishti Lake (the Java PersistedNames): refused
HOUR_MS = 3_600_000
_UNITS = {"week": 7 * 24 * HOUR_MS, "day": 24 * HOUR_MS, "hour": HOUR_MS, "minute": 60_000, "second": 1_000}


def _bool(k, v):
    if v.lower() in ("true", "false"):
        return v.lower() == "true"
    raise ValueError(f"target.properties.{k}: true or false, not {v}")


def _whole(k, v, what):
    if re.fullmatch(r"\d+", v):
        return int(v)
    raise ValueError(f"target.properties.{k}: {what}, 0 or more, not {v}")


def interval_ms(k, v):
    p = v.lower().split()
    if len(p) == 3 and p[0] == "interval" and re.fullmatch(r"\d+", p[1]):
        unit = _UNITS.get(p[2][:-1] if p[2].endswith("s") else p[2])
        if unit:
            return int(p[1]) * unit
    raise ValueError(f"target.properties.{k}: interval N days (or weeks, hours, minutes, seconds), not {v}")


def _ceil_hours(ms):
    return (ms + HOUR_MS - 1) // HOUR_MS


def _interval(hours):
    return f"interval {hours // 24} days" if hours % 24 == 0 else f"interval {hours} hours"


def for_delta(given: dict) -> dict:
    """The Delta properties a new table is created with (TableProperties.forStore("delta", ...)); ValueError in the Java words."""
    append = hours = None
    append_from = hours_from = None
    out = {}
    for k, raw in (given or {}).items():
        v = "" if raw is None else (str(raw).lower() if isinstance(raw, bool) else str(raw)).strip()
        if k.startswith(EARLIER):
            raise ValueError(f"target.properties.{k}: the product's properties are lake.* ({APPEND_ONLY}, {RETENTION_HOURS}); {EARLIER}* names "
                             "tables written before Drishti Lake")
        if k in (APPEND_ONLY, DELTA_APPEND_ONLY):
            b = _bool(k, v)
            if append is not None and append != b:
                raise ValueError(f"target.properties: {append_from} and {k} name one property with different values")
            append, append_from = b, k
        elif k in (RETENTION_HOURS, ICEBERG_MAX_AGE, DELTA_FILE_RETENTION):
            h = (_whole(k, v, "whole hours") if k == RETENTION_HOURS else _ceil_hours(_whole(k, v, "milliseconds")) if k == ICEBERG_MAX_AGE
                 else _ceil_hours(interval_ms(k, v)))
            if hours is not None and hours != h:
                raise ValueError(f"target.properties: {hours_from} and {k} name one property with different values")
            hours, hours_from = h, k
        elif k == DELTA_LOG_RETENTION:
            interval_ms(k, v)
            out[k] = v
        else:
            out[k] = raw if isinstance(raw, str) else v
    if append is not None:
        out[DELTA_APPEND_ONLY] = str(append).lower()
    if hours is not None:
        native = str(given[DELTA_FILE_RETENTION]).strip() if DELTA_FILE_RETENTION in given else _interval(hours)
        out[DELTA_FILE_RETENTION] = native
        out.setdefault(DELTA_LOG_RETENTION, native)
    return out


def append_only(props: dict) -> bool:
    props = props or {}
    return str(props.get(DELTA_APPEND_ONLY, props.get(APPEND_ONLY, "false"))).strip().lower() == "true"


def refuse_replace(props: dict, table: str, partitions_only: bool):
    """The Java TableProperties.refuseReplace: an append-only table takes appends only (a ValueError in its words)."""
    if append_only(props):
        what = "a replace of partitions (load mode overwrite-partitions)" if partitions_only else "a replace of dates (load mode overwrite-dates)"
        raise ValueError(f"{table} is append-only ({APPEND_ONLY}): {what} is refused; the table takes appends only")
