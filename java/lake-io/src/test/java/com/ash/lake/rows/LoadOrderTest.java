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
package com.ash.lake.rows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The one load order: the run's time, then the commit for runs of one millisecond, then the id. */
class LoadOrderTest {

    private static final String A = "20261121T131500.123-77-9.pv.2026-10-27.jsonl";
    private static final String B = "20261121T131500.123-77-10.pv.2026-10-27.jsonl";
    private static final String C = "20261121T131500.123-100.pv.2026-10-27.jsonl";

    @Test
    void runsOfOneMillisecondGoByCommitNotByProcessIdText() {
        assertTrue(A.compareTo(B) > 0 && B.compareTo(C) > 0);          // the text order is the reverse of the commits'
        assertTrue(LoadOrder.needsCommits(List.of(A, B, C)));
        assertTrue(LoadOrder.compare(A, 1, B, 2) < 0);
        assertTrue(LoadOrder.compare(B, 2, C, 3) < 0);
        assertTrue(LoadOrder.compare(C, 3, A, 1) > 0);
    }

    @Test
    void anEarlierRunIsOlderWhateverItsCommit() {
        String earlier = "20261121T131500.122-99999999-12.x";
        assertFalse(LoadOrder.needsCommits(List.of(earlier, A)));
        assertTrue(LoadOrder.compare(earlier, 9, A, 1) < 0);
    }

    @Test
    void oneRunsFilesNeedNoCommitAndGoByTheirIds() {
        assertFalse(LoadOrder.needsCommits(List.of(A, "20261121T131500.123-77-9.other.jsonl")));
        assertEquals("20261121T131500.123", LoadOrder.time(A));
        assertEquals("20261121T131500.123-77-9", LoadOrder.run(A));
        assertTrue(LoadOrder.compare("20261121T131500.123-77-9.a", 0, "20261121T131500.123-77-9.b", 0) < 0);
        assertEquals("", LoadOrder.time(null));
    }
}
