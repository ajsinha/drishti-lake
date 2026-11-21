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

import com.ash.lake.read.Bounds;
import com.ash.lake.spi.Expr;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pushdown rule: false only when no row of the file can match; whatever is not understood or not stated may match. */
class PruningTest {

    private static final LocalDate D = LocalDate.parse("2026-10-27");
    private static final Map<String, Bounds> B = Map.of("DESK", new Bounds("DESK-COMM", "DESK-FX", 0L), "BOOK", new Bounds(null, null, 3L));

    private static boolean may(Expr e) {
        return Pruning.mayHold(e, D, Map.of(), B::get);
    }

    @Test
    void datesAndBoundsPrune() {
        assertThat(may(Expr.eq("business_date", LocalDate.parse("2026-10-28")))).isFalse();
        assertThat(may(Expr.eq("business_date", "2026-10-27"))).isTrue();
        assertThat(may(Expr.between("business_date", "2026-10-28", null))).isFalse();
        assertThat(may(Expr.eq("DESK", "DESK-RATES"))).isFalse();
        assertThat(may(Expr.eq("DESK", "DESK-CREDIT"))).isTrue();
        assertThat(may(Expr.in("DESK", Set.of("A", "DESK-RATES")))).isFalse();
        assertThat(may(Expr.in("DESK", Set.of("A", "DESK-FX")))).isTrue();
        assertThat(may(Expr.between("DESK", "DESK-G", null))).isFalse();
        assertThat(may(Expr.and(Expr.eq("DESK", "DESK-FX"), Expr.eq("business_date", "2026-10-28")))).isFalse();
        assertThat(may(Expr.or(Expr.eq("DESK", "DESK-RATES"), Expr.eq("business_date", "2026-10-27")))).isTrue();
        assertThat(may(Expr.isNull("DESK"))).isFalse();                 // no nulls stated
        assertThat(may(Expr.isNull("BOOK"))).isTrue();
    }

    @Test
    void whatIsNotKnownMayMatch() {
        assertThat(may(Expr.not(Expr.eq("DESK", "DESK-FX")))).isTrue();
        assertThat(may(Expr.eq("DESK", 5.0))).isTrue();                  // a number against text: Expr's own comparison, not pruned
        assertThat(may(Expr.eq("BOOK", "X"))).isTrue();                  // nulls only: values not stated
        assertThat(may(Expr.eq("OTHER", "X"))).isTrue();                 // no statistics
        assertThat(may(Expr.eq("DESK", "DESK-"))).isTrue();        // beyond U+D800 Java's order may not be the writer's
        assertThat(Pruning.mayHold(Expr.eq("business_date", "2026-10-28"), null)).isTrue();
    }

    @Test
    void partitionValuesPrune() {
        Map<String, String> p = new HashMap<>();
        p.put("LEGAL_ENTITY", "LE-NY");
        p.put("SOURCE", null);
        assertThat(Pruning.mayHold(Expr.eq("LEGAL_ENTITY", "LE-LDN"), D, p, c -> null)).isFalse();
        assertThat(Pruning.mayHold(Expr.eq("LEGAL_ENTITY", "LE-NY"), D, p, c -> null)).isTrue();
        assertThat(Pruning.mayHold(Expr.eq("SOURCE", "X"), D, p, c -> null)).isFalse();
        assertThat(Pruning.mayHold(Expr.isNull("SOURCE"), D, p, c -> null)).isTrue();
        assertThat(Pruning.mayHold(Expr.isNull("LEGAL_ENTITY"), D, p, c -> null)).isFalse();
    }
}
