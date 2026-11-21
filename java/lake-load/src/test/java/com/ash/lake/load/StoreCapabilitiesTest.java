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

import com.ash.lake.spi.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@code tck/capabilities.yaml} is what the built-in stores declare (design section 4): the same capabilities with the same reasons, and
 * each named case is in the parity kit. A store that loses or gains a gap fails here until the list says so (and the list only shrinks).
 */
class StoreCapabilitiesTest {

    private static final Path REPO = Path.of(System.getProperty("lake.repo.root")).toAbsolutePath().normalize();

    @Test
    void theListIsWhatTheStoresDeclare() throws Exception {
        JsonNode list = new ObjectMapper(new YAMLFactory()).readTree(REPO.resolve("tck/capabilities.yaml").toFile());
        for (String format : new String[] {"delta", "iceberg", "files"}) {
            Map<String, String> declared = new TreeMap<>();
            Stores.builtIn(format).capabilities().missing().forEach((c, why) -> declared.put(c.name(), why));
            Map<String, String> listed = new TreeMap<>();
            list.path(format).fields().forEachRemaining(e -> {
                listed.put(e.getKey(), e.getValue().path("reason").asText());
                String c = e.getValue().path("case").asText();
                assertThat(c.equals("none") || Files.isRegularFile(REPO.resolve("tck/cases/" + c + ".yaml"))).as(format + " " + e.getKey() + ": " + c)
                        .isTrue();
                Capability.valueOf(e.getKey());
            });
            assertThat(listed).as(format).isEqualTo(declared);
        }
    }
}
