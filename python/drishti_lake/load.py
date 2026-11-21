# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The Python path of a load (LK-7: one Java loader, one Python path; both under the parity kit): the Java loader's command line, its
configuration file, its rules and its summary, writing Delta tables through delta-rs.

    python -m drishti_lake load --config FILE [--set key.path=value ...] [--as-of YYYY-MM-DD] [--key FIELD] [--mode append|replace]
        [--date-from auto|name] [--summary FILE] [--no-lock] [--no-run-log] [--dry-run] [--plan] [--run-id ID] [--json] [-q]

What it does, in the Java loader's order: the configuration read and checked; the input resolved (``inputs``); each file's business date
decided (``filedate``: given, the ``.ready`` file, the name, a tar's folder, the rows' own date, the business today; none after today);
every line through the row rule (``rowrule``), rejects counted by reason; the rows' dates checked (a replace never guesses one); the
reject share checked (``rejects.max-pct``); one id's copies on a date resolved (``latest-by``, else the last read); then one commit per
business date (``delta``). The exit code is the Java loader's: 0 loaded, 2 loaded with rejects, 1 failed, 3 locked."""
from __future__ import annotations

import fcntl
import json
import os
import socket
import sys
import time
from datetime import date, datetime, timezone

from . import config as configs
from . import delta, filedate, inputs, rowrule

OK, FAILED, PARTIAL, LOCKED = 0, 1, 2, 3
STATE = "_drishti-lake"
USAGE = ("usage: python -m drishti_lake load --config FILE [--set key.path=value ...] [--as-of YYYY-MM-DD] [--table T] [--kind K] "
         "[--mode append|replace] [--key FIELD] [--date-from auto|name] [--plan] [--summary FILE] [--progress FILE] [--no-lock] "
         "[--no-run-log] [--dry-run] [--run-id ID] [--json] [-q]")


class LoadError(Exception):
    """The load refused while it ran (status failed, nothing written)."""


class Locked(Exception):
    pass


def run_id() -> str:
    now = datetime.now().astimezone()
    return now.strftime("%Y%m%dT%H%M%S.") + f"{now.microsecond // 1000:03d}-{os.getpid()}"


