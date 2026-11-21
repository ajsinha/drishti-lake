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
package com.ash.lake.iceberg.table;

import com.ash.lake.read.TableIdentity;
import com.ash.lake.read.TableReader;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.AsOf;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** One table of an {@link IcebergLakeReader}: its snapshots, kept per snapshot id (the newest four). */
final class IcebergTableReader implements TableReader {

    private static final int KEPT = 4;

    private final IcebergLakeReader lake;
    private final String name;
    private volatile NativeKindTable table;
    private final Map<Long, IcebergSnapshot> snapshots = new LinkedHashMap<>();

    IcebergTableReader(IcebergLakeReader lake, String name) {
        this.lake = lake;
        this.name = name;
    }

    NativeKindTable kind() {
        NativeKindTable t = table;
        if (t == null) {
            t = (NativeKindTable) lake.lake.table(name, lake.pool);
            table = t;
        }
        return t;
    }

    void refresh() {
        if (table != null) {
            table.refresh();
        }
    }

    synchronized void forget() {
        snapshots.clear();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public TableIdentity identity() {
        Optional<String[]> u = kind().uuidAndLocation();
        if (u.isPresent() && u.get()[0] != null) {
            return new TableIdentity("iceberg", u.get()[0], u.get()[1]);
        }
        String where = lake.root + "/" + name;
        return new TableIdentity("location", where, where);
    }

    @Override
    public Optional<TableSnapshot> snapshot(AsOf asOf) throws IOException {
        NativeKindTable t = kind();
        Optional<Long> id = lake.problems.read(name, null, -1, () -> {
            Optional<Long> current = t.current();
            if (current.isEmpty() || asOf.latest()) {
                return current;
            }
            if (asOf.version() != null) {
                if (t.table().flatMap(x -> x.metadata().snapshot(asOf.version())).isEmpty()) {
                    throw new IOException(asOf.missing(name));
                }
                return Optional.of(asOf.version());
            }
            return t.snapshotAt(asOf.time());                // empty: a time before the first snapshot, no table then
        });
        if (id.isEmpty()) {
            return Optional.empty();
        }
        long v = id.get();
        synchronized (this) {
            IcebergSnapshot kept = snapshots.get(v);
            if (kept != null) {
                return Optional.of(kept);
            }
        }
        IcebergSnapshot made = lake.problems.read(name, null, v, () -> new IcebergSnapshot(lake, name, t, t.layout(v, true), t.schemaOf(v)));
        if (asOf.latest()) {
            lake.problems.version(name, v);
        }
        synchronized (this) {
            snapshots.put(v, made);
            while (snapshots.size() > KEPT) {
                snapshots.remove(snapshots.keySet().iterator().next());
            }
        }
        return Optional.of(made);
    }
}
