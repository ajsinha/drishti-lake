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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.lake.common.LakeException;
import com.ash.lake.read.VectorSink;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * A read whose caller's sink refuses a row (a duplicate trade, say) fails with the caller's own exception, unchanged, and records no read
 * problem; a read that itself fails is still LK-1006 and recorded.
 */
class ProblemsCallerTest {

    static final class Refused extends RuntimeException {
        Refused(String m) {
            super(m);
        }
    }

    static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @Test
    void theCallersRefusalIsItsOwnAndNoReadProblem() {
        Problems problems = new Problems("test", Problems::describe);
        Refused refused = new Refused("the trade T1 has two P&L vectors");
        VectorSink sink = Problems.caller((VectorSink) (id, values, length, texts) -> {
            throw refused;
        });
        assertThatThrownBy(() -> problems.read("pnl-vector", DAY, 1, () -> {
            sink.row("T1", new double[] {1}, 1, new String[0]);
            return null;
        })).isSameAs(refused);
        // a parallel read hands the worker's failure over wrapped: still the caller's own
        assertThatThrownBy(() -> problems.read("pnl-vector", DAY, 1, () -> CompletableFuture.runAsync(() -> sink.row("T1", new double[] {1}, 1,
                new String[0])).join())).isSameAs(refused);
        assertThat(problems.all()).isEmpty();
    }

    @Test
    void aReadThatFailsIsStillAReadProblem() {
        Problems problems = new Problems("test", Problems::describe);
        assertThatThrownBy(() -> problems.read("pnl-vector", DAY, 1, () -> {
            throw new java.io.IOException("a file is missing");
        })).isInstanceOf(LakeException.class).hasMessageStartingWith("LK-1006");
        assertThat(problems.all()).hasSize(1);
    }
}
