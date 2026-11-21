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

/**
 * One row as the loader hands it to a {@link TableWriter}, read in place and reused row after row by one writer thread (copy what you
 * keep). Texts are UTF-8 bytes. A promoted value ({@code i}, in {@link WriteSpec#promoted()} order) is null, a number (with the text it
 * was written as), a text, or a JSON text (an object or a list), as {@link #tag} says.
 */
public interface LoadRow {

    byte NULL = 0;
    byte NUM = 1;
    byte STR = 2;
    byte JSON = 3;

    /** The row's id. */
    byte[] id();

    /** The row's document (its JSON text). */
    byte[] doc();

    /** The sort key the loader ordered the row by. */
    byte[] key();

    /** The kind of promoted value {@code i}: {@link #NULL}, {@link #NUM}, {@link #STR} or {@link #JSON}. */
    byte tag(int i);

    /** Promoted value {@code i} as a number (when {@link #tag} is {@link #NUM}). */
    double num(int i);

    /** Promoted value {@code i}'s text as the input wrote the number. */
    byte[] numText(int i);

    /** Promoted value {@code i}'s text (a text or JSON value). */
    byte[] text(int i);

    /** Partition value {@code j} ({@link WriteSpec#partition()} order), or null. */
    String partition(int j);

    /** Vector {@code k} ({@link WriteSpec#vectors()} order; a null element is NaN), or null when the row has none. */
    double[] vector(int k);

    /** The load id of the input file the row came from (the {@code _load_id} column). */
    byte[] loadId();

    /** The name of the input file the row came from (the {@code _source_file} column). */
    byte[] sourceFile();
}
