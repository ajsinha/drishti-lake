# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
# Move step L8 part 2: the git filter-repo --filename-callback body for tools/split/l8b-paths.txt (the memory lake into lake-memory);
# the map is read from the file named by L8B_PATHS (old path, new path a line).
import os
global L8B
try:
    L8B
except NameError:
    L8B = {}
    for line in open(os.environ["L8B_PATHS"]):
        if line.strip() and not line.startswith("#"):
            o, n = line.split()
            L8B[o] = n
if filename is None:
    return filename
f = filename.decode()
if f not in L8B:
    raise SystemExit("unmapped " + f)
return L8B[f].encode()
