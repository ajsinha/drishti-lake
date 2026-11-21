<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# The read API

How a host reads a lake: `com.ash.lake.read` (module `lake-api`), the same on Delta, Iceberg and files, and on each of them in memory. Own types
only (records, interfaces, `String`, `double[]`, `java.time`): no Delta Kernel, Iceberg or Parquet type is in a public signature, which a build
guard enforces. The reasoning and the measurements behind it: [design/READ_API.md](../design/READ_API.md), [BENCHMARK.md](../BENCHMARK.md).

```text
LakeRuntime -> TableStore.open(runtime, LakeAddress) -> LakeReader
                                                          LakeReader.table(name) -> TableReader
                                                                                      TableReader.snapshot(AsOf) -> TableSnapshot   (immutable)
```

| Type | What it gives |
|---|---|
| `LakeAddress` | where a domain's tables are: `LakeAddress.of(root, domain)`, or `of(runtime, settings)` from a connector's settings (`root`, `domain`, storage options, catalog, `relocate`, `table.<name>` for a table read from a folder of another name) |
| `LakeReader` | `format()`, `tables()`, `newTables(known)`, `health(now)`, `problems()`, `foreign(name, shape)` (a table another engine wrote), `refresh()`, `purge()`, `close()` |
| `TableReader` | `name()`, `identity()` (`delta:<id>`, `iceberg:<uuid>`, `files:<id>`), `snapshot(AsOf)` |
| `AsOf` | `AsOf.LATEST`, `AsOf.version(n)` (Delta version, Iceberg snapshot id, files commit number), `AsOf.time(instant)` (newest commit at or before it); a version no longer held is an `IOException` naming the table |
| `TableSnapshot` | `version()`, `committed()`, `columns()`, `idColumn()`, `promoted()`, `vectorColumns()`, `dates()`, `day(date)` (files, rows, bytes, per-file bounds: from the log alone, no data read), `ids(date)` (sorted `IdMap`), `document(date, file, id)`, `documents(date, sink)`, `columns(date, asks)` (promoted columns as arrays), `vectors(request, sink)`, `vectorsOf(date, file, ids, columns)`, `scanFile(date, file, columns, where, sink)`; deleted rows (deletion vectors, delete files) are never returned |
| `IdMaps` | a sorted id map written to and opened from a memory-mapped file (`IdMaps.write`, `open`) |
| `ReadProblems` | what could not be read (a missing file, a corrupt footer), recorded rather than thrown, so a reader serves what it can and says what it could not |

Whole-row scans, with projection and pushdown (`Expr`), stay on the store: `TableStore.scan(runtime, TableRef, ScanRequest)` returns a
`RowScan`. There is one way to do each job. Vector rows are delivered into one reused buffer per decoding piece (`VectorSink.row(id, buf,
length, texts)`), so no row is allocated on the way.

## Tutorial: one read, three stores

After the three loads of [LOADER.md](LOADER.md) (lakes `/work/jl-delta`, `/work/jl-iceberg`, `/work/jl-files`, domain `risk`, kind `trade`), this program reads
each. Run it with `java -cp "$CP" ReadEx.java /work` (the loader's class path):

```java
import com.ash.lake.LakeRuntime;
import com.ash.lake.load.Stores;
import com.ash.lake.read.*;
import com.ash.lake.spi.AsOf;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public class ReadEx {
  public static void main(String[] a) throws Exception {
    LakeRuntime rt = LakeRuntime.fromProcess();
    for (String format : List.of("delta", "iceberg", "files")) {
      LakeAddress where = LakeAddress.of(a[0] + "/jl-" + format, "risk");
      try (LakeReader lake = Stores.of(rt, format).open(rt, where)) {
        System.out.println(format + ": reachable=" + lake.health(Instant.now()).reachable() + " tables=" + lake.tables());
        TableSnapshot t = lake.table("trade").snapshot(AsOf.LATEST).orElseThrow();
        LocalDate day = t.dates().last();
        System.out.println("  dates=" + t.dates() + " promoted=" + t.promoted().keySet() + " rows on " + day + "=" + t.day(day).orElseThrow().rows());
        List<String> ids = new ArrayList<>();
        t.documents(day, (id, json) -> ids.add(id + " " + json.length() + " chars"));
        Collections.sort(ids);
        System.out.println("  documents: " + ids);
      }
    }
  }
}
```

The output, identical for the three stores (the libraries' own log lines are not shown):

```text
delta: reachable=true tables=[trade]
  dates=[2026-11-20] promoted=[DESK, BOOK, NOTIONAL] rows on 2026-11-20=3
  documents: [T-1 119 chars, T-2 73 chars, T-3 59 chars]
iceberg: reachable=true tables=[trade]
  ...the same two lines...
files: reachable=true tables=[trade]
  ...the same two lines...
```

A runtime built for a host (own catalog, extra storage providers such as `mem://`) uses `LakeRuntime.builder()` (`.options`, `.settings`,
`.catalog`, `.storage`, `.store`); `LakeRuntime.fromProcess()` reads the process's `-D` properties and environment, as the command lines do.
There is no static registry, no `ServiceLoader` and no hidden system-property read: two runtimes in one JVM are independent.
