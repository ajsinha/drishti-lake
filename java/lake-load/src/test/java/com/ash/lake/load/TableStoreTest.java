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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.lake.LakeOptions;
import com.ash.lake.LakeRuntime;
import com.ash.lake.files.FilesStore;
import com.ash.lake.spi.Capabilities;
import com.ash.lake.spi.Capability;
import com.ash.lake.spi.ColumnType;
import com.ash.lake.spi.Columns;
import com.ash.lake.spi.DateChange;
import com.ash.lake.spi.Expr;
import com.ash.lake.spi.Row;
import com.ash.lake.spi.RowScan;
import com.ash.lake.spi.ScanRequest;
import com.ash.lake.spi.TableRef;
import com.ash.lake.spi.TableStore;
import com.ash.lake.spi.TableWriter;
import com.ash.lake.spi.WriteSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** The built-in stores through the {@link TableStore} SPI alone: the loader writes through it, and it reads, deletes dates and undoes. */
class TableStoreTest {

    private static final LocalDate D1 = LocalDate.parse(PvData.DATES[0]);
    private static final LocalDate D2 = LocalDate.parse(PvData.DATES[1]);

    @TempDir
    Path tmp;

    private LakeRuntime runtime(TableStore... stores) {
        return LakeRuntime.builder().options(LakeOptions.builder().home(tmp).tempDir(tmp.resolve("tmp")).build()).store(stores).build();
    }

    private TableRef load(LakeRuntime rt, String format) throws IOException {
        Path in = Files.createDirectories(tmp.resolve("in"));
        PvData.byDate(in, PvData.lines(40, 4, 2, 7));
        Path lake = tmp.resolve("lake-" + format);
        Path cfg = Files.writeString(tmp.resolve("load-" + format + ".yaml"), PvData.config(in + "/*", lake.toString(), format));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(LakeLoad.run(rt, new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(out))).as(out.toString()).isEqualTo(LakeLoad.OK);
        return new TableRef(lake.toString(), "risk", "scenario-pv");
    }

    private static List<Row> all(RowScan s) {
        List<Row> out = new ArrayList<>();
        try (s) {
            s.forEachRemaining(out::add);
        }
        return out;
    }

