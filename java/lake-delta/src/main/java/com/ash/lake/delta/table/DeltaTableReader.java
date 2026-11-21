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
package com.ash.lake.delta.table;

import com.ash.lake.read.TableIdentity;
import com.ash.lake.read.TableReader;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.AsOf;
import io.delta.kernel.Snapshot;
import io.delta.kernel.Table;
import io.delta.kernel.exceptions.TableNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** One table of a {@link DeltaLakeReader}: its snapshots, kept per version (the newest four). */
final class DeltaTableReader implements TableReader {

    private static final int KEPT = 4;

    private final DeltaLakeReader lake;
    private final String name;
    private volatile DeltaTable table;
    private final Map<Long, DeltaSnapshot> snapshots = new LinkedHashMap<>();

    DeltaTableReader(DeltaLakeReader lake, String name) {
        this.lake = lake;
        this.name = name;
    }

    DeltaTable delta() {
        DeltaTable t = table;
        if (t == null) {
            t = new DeltaTable(lake.lake.engine(), lake.lake.table(name), lake.idColumn, lake.docColumn, lake.dateColumn);
            table = t;
        }
        return t;
    }

    synchronized void forget() {
        snapshots.clear();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public TableIdentity identity() throws IOException {
        DeltaTable t = delta();
        Optional<Snapshot> s = kernel(AsOf.LATEST);
        if (s.isPresent()) {
            return new TableIdentity("delta", DeltaTable.metadataId(s.get()), t.path());
        }
        String id = t.identity();                               // no table yet: where it would be
        return new TableIdentity("location", id.substring(id.indexOf(':') + 1), t.path());
    }

    /** The Kernel's snapshot {@code asOf} names (a time at or after the newest commit reads the newest); empty: no table. */
    private Optional<Snapshot> kernel(AsOf asOf) throws IOException {
        DeltaTable t = delta();
        Table table = Table.forPath(t.engine(), t.path());
        Snapshot latest;
        try {
            latest = table.getLatestSnapshot(t.engine());
        } catch (TableNotFoundException e) {
            return Optional.empty();
        }
        if (asOf.latest()) {
            return Optional.of(latest);
        }
        try {
            if (asOf.version() != null) {
                return Optional.of(asOf.version() == latest.getVersion() ? latest : table.getSnapshotAsOfVersion(t.engine(), asOf.version()));
            }
            long ms = asOf.time().toEpochMilli();
            return Optional.of(ms >= latest.getTimestamp(t.engine()) ? latest : table.getSnapshotAsOfTimestamp(t.engine(), ms));
        } catch (io.delta.kernel.exceptions.KernelException | IllegalArgumentException e) {
            throw new IOException(asOf.missing(t.path()), e);
        }
    }

    @Override
    public Optional<TableSnapshot> snapshot(AsOf asOf) throws IOException {
        Optional<Snapshot> s;
        try {
            s = kernel(asOf);
        } catch (IOException | RuntimeException e) {
            lake.problems.failed(name, "log", -1, e);
            throw e;
        }
        lake.problems.ok(name, "log");
        if (s.isEmpty()) {
            return Optional.empty();
        }
        long version = s.get().getVersion();
        synchronized (this) {
            DeltaSnapshot kept = snapshots.get(version);
            if (kept != null) {
                return Optional.of(kept);
            }
        }
        DeltaSnapshot made;
        try {
            made = new DeltaSnapshot(lake, name, delta(), delta().layout(s.get()));
        } catch (RuntimeException e) {
            lake.problems.failed(name, "log", version, e);
            throw e;
        }
        if (asOf.latest()) {
            lake.problems.version(name, version);
        }
        synchronized (this) {
            snapshots.put(version, made);
            while (snapshots.size() > KEPT) {
                snapshots.remove(snapshots.keySet().iterator().next());
            }
        }
        return Optional.of(made);
    }
}
