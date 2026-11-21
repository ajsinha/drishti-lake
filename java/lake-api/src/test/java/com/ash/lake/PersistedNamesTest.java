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
package com.ash.lake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The persisted names: lake.* only; a table that carries a host's older names is refused with the way out (rebuild the lake). */
class PersistedNamesTest {

    @Test
    void readsTheProductsNamesOnly() {
        assertThat(PersistedNames.get(Map.of("lake.load-ids", "a,b"), "load-ids")).isEqualTo("a,b");
        assertThat(PersistedNames.get(Map.of(), "load-ids", "none")).isEqualTo("none");
        assertThat(PersistedNames.get(null, "load-ids")).isNull();
        assertThat(PersistedNames.get(Map.of("lake.load-ids", "a", PersistedNames.EARLIER + "load-ids", "b"), "load-ids")).isEqualTo("a");
    }

    @Test
    void aTableWithTheOlderNamesIsRefusedWithTheWayOut() {
        assertThatThrownBy(() -> PersistedNames.get(Map.of(PersistedNames.EARLIER + "business-dates", "2026-10-27"), "business-dates"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rebuild the lake").hasMessageContaining("lake.business-dates");
    }

    @Test
    void aHostsSettingIsNamedWithTheProductsName() {
        assertThat(PersistedNames.earlier(PersistedNames.EARLIER + "data.dir")).isTrue();
        assertThat(PersistedNames.earlier("lake.data.dir")).isFalse();
        assertThat(PersistedNames.product(PersistedNames.EARLIER + "data.dir")).isEqualTo("lake.data.dir");
    }
}
