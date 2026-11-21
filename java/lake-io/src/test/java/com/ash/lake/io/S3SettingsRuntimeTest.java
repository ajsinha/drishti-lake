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
package com.ash.lake.io;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.lake.LakeOptions;
import com.ash.lake.LakeRuntime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The runtime's S3 store ({@code lake.s3.*}) under a call's own settings: what undo, retention and the readers reach S3 with. */
class S3SettingsRuntimeTest {

    @Test
    void aCallsSettingsWinOverTheRuntimes() {
        LakeRuntime rt = LakeRuntime.builder().options(LakeOptions.builder().set(LakeOptions.S3_ENDPOINT, "http://127.0.0.1:9000")
                .set(LakeOptions.S3_REGION, "eu-west-1").build()).build();
        S3Settings none = S3Settings.from(rt, Map.of());
        assertThat(none.endpoint()).isEqualTo("http://127.0.0.1:9000");
        assertThat(none.region()).isEqualTo("eu-west-1");
        assertThat(none.pathStyle()).isTrue();                       // an endpoint's default
        S3Settings own = S3Settings.from(rt, Map.of("s3.region", "us-east-2", "s3.path-style", "false"));
        assertThat(own.endpoint()).isEqualTo("http://127.0.0.1:9000");
        assertThat(own.region()).isEqualTo("us-east-2");
        assertThat(own.pathStyle()).isFalse();
        assertThat(S3Settings.from(LakeRuntime.builder().build(), Map.of()).endpoint()).isEmpty();
    }
}