def main(argv: list[str], out=None) -> int:
    """Runs a load as the command line would; returns the exit code."""
    out = out or sys.stdout
    t0 = time.monotonic()
    cfg_file, sets, json_out, quiet, given_run = None, [], False, False, None
    flags = {"--summary": "report.summary", "--progress": "report.progress", "--dataset": "source.dataset", "--as-of": "source.as-of",
             "--table": "target.table", "--pack": "source.pack", "--kind": "target.kind", "--mode": "target.mode", "--key": "source.id",
             "--date-from": "source.date-from"}
    try:
        i = 0
        while i < len(argv):
            a = argv[i]
            if a in ("--config", "--set", "--run-id") or a in flags:
                if i + 1 >= len(argv):
                    raise ValueError(f"{a} needs a value")
                v = argv[i + 1]
                i += 1
                if a == "--config":
                    cfg_file = v
                elif a == "--set":
                    sets.append(v)
                elif a == "--run-id":
                    given_run = v
                else:
                    sets.append(f"{flags[a]}={v.lower() if a == '--table' else v}")
            elif a == "--no-lock":
                sets.append("lock=false")
            elif a == "--no-run-log":
                sets.append("report.run-log=false")
            elif a in ("--dry-run", "--plan"):
                sets.append("report.dry-run=true")
            elif a == "--json":
                json_out = True
            elif a in ("-q", "--quiet"):
                quiet = True
            elif a in ("-h", "--help"):
                print(USAGE, file=out)
                return OK
            else:
                raise ValueError(f"unknown argument {a} (--help)")
            i += 1
        if cfg_file is None and not sets:
            raise ValueError("give --config FILE (docs/guides/CLI_REFERENCE.md section 4.13)")
    except ValueError as e:
        print(f"lakeload: {e}", file=out)
        return FAILED
    try:
        cfg = configs.read(cfg_file, sets)
    except (OSError, ValueError) as e:
        print(f"lakeload: the configuration: {e}", file=out)
        return FAILED
    today = filedate.today()
    try:
        files = inputs.resolve(cfg.files, cfg.recursive, today)
    except inputs.InputError as e:
        msg = str(e)
        print("lakeload: " + ("" if "refused, nothing was loaded" in msg else "the input: ") + msg, file=out)
        return FAILED
    except OSError as e:
        print(f"lakeload: the input: {e}", file=out)
        return FAILED
    report = Report(cfg, given_run or run_id(), today)
    error, lock = None, None
    try:
        if cfg.lock and not cfg.dry_run:
            lock = take_lock(cfg.lake_root, cfg.domain, cfg.table)
        Loader(cfg, files, report).run()
    except Locked as e:
        report.status, error = "locked", str(e)
    except (LoadError, delta.TableError, ValueError) as e:
        error = str(e)
    except Exception as e:  # noqa: BLE001 - one line, as the Java loader's
        error = f"{type(e).__name__}: {str(e).splitlines()[0] if str(e) else ''}"
    finally:
        if lock is not None:
            lock.close()
    if error is not None and report.status != "locked":
        report.status = "failed"
    summary = report.summary(error, time.monotonic() - t0)
    if cfg.summary:
        os.makedirs(os.path.dirname(os.path.abspath(cfg.summary)), exist_ok=True)
        summary["summaryFile"] = os.path.abspath(cfg.summary)
        with open(cfg.summary, "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2, ensure_ascii=False)
    if json_out:
        print(json.dumps(summary, indent=2, ensure_ascii=False), file=out)
    else:
        if not quiet:
            if error:
                print(f"lakeload: {error}", file=out)
            print(f"loader: delta table {cfg.table_path}", file=out)
            for d, n in summary["rows"].items():
                print(f"  {d}  {n:,} rows", file=out)
            print(f"documents read: {report.documents:,}, rows written: {0 if report.status == 'failed' or cfg.dry_run else report.accepted:,}, "
                  f"rejected: {report.rejected:,}", file=out)
        print(report.log_line(), file=out)
    return report.exit_code()


def take_lock(lake: str, domain: str, table: str):
    """The Java loader's locks: the lake shared, the table exclusive (``<lake>/_drishti-lake/locks/<domain>/<table>.lock``, byte 0, a POSIX
    lock: a Java and a Python run exclude each other)."""
    held = []
    try:
        held.append(_take(os.path.join(lake, STATE, "lock"), True, "the lake"))
        held.append(_take(os.path.join(lake, STATE, "locks", domain, table + ".lock"), False, f"table {domain}/{table}"))
    except BaseException:
        for f in reversed(held):
            f.close()
        raise

    class _Held:
        def close(self):
            for f in reversed(held):
                f.close()
    return _Held()


def _take(path: str, shared: bool, what: str):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    f = open(path, "a+b")
    try:
        fcntl.lockf(f, (fcntl.LOCK_SH if shared else fcntl.LOCK_EX) | fcntl.LOCK_NB, 1, 0)
    except OSError:
        f.seek(0)
        holder = f.read().decode("utf-8", "replace").strip()
        f.close()
        raise Locked(f"{what} is locked by {holder or 'another run'}") from None
    if not shared:
        f.truncate(0)
        f.write(json.dumps({"pid": os.getpid(), "host": socket.gethostname(), "since": datetime.now().astimezone().isoformat(timespec="seconds"),
                            "by": "lakeload"}).encode())
        f.flush()
    return f


class Report:
    """What a load did (``LoadReport``): the summary's keys, the run log line, the exit code."""

    def __init__(self, cfg, run: str, today: date):
        self.cfg, self.run, self.today = cfg, run, today
        self.started = datetime.now().astimezone().isoformat(timespec="seconds")
        self.status = "ok"
        self.documents = self.accepted = self.rejected = 0
        self.by_reason: dict[str, int] = {}
        self.first: list = []
        self.rows: dict[str, int] = {}
        self.commits: dict[str, dict] = {}
        self.file_dates: list = []
        self.loads: dict[str, str] = {}
        self.duplicates: dict[str, int] = {}
        self.skipped: list = []
        self.columns: dict = {}
        self.left_out: list = []

    def reject(self, reason: str, where: str):
        self.rejected += 1
        self.by_reason[reason] = self.by_reason.get(reason, 0) + 1
        if len(self.first) < self.cfg.keep:
            self.first.append({"where": where, "reason": reason})

    def exit_code(self) -> int:
        return {"ok": OK, "dry-run": OK, "partial": PARTIAL, "locked": LOCKED}.get(self.status, FAILED)

    def summary(self, error: str | None, seconds: float) -> dict:
        c = self.cfg
        s = {"run": self.run, "startedAt": self.started, "finishedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
             "status": self.status, "exitCode": self.exit_code(), "dryRun": c.dry_run, "mode": "append" if c.mode == "append" else "replace-date",
             "format": "delta", "engine": "python", "engineWhy": "the Python path (drishti_lake" + (", Polars" if delta.polars_installed() else "")
             + ")", "table": c.table_path, "domain": c.domain, "kind": c.table, "key": c.key, "date": c.date,
             "asOf": c.as_of.isoformat() if c.as_of else None, "promote": c.promote, "partitionBy": c.partition_by,
             "rows": dict(sorted(self.rows.items())), "documents": self.documents, "accepted": self.accepted, "rejected": self.rejected,
             "rejects": {"byReason": dict(sorted(self.by_reason.items())), "first": self.first}, "duplicates": self.duplicates,
             "latestBy": c.latest_by, "dateFrom": c.date_from, "fileDates": self.file_dates, "loads": self.loads,
             "columns": self.columns, "leftOut": self.left_out, "timings": {"totalSeconds": round(seconds, 3)}}
        if self.commits:
            s["commit"] = {"commits": len(self.commits), "dates": self.commits}
        if self.skipped:
            s["skippedBatch"] = self.skipped
        if error is not None:
            s["error"] = error
        return s

    def log_line(self) -> str:
        now = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
        return (f"{now} lake-build {self.status} exit={self.exit_code()} run={self.run} domain={self.cfg.domain} kind={self.cfg.table} "
                f"engine=python mode={self.cfg.mode} rows={self.accepted} rejected={self.rejected} dates={len(self.rows)}")


class Loader:
    """One table's load (module doc)."""

    def __init__(self, cfg, files: list, report: Report):
        self.cfg, self.files, self.report = cfg, files, report
        promote = list(cfg.promote) + [n for n in cfg.flatten if n not in cfg.promote]
        promote = [p for p in promote if p not in cfg.partition_by]
        vectors = [v for v in cfg.vectors if v not in cfg.partition_by and v not in promote]
        latest = cfg.latest_by.strip() if cfg.latest_by and cfg.latest_by.strip() else None
        self.wrapped = cfg.loader_rows_of                  # source.format loader-rows: "<domain>/<kind>" the rows must name
        self.plan = rowrule.Plan(key=cfg.key, date=cfg.date, partition_by=tuple(cfg.partition_by), promote=tuple(promote),
                                 flatten=dict(cfg.flatten), vectors=tuple(vectors), latest_by=latest)

    def run(self):
        cfg, rep, today = self.cfg, self.report, self.report.today
        decided = []
        for f in self.files:
            d = self.decide(f, today)
            if d.error:
                raise LoadError(d.error + " (nothing was loaded)")
            decided.append(d)
        load_ids, sources = {}, {}
        by_top = {}
        for i, f in enumerate(self.files):
            lid = by_top.setdefault(f.top, f"{rep.run}.{len(by_top) + 1}")
            load_ids[i], sources[i] = lid, f.name
        for top, lid in by_top.items():
            rep.loads[lid] = os.path.basename(top)
        today_day = rowrule.epoch_day(filedate.business_today(today))
        by_date: dict[int, dict] = {}
        types = rowrule.Types(self.plan)
        row_days: dict[int, dict] = {}
        read = Report(cfg, rep.run, today)          # the counts reach the report once the dates are checked (as LoadReport.collect)
        for i, (f, d) in enumerate(zip(self.files, decided)):
            rows_or_today = d.source == "today" and self.plan.date is not None
            file_day = None if rows_or_today else rowrule.epoch_day(d.date)
            n = 0
            counts = row_days.setdefault(i, {}) if self.plan.date is not None else {}
            for ln, line in enumerate(inputs.lines(f), 1):
                read.documents += 1
                r = rowrule.row(self.plan, line, file_day, today_day, rows_or_today, self.wrapped)
                if isinstance(r, str):
                    read.reject(r, f"{f.name}:{ln}")
                    continue
                r.file = i
                read.accepted += 1
                n += 1
                types.add(r)
                counts[r.own] = counts.get(r.own, 0) + 1
                keep = by_date.setdefault(r.day, {})
                old = keep.get(r.ident)
                if old is not None:
                    rep.duplicates[rowrule.day_of(r.day).isoformat()] = rep.duplicates.get(rowrule.day_of(r.day).isoformat(), 0) + 1
                    if not _newer(r, old):
                        continue
                keep[r.ident] = r
            rep.file_dates.append({"file": f.name, "rows": n})
        self.check_row_dates(row_days, decided)
        for fd, d in zip(rep.file_dates, decided):
            fd.update({"date": d.date.isoformat() if d.date else None, "source": d.source})
        future = sorted(day for day in by_date if day > rowrule.epoch_day(today))
        if future:
            more = len(future) - 1
            raise LoadError(filedate.future_error(rowrule.day_of(future[-1]), today)
                            + (f" (and {more} other future date{'s' if more > 1 else ''})" if more else "") + " (nothing was written)")
        rep.documents, rep.accepted, rep.rejected = read.documents, read.accepted, read.rejected
        rep.by_reason, rep.first = read.by_reason, read.first
        for day, rows in by_date.items():
            rep.rows[rowrule.day_of(day).isoformat()] = len(rows)
        if rep.documents > 0 and rep.rejected * 100.0 / rep.documents > cfg.max_pct:
            raise LoadError(f"{rep.rejected:,} of {rep.documents:,} documents rejected ({rep.rejected * 100.0 / rep.documents:.2f}%), more than "
                            f"rejects.max-pct {float(cfg.max_pct)}: nothing was written")
        if cfg.dry_run:
            rep.status = "dry-run"
            return
        if rep.accepted == 0:
            raise LoadError("no document could be loaded (every one was rejected: see rejects)")
        w = delta.Writer(cfg.table_path, self.plan, types.kinds(), list(self.plan.vectors), cfg.batch_id, cfg.mode == "append",
                         cfg.float32)
        rep.columns, rep.left_out = w.column_types(), w.left_out
        for day in sorted(by_date):
            d = rowrule.day_of(day)
            if w.appended(d):                         # this batch's append of the date is committed already: never twice
                rep.skipped.append(d.isoformat())
                rep.commits[d.isoformat()] = {"skipped": "batch appended already", "files": 0, "rows": 0, "bytes": 0}
                continue
            table = w.frame(d, list(by_date[day].values()), load_ids, sources, cfg.sort_by)
            version = w.commit(d, table)
            rep.commits[d.isoformat()] = {"version": version, "rows": table.num_rows}
        rep.status = "partial" if rep.rejected > 0 else "ok"

    def decide(self, f, today: date) -> filedate.Decided:
        """A file's business date (``ParseWorker.decide``): a tar member's folder; else given, the ``.ready`` file, the name, today."""
        if f.folder_date is not None:
            return filedate.Decided(f.folder_date, "folder")
        given = self.cfg.as_of if self.cfg.as_of is not None else filedate.ready_date(f.top)
        d = filedate.decide(given, filedate.of(f.dated_name, today), self.cfg.date_from, today, f.dated_name)
        if d.error is None and self.cfg.as_of is None and given is not None:
            return filedate.Decided(d.date, "ready")
        return d

    def check_row_dates(self, row_days: dict, decided: list):
        """The business date of a file the rows decide (``Loader.checkRowDates``), checked after the read and before anything is
        written: no date given or named, every row carrying one date: that date (source ``rows``); in a replace, rows carrying none or
        several refuse the load, and a file named for a date none of its rows carries is refused."""
        if self.plan.date is None:
            return
        replace = self.cfg.mode != "append"
        field = self.plan.date
        refused = []
        for i, m in row_days.items():
            if not m:
                continue
            f, d = self.files[i].name, decided[i]
            none = m.get(None, 0)
            own = sorted(k for k in m if k is not None)
            if d.source == "today":
                if none == 0 and (len(own) == 1 or not replace):
                    decided[i] = filedate.Decided(rowrule.day_of(own[0]), "rows")
                elif replace and not own:
                    refused.append(f"{f}: no business date: none given (--as-of, the .ready file's business-date), none in its name "
                                   f"(<table>.<YYYY-MM-DD>.jsonl) and none of its {none} rows carries {field}; a replace under today's date "
                                   f"({d.date.isoformat()}) could take out a loaded date: give the date")
                elif replace:
                    refused.append(f"{f}: its business date is ambiguous: no date given or in its name, and its rows carry "
                                   f"{_day_words(m, field)}; give the date (--as-of, the .ready file's business-date, or "
                                   "<table>.<YYYY-MM-DD>.jsonl)")
            elif replace and d.source == "name" and own and rowrule.epoch_day(d.date) not in m:
                refused.append(f"{f}: its name says {d.date.isoformat()}, but none of its rows carries that date (they carry "
                               f"{_day_words(m, field)}): a replace would put another day's rows under {d.date.isoformat()}; rename the "
                               "file, or give the date (--as-of, business-date)")
        if refused:
            raise LoadError("; ".join(refused) + " (nothing was written)")


def _day_words(m: dict, field: str) -> str:
    """``2026-11-12 in 300 rows, 2026-11-13 in 2 rows, none in 5 rows``."""
    out = []
    for d in sorted(m, key=lambda k: (k is None, k or 0)):
        n = m[d]
        out.append((f"no {field}" if d is None else rowrule.day_of(d).isoformat()) + f" in {n} row{'' if n == 1 else 's'}")
    return ", ".join(out)


def _newer(r, old) -> bool:
    """Whether ``r`` replaces ``old`` (same id and date): the later latest-by time wins, a time beats none, else the last read."""
    a, b = _micros(r.latest), _micros(old.latest)
    if a is None and b is None:
        return True
    if a is None:
        return False
    if b is None:
        return True
    return a >= b


def _micros(text):
    if not text:
        return None
    try:
        t = datetime.fromisoformat(text.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if t.tzinfo is None:
        t = t.replace(tzinfo=timezone.utc)
    return int(t.timestamp() * 1_000_000)

