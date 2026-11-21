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
package com.ash.lake.iceberg;

import com.ash.lake.iceberg.expr.Expr;
import com.ash.lake.iceberg.io.DrishtiFileIO;
import com.ash.lake.iceberg.io.FileAccess;
import com.ash.lake.iceberg.meta.Snapshot;
import com.ash.lake.iceberg.meta.TableMetadata;
import com.ash.lake.iceberg.read.TaskReader;
import com.ash.lake.iceberg.scan.FileTask;
import com.ash.lake.iceberg.scan.Planner;
import com.ash.lake.iceberg.types.NestedField;
import com.ash.lake.iceberg.types.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.apache.iceberg.HasTableOperations;

/**
 * One Iceberg table: Apache Iceberg's own {@link org.apache.iceberg.Table} for metadata, planning and commits, Drishti's Parquet reader
 * for rows. The metadata view is swapped atomically, so readers on any thread see one version or the next, never a mix.
 */
public final class Table {

    private static final long RENEW_BEFORE_MS = 5 * 60_000L;

    private final String name;
    private final Supplier<org.apache.iceberg.Table> reload;
    private volatile org.apache.iceberg.Table table;
    private volatile TableMetadata view;

    /** @param reload loads the table again, for fresh vended credentials (null: its file access never expires) */
    public Table(String name, org.apache.iceberg.Table table, Supplier<org.apache.iceberg.Table> reload) {
        this.name = name;
        this.table = table;
        this.reload = reload;
    }

    public String name() {
        return name;
    }

    /** Apache Iceberg's table. */
    public org.apache.iceberg.Table iceberg() {
        return table;
    }

    private org.apache.iceberg.TableMetadata current() {
        return ((HasTableOperations) table).operations().current();
    }

    public TableMetadata metadata() {
        org.apache.iceberg.TableMetadata c = current();
        TableMetadata v = view;
        if (v == null || v.iceberg() != c) {
            v = new TableMetadata(c);
            view = v;
        }
        return v;
    }

    /** The metadata file the current version was read from. */
    public String metadataLocation() {
        return current().metadataFileLocation();
    }

    /** The files of this table (through the catalog's vended credentials, when it gives any). */
    public FileAccess io() {
        return ((DrishtiFileIO) table.io()).access();
    }

    /** Re-reads the metadata; true when it changed. */
    public boolean refresh() {
        if (reload != null && ((DrishtiFileIO) table.io()).expiring(RENEW_BEFORE_MS)) {
            try {
                table = reload.get();
            } catch (RuntimeException e) {
                // the catalog is unreachable: the old credentials serve until they really expire
            }
        }
        org.apache.iceberg.TableMetadata before = current();
        table.refresh();
        return current() != before && !java.util.Objects.equals(current().metadataFileLocation(), before.metadataFileLocation());
    }

    public Optional<Snapshot> currentSnapshot() {
        return metadata().currentSnapshot();
    }

    /** The data files of a snapshot that may hold rows matching {@code filter}, with their deletes. */
    public List<FileTask> plan(Snapshot snapshot, Expr filter, ExecutorService pool) {
        return Planner.plan(table, metadata().schemaFor(snapshot), snapshot, filter, pool);
    }

    /**
     * The live rows of a planned file: the columns {@code ids} (top-level field ids of {@code schema}, the snapshot's)
     * in that order, row groups pruned by {@code filter}.
     */
    public TaskReader rows(FileTask task, Schema schema, List<Integer> ids, Expr filter, boolean pageIndex) {
        return rows(task, schema, ids, filter, pageIndex, 0, Integer.MAX_VALUE);
    }

    /** As above, the file's row groups {@code [fromGroup, toGroup)} only (a piece of the file, for parallel decoding). */
    public TaskReader rows(FileTask task, Schema schema, List<Integer> ids, Expr filter, boolean pageIndex, int fromGroup, int toGroup) {
        List<NestedField> projection = ids.stream().map(id -> {
            NestedField f = schema.field(id);
            if (f == null) {
                throw new IllegalArgumentException("no column " + id + " in schema " + schema.schemaId() + " of " + name);
            }
            return f;
        }).toList();
        try {
            return new TaskReader(task, metadata(), schema, projection, filter, io(), pageIndex, fromGroup, toGroup);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + task.file().path(), e);
        }
    }

    /** The uncompressed bytes of each row group of {@code task}'s data file, from its footer (the runtime's footer cache). */
    public long[] rowGroups(FileTask task) throws IOException {
        FileAccess io = io();
        String p = io.resolve(task.file().path());
        org.apache.parquet.io.InputFile in = com.ash.lake.io.ParquetInput.of(io.storage(p), p, task.file().fileSizeInBytes());
        try (org.apache.parquet.io.SeekableInputStream stream = in.newStream()) {
            return com.ash.lake.io.ParquetFooters.of(io.runtime()).footer(task.file().path(), task.file().fileSizeInBytes(), in, stream,
                    com.ash.lake.io.ParquetFooters.options(io.codecs()).build()).getBlocks().stream().mapToLong(b -> b.getTotalByteSize()).toArray();
        }
    }

    @Override
    public String toString() {
        return name + " (" + table.location() + ")";
    }
}
