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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The product's one settings object: built options read nothing from the process; the process's read the lake.* names; defaults hold. */
class LakeOptionsTest {

    @Test
    void builtOptionsHoldExactlyTheValuesSet() {
        LakeOptions o = LakeOptions.builder().home(Path.of("/srv/lake")).set(LakeOptions.ROW_GROUP_KB, "128").build();
        assertThat(o.readsProcess()).isFalse();
        assertThat(o.homeSetting()).isEqualTo("/srv/lake");
        assertThat(o.rowGroupBytes()).isEqualTo(128 * 1024);
        assertThat(o.value(LakeOptions.DATA_DIR)).isNull();          // the build's -Dlake.data.dir is not read
        assertThat(LakeOptions.builder().set(LakeOptions.ROW_GROUP_MB, "2").set(LakeOptions.ROW_GROUP_KB, "128").build().rowGroupBytes())
                .isEqualTo(2L << 20);
        assertThatThrownBy(() -> LakeOptions.builder().dataDir(Path.of("data"))).hasMessageContaining("absolute");
    }

    @Test
    void theDefaults() {
        LakeOptions o = LakeOptions.builder().build();
        assertThat(o.rowGroupBytes()).isEqualTo(256 * 1024);
        assertThat(o.pageBytes()).isEqualTo(64 * 1024);
        assertThat(o.parquetPageIndex()).isTrue();
        assertThat(o.footerCacheBytes()).isEqualTo(64L << 20);
        assertThat(o.icebergDeleteCacheRows()).isEqualTo(5_000_000);
        assertThat(o.icebergManifestCacheBytes()).isEqualTo(512L << 20);
        assertThat(o.loaderTrace()).isFalse();
    }

    @Test
    void theProcessOptionsReadTheProductsNamesAndAHostsAreItsOwn() {
        assertThat(LakeRuntime.fromProcess().options().readsProcess()).isTrue();
        assertThat(LakeRuntime.fromProcess().options().value(LakeOptions.DATA_DIR)).isEqualTo(System.getProperty("lake.data.dir"));
        LakeRuntime host = LakeRuntime.builder().options(LakeOptions.builder().home(Path.of("/opt/host")).build()).build();
        assertThat(host.roots().home()).isEqualTo(Path.of("/opt/host"));
        assertThat(host.roots().data()).isEqualTo("/opt/host/data");
        assertThat(host.roots().connectors()).isEqualTo(Path.of("/opt/host/config/connectors"));
        assertThat(LakeRuntime.fromProcess().roots().home()).isEqualTo(Path.of(System.getProperty("lake.home")).normalize());
    }
}
