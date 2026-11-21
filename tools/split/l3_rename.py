# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Drishti Lake. Proprietary: see the LICENSE file at the root of this repository.
# Move step L3: the git filter-repo --filename-callback body that gives each allow-listed path (tools/split/l3-paths.txt) its
# place in this repository (module and package), over every path the file had in the host's history.
import re
if filename is None:
    return filename
f = filename.decode()
if f.startswith('tools/lake/testdata/'):
    return ('tck/fixtures/' + f[len('tools/lake/testdata/'):]).encode()
m = re.match(r'^(?:[a-z]+/)?(drishti-[a-z-]+)/src/(main|test)/(java|resources)/(.*)$', f)
if not m:
    raise SystemExit("unmapped " + f)
mod, scope, kind, rest = m.groups()
if kind == 'java':
    special = re.match(r'com/ash/drishti/(?:common|lakeload|memlake)/(FileDates|RowRetention|TempFiles|JvmTempDir)\.java$', rest)
    if special:
        return ('java/lake-io/src/main/java/com/ash/lake/common/' + special.group(1) + '.java').encode()
    for old, module, new in [
        ('com/ash/drishti/lakeio/', 'lake-io', 'com/ash/lake/io/'),
        ('com/ash/drishti/lakeconfig/', 'lake-config', 'com/ash/lake/config/'),
        ('com/ash/drishti/deltalake/', 'lake-delta', 'com/ash/lake/delta/'),
        ('com/ash/drishti/plugin/delta/', 'lake-delta', 'com/ash/lake/delta/table/'),
        ('com/ash/drishti/iceberg/', 'lake-iceberg', 'com/ash/lake/iceberg/'),
        ('com/ash/drishti/plugin/iceberg/', 'lake-iceberg', 'com/ash/lake/iceberg/table/'),
        ('com/ash/drishti/plugin/icebergext/', 'lake-iceberg', 'com/ash/lake/iceberg/external/'),
        ('com/ash/drishti/lakeload/', 'lake-load', 'com/ash/lake/load/'),
        ('com/ash/drishti/dataload/', 'lake-load', 'com/ash/lake/inbox/'),
        ('com/ash/drishti/testkit/', 'lake-tck', 'com/ash/lake/tck/'),
        ('com/ash/drishti/testroots/', 'lake-tck', 'com/ash/lake/tck/'),
    ]:
        if rest.startswith(old):
            return ('java/%s/src/%s/java/%s%s' % (module, scope, new, rest[len(old):])).encode()
    raise SystemExit("unmapped java " + f)
module = {'drishti-lakeio': 'lake-config', 'drishti-lakeconfig': 'lake-config', 'drishti-deltalake': 'lake-delta',
          'drishti-iceberg': 'lake-iceberg', 'drishti-plugin-iceberg': 'lake-iceberg',
          'drishti-lakeload': 'lake-load', 'drishti-dataload': 'lake-load'}.get(mod)
if module is None:
    raise SystemExit("unmapped resource " + f)
return ('java/%s/src/%s/resources/%s' % (module, scope, rest)).encode()