    /**
     * The same load and the same questions for every store; what a store declares it cannot do is asked too and must be refused with its
     * reason (a capability case, never a skip).
     */
    @ParameterizedTest
    @ValueSource(strings = {"delta", "iceberg", "files"})
    void readsDeletesAndUndoesThroughTheSpi(String format) throws IOException {
        LakeRuntime rt = runtime();
        TableRef t = load(rt, format);
        TableStore store = Stores.of(rt, format);
        Capabilities caps = store.capabilities();
        assertThat(store.format()).isEqualTo(format);
        assertThat(store.exists(rt, t)).isTrue();
        assertThat(store.exists(rt, new TableRef(t.root(), "risk", "absent"))).isFalse();

        Columns cols = store.columns(rt, t);
        assertThat(cols.types()).containsEntry("id", ColumnType.STRING).containsEntry("business_date", ColumnType.DATE);
        if (caps.supports(Capability.PROMOTED_COLUMNS)) {
            assertThat(cols.types()).containsEntry("QUANTITY", ColumnType.DOUBLE).containsEntry("SCENARIO_PV", ColumnType.DOUBLE_LIST);
        } else {
            assertThat(cols.names()).containsExactly("id", "business_date", "doc");
        }

        SortedMap<LocalDate, Long> dates = store.dates(rt, t);
        assertThat(dates.keySet()).containsExactly(D1, D2);
        List<Row> rows = all(store.scan(rt, t, ScanRequest.all()));
        assertThat((long) rows.size()).isEqualTo(dates.values().stream().mapToLong(Long::longValue).sum());
        assertThat(rows.get(0).get("business_date")).isInstanceOf(LocalDate.class);

        String column = caps.supports(Capability.PROMOTED_COLUMNS) ? "DESK" : "id";
        Object value = rows.stream().filter(r -> D1.equals(r.get("business_date"))).findFirst().orElseThrow().get(column);
        List<Row> some = all(store.scan(rt, t, ScanRequest.all().dates(D1).columns("id", column).where(Expr.eq(column, value))));
        assertThat(some).isNotEmpty().allSatisfy(r -> assertThat(r.get(column)).isEqualTo(value));
        assertThat((long) some.size()).isLessThan(dates.get(D1));
        assertThatThrownBy(() -> store.scan(rt, t, ScanRequest.all().columns("NOPE"))).isInstanceOf(IllegalArgumentException.class);

        List<com.ash.lake.spi.TableVersion> before = store.versions(rt, t);
        assertThat(before).isNotEmpty();
        assertThat(store.versions(rt, new TableRef(t.root(), "risk", "absent"))).isEmpty();

        List<DateChange> dry = store.deleteDates(rt, t, Set.of(D1), true);
        assertThat(dry).extracting(DateChange::date).containsExactly(D1);
        assertThat(store.dates(rt, t).keySet()).containsExactly(D1, D2);
        store.deleteDates(rt, t, Set.of(D1), false);
        assertThat(store.dates(rt, t).keySet()).containsExactly(D2);
        assertThat(all(store.scan(rt, t, ScanRequest.all().dates(D1)))).isEmpty();

        com.ash.lake.spi.TableVersion loaded = before.get(before.size() - 1);    // time travel: the rows before the delete, by version and time
        assertThat(store.versions(rt, t)).hasSizeGreaterThan(before.size()).startsWith(before.toArray(new com.ash.lake.spi.TableVersion[0]));
        assertThat(all(store.scan(rt, t, ScanRequest.all().asOf(com.ash.lake.spi.AsOf.version(loaded.version()))))).hasSize(rows.size());
        assertThat(all(store.scan(rt, t, ScanRequest.all().dates(D1).asOf(com.ash.lake.spi.AsOf.time(loaded.committed())))))
                .hasSize(dates.get(D1).intValue());
        assertThatThrownBy(() -> store.scan(rt, t, ScanRequest.all().asOf(com.ash.lake.spi.AsOf.version(-1)))).isInstanceOf(IOException.class)
                .hasMessageContaining("no version -1");
        assertThatThrownBy(() -> store.scan(rt, t, ScanRequest.all().asOf(com.ash.lake.spi.AsOf.time(before.get(0).committed().minusSeconds(86400)))))
                .isInstanceOf(IOException.class).hasMessageContaining("no version committed at or before");

        if (caps.supports(Capability.UNDO)) {
            String loadId = (String) all(store.scan(rt, t, ScanRequest.all().columns("_load_id"))).get(0).get(0);
            List<DateChange> undone = store.undo(rt, t, loadId, false);
            assertThat(undone).extracting(DateChange::date).contains(D2);
            assertThat(all(store.scan(rt, t, ScanRequest.all().where(Expr.eq("_load_id", loadId))))).isEmpty();
        } else {
            assertThatThrownBy(() -> store.undo(rt, t, "any", true)).isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining(caps.missing().get(Capability.UNDO));
        }
        if (!caps.supports(Capability.APPEND_BATCH_ONCE)) {
            Path cfg = Files.writeString(tmp.resolve("append.yaml"), PvData.config(tmp.resolve("in") + "/*", t.root(), format, "  mode: append",
                    "  batch-id: b1"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(LakeLoad.run(rt, new String[] {"--config", cfg.toString(), "-q"}, new PrintStream(out))).isNotEqualTo(LakeLoad.OK);
            assertThat(out.toString()).contains(caps.missing().get(Capability.APPEND_BATCH_ONCE));
        }
    }

    @Test
    void theLoaderWritesThroughTheRuntimesStore() throws IOException {
        AtomicInteger writers = new AtomicInteger();
        TableStore counting = new Delegate(Stores.builtIn("delta")) {
            @Override
            public TableWriter writer(WriteSpec spec) throws IOException {
                writers.incrementAndGet();
                assertThat(spec.vectors()).containsExactly("SCENARIO_PV");
                assertThat(spec.domain()).isEqualTo("risk");
                return super.writer(spec);
            }
        };
        LakeRuntime rt = runtime(counting);
        TableRef t = load(rt, "delta");
        assertThat(writers.get()).isEqualTo(1);
        assertThat(Stores.of(rt, "delta")).isSameAs(counting);
        assertThat(Stores.of(rt, "iceberg")).isSameAs(Stores.builtIn("iceberg"));
        assertThat(counting.dates(rt, t)).hasSize(2);
    }

    @Test
    void refusesWhatTheSpiDoesNotAllow() {
        assertThatThrownBy(() -> new TableRef("relative/lake", "risk", "pv")).hasMessageContaining("absolute");
        assertThatThrownBy(() -> new TableRef("/lake", "risk/x", "pv")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Stores.builtIn("parquet-folders")).hasMessageContaining("register");
        assertThat(Stores.builtIn("files")).isInstanceOf(FilesStore.class);
        assertThatThrownBy(() -> runtime(Stores.builtIn("delta"), DeltaStore.create())).hasMessageContaining("two table stores");
        Capabilities c = Capabilities.allBut(com.ash.lake.spi.Capability.TIME_TRAVEL, "plain files keep one version");
        assertThatThrownBy(() -> c.require("files", com.ash.lake.spi.Capability.TIME_TRAVEL)).hasMessageContaining("one version");
        assertThat(new TableRef("s3://bucket/lake/", "risk", "pv").location()).isEqualTo("s3://bucket/lake/risk/pv");
    }

    /** A store that hands every call to another. */
    private static class Delegate implements TableStore {
        private final TableStore d;

        Delegate(TableStore d) {
            this.d = d;
        }

        @Override
        public com.ash.lake.read.LakeReader open(com.ash.lake.LakeRuntime runtime, com.ash.lake.read.LakeAddress address) throws java.io.IOException {
            return d.open(runtime, address);
        }

        @Override
        public String format() {
            return d.format();
        }

        @Override
        public Capabilities capabilities() {
            return d.capabilities();
        }

        @Override
        public boolean exists(LakeRuntime rt, TableRef t) throws IOException {
            return d.exists(rt, t);
        }

        @Override
        public Columns columns(LakeRuntime rt, TableRef t) throws IOException {
            return d.columns(rt, t);
        }

        @Override
        public SortedMap<LocalDate, Long> dates(LakeRuntime rt, TableRef t) throws IOException {
            return d.dates(rt, t);
        }

        @Override
        public List<com.ash.lake.spi.TableVersion> versions(LakeRuntime rt, TableRef t) throws IOException {
            return d.versions(rt, t);
        }

        @Override
        public RowScan scan(LakeRuntime rt, TableRef t, ScanRequest r) throws IOException {
            return d.scan(rt, t, r);
        }

        @Override
        public TableWriter writer(WriteSpec spec) throws IOException {
            return d.writer(spec);
        }

        @Override
        public List<DateChange> deleteDates(LakeRuntime rt, TableRef t, Set<LocalDate> dates, boolean dryRun) throws IOException {
            return d.deleteDates(rt, t, dates, dryRun);
        }

        @Override
        public List<DateChange> undo(LakeRuntime rt, TableRef t, String loadId, boolean dryRun) throws IOException {
            return d.undo(rt, t, loadId, dryRun);
        }
    }
}
