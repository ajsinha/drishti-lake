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
import com.ash.lake.spi.Capabilities;
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

    @ParameterizedTest
    @ValueSource(strings = {"delta", "iceberg"})
    void readsDeletesAndUndoesThroughTheSpi(String format) throws IOException {
        LakeRuntime rt = runtime();
        TableRef t = load(rt, format);
        TableStore store = Stores.of(rt, format);
        assertThat(store.format()).isEqualTo(format);
        assertThat(store.capabilities().missing()).isEmpty();
        assertThat(store.exists(rt, t)).isTrue();
        assertThat(store.exists(rt, new TableRef(t.root(), "risk", "absent"))).isFalse();

        Columns cols = store.columns(rt, t);
        assertThat(cols.types()).containsEntry("id", ColumnType.STRING).containsEntry("business_date", ColumnType.DATE)
                .containsEntry("QUANTITY", ColumnType.DOUBLE).containsEntry("SCENARIO_PV", ColumnType.DOUBLE_LIST);

        SortedMap<LocalDate, Long> dates = store.dates(rt, t);
        assertThat(dates.keySet()).containsExactly(D1, D2);
        List<Row> rows = all(store.scan(rt, t, ScanRequest.all()));
        assertThat((long) rows.size()).isEqualTo(dates.values().stream().mapToLong(Long::longValue).sum());
        assertThat(rows.get(0).get("business_date")).isInstanceOf(LocalDate.class);
        assertThat(rows.get(0).get("SCENARIO_PV")).isInstanceOf(List.class);

        List<Row> fx = all(store.scan(rt, t, ScanRequest.all().dates(D1).columns("id", "DESK").where(Expr.eq("DESK", "DESK-FX"))));
        assertThat(fx).isNotEmpty().allSatisfy(r -> {
            assertThat(r.columns().names()).containsExactly("id", "DESK");
            assertThat(r.get("DESK")).isEqualTo("DESK-FX");
        });
        assertThat((long) fx.size()).isLessThan(dates.get(D1));
        assertThatThrownBy(() -> store.scan(rt, t, ScanRequest.all().columns("NOPE"))).isInstanceOf(IllegalArgumentException.class);

        List<DateChange> dry = store.deleteDates(rt, t, Set.of(D1), true);
        assertThat(dry).extracting(DateChange::date).containsExactly(D1);
        assertThat(store.dates(rt, t).keySet()).containsExactly(D1, D2);
        store.deleteDates(rt, t, Set.of(D1), false);
        assertThat(store.dates(rt, t).keySet()).containsExactly(D2);
        assertThat(all(store.scan(rt, t, ScanRequest.all().dates(D1)))).isEmpty();

        String loadId = (String) all(store.scan(rt, t, ScanRequest.all().columns("_load_id"))).get(0).get(0);
        List<DateChange> undone = store.undo(rt, t, loadId, false);
        assertThat(undone).extracting(DateChange::date).contains(D2);
        assertThat(all(store.scan(rt, t, ScanRequest.all().where(Expr.eq("_load_id", loadId))))).isEmpty();
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
        assertThatThrownBy(() -> Stores.builtIn("files")).hasMessageContaining("register");
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
