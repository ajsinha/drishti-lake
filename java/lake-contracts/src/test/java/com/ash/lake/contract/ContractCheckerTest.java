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
package com.ash.lake.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sample contracts of {@code tck/fixtures/contracts}: the reader, and the record check in the Python path's words (records.json). */
class ContractCheckerTest {

    /** The module's folder (the build runs each module's tests in it), up to the repository's shared fixtures. */
    static final Path FIX = Path.of(System.getProperty("user.dir")).resolve("../../tck/fixtures/contracts").normalize();
    static final Path POSITION = FIX.resolve("desk/contracts/position.contract.yaml");

    @Test
    void readerReadsTheContractAndKeepsEveryKey() throws IOException {
        Contract c = Contract.read(POSITION);
        assertEquals("position", c.kind());
        assertEquals("1.2", c.version());
        assertEquals("BUSINESS_DATE", c.datedBy());
        assertEquals(List.of("POSITION_ID", "BUSINESS_DATE", "DESK"), c.required());
        assertEquals(List.of("POSITION_ID", "BUSINESS_DATE", "DESK"), c.requiredFields());
        assertEquals("POSITION_ID", c.singleKey());
        assertEquals(List.of("KYC", "LEGS[]"), new ArrayList<>(c.nested().keySet()));
        assertEquals(13, c.fields().size());
        assertEquals("market-risk", c.node().path("owner-team").asText());      // a host's key, untouched
        Contract planned = Contract.read(FIX.resolve("desk/contracts/limit.contract.yaml"));
        assertEquals("DESK", planned.singleKey());
        assertEquals(List.of("DESK", "LIMIT_USD"), planned.required());
    }

    @Test
    void singleKeyOnlyForOneField(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("k.contract.yaml");
        Files.writeString(f, "kind: k\nkey: [A, B]\n");
        assertNull(Contract.read(f).singleKey());
        Files.writeString(f, "kind: k\nkey: A\n");
        assertEquals("A", Contract.read(f).singleKey());
        Files.writeString(f, "kind: k\nkey: 'A, B'\n");
        assertNull(Contract.read(f).singleKey());
        Files.writeString(f, "- a list\n");
        assertThrows(IOException.class, () -> Contract.read(f));
    }

    @Test
    void recordProblemsAreThePythonPathsWords() throws IOException {
        Contract c = Contract.read(POSITION);
        JsonNode cases = new ObjectMapper().readTree(Files.readString(FIX.resolve("records.json")));
        assertTrue(cases.size() >= 10);
        for (JsonNode k : cases) {
            List<String> want = new ArrayList<>();
            k.path("problems").forEach(x -> want.add(x.asText()));
            assertEquals(want, ContractChecker.standard().problems(c, k.path("record")), k.path("record").toString());
        }
    }

    @Test
    void reasonNamesTheContractAndCountsTheRest() throws IOException {
        Contract c = Contract.read(POSITION);
        assertEquals("the contract of position (version 1.2): A: x", ContractChecker.reason(c, List.of("A: x")));
        assertEquals("the contract of position (version 1.2): A: x; and 2 more", ContractChecker.reason(c, List.of("A: x", "B: y", "C: z")));
    }

    @Test
    void enumValuesCompareWithinTheirKind() {
        JsonNodeFactory n = JsonNodeFactory.instance;
        assertTrue(RecordCheck.same(n.numberNode(1), n.numberNode(1.0)));
        assertTrue(!RecordCheck.same(n.numberNode(1), n.booleanNode(true)));
        assertTrue(!RecordCheck.same(n.textNode("1"), n.numberNode(1)));
        assertTrue(!RecordCheck.same(n.numberNode(Double.NaN), n.numberNode(Double.NaN)));
    }
}
