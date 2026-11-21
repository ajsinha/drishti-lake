/*
 * Drishti Lake · Delta, Iceberg and plain files, loaded and read as one lake.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.lake.load;

import com.ash.lake.LakeRuntime;
import com.ash.lake.delta.table.DeltaRows;
import com.ash.lake.delta.write.LakeWriter;
import com.ash.lake.spi.AsOf;
import com.ash.lake.spi.Capabilities;
import com.ash.lake.spi.Column;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.DateChange;
import com.ash.lake.spi.RowScan;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableStore;
import com.ash.lake.spi.TableVersion;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import io.delta.kernel.Scan;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.data.ArrayValue;
import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.exceptions.TableNotFoundException;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.ArrayType;
import io.delta.kernel.types.BinaryType;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampNTZType;
import io.delta.kernel.types.TimestampType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;

/**
 * The Delta store: tables in the lake's layout ({@code <root>/<domain>/<table>}, partitioned by {@code business_date}), written by the
 * loader through {@link DeltaTarget} and read through Delta Kernel on the runtime's native engine. Kernel's types stay inside: the
 * columns and rows are the product's.
 */
public final class DeltaStore implements TableStore {

    public static final String FORMAT = "delta";

    private DeltaStore() {
    }

    public static DeltaStore create() {
        return new DeltaStore();
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.all();
    }

