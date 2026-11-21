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
package com.ash.lake.read;

import java.util.List;
import java.util.Optional;

/** The reads of a lake that failed and have not read well since. */
public interface ReadProblems {

    List<ReadProblem> all();

    boolean isEmpty();

    /** At most {@code max} problems in one line each, with a count of the rest. */
    String summary(int max);

    /** The last listing of {@code table} failed: why, and what is served meanwhile. */
    Optional<String> listing(String table);
}
