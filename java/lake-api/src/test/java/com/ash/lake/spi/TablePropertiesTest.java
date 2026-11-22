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
package com.ash.lake.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The mapping of a load's target.properties to each format's native properties, and back (TableProperties). */
class TablePropertiesTest {

    @Test
    void theProductsNamesBecomeEachFormatsOwn() {
        Map<String, String> given = Map.of(TableProperties.APPEND_ONLY, "true", TableProperties.RETENTION_HOURS, "61368");
        assertThat(TableProperties.forStore("delta", given)).containsEntry("delta.appendOnly", "true")
                .containsEntry("delta.deletedFileRetentionDuration", "interval 2557 days").containsEntry("delta.logRetentionDuration", "interval 2557 days")
                .doesNotContainKeys(TableProperties.APPEND_ONLY, TableProperties.RETENTION_HOURS);
        assertThat(TableProperties.forStore("iceberg", given)).containsEntry(TableProperties.APPEND_ONLY, "true")
                .containsEntry("history.expire.max-snapshot-age-ms", String.valueOf(61368L * 3_600_000L)).doesNotContainKey(TableProperties.RETENTION_HOURS);
        assertThat(TableProperties.forStore("files", given)).isEqualTo(given);
        for (String f : new String[] {"delta", "iceberg", "files"}) {
            assertThat(TableProperties.effective(TableProperties.forStore(f, given))).as(f).isEqualTo(new TableProperties.Effective(true, 61368L));
        }
    }

    @Test
    void aFormatsNativeNamesWorkOnEveryStoreAndOtherKeysAreKept() {
        Map<String, String> delta = Map.of("delta.appendOnly", "true", "delta.deletedFileRetentionDuration", "interval 7 days",
                "delta.logRetentionDuration", "interval 30 days", "write.parquet.page-size-bytes", "1048576");
        assertThat(TableProperties.forStore("delta", delta)).isEqualTo(delta);
        assertThat(TableProperties.forStore("iceberg", delta)).containsEntry("history.expire.max-snapshot-age-ms", String.valueOf(168L * 3_600_000L))
                .containsEntry(TableProperties.APPEND_ONLY, "true").containsEntry("write.parquet.page-size-bytes", "1048576")
                .doesNotContainKeys("delta.appendOnly", "delta.logRetentionDuration");
        assertThat(TableProperties.effective(TableProperties.forStore("files", delta))).isEqualTo(new TableProperties.Effective(true, 168L));
        assertThat(TableProperties.forStore("delta", Map.of(TableProperties.RETENTION_HOURS, "36")))
                .containsEntry("delta.deletedFileRetentionDuration", "interval 36 hours");
        assertThat(TableProperties.effective(Map.of())).isEqualTo(new TableProperties.Effective(false, null));
    }

    @Test
    void earlierNamesBadValuesAndDisagreementsAreRefused() {
        assertThatThrownBy(() -> TableProperties.forStore("iceberg", Map.of("dri" + "shti.append-only", "true"))).hasMessageContaining("lake.*");
        assertThatThrownBy(() -> TableProperties.forStore("delta", Map.of(TableProperties.APPEND_ONLY, "yes"))).hasMessageContaining("true or false");
        assertThatThrownBy(() -> TableProperties.forStore("delta", Map.of("delta.deletedFileRetentionDuration", "7 days")))
                .hasMessageContaining("interval N days");
        assertThatThrownBy(() -> TableProperties.forStore("files", Map.of(TableProperties.RETENTION_HOURS, "24", "history.expire.max-snapshot-age-ms", "3600000")))
                .hasMessageContaining("name one property with different values");
        assertThatThrownBy(() -> TableProperties.refuseRewrite(Map.of("delta.appendOnly", "true"), "risk/official", "a delete of dates"))
                .isInstanceOf(IllegalStateException.class).hasMessage("risk/official is append-only (lake.append-only): a delete of dates is refused; "
                        + "the table takes appends only");
    }
}
