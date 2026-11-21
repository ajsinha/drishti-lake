<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti Lake. Proprietary: see LICENSE. -->
# The read API: design

What a host's source adapters read a lake through. Written 2026-11-21 against `main` `0ed2fc3`; **design only, nothing built**. It
answers [SPLIT_STATUS](../SPLIT_STATUS.md) "The host's read API" (the 14 areas) and the G4 rehearsal's L1 (Drishti branch
`wip/g4-rehearsal`, `docs/plans/G4_REHEARSAL.md` section 4), under [the design's](DRISHTI_LAKE_DESIGN.md) LK-4, LK-6 and LK-13.

## Contents

1. [The answer in one page](#1-the-answer-in-one-page)
2. [The API](#2-the-api)
3. [Every host call, mapped](#3-every-host-call-mapped)
4. [Per store](#4-per-store)
5. [Iceberg and `source.partition-by`](#5-iceberg-and-sourcepartition-by)
6. [TCK cases to add](#6-tck-cases-to-add)
7. [Performance proof](#7-performance-proof)
8. [Build steps](#8-build-steps)
9. [Decisions RA-1 to RA-12](#9-decisions-ra-1-to-ra-12)
10. [Not verified while writing](#10-not-verified-while-writing)

## 1. The answer in one page

- **One new public package, `com.ash.lake.read` (in `lake-api`), and one new method on the SPI**: `TableStore.open(runtime, address)`
  gives a `LakeReader` (a lake's tables, health, problems, purges); `LakeReader.table(name)` gives a `TableReader`;
  `TableReader.snapshot(asOf)` gives an immutable `TableSnapshot`; every read is a method of the snapshot. Whole-row scans stay
  `TableStore.scan` and get pushdown (2.7). There is no second way to do any of it.
- **Own types only** (LK-13): records, interfaces, `String`, `double[]`, `String[]`, `java.time`, `java.nio.file.Path`. The Kernel's
  `Row`, `StructType`, `Engine`, `ColumnVector` and Iceberg's `FileScanTask`, `DataFile`, `Table` stay behind the snapshot's
  implementation class in each store's `internal` package. `ApiSurfaceTest` already fails the build on a library type.
- **The same API on Delta, Iceberg, files, and each of them on `mem://`.** No new capability may be declared missing. What a store
  does not do today is listed in section 4 as a **defect with the step that closes it** (deletes applied in column and vector reads,
  file statistics in the files store, the files table's identity, Iceberg `partition-by`).
- **The fast paths are today's code, moved behind an interface of the same shape.** A vector row is still delivered into one
  buffer per decoding piece, reused for every row (`VectorSink.row(id, buf, length, texts)`); promoted columns still come back as
  the `double[]` and `String[]` the reader filled, handed over without a copy. The adapter's sink is the host's own sink (same four
  arguments), so the cut adds one interface call per row and no allocation. Section 7 says how that is measured before it is believed.
- **The adapters get smaller.** Of the Delta adapter's calls into the table layer, problem recording (22 calls in the two adapters), column-mapping
  names, the Kernel schema tests and the file-row unpacking disappear into the product; what remains is in section 3.
- **Cost: about 19 builder days in this repository** (section 8), three lanes at most at once, before Drishti's 5-day adapter
  rewrite (rehearsal L1) can start. The rewrite's estimate stands; this design removes its unknowns, not its days.

## 2. The API

All in `com.ash.lake.read` unless marked. Signatures are the contract; names of private helpers are not.

### 2.1 Opening a lake (areas 1, 2, 12, 13)

```java
// com.ash.lake.spi.TableStore: the one addition
LakeReader open(LakeRuntime runtime, LakeAddress address) throws IOException;

/** Where a lake's tables of one domain are and how they are reached: what a connector's settings say, already resolved. */
public record LakeAddress(String root, String domain, Map<String, String> settings, Map<String, String> tableNames) {
    /** From a connector's settings (root, domain, storage options, catalog, relocate, table.<name>): validated here, once. */
    public static LakeAddress of(LakeRuntime runtime, Map<String, String> settings);
}

public interface LakeReader extends AutoCloseable {
    String format();                                   // delta, iceberg, files
    List<String> tables() throws IOException;          // every table of the domain now
    List<String> newTables(Set<String> known) throws IOException;   // loaded after start: those not in known
    TableReader table(String name);                    // cheap; nothing is read until a snapshot is asked for
    LakeHealth health(Instant now);
    ReadProblems problems();
    void refresh();                                    // newest versions are read again at the next snapshot()
    void purge();                                      // every cache the product holds for this lake (footers, snapshots, id maps it mapped)
    void purge(LocalDate date);
    @Override void close();
}

/** reachable: the root answers; describe: the place in plain words; engine: the reading engine's name; notes: TLS expiry, proxy in force. */
public record LakeHealth(boolean reachable, boolean memoryOnly, String describe, String engine, List<String> notes) {}
```

- The S3 Tables catalog (`catalog: s3tables`, its ARN) and `relocate` pairs are **settings of the address**. `LakeAddress.of`
  expands and validates them (a pair that cannot be read fails there, with the `LK-` code of today's message); the host no longer
  calls `S3Tables.named/arnOf/expand` or `Relocation.parse`.
- `table.<name>` settings (today's `NamedTables`, `LakeTables.Named`) are `tableNames`; `tables()` returns the host's names.
- No `Engine` accessor. `purge()` replaces `NativeEngine.purgeFooterCache()`: the host never names an engine.

### 2.2 A table and its snapshots (areas 3, 4, 10, 11)

```java
public interface TableReader {
    String name();
    TableIdentity identity() throws IOException;
    /** The table at asOf (AsOf.LATEST, a version, a time). Empty: no table then. Snapshots are cached per version by the reader. */
    Optional<TableSnapshot> snapshot(AsOf asOf) throws IOException;
}

/** kind: the format's own id when the table carries one (delta, iceberg, files), else "location". The same on every replica. */
public record TableIdentity(String kind, String id, String location) {
    public String text();                              // "delta:<id>", "iceberg:<uuid>", "files:<id>", "location:<host>:<path>"
}

public interface TableSnapshot {
    long version();                                    // Delta version, Iceberg snapshot id, files commit number
    Instant committed();
    long sequence();                                   // rises with every commit (Iceberg sequence number; the version elsewhere)
    Columns columns();                                 // spi.Columns: logical names and the product's types
    String idColumn();
    Map<String, String> promoted();                    // document path -> column, for the paths the table keeps as columns
    Set<String> vectorColumns();
    boolean deletes();                                 // any date carries deletion vectors or delete files
    NavigableSet<LocalDate> dates();
    Optional<DayFiles> day(LocalDate date);            // from the log or manifests alone: no data file is read

    IdMap ids(LocalDate date) throws IOException;                                   // 2.3
    Optional<String> document(LocalDate date, int file, String id) throws IOException;
    void documents(LocalDate date, DocumentSink sink) throws IOException;
    ColumnBlock columns(LocalDate date, List<ColumnAsk> asks) throws IOException;  // 2.4
    void vectors(VectorRequest request, VectorSink sink) throws IOException;       // 2.5
    Map<String, Map<String, double[]>> vectorsOf(LocalDate date, int file, Set<String> ids, List<String> columns) throws IOException;
    void scanFile(LocalDate date, int file, List<String> columns, Expr where, RowSink sink) throws IOException;   // 2.6
}

/** One business date of a snapshot. stamp: changes when and only when the date's files change (the host's cache key). */
public record DayFiles(LocalDate date, List<DataFile> files, long rows, long bytes, long bytesUncounted, int filesUncounted,
        boolean deletes, String stamp) {}

/** One data file. rows: -1 when not stated. partition: the file's constant columns. bounds: per column, where the store states them. */
public record DataFile(String path, long size, FileFormat format, long rows, boolean deletes, Map<String, String> partition,
        Map<String, Bounds> bounds) {
    public Optional<Path> local();                     // the file on this machine's disk, for a host engine that reads it directly
}
public enum FileFormat { PARQUET, JSONL }
/** A column's statistics in one file as text; null: not stated (a value the writer may have cut is not stated). */
public record Bounds(String min, String max, Long nulls) {}
```

- `stamp` is one text on every store: the SHA-256 of the date's `path:size` lines, sorted, first 16 hex digits (today's Delta
  `filesStamp`). Iceberg's `fingerprint` (a long) is retired for it: one rule (RA-6). The host's id-map files are keyed by it, so
  Iceberg's id-map files are rebuilt once after G4.
- `bounds` keys are **logical** column names. Column mapping (`col-<uuid>`) never reaches the host: `Layout.phys` stays inside.
- `rows`, `bytes`, `bytesUncounted`, `filesUncounted` are today's `DeltaTable.DayCount` and Iceberg's `Day.rows`.

### 2.3 Id maps and documents (areas 5, 6)

```java
public interface IdMap {
    int size();
    String id(int i);                                  // the i-th id in order
    int file(String id);                               // index into DayFiles.files(), or -1
    long bytes();                                      // heap held (a mapped map: its small header)
    String[] ids();
}
public final class IdMaps {
    public static MappedIdMap write(IdMap map, Path file) throws IOException;   // the product's one id-map file format
    public static MappedIdMap open(Path file) throws IOException;
}
public interface MappedIdMap extends IdMap, AutoCloseable {
    long fileBytes();
    boolean closed();
    @Override void close();
}
@FunctionalInterface public interface DocumentSink { void document(String id, String json); }
```

- `ids(date)` already applies "the newest load wins" and deletes (today's `shadow` and `seen`): the host never sees a shadowed id,
  so `DeltaTable.shadow/seen/newestLast/severalLoads` have no public form.
- The two `MappedIdMap` classes of today (Delta's and Iceberg's, 82 and 83 lines, the same idea twice) become one format here.
  **Where** the files live, their TTL, size cap and idle release stay the host's (`IdMapFolder`): the product writes and maps a
  file it is given a path for and keeps no folder of its own (RA-5).
- `document(..)` returns the stored document; vector markers are filled by the host from `vectorsOf` as today (the marker format
  is the loader's, so the fill moves here too: `Documents.withVectors(json, vectors)`, replacing the two copies in the adapters).

### 2.4 Promoted columns (area 7)

```java
public record ColumnAsk(String path, String column, boolean numeric) {}    // the host's path, the table's column, read as a number

/** ids and one array per asked path, all of the same length. The arrays are the reader's own, handed over: not copied, not kept. */
public record ColumnBlock(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts) {}
```

- Exactly today's `DeltaTable.columns` and `KindTable.columns` result, without the host's `ColumnSet` type: a null number is
  `NaN`, repeated texts share one `String` (interning up to 200,000 distinct values per column), a partition column's value is
  filled from the file's `partition`. The adapter builds its `ColumnSet` around the same arrays: `new ColumnSet(b.ids(),
  b.numbers(), b.texts(), date)`.
- The physical-column map the host needs is `TableSnapshot.promoted()` (path to logical column) and `columns()` (types): that is
  what `promotedColumns(kind, layout)` computes today from the Kernel schema.
- **Deletes are applied by the read** (today both adapters give up on a date with deletes and read documents instead). The fast
  path is taken per file: a file without deletes is read as today; a file with them goes through the store's selection. See 4.

### 2.5 Vectors (area 9)

```java
/** One vector column of one date with some text columns. keep: which files to read (statistics pruning). decoders: 1 = one thread. */
public record VectorRequest(LocalDate date, String column, List<String> texts, Predicate<DataFile> keep, int decoders, long pieceBytes) {
    public static VectorRequest of(LocalDate date, String column, List<String> texts);
    public VectorRequest keep(Predicate<DataFile> keep);
    public VectorRequest parallel(int decoders, long pieceBytes);
}

@FunctionalInterface
public interface VectorSink {
    /** values[0..length) is the row's vector (length -1: null). values and texts are reused for the next row: copy what is kept. */
    void row(String id, double[] values, int length, String[] texts);
}
```

- The same four arguments as the host's sink, on purpose: the adapter passes `hostSink::row`.
- Delivery is **in file order** on the caller's thread whatever `decoders` is, as today. The ordered parallel decode
  (`VectorReads.inFileOrder`, the row-group pieces of about `pieceBytes`) moves into the product with the request's two numbers;
  the host's `VectorReads.parallel(sink, ..)` marker is translated by the adapter into `request.parallel(..)` (RA-4).
- `float32` vectors are widened exactly; a null vector is `length -1`; text columns are read in the table's type and given as text.
- `vectorsOf(date, file, ids, columns)` is the page read (one decode of a file's vector columns for several ids). The
  coalescing window (`VectorBatches`: concurrent single-id asks become one read) is the host's fetch pattern and goes back to the
  adapter, 104 lines on public types (RA-7).

### 2.6 File-level scans and pruning (area 8)

```java
@FunctionalInterface public interface RowSink { void row(Object[] values); }   // one array per call, reused; values as spi.Row's
```

- `scanFile(date, file, columns, where, sink)` is `KindTable.scan(l, day, file, columns, idEquals, sink)` with `Expr` in place of
  the one `idEquals`; the store pushes `where` to row groups where it can and tests every row regardless.
- Pruning is the host's rule over `DataFile.bounds()` (a VaR shard's `KeyRange.mayHold(min, max, mayHaveNulls)`), given to
  `VectorRequest.keep` or used to choose files for `scanFile`. `textBounds` has no other public form.

### 2.7 Pushdown in `TableStore.scan` (area 14)

No signature changes. `ScanRequest.where` is handed to the store, which uses it three times: dates (partition pruning), files
(`Bounds`), rows (the per-row test of today, kept as the last word, so a store that prunes nothing is still right). A scan reports
what it skipped: `RowScan.stats()` returns `ScanStats(long filesRead, long filesSkipped, long rowsRead, long rowsReturned)` (a
default method; the kit asserts on it).

### 2.8 Problems (area 2, second half)

```java
public interface ReadProblems {
    List<ReadProblem> all();
    boolean isEmpty();
    String summary(int max);
    Optional<String> listing(String table);            // the last listing of the table failed: why, and what is served meanwhile
}
public record ReadProblem(String table, String where, long version, String reason, Instant at) {}
```

Every read of 2.2 to 2.6 **records its own failure and its own recovery**: it throws `LakeException` with the classified reason
(today's `TableProblems.classify`: a missing file, a moved table, access refused) and the next good read of the same table and date
clears it. The adapters' 18 `problems.failed(..)`/`problems.ok(..)` calls (13 Delta, 5 Iceberg) and the 4 version and listing notes go; they catch `LakeException` only to map the code.

### 2.9 What stays out

Maintenance, writes, undo and retention are not read API (the loader and `lake maintain` own them). The host's caches (day parts,
column sets, documents, the disk document cache, the type-ahead index) stay the host's: the product gives stamps and purges, not a
cache policy. `Row`-per-document objects are not offered on the fast paths: a `Row` per vector would be the allocation this design exists to avoid.

## 3. Every host call, mapped

Counts are call sites in `DeltaSourcePlugin`, `DeltaLakeFiles`, `MappedIdMap` (Delta) and `IcebergSourcePlugin` (Iceberg) at the
freeze commit `9ce7c3170`. "gone": the product does it inside the call named.

| # | Area | Delta adapter today | Iceberg adapter today | Read API |
|---|---|---|---|---|
| 1 | open | `LakeStore.of(root, domain, settings)` | `LakeTables.open(settings)`, `S3Tables.named/arnOf/expand`, `Relocation.parse` | `Stores.of(runtime, format).open(runtime, LakeAddress.of(runtime, settings))` |
| 1 | discovery | `lake.tables()`, `lake.table(kind)`, `NewTables.find(lake, known, add)` | `lake.kinds()`, `lake.table(kind, pool)` | `LakeReader.tables()`, `.table(name)`, `.newTables(known)` |
| 2 | health | `lake.reachable()` ×3, `.describe()` ×3, `.engineName()` ×2, `.tls()` ×3, `.proxy()` ×2, `.engine()`, `.close()` | `lake.reachable()`, `.describe()` ×2, `.tls()` ×2, `.proxy()`, `.close()` | `LakeReader.health(now)`, `.close()`; no `engine()` |
| 2 | problems | `problems.failed` ×7, `.ok` ×6, `.version`, `.listingFailed`, `.listingOk` ×2, `.listing`, `.isEmpty`, `.summary` | `problems.failed` ×2, `.ok` ×3, `.of`, `.forReaders`, `.isEmpty`, `.summary` | recorded by the reads; `ReadProblems.listing/isEmpty/summary/all` |
| 3 | snapshot | `t.layout(knownAt)` ×2, `l.version()` ×4, `l.timestamp()` ×5 | `t.current()`, `t.snapshotAt(at)`, `t.layout(id)`, `t.refresh()`, `l.snapshotId()` ×3, `l.timestamp()` | `TableReader.snapshot(asOf)`, `.version()`, `.committed()`; `LakeReader.refresh()` |
| 3 | files of a date | `l.files()` ×14, `InternalScanFileUtils.getAddFileStatus` (path, size) | `l.days()` ×18, `d.files()`, `d.date()` ×12 | `snapshot.dates()`, `.day(date)`, `DayFiles.files()`, `DataFile.path/size/format` |
| 3 | cache stamp | `DeltaTable.filesStamp(l, date)` | `d.fingerprint()` ×5, `t.sequenceNumber(id)` | `DayFiles.stamp()`, `snapshot.sequence()` |
| 4 | deletes | `l.deletionVectors()` ×4 | `l.deletes()`, `day.deletes()` | `snapshot.deletes()`, `DayFiles.deletes()`, `DataFile.deletes()`; the fallbacks they guard go (2.4) |
| 5 | id maps | `t.ids(l, date)`, `ids.size/id/file/ids`, `MappedIdMap.write/open` | `t.ids(l, day)`, `t.file(id)` ×6, `MappedIdMap`, `disk.idMap(kind, day, read)` | `snapshot.ids(date)`, `IdMap`, `IdMaps.write/open` |
| 6 | document | `t.doc(l, date, file, id)`, `t.read`, `t.forEachDoc` | `t.doc(l, day, file, id)`, `IcebergVectors.documents`, `.withVectors` | `snapshot.document(..)`, `.documents(..)`, `Documents.withVectors` |
| 7 | columns | `t.columns(l, date, pathToColumn, numeric)`, `l.schema()` ×4 (Kernel type tests), `l.phys(..)`, `table.idColumn()` | `t.columns(l, day, pathToColumn)`, `l.hasColumn`, `l.numeric`, `IcebergLayout.column(path)` ×2 | `snapshot.columns(date, asks)`, `.columns()`, `.promoted()`, `.idColumn()` |
| 8 | pruning, file scan | `DeltaTable.textBounds(file, phys)` | `kt.textBounds(l, day, file, column)`, `t.scan(..)` | `DataFile.bounds()`, `snapshot.scanFile(..)` |
| 9 | vectors | `t.vectors(l, date, column, texts, keep, sink)`, `t.vectorsOf(l, date, file, ids, cols)`, `vectorBatches.get` | `IcebergVectors.vectors(..)`, `.vectorsOf(..)` | `snapshot.vectors(request, sink)`, `.vectorsOf(..)`; `VectorBatches` in the adapter |
| 10 | counts | `t.counts(version)` | `d.rows()` ×2 | `DayFiles.rows/bytes/bytesUncounted/filesUncounted` |
| 11 | identity | `t.identity()` | `t.uuidAndLocation()` | `TableReader.identity()` |
| 12 | purges | `NativeEngine.purgeFooterCache()` | `disk.purge()`, `disk.purgeDate(date)` (the host's own caches) | `LakeReader.purge()`, `.purge(date)` |
| 13 | library types | imports `io.delta.kernel.engine.Engine`; uses `kernel.data.Row`, `kernel.types.*`, `InternalScanFileUtils` | `KindTable.Day/IdMap/Layout`, `com.ash.drishti.iceberg.catalog.S3Tables` | none |
| 14 | pushdown | not used (`scan` is the Arrow route, the host's) | not used | `TableStore.scan` with `ScanStats` |

`DeltaLakeFiles.of` (the pivot engine's direct route) becomes: `snapshot.deletes()` false is no longer asked (RA-3: the host's
engine reads raw files, so the route is still refused when `DayFiles.deletes()`), every file's `DataFile.local()` present, then
paths, `promoted()`, `idColumn()`, `bytes`, `rows`. On the files store `format` is `JSONL`: the host decides whether its engine reads it.

`IcebergExternalPlugin` (tables not in the layout: `ExternalTable`, `Shape`, `KindConfig`; it imports `com.ash.drishti.iceberg.Table`,
`Catalog`, `Catalogs`, `S3Tables`, `JsonValues`) reads through the same API: `LakeAddress.settings` carries the table's shape (`id`,
`date`, the columns that make the document), and `document(..)` assembles the document from columns. It is step RA-S8, after the
layout readers, and is the one place where the Delta and files stores get a new feature for parity (a shaped read of a foreign
table of their format). RA-9 asks whether that is wanted in the first release.

## 4. Per store

One implementation class per store, in its `internal` package, holding the library's objects; one shared abstract base in
`lake-io` (`internal`) for what is the same everywhere (newest-wins shadow, id-map build, interning, ordered parallel delivery,
problem recording), so the rule is written once.

| | Delta (`lake-delta`) | Iceberg (`lake-iceberg`) | Files (`lake-files`) | `mem://` |
|---|---|---|---|---|
| Snapshot | `DeltaTable.Layout` behind `TableSnapshot`; the Kernel rows stay in it | `KindTable.Layout`; `FileScanTask`s stay in it | a commit of `_log/<table>/<n>.json` | the store's own reader on the memory storage: nothing separate |
| `DataFile` | add action: path, size, `numRecords`, `partitionValues`, `minValues/maxValues/nullCount` mapped back to logical names | manifest entry: record count, partition tuple, lower and upper bounds, null counts | `<n>.jsonl` of the date; rows and bounds **from the commit** (defect F1) | as its format |
| Deletes | deletion vectors. Today: ids honour them, columns and vectors do not. **Defect D1**: columns and vectors apply the Kernel's selection for files that carry one | positional and equality delete files. Today the adapter falls back to documents. **Defect I1**: column, vector and file reads through iceberg-core's delete filter | none exist: a delete is a new commit of the date. `deletes` is always false, truthfully | as its format |
| Id map | `DeltaTable.ids` | `KindTable.ids` (`Sorted`) | a pass over the date's lines (id only) | as its format |
| Columns | `DeltaTable.columns` (batch loop unchanged) | `KindTable.columns` over `scan` | typed columns of each line, into the same arrays | as its format |
| Vectors | `DeltaTable.vectors`, `pieces`, `fileVectors` unchanged; `VectorSink` type swapped | `IcebergVectors.vectors`; **defect I2**: row-group pieces as Delta's, so a few large files decode on every thread | number scanner straight from the line's bytes into the reused buffer, no `List<Double>`; pieces are byte ranges cut at line ends | as its format |
| Identity | `delta:<metaData.id>`. Today read from commit 0 on a local disk only. **Defect D2**: from the snapshot's metadata on every storage | `iceberg:<uuid>` | **Defect F2**: `columns.json` gets an `id` at creation; a table without one answers `location:` until its next commit writes it | as its format; a memory lake's location is its `mem://` name (no host name) |
| Time travel | versions | snapshots | commits | as its format |
| Statistics for pruning | written by the loader today | written by iceberg-core | **Defect F1**: the commit records rows and, per promoted text column, min, max, nulls (values of 32 characters or more not stated, as Delta) | as its format |
| Pushdown (2.7) | dates and files from the log; row groups through the Kernel predicate | iceberg-core's scan filter | dates and files from the commit; rows tested | as its format |
| `local()` | `file:` paths | `file:` paths | `file:` paths | never: the bytes are in the heap |

Defects, each closed by a step of section 8 and none declared in `tck/capabilities.yaml`: **D1, D2, I1, I2, F1, F2**, and **P1**
(section 5). Speed is not a parity feature: the files store must give the same answers through the same calls; its vector read is
measured and published (section 7), not held to Parquet's number (RA-10).

## 5. Iceberg and `source.partition-by`

Today `LoadConfig` refuses it for Iceberg ("source.partition-by is Delta's"). The reason was the layout's readers: they assume one
identity partition, the business date. Closing it (**P1**):

1. **Write.** `NativeLayout.schema/open` take the partition fields: the spec is `identity(business date)` then `identity(field)`
   for each, in the order given; the fields are columns as Delta's are. `writeDay` fans out by the fields' values. A table that
   exists with another spec is refused with Delta's words ("give the table's partition-by, or write a new table"): no spec evolution.
2. **Read.** `dateIndex` already finds the date field by name; `DataFile.partition()` carries the other fields from the manifest's
   tuple; column and vector reads fill a partition field from it (iceberg-core's constants map), exactly what Delta's read does
   from `partitionValues`.
3. **Maintenance.** `relayout`, `SortedRuns` and `keepDays` keep the spec (rewrite within a partition tuple, not within a date).
4. **Files store.** Not verified here whether it accepts the setting or ignores it (section 10). The rule to hold it to: accepted,
   the fields are typed columns, `DataFile.partition()` is empty or the file's constants, and the answers equal Delta's.
5. **Proof.** The parity case `layout/partition-by-beside-the-date` on every store; `config:source.partition-by` leaves
   `tck/coverage-baseline.txt`; a table written by the loader with two partition fields is read by iceberg-core alone (interop).

## 6. TCK cases to add

Each runs on Delta, Iceberg, files and their `mem://` rows (and the `~s3` row where the simulator can); the files and Iceberg lines
must equal the Delta line. New `store:` verbs in `TckRunner` print through the read API only (`read-days`, `read-ids`, `read-doc`,
`read-columns`, `read-vectors`, `read-identity`, `read-health`, `read-problems`, `scan-stats`). Paths, stamps and identities are
printed in a normalised form (file count, sizes summed, stamp equality between two reads) so the lines compare across stores.

| Area | Case | Shows |
|---|---|---|
| read/discovery | `tables-and-new-tables` | `tables()`, a table loaded after open is in `newTables`, named tables |
| read/health | `reachable-empty-missing` | health of a good lake, an empty one, a root that is not there; the same words |
| read/health | `problem-recorded-and-cleared` | a data file removed: the read fails with the classified reason, `problems()` lists it; restored: cleared |
| read/snapshot | `days-files-and-counts` | per date: files, rows, bytes, uncounted; after a replace only that date's stamp changes |
| read/snapshot | `as-of-version-and-time` | the read API over the cases of `time-travel/versions-and-earlier-rows` |
| read/ids | `ids-and-file-of-id` | sorted ids, `file(id)`, an absent id is -1; two loads of one date: the newest wins |
| read/ids | `mapped-equals-heap` | `IdMaps.write` then `open`: every call equal to the heap map; closed after `close()` |
| read/document | `by-id-and-all` | `document` for each id equals the scan's document; vector markers filled |
| read/columns | `numbers-texts-nulls` | arrays equal the scan's values; null number is NaN; a text column stored as a number reads as its text |
| read/columns | `partition-column-filled` | a `partition-by` field is filled in `columns` and `vectors` texts (needs P1 on Iceberg) |
| read/vectors | `all-rows-in-file-order` | every row once, in file order, with 1 and with 4 decoders: identical output |
| read/vectors | `float32-and-null-vector` | exact widening; length -1 |
| read/vectors | `keep-prunes-files` | a `keep` over `bounds`: the pruned read equals the filtered full read; fewer files read |
| read/vectors | `of-ids-in-one-file` | `vectorsOf` for 1, 3 and an absent id |
| read/deletes | `deleted-rows-not-read` | after a row delete (deletion vector, delete file, files commit): ids, documents, columns, vectors all without the row (D1, I1) |
| read/identity | `same-after-reopen-differs-per-table` | stable across two opens and two runtimes; two tables differ; survives `relocate` |
| read/pushdown | `scan-skips-files` | `ScanStats.filesSkipped > 0` and rows equal to the unfiltered scan filtered by hand |
| read/purge | `purge-then-read` | `purge()` and `purge(date)` then every read still answers the same |
| layout | `partition-by-beside-the-date` | section 5 |
| read/shape | `foreign-table-shaped` | RA-S8 only: a table not in the layout read through a shape |

20 cases × 3 stores (+ memory rows). Unit tests beside them: `ApiSurfaceTest` (the new package is in the dump, no library type),
`TwoRuntimesTest` extended (two readers of one lake in two runtimes share no cache), `StartOnFewCoresTest` extended (eight
`vectors` reads on one carrier thread do not hang: the start-up deadlock class).

## 7. Performance proof

**Claim to prove:** through the public API, the three hot reads cost what they cost today on Delta and Iceberg, in time and in
allocation. "Today" is Drishti's jars at the freeze commit `9ce7c3170`, driven as `tools/split/make_reference.py` already drives them.

- **Harness.** `lake-tck`: `ReadBench` (a `main`, not a test; no new dependency: `System.nanoTime` and
  `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`). The same class body is compiled against the reference jars (calling
  `DeltaTable`/`KindTable` through a same-package shim under `tools/split/`) and against this build (calling the read API).
  `tools/ci/bench.py` builds the lake once, runs both, writes `docs/BENCHMARK.md` (one benchmark document). Not part of `check.py`.
- **Data, within the local limit.** One scenario-P&L table: 10,000 positions × 509 scenarios (`pv` generator, about 41 MB of
  doubles), 4 files, 6 promoted columns, one date; a second date with 10% of rows deleted. One heavy job at a time; temp files removed on exit.
- **Measures**, median of 7 timed runs after 3 warm-ups, single JVM flags for both sides:

| Read | Measure | Accept |
|---|---|---|
| `vectors` 1 decoder, 4 decoders | rows/s; bytes allocated per row | time within 3% of today; allocation per row not above today's (expected equal: one buffer per piece) |
| `columns` (6 paths, 3 numeric) | ms; bytes allocated | within 3%; allocation not above today's |
| `ids` build; `IdMaps.open` + 10,000 `file(id)` | ms; ns per lookup | within 3% |
| `document` by id (1,000 random) | p50, p99 µs | within 5% (small numbers, noisier) |
| `vectorsOf` a page of 50 ids | ms | within 3% |
| `vectors`, `columns` on the date with deletes | rows/s | published; no "today" to compare (the fallback read documents) |
| files store: all of the above | as measured | published, with the ratio to Delta (RA-10) |
| `scan` with a selective `where`, pushdown on | ms, files skipped | faster than today's per-row test; published |

- **Structural checks, cheaper than timing and run in `check.py`:** a test sink asserts the `values` array identity is the same
  for consecutive rows of one piece (the buffer is reused); `ColumnBlock` arrays are the arrays the reader filled (no copy on return:
  identity through a package-private probe); the allocation-per-row figure of a 1,000-row read is under a fixed byte bound.
- **At G4, in Drishti:** VaR and ES goldens identical on the pin; the VaR read stage of its own benchmark within 3% of its last
  recorded run. That is the proof that counts; the product's bench exists so a regression is caught here, weeks earlier.
- **If a number misses:** the cut is an interface call per row, so a miss means megamorphic dispatch at the sink. The fallback,
  decided now so nobody improvises: a batch form `VectorSink.rows(String[] ids, double[] flat, int[] offsets, int count,
  String[][] texts)` that the reader fills per decoded batch. It is **not** in the API unless the bench demands it (RA-11).

## 8. Build steps

Grades as the design's section 7: F Fable, O Opus, S Sonnet, H Haiku. At most three lanes at once (A, B, C); every step ends
green on `tools/ci/check.py` and rewrites `java/api-surface.txt` in the same commit.

| # | Step | Lane | Needs | Grade | bd |
|---|---|---|---|---|---|
| RA-S0 | Owner decisions RA-1 to RA-12 | | | F | 0.5 |
| RA-S1 | `com.ash.lake.read` types and `TableStore.open` in `lake-api`; the shared base (`lake-io`, internal); `ReadBench` and the reference shim, with "today's" numbers recorded **before** any reader is touched | A | S0 | O | 2 |
| RA-S2 | Delta reader over `DeltaTable`: 2.1 to 2.6, 2.8; D2 (identity from metadata); the `read/*` verbs in `TckRunner` and the first 12 cases on Delta | A | S1 | O | 3 |
| RA-S3 | Iceberg reader over `KindTable`/`IcebergVectors`; I2 (row-group pieces); one stamp rule; S3 Tables and relocation through `LakeAddress` | B | S1 | O | 3 |
| RA-S4 | Files reader; F1 (statistics in the commit), F2 (identity); the byte-level vector scanner | C | S1 | O (scanner), S | 2.5 |
| RA-S5 | Deletes applied in columns, vectors, file scans: D1 (Delta), I1 (Iceberg); case `read/deletes` | A then B | S2, S3 | O | 2 |
| RA-S6 | Iceberg `partition-by` (P1): writer, reader, maintenance, the case, the coverage line | B | S3 | O | 2 |
| RA-S7 | Pushdown in `scan` on the three stores, `ScanStats`, its case | C | S2 to S4 | S, O reviews | 1.5 |
| RA-S8 | Shaped reads of foreign tables (for `IcebergExternalPlugin`), if RA-9 says now | B | S3 | O | 2 |
| RA-S9 | Remaining cases to 20, memory and `~s3` rows, the extended `TwoRuntimesTest` and `StartOnFewCoresTest`; `bench.py`, `docs/BENCHMARK.md`; the old package-private entry points the adapters used made unreachable (moved to `internal`) | A | S2 to S7 | S, H for case files | 2 |
| RA-S10 | Docs: the read API in the API guide, SPLIT_STATUS, CHANGELOG; the rehearsal's L1 answered in writing for Drishti (the table of section 3 with final names) | C | S9 | S | 0.5 |
| | **Total** (S8 included) | | | | **21** (19 without S8) |

Order: S0, S1; then S2, S3, S4 side by side; S5, S6, S7 side by side; S8; S9; S10. About 8 working days elapsed with three lanes.
Drishti is not touched; its adapter rewrite (rehearsal L1, 5 bd, Opus) starts at G4 on the tagged pin.

## 9. Decisions RA-1 to RA-12

| # | Decision | Recommendation |
|---|---|---|
| RA-1 | Where the read API lives | **A new public package `com.ash.lake.read` in `lake-api`, entered only through `TableStore.open`.** A third party's store implements the same interface; nothing is found by name or on the class path (LK-6) |
| RA-2 | Snapshot object or stateless calls | **An immutable `TableSnapshot`; every read is its method.** It is what both adapters already hold (`Layout`), it makes a consistent read across calls the default, and it is where the library's objects hide |
| RA-3 | Deletes in column and vector reads | **The product applies them; the adapters' fall-back to documents is deleted.** A read that silently ignores deletes on one store and refuses on another is the parity defect. The pivot engine's direct-file route stays refused on a date with deletes (the host's engine reads raw files) |
| RA-4 | Parallel vector decoding | **In the product, asked for by the request (`decoders`, `pieceBytes`), delivered in file order.** The host keeps only its marker and translates it |
| RA-5 | Id-map files | **One format, the product's (`IdMaps`); the folder, TTL and cap stay the host's.** The Delta and Iceberg copies fold into one |
| RA-6 | The cache stamp | **One rule on every store (hash of `path:size`); Iceberg's `fingerprint` retired.** Cost: Iceberg id-map files rebuilt once after G4 |
| RA-7 | `VectorBatches` (coalescing window) | **Back to the host's adapter**, on `vectorsOf`. It is a fetch pattern of pages, not a table rule; alternative: a `read.vector-batch-window` setting inside the reader (rejected: a hidden wait in a library call) |
| RA-8 | Problem recording | **Inside the reads; the host only reads the list.** 22 call sites go; the classification is written once instead of once per store |
| RA-9 | Foreign-table shapes (RA-S8) in the first release | **Yes, but last**: without it `IcebergExternalPlugin` keeps library imports and LK-13 is not met at G4. If time is short, it is the one step to move after G4, with the plugin listed as an exemption with an end date |
| RA-10 | The files store's speed | **Same answers, same calls, measured and published; not held to Parquet's number.** No binary sidecar for vectors: that would be a second storage of the same data (one way per job). A site that needs VaR speed uses Delta or Iceberg, and the guide says so with the ratio |
| RA-11 | A batch form of `VectorSink` | **Not now.** Only if the bench shows the per-row call costs more than 3%; then it replaces the row form, it is not added beside it |
| RA-12 | Iceberg `partition-by` | **Close it as section 5, identity fields after the date, no spec evolution**, in the same train as the read API because the reader must fill partition columns anyway |

## 10. Not verified while writing

- Nothing was built or run: no signature was compiled, no benchmark exists yet, `check.py` was not run for this document.
- The call counts of section 3 come from a text search of the three adapter files, not from the compiler; calls made through
  local variables with other names may be missed. RA-S2 and RA-S3 start by compiling the adapters' call list against the new types.
- `IcebergExternalPlugin`/`ExternalTable` were read by their imports only; the shape of RA-S8 needs its own half page before it is built.
- Whether the files store accepts or ignores `source.partition-by` today (no reference to it in `lake-files`).
- How the Kernel and iceberg-core expose delete selection to a raw file read at the versions pinned in `lake-bom` (D1, I1 are
  sized on the assumption that both do; if the Kernel does not for raw Parquet reads, D1 grows by about a day).
- "Zero-copy" is used as the task used it: no copy **added** by the API. Today's reads already copy each vector once from the
  decoded column into the reused buffer; this design keeps that and adds none.

## Decisions taken (lead, under the owner's delegation, 2026-11-21)

RA-1 to RA-12 are **accepted as recommended** (including RA-3 the product applies deletes, RA-6 one stamp rule with Iceberg id-map files rebuilt once, RA-7 `VectorBatches` back to the adapter, RA-9 foreign-table shapes last, RA-10 the files store measured but not held to Parquet speed, no sidecar). Feature parity stays absolute (owner rule); speed parity is not required for the files store. The unverified points of section 10 are checked first in RA-S0.

## Checked in RA-S0 (2026-11-21)

- **Files store and `source.partition-by`**: accepted. `FilesWriter` writes each field as a typed column (`'p'`, text) of every line;
  `DataFile.partition()` is empty for a files table (the values are in the lines). No change needed for P1 beyond the case.
- **Delta deletes (D1)**: the Kernel 4.4.0 applies a file's deletion vector in `Scan.transformPhysicalData` (already used by `ids` and
  `doc`, with a scan state for the narrow read schema); the product's Parquet reader (`ParquetBatches`) serves the `ROW_INDEX` metadata
  column. D1 is sized as written: a file with a deletion vector goes through a narrow scan state, a file without one stays raw.
- **Iceberg deletes (I1)**: the product's own reader applies position and equality deletes in every file read (`read/Deletes` in
  `TaskReader`), and `KindTable.ids/columns/scan` and `IcebergVectors` all read through it: the table layer is already right; the
  documents fallback was the adapter's. I1 is the case plus removing the fallback at G4.
- **Call counts**: the adapters are host code (not compiled here); RA-S2/S3 compile the read API against the call list of section 3.
- **Today's numbers** (`tools/bench/bench.py`, `ReadBench` over Drishti's plugins at `9ce7c3170` built in a scratch folder with their
  own local repository, and the same shims over this build's table classes): `tck/bench/readbench-before-9ce7c3170.jsonl`. The table
  is 10,000 positions x 509 scenarios in 13 files (the loader's runs), one date. At a machine load of about 15 the same code differs
  between two JVMs by up to 40% on the reads under 20 ms (columns, ids): those rows are compared over repeated runs, not one.
