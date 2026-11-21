#!/usr/bin/env python3
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
"""The parity kit (design section 4): run tck/cases over a class path and write one canonical result line per case and store.

    python3 tools/tck/tck.py --out results.jsonl                      # this repository (built here: ./mvnw package first)
    python3 tools/tck/tck.py --classpath-file CP --runner R.java --out ref.jsonl   # the bridge (tools/split/make_reference.py)

A case (tck/cases/<area>/<name>.yaml) names its stores, its input files (text, or the deterministic `pv` generator), the loader's
configuration (with ${lake} and ${in}), its steps (load, undo, retention with their arguments) and the tables to read back. Each case
runs once per store in a fresh folder; the result has no time, id, path or size in it, so two runs compare byte for byte
(tools/split/compare_results.py)."""
import argparse, gzip, io, json, os, random, shutil, subprocess, sys, tarfile, tempfile, zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CASES = ROOT / "tck" / "cases"
JAVA_HOME = os.environ.get("LAKE_JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")


def pv_lines(n, scenarios, dates, seed, start="2026-10-27"):
    """Synthetic scenario-PV positions (the shape of the loader tests' PvData; not real data)."""
    import datetime as dt
    r = random.Random(seed)
    d0 = dt.date.fromisoformat(start)
    out = []
    for i in range(n):
        desk = ["DESK-RATES", "DESK-FX", "DESK-COMM", "DESK-CREDIT"][r.randrange(4)]
        doc = {"POSITION_ID": f"P-{1000000 + i}", "BUSINESS_DATE": str(d0 + dt.timedelta(days=i % dates)),
               "LEGAL_ENTITY": ["LE-NY", "LE-LDN", "LE-TKY"][r.randrange(3)], "DESK": desk,
               "TT_BOOK": f"BOOK-{desk[5:]}-{r.randrange(5)}", "TRADE_ID": 2000000 + i, "QUANTITY": r.randrange(2000) - 1000,
               "BASE_PV": round(r.gauss(0, 1e5), 2), "CCY": "EUR" if i % 7 == 0 else "USD",
               "COMMENT": 'café "quoted" line\nnext – ü' if i % 11 == 0 else "",
               "LEGS": [{"LEG": 1, "RATE": r.randrange(500) / 10000.0, "FLAGS": [True, False, None]}, {"LEG": 2, "NOTIONAL": 1.0e7}],
               "SCENARIO_PV": [None if (k + i) % 211 == 0 else round(r.gauss(0, 1e3), 4) for k in range(scenarios)]}
        out.append(json.dumps(doc, ensure_ascii=False, separators=(",", ":")))
    return out


def content(spec):
    """An input file's lines: literal text, or {pv: {n, scenarios, dates, seed, date?}} (only lines of that date when given)."""
    if isinstance(spec, str):
        return spec if spec.endswith("\n") else spec + "\n"
    g = spec["pv"]
    lines = pv_lines(g["n"], g.get("scenarios", 4), g.get("dates", 1), g.get("seed", 1), g.get("start", "2026-10-27"))
    if "date" in g:
        lines = [l for l in lines if f'"BUSINESS_DATE":"{g["date"]}"' in l]
    return "".join(l + "\n" for l in lines)


def write_input(work, name, spec):
    p = work / name
    p.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(spec, dict) and ("tar" in spec or "zip" in spec):
        members = spec.get("tar") or spec.get("zip")
        if "tar" in spec:
            with tarfile.open(p, "w:gz" if name.endswith(("gz", "tgz")) else "w", format=tarfile.PAX_FORMAT) as t:
                for m, s in members.items():
                    b = content(s).encode()
                    ti = tarfile.TarInfo(m); ti.size = len(b); ti.mtime = 0
                    t.addfile(ti, io.BytesIO(b))
        else:
            with zipfile.ZipFile(p, "w") as z:
                for m, s in members.items():
                    z.writestr(zipfile.ZipInfo(m, (2026, 1, 1, 0, 0, 0)), content(s))
        return
    b = content(spec).encode()
    if name.endswith(".gz"):
        b = gzip.compress(b, mtime=0)
    elif name.endswith(".zst"):
        b = subprocess.run(["zstd", "-q", "-c"], input=b, capture_output=True, check=True).stdout
    p.write_bytes(b)


def subst(x, env):
    if isinstance(x, str):
        for k, v in env.items():
            x = x.replace("${" + k + "}", v)
        return x
    if isinstance(x, list):
        return [subst(i, env) for i in x]
    return x


MEM = "~mem"   # the matrix's memory row: a case's lake on mem:// (its result must equal the local one's, paths aside)
S3 = "~s3"     # the matrix's S3 row: a case's lake on s3:// (S3Mock in the runner, reached through the runtime's S3 settings)
ROW = {"mem": MEM, "s3": S3}