    private static Optional<Snapshot> snapshot(LakeRuntime runtime, TableRef t, Engine[] engine) {
        engine[0] = LakeWriter.engineFor(runtime, t.location(), Map.of());
        try {
            return Optional.of(Table.forPath(engine[0], t.location()).getLatestSnapshot(engine[0]));
        } catch (TableNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * The snapshot {@code asOf} names, through the Kernel's snapshot at a version or a timestamp (a time at or after the newest commit
     * reads the newest; a version the log no longer holds, or a time before the first commit, is refused).
     */
    private static Optional<Snapshot> snapshot(LakeRuntime runtime, TableRef t, AsOf asOf, Engine[] engine) throws IOException {
        Optional<Snapshot> latest = snapshot(runtime, t, engine);
        if (latest.isEmpty() || asOf.latest()) {
            return latest;
        }
        Table table = Table.forPath(engine[0], t.location());
        try {
            if (asOf.version() != null) {
                return asOf.version() == latest.get().getVersion() ? latest : Optional.of(table.getSnapshotAsOfVersion(engine[0], asOf.version()));
            }
            long ms = asOf.time().toEpochMilli();
            return ms >= latest.get().getTimestamp(engine[0]) ? latest : Optional.of(table.getSnapshotAsOfTimestamp(engine[0], ms));
        } catch (io.delta.kernel.exceptions.KernelException | IllegalArgumentException e) {
            throw new IOException(asOf.missing(t.location()), e);
        }
    }

    /**
     * The versions the log holds, oldest first, with their commit times: the Kernel's commit range from the oldest version it still
     * reads (0, else the newest checkpoint's) to the newest.
     */
    @Override
    public List<TableVersion> versions(LakeRuntime runtime, TableRef table) throws IOException {
        Engine[] e = new Engine[1];
        Optional<Snapshot> latest = snapshot(runtime, table, e);
        List<TableVersion> out = new ArrayList<>();
        if (latest.isEmpty()) {
            return out;
        }
        long end = latest.get().getVersion();
        long checkpoint = latest.get().getLogSegment().getCheckpointVersionOpt().orElse(0L);
        for (long start : new long[] {0, checkpoint}) {
            try {
                io.delta.kernel.CommitRange range = io.delta.kernel.TableManager.loadCommitRange(table.location(),
                        io.delta.kernel.CommitRangeBuilder.CommitBoundary.atVersion(start))
                        .withEndBoundary(io.delta.kernel.CommitRangeBuilder.CommitBoundary.atVersion(end)).build(e[0]);
                try (CloseableIterator<io.delta.kernel.CommitActions> commits = range.getCommitActions(e[0],
                        Set.of(io.delta.kernel.internal.DeltaLogActionUtils.DeltaAction.COMMITINFO))) {
                    while (commits.hasNext()) {
                        try (io.delta.kernel.CommitActions c = commits.next()) {
                            out.add(new TableVersion(c.getVersion(), java.time.Instant.ofEpochMilli(c.getTimestamp())));
                        } catch (IOException | RuntimeException x) {
                            throw x;
                        } catch (Exception x) {
                            throw new IOException(x.getMessage(), x);
                        }
                    }
                }
                return out;
            } catch (io.delta.kernel.exceptions.KernelException | IllegalArgumentException older) {
                out.clear();                                    // the oldest commits are gone (log retention): from the checkpoint on
            }
        }
        out.add(new TableVersion(end, java.time.Instant.ofEpochMilli(latest.get().getTimestamp(e[0]))));
        return out;
    }

    @Override
    public boolean exists(LakeRuntime runtime, TableRef table) {
        return snapshot(runtime, table, new Engine[1]).isPresent();
    }

    @Override
    public Columns columns(LakeRuntime runtime, TableRef table) throws IOException {
        Snapshot s = snapshot(runtime, table, new Engine[1]).orElseThrow(() -> new IOException("no Delta table at " + table.location()));
        return DeltaRows.columns(s.getSchema());
    }

    @Override
    public SortedMap<LocalDate, Long> dates(LakeRuntime runtime, TableRef table) throws IOException {
        return exists(runtime, table) ? StoreScans.dates(runtime, FORMAT, table) : new java.util.TreeMap<>();
    }

    @Override
    public RowScan scan(LakeRuntime runtime, TableRef table, ScanRequest request) throws IOException {
        Engine[] e = new Engine[1];
        Optional<Snapshot> found = snapshot(runtime, table, request.asOf(), e);
        if (found.isEmpty()) {
            return RowScan.of(Columns.of(List.of()), List.of());
        }
        Engine engine = e[0];
        Snapshot s = found.get();
        Columns cols = DeltaRows.columns(s.getSchema());
        StoreScans rows = new StoreScans(cols, request);
        Set<String> partitions = Set.copyOf(s.getPartitionColumnNames());
        Scan scan = s.getScanBuilder().build();
        Row state = scan.getScanState(engine);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
        var facts = DeltaRows.facts(s, cols);
        try (CloseableIterator<FilteredColumnarBatch> files = DeltaRows.scanFilesWithStats(scan, engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> fileRows = files.next().getRows()) {
                    while (fileRows.hasNext()) {
                        Row file = fileRows.next();
                        String day = InternalScanFileUtils.getPartitionValues(file).get("business_date");
                        DeltaRows.FileFacts f = facts.apply(file);
                        if (!rows.keep(day == null ? null : LocalDate.parse(day), f.partition(), f.bounds()::get)) {
                            continue;                                   // a file the dates or the where's pushdown rule out
                        }
                        DeltaRows.read(engine, state, physical, file, cols, partitions, rows::add);
                    }
                }
            }
        }
        return rows.scan();
    }

    @Override
    public TableWriter writer(WriteSpec spec) throws IOException {
        return new DeltaTarget(spec);
    }

    @Override
    public com.ash.lake.read.LakeReader open(LakeRuntime runtime, com.ash.lake.read.LakeAddress address) {
        return com.ash.lake.delta.table.DeltaLakeReader.open(runtime, address);
    }

    @Override
    public List<DateChange> deleteDates(LakeRuntime runtime, TableRef table, Set<LocalDate> dates, boolean dryRun) throws IOException {
        return StoreScans.delete(runtime, FORMAT, table, dates, dryRun);
    }

    @Override
    public List<DateChange> undo(LakeRuntime runtime, TableRef table, String loadId, boolean dryRun) throws IOException {
        List<DateChange> out = new ArrayList<>();
        for (LakeUndo.Done d : LakeUndo.undoTable(runtime, table.location(), table.name(), loadId, dryRun)) {
            out.add(new DateChange(LocalDate.parse(d.date()), d.removed(), d.restored(), dryRun ? -1 : d.version()));
        }
        return out;
    }
}
