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

import java.util.List;

/**
 * One record against a data contract, before anything is written (the loader's {@code target.contract}): what is wrong with it, in
 * words that name the field and the rule and never the value (a reject reason is counted by its words). The standard checker is the
 * product's; {@code drishti_lake.contracts.checker.record} gives the same words on the Python path.
 */
public interface ContractChecker {

    /** What is wrong with {@code record} (a JSON object) under {@code contract}; empty when nothing is. */
    List<String> problems(Contract contract, JsonNode record);

    /** The product's checker: attributes the contract lacks, required fields, types, enums, patterns, derived fields, nested records. */
    static ContractChecker standard() {
        return RecordCheck.STANDARD;
    }

    /** A rejected row's reason: the first problem and how many more. */
    static String reason(Contract contract, List<String> problems) {
        int more = problems.size() - 1;
        return "the contract of " + contract.kind() + " (version " + contract.version() + "): " + problems.get(0)
                + (more > 0 ? "; and " + more + " more" : "");
    }
}