def step(s, work, env, loader="java"):
    """One step of a case: {load|undo|retention|store: args}, or {parallel: [steps]} (writers started together). ``loader``: who runs
    its loads (the matrix's loader column: java, or python for the Python path; every other step is the Java tools')."""
    (op, arg), = s.items()
    if op == "parallel":
        return {"op": "parallel", "steps": [step(x, work, env, loader) for x in arg]}
    arg = arg or []
    if op == "load":
        cfg = "main"
        if isinstance(arg, dict):
            cfg, arg = arg.get("config", "main"), arg.get("args", [])
        out = {"op": op, "args": ["--config", str(work / f"{cfg}.yaml")] + subst(arg, env)}
        if loader != "java":
            out["loader"] = loader
        return out
    return {"op": op, "args": subst(arg, env)}


PYTHON = "^python"   # the matrix's Python row (LK-7): a case's loads by the Python path, its lake on the local disk, Delta (design 3.3)


def jobs(cases, scratch, stores=None, storages=("local",), loaders=("java",)):
    out = []
    rows = [(f, s, "java") for f in cases for s in storages if "java" in loaders]
    if "python" in loaders:
        rows += [(f, "local", "python") for f in cases]
    for f, storage, loader in rows:
        c = yaml.safe_load(f.read_text(encoding="utf-8"))
        name = str(f.relative_to(CASES).with_suffix(""))
        for store in c.get("stores", ["delta", "iceberg", "files"]):
            if stores is not None and store not in stores:
                continue
            if loader == "python" and store != "delta":
                continue                                             # the Python path writes Delta tables
            if storage != "local" and (store not in ("delta", "iceberg") or c.get("local-only")):
                continue                                             # the memory and S3 rows: Delta and Iceberg tables (files: local disk)
            if storage in (c.get("not-on") or {}):
                continue                                             # a storage the case cannot run on, with the reason in the case
            cid = f"{name}@{store}" + ROW.get(storage, "") + (PYTHON if loader == "python" else "")
            work = scratch / cid.replace("/", "_").replace("~", "_").replace("^", "_")
            work.mkdir(parents=True)
            memory = f"tck{len(out)}" if storage == "mem" else ""
            remote = f"mem://{memory}" if memory else f"s3://tck/{len(out)}" if storage == "s3" else None
            lake = f"{remote}/lake" if remote else str(work / "lake")
            env = {"work": str(work), "lake": lake, "in": str(work / "in"), "store": store}
            for n, spec in (c.get("inputs") or {}).items():
                write_input(work / "in", n, spec)
            configs = c.get("configs") or {"main": c["config"]}
            for k, y in configs.items():
                (work / f"{k}.yaml").write_text(subst(y, env), encoding="utf-8")
            steps = [step(s, work, env, loader) for s in c["steps"]]
            dumps = []
            for t in c.get("tables", ["risk/scenario-pv"]):
                dom, tab = t.split("/")
                d = {"name": t, "format": store}
                d.update({"path": f"{lake}/{dom}/{tab}"} if store == "delta" else
                         {"root": lake, "domain": dom, "table": tab})
                dumps.append(d)
            job = {"case": cid, "steps": steps, "dumps": dumps}
            if memory:
                job["memory"] = memory
            out.append((job, str(work), remote, storage))
    return out


def product_classpath():
    """This repository's loader and its run-time class path, packaged here (never installed)."""
    java = ROOT / "java"
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    subprocess.run(["./mvnw", "-q", "-o", "package", "dependency:build-classpath", "-DskipTests", "-pl", "lake-load,lake-memory,lake-tck", "-am",
                    "-Dmdep.outputFile=target/tck-classpath.txt", "-Dmdep.includeScope=runtime"], cwd=java, env=env, check=True)
    return os.pathsep.join(dict.fromkeys(module_cp(java, "lake-load") + module_cp(java, "lake-memory")))


def s3_classpath():
    """The S3 row's addition: lake-tck (the S3 simulator, S3Mock, test support only), packaged with the rest by product_classpath()."""
    return os.pathsep.join(module_cp(ROOT / "java", "lake-tck"))


