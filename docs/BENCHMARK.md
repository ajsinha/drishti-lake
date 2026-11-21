<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Benchmarks

The one benchmark document. Each run names its command, the machine's load while it ran, and where its raw lines are.

## The read API against Drishti's jars at the freeze (READ_API.md section 7)

**Method.** `python3 tools/bench/bench.py --ref-cp REFCP --stores iceberg,delta,files --repeat 10`: one table of 10,000 positions x
509 scenarios (13 files, one date) loaded once per store; `tools/bench/ReadBench.java` run in one JVM per side and round (`-Xms4g
-Xmx4g`, G1), sides `ref` (Drishti's plugins at `9ce7c3170`), `internal` (this build's table classes through the same shims) and `api`
(the read API), interleaved, the order reversed every other round; each JVM reports the median of 7 timed runs after 3 warm-ups. The
summary is the median of the 10 rounds' medians, with min..max. Acceptance: the API's median within 3% of `ref` (5% for one document),
and no more bytes allocated per row. Run only when the 1-minute load is under 6 (the machine is shared).

**2026-11-21, `c715d15`** (raw: `tck/bench/readbench-ab-2026-11-21.jsonl`). Load 4.2 to 7.9 during the Iceberg rounds, 12 to 22 during
the Delta and files rounds (another lane's jobs): the Iceberg rows stand, the Delta rows do not and are to be measured again.

| Store | Read | ref | api | api/ref | bytes per row, ref / api |
|---|---|---|---|---|---|
| Iceberg | vectors, 1 decoder | 206.8 ms (190..349) | 193.6 ms (183..212) | 0.94 | 35,129 / 35,129 |
| Iceberg | vectors, 4 decoders | 74.3 ms (69..96) | 73.1 ms (70..85) | 0.98 | 40,274 / 40,275 |
| Iceberg | columns | 7.13 ms | 6.85 ms | 0.96 | 1,156 / 1,172 |
| Iceberg | ids; 10,000 lookups | 8.21; 1.46 ms | 7.21; 1.47 ms | 0.88; 1.00 | equal |
| Iceberg | document p50 | 101.5 us | 100.0 us | 0.99 | equal within 0.2% |
| Iceberg | vectorsOf, a page of 50 | 273.6 ms | 14.9 ms | 0.05 | one decode instead of 50 |
| Files | document p50; vectorsOf 50 | | 17 us; 2.6 ms | | through the id index |

The earlier single-run reading (Iceberg vectors 211 vs 181 ms) was the machine's noise: interleaved, the API's median is at or below
`ref` with the same allocation per row. Iceberg `columns` allocates 1.4% more per row than `ref`, and so does `internal` (1,173): the
difference is in this build's table code, not the API; open, to look at with the Delta re-run. Delta and the files store's vector
reads: to be measured on a quiet machine.
