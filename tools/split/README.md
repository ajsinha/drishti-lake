<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Reference comparison tools

The parity kit's results over the product are compared with **reference results**: the kit run over the lake code as it was before it moved here
(`tck/results/reference-9ce7c3170.jsonl.gz`). These scripts make and use them; [docs/TCK.md](../../docs/TCK.md) says where they sit in the check.

| File | What |
|---|---|
| `make_reference.py` | runs the kit over the original code's jars (a clone of its repository, built in a scratch folder, never changed; `--drishti`, `--commit`, `--out`, `--keep`, `--no-build`); `l3_rename_packages.py` holds the package map it reads |
| `compare_results.py` | `REFERENCE.jsonl LAKE.jsonl`: every case equals its reference line (differences only as listed in `known-differences.txt`), every store equals its Delta case, the memory, S3 and Python rows equal their local Java rows |
| `known-differences.txt` | the cases that may differ from the reference, each with its reason |
| `lake_models.py`, `LakeModelDump.java` | the lake files read by both code bases to the same model |
| `contracts_proof.py` | the contract readers and pages against a host's contract files |
| `bench/` | the same-package shims the read benchmark ([BENCHMARK.md](../../docs/BENCHMARK.md)) runs the original code through |