def python_command(scratch):
    """The Python path's command for the runner (-Dtck.python): the wheel built from python/ into the scratch folder, run by uv with the
    pins of tools/ci/interop-pins.txt and Polars (the path uses Polars when it is installed), offline first."""
    uv = shutil.which("uv") or str(Path.home() / ".local" / "bin" / "uv")
    dist = scratch / "dist"
    subprocess.run([uv, "build", "--offline", "--quiet", "--wheel", "--out-dir", str(dist), str(ROOT / "python")], check=True)
    wheel = next(dist.glob("drishti_lake-*.whl"))
    pins = [l.strip() for l in (ROOT / "tools" / "ci" / "interop-pins.txt").read_text().splitlines() if l.strip() and not l.startswith("#")]
    withs = [x for p in pins + [str(wheel), "polars==2.1.0", "pyyaml"] for x in ("--with", p)]
    return [uv, "run", "--offline", "--quiet", "--no-project"] + withs + ["python", "-I", "-m", "drishti_lake", "load"]


def module_cp(java, m):
    jar = next(j for j in sorted((java / m / "target").glob(m + "-*.jar")) if not j.name.endswith("-tests.jar"))
    return [str(jar)] + [p for p in (java / m / "target" / "tck-classpath.txt").read_text().strip().split(os.pathsep)
                         if not p.endswith("-tests.jar")]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", required=True)
    ap.add_argument("--classpath-file", help="the class path to run over (default: this repository's built modules)")
    ap.add_argument("--runner", default=str(ROOT / "tools" / "tck" / "TckRunner.java"))
    ap.add_argument("--only", help="run the cases whose name starts with this")
    ap.add_argument("--storages", default="local,mem,s3",
                    help="local, mem, s3 (comma separated; the reference: local; the host's kit has no memory or S3 row)")
    ap.add_argument("--stores", help="run these stores only (comma separated; the reference: delta,iceberg); default every store a case names")
    ap.add_argument("--loaders", default="java,python",
                    help="java, python (comma separated; the reference: java): the Python row runs each Delta case's loads by the Python path")
    a = ap.parse_args(argv)
    cases = sorted(CASES.rglob("*.yaml"))
    if a.only:
        cases = [c for c in cases if str(c.relative_to(CASES)).startswith(a.only)]
    cp = Path(a.classpath_file).read_text().strip() if a.classpath_file else product_classpath()
    scratch = Path(tempfile.mkdtemp(prefix="lake-tck-", dir=os.environ.get("LAKE_TCK_TMP")))   # the caller's scratch folder
    try:
        loaders = a.loaders.split(",")
        js = jobs(cases, scratch, set(a.stores.split(",")) if a.stores else None, a.storages.split(","), loaders)
        java = str(Path(JAVA_HOME) / "bin" / "java")
        results = []
        # one runner for the local and memory rows; the S3 row in its own, with the simulator's class path (never on the others')
        for group in ("plain", "s3"):
            mine = [j for j in js if (j[3] == "s3") == (group == "s3")]
            if not mine:
                continue
            jf, rf, log = scratch / f"jobs-{group}.jsonl", scratch / f"results-{group}.jsonl", scratch / f"runner-{group}.log"
            jf.write_text("".join(json.dumps(j) + "\n" for j, _, _, _ in mine), encoding="utf-8")
            extra = [f"-Dtck.python={json.dumps(python_command(scratch))}"] if any(j[0]["case"].endswith(PYTHON) for j in mine) else []
            run_cp = cp
            if group == "s3":
                run_cp = cp + os.pathsep + s3_classpath()
                extra += [f"-Dtck.s3={scratch / 's3mock'}"]
            with open(log, "w") as lf:
                p = subprocess.run([java, "-Xmx2g", f"-Djava.io.tmpdir={scratch}"] + extra + ["-cp", run_cp, a.runner, str(jf), str(rf)],
                                   cwd=scratch, stdout=lf, stderr=subprocess.STDOUT)
            if p.returncode != 0:
                print(f"the runner ({group}) failed:\n" + log.read_text()[-4000:], file=sys.stderr)
                return 1
            results += rf.read_text(encoding="utf-8").splitlines()
        works = {j["case"]: (w, m) for j, w, m, _ in js}
        lines = {}
        for l in results:
            r = json.loads(l)
            text = json.dumps(r, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
            work, remote = works[r["case"]]
            text = text.replace(work, "${work}").replace(str(scratch), "${tmp}")
            if remote:
                text = text.replace(remote, "${work}")               # a memory or S3 lake's root reads as the local one's
            lines[r["case"]] = text
        Path(a.out).write_text("".join(lines[k] + "\n" for k in sorted(lines)), encoding="utf-8")
        bad = [k for k in sorted(lines) if json.loads(lines[k])["status"] != "ok"]
        print(f"{len(lines)} results -> {a.out}" + (f"; errors: {bad}" if bad else ""))
        return 0
    finally:
        shutil.rmtree(scratch, ignore_errors=True)

if __name__ == "__main__":
    sys.exit(main())
