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
package bench;

import com.ash.lake.read.ColumnAsk;
import com.ash.lake.read.IdMap;
import com.ash.lake.read.LakeAddress;
import com.ash.lake.read.LakeReader;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.read.VectorRequest;
import com.ash.lake.spi.AsOf;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The bench's side through the public read API (TableStore.open): what a host's adapter calls after G4. */
public final class ApiReads implements ReadBench.Reads {
    private final String store;
    private final com.ash.lake.LakeRuntime rt = com.ash.lake.LakeRuntime.fromProcess();
    private LakeReader lake;
    private TableSnapshot snap;
    private IdMap ids;
    private LocalDate date;

    public ApiReads(String store) {
        this.store = store;
    }

    @Override
    public void open(String root, String domain, String table, LocalDate date) throws Exception {
        lake = com.ash.lake.load.Stores.of(rt, store).open(rt, LakeAddress.of(root, domain));
        snap = lake.table(table).snapshot(AsOf.LATEST).orElseThrow();
        this.date = date;
    }

    @Override
    public int ids() throws Exception {
        ids = snap.ids(date);
        return ids.size();
    }

    @Override
    public String[] idList() {
        return ids.ids();
    }

    @Override
    public int fileOf(String id) {
        return ids.file(id);
    }

    @Override
    public void vectors(String column, List<String> texts, int decoders, ReadBench.Sink sink) throws Exception {
        snap.vectors(VectorRequest.of(date, column, texts).parallel(decoders, 64L << 20), sink::row);
    }

    @Override
    public int columns(Map<String, Boolean> paths) throws Exception {
        List<ColumnAsk> asks = new ArrayList<>();
        paths.forEach((p, n) -> asks.add(new ColumnAsk(p, snap.promoted().getOrDefault(p, p), n)));
        return snap.columns(date, asks).ids().length;
    }

    @Override
    public String document(int file, String id) throws Exception {
        return snap.document(date, file, id).orElse(null);
    }

    @Override
    public int vectorsOf(int file, Set<String> ids, List<String> columns) throws Exception {
        return snap.vectorsOf(date, file, ids, columns).size();
    }

    @Override
    public void close() {
        lake.close();
    }
}
