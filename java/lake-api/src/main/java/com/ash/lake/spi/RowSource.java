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

import java.util.List;
import java.util.Map;

/**
 * The host's rows for the memory lake (design section 3.2): what a memory table's replenish reads from a connector of the host, and which
 * of the host's connectors serve memory-only data (the retention guard). Documents are plain values: maps, lists, text, numbers, booleans
 * and nulls. A file or a URL source needs no host: the memory lake reads those itself.
 */
public interface RowSource {

    /** A host with no connectors: a table that names a connector is refused, nothing is memory-only. */
    RowSource NONE = new RowSource() {
        @Override
        public List<Map<String, Object>> documents(String connector, String kind) {
            throw new IllegalStateException("connector " + connector + " is not running (the host gave the memory lake no connectors)");
        }

        @Override
        public List<Map<String, Object>> fetch(String connector, String kind, List<String> ids) {
            throw new IllegalStateException("connector " + connector + " is not running (the host gave the memory lake no connectors)");
        }
    };

    /** A memory-only connector of the host: its name, the root it reads ({@code mem://<store>/...}) and the kinds it serves. */
    record Served(String connector, String root, List<String> kinds) {
        public Served {
            kinds = List.copyOf(kinds);
        }
    }

    /**
     * Every current document of {@code kind} the connector holds.
     *
     * @throws Exception naming why in words: the connector not running, itself memory-only, or unable to list its documents
     */
    List<Map<String, Object>> documents(String connector, String kind) throws Exception;

    /**
     * The documents of {@code ids}, each fetched from the connector (one that computes a document when asked); a miss is left out.
     *
     * @throws Exception when the connector is not running, or answered none of the ids
     */
    List<Map<String, Object>> fetch(String connector, String kind, List<String> ids) throws Exception;

    /** A marker of the connector's state, so a change can start a run (its last update and the kind's data version), or null. */
    default String mark(String connector, String kind) {
        return null;
    }

    /** The host's memory-only connectors (none by default). */
    default List<Served> memoryOnly() {
        return List.of();
    }
}
