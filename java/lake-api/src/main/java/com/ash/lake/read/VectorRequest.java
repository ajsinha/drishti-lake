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

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

/**
 * One vector column of one date with some text columns. {@code keep}: which files to read (statistics pruning); {@code decoders}: files
 * (or row-group pieces) decoded at once, 1 = on the caller's thread; {@code bufferBytes}: the decoded rows waiting at most (the pieces
 * are cut from it). Rows are delivered in file order on the caller's thread whatever {@code decoders} is.
 */
public record VectorRequest(LocalDate date, String column, List<String> texts, Predicate<DataFile> keep, int decoders, long bufferBytes) {

    public VectorRequest {
        texts = List.copyOf(texts);
        keep = keep == null ? f -> true : keep;
        decoders = Math.max(1, decoders);
        bufferBytes = Math.max(1L << 20, bufferBytes);
    }

    /** Every file, one decoder. */
    public static VectorRequest of(LocalDate date, String column, List<String> texts) {
        return new VectorRequest(date, column, texts, null, 1, 1L << 20);
    }

    /** Only the files {@code keep} accepts. */
    public VectorRequest keep(Predicate<DataFile> keep) {
        return new VectorRequest(date, column, texts, keep, decoders, bufferBytes);
    }

    /** {@code decoders} at once, about {@code bufferBytes} decoded rows waiting at most. */
    public VectorRequest parallel(int decoders, long bufferBytes) {
        return new VectorRequest(date, column, texts, keep, decoders, bufferBytes);
    }
}
