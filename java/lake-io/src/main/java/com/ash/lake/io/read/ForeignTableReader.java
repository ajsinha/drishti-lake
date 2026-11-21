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
package com.ash.lake.io.read;

import com.ash.lake.read.TableIdentity;
import com.ash.lake.read.TableReader;
import com.ash.lake.read.TableShape;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.AsOf;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * A foreign table behind the read API ({@code LakeReader.foreign}): a store's {@link ForeignSource} read through a {@link TableShape},
 * snapshots kept per version (the newest four). A shape the version cannot answer is refused ({@code LK-1001}) and is not a read problem;
 * a read that fails is recorded in the lake's problems ({@code LK-1006}).
 */
public final class ForeignTableReader implements TableReader {

    private static final int KEPT = 4;

    private final String name;
    private final TableShape shape;
    private final ForeignSource source;
    private final ExecutorService pool;
    private final Problems problems;
    private final Map<Long, ShapedSnapshot> snapshots = new LinkedHashMap<>();

    public ForeignTableReader(String name, TableShape shape, ForeignSource source, ExecutorService pool, Problems problems) {
        this.name = name;
        this.shape = shape;
        this.source = source;
        this.pool = pool;
        this.problems = problems;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String absence() {
        return source.missing();
    }

    @Override
    public TableIdentity identity() throws IOException {
        return source.identity();
    }

    @Override
    public Optional<TableSnapshot> snapshot(AsOf asOf) {
        Optional<ForeignSource.Version> v = problems.read(name, null, -1, () -> source.version(asOf));
        if (v.isEmpty()) {
            return Optional.empty();
        }
        long id = v.get().version();
        synchronized (this) {
            ShapedSnapshot kept = snapshots.get(id);
            if (kept != null) {
                return Optional.of(kept);
            }
        }
        ShapedTable resolved = ShapedTable.resolve(shape, v.get());
        ShapedSnapshot made = problems.read(name, null, id, () -> new ShapedSnapshot(name, v.get(), resolved, pool, problems));
        if (asOf.latest()) {
            problems.version(name, id);
        }
        synchronized (this) {
            snapshots.put(id, made);
            while (snapshots.size() > KEPT) {
                snapshots.remove(snapshots.keySet().iterator().next());
            }
        }
        return Optional.of(made);
    }

    /** Snapshots are made again from the table's newest state. */
    public synchronized void forget() {
        snapshots.clear();
    }
}
