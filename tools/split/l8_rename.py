# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
# Move step L8: the git filter-repo --filename-callback body for tools/split/l8-paths.txt (the files store into lake-files),
# over every path the files had in the host's history.
import re
if filename is None:
    return filename
f = filename.decode()
m = re.match(r'^plugins/drishti-(?:connectors|plugin-file)/src/main/java/com/ash/drishti/plugin/file/(Jsonl\w+\.java)$', f)
if not m:
    raise SystemExit("unmapped " + f)
return ('java/lake-files/src/main/java/com/ash/lake/files/' + m.group(1)).encode()
