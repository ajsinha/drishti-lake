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

import java.util.Map;

/**
 * A date's promoted columns: the ids and one array per asked path, all of the same length. A null number is NaN; repeated texts share
 * one {@code String}. The arrays are the reader's own, handed over: not copied, not kept.
 */
public record ColumnBlock(String[] ids, Map<String, double[]> numbers, Map<String, String[]> texts) {}
