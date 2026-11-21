<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# Split tools

How the lake code was moved here with its history (step L3, [SPLIT_STATUS](../../docs/SPLIT_STATUS.md)). The host's repository is
never touched: everything runs on a fresh clone in a scratch folder.

```text
git clone --no-local --single-branch --branch main --no-tags <drishti> fresh && cd fresh && git reset --hard 9ce7c3170
git remote remove origin
git filter-repo --paths-from-file <this folder>/l3-paths.txt --filename-callback "$(sed -n '5,$p' <this folder>/l3_rename.py)"
# scan every blob of the result for size, keys and private data before anything is merged
cd <drishti-lake> && git fetch <fresh> main && git merge --allow-unrelated-histories FETCH_HEAD
python3 tools/split/l3_rename_packages.py java tck          # the rename commit
```

(`git filter-repo` from the distribution, or `uv run --with git-filter-repo git-filter-repo`.)

| File | What |
|---|---|
| `l3-paths.txt` | the reviewed path allow-list ([MOVE_ALLOWLIST](../../docs/MOVE_ALLOWLIST.md) says why, file by file) |
| `l3_rename.py` | the filename callback: each path's module and package here, over every path it had in the host's history |
| `l3_rename_packages.py` | the rename commit: packages, host types to `hostcompat`, the header line |

Later moves get an allow-list of their own here, as Rachana's A9 and A13 did: `l7-paths.txt` (L7, the Delta lake opener, the L3
callback), `l8-paths.txt` with `l8_rename.py` (L8, the files store). `make_reference.py` runs the reference's stores only
(`--stores delta,iceberg`); `compare_results.py` accepts the cases of a store the host never had (`BEYOND`).
