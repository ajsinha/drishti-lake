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

/** The one load order: the commit, then a file of older copies first, then the run's time (within one commit), then the id. */
class LoadOrderTest {

    private static final String A = "20261121T131500.123-77-9.pv.2026-10-27.jsonl";
    private static final String B = "20261121T131500.123-77-10.pv.2026-10-27.jsonl";
    private static final String C = "20261121T131500.123-100.pv.2026-10-27.jsonl";

    @Test
    void runsOfOneMillisecondGoByCommitNotByProcessIdText() {
        assertTrue(A.compareTo(B) > 0 && B.compareTo(C) > 0);          // the text order is the reverse of the commits'
        assertTrue(LoadOrder.compare(A, 1, B, 2) < 0);
        assertTrue(LoadOrder.compare(B, 2, C, 3) < 0);
        assertTrue(LoadOrder.compare(C, 3, A, 1) > 0);
    }

    @Test
    void theLaterCommitIsNewerWhateverTheClocksSaid() {
        String later = "20261121T131500.122-99999999-12.x";           // a machine whose clock is behind, or a long load that began first
        assertTrue(LoadOrder.compare(later, 9, A, 1) > 0);
        assertTrue(LoadOrder.compare(A, 1, later, 9) < 0);
    }

    @Test
    void withinOneCommitTheTimeThenTheId() {
        String earlier = "20261121T131500.122-99999999-12.x";
        assertTrue(LoadOrder.compare(earlier, 4, A, 4) < 0);
        assertEquals("20261121T131500.123", LoadOrder.time(A));
        assertEquals("20261121T131500.123-77-9", LoadOrder.run(A));
        assertTrue(LoadOrder.compare("20261121T131500.123-77-9.a", 0, "20261121T131500.123-77-9.b", 0) < 0);
        assertEquals("", LoadOrder.time(null));
    }

    @Test
    void inOneCommitAFileOfOlderCopiesIsOlderWhateverItsRunSays() {
        String late = "20261121T131510.000-1-1.1";                     // a run id later than the copy readers see
        String early = "20261121T131500.000-2-1.1";
        assertTrue(LoadOrder.compare(late, 5, true, early, 5, false) < 0);
        assertTrue(LoadOrder.compare(early, 5, false, late, 5, true) > 0);
        assertEquals(0, LoadOrder.compare(late, 5, true, early, 5, true));   // two files of older copies: the caller's order (file, row)
        assertTrue(LoadOrder.compare(early, 6, true, late, 5, false) > 0);   // the commit first: a later commit's older copies are newer
        assertTrue(LoadOrder.olderByName("s3://b/t/data/business_date=2026-10-27/2026-10-27-older-00000-0f.parquet"));
        assertFalse(LoadOrder.olderByName("/t/data/2026-10-27-00000-0f.parquet"));
        assertFalse(LoadOrder.olderByName("/older-copies/2026-10-27-00000-0f.parquet"));
        assertEquals("lake.copies", LoadOrder.OLDER_TAG);
    }
}
