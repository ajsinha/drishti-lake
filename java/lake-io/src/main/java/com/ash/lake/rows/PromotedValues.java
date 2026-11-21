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
package com.ash.lake.rows;

import com.ash.lake.spi.LoadRow;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A promoted value of a loaded row as its column holds it, the one rule every store follows: into a number column a number (text that
 * reads as one, else null); into a {@code list<string>} column a list of simple values as their texts ({@link ListValues}), a single
 * value as a list of one; into a text column the text as the input wrote it (a number's own digits).
 */
public final class PromotedValues {

    private PromotedValues() {
    }

    /** Into a number column. */
    public static Double number(LoadRow r, int i) {
        byte tag = r.tag(i);
        if (tag == LoadRow.NULL) {
            return null;
        }
        if (tag == LoadRow.NUM) {
            return r.num(i);
        }
        try {
            return Double.parseDouble(new String(r.text(i), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Into a {@code list<string>} column. */
    public static List<String> list(LoadRow r, int i) {
        byte tag = r.tag(i);
        if (tag == LoadRow.NULL) {
            return null;
        }
        if (tag == LoadRow.NUM) {
            return List.of(new String(r.numText(i), StandardCharsets.UTF_8));
        }
        String text = new String(r.text(i), StandardCharsets.UTF_8);
        if (tag == LoadRow.STR) {
            return List.of(text);
        }
        List<String> values = ListValues.of(text);
        return values == null ? List.of(text) : values;                  // an object or a nested list: its JSON text, the one element
    }

    /** Into a text column. */
    public static String text(LoadRow r, int i) {
        byte tag = r.tag(i);
        if (tag == LoadRow.NULL) {
            return null;
        }
        return new String(tag == LoadRow.NUM ? r.numText(i) : r.text(i), StandardCharsets.UTF_8);
    }
}
