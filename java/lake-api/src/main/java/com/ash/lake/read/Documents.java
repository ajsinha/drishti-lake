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

/** The loader's document rules a reader needs. */
public final class Documents {

    private Documents() {
    }

    /**
     * A document whose vector columns were kept out of it ({@code {"$vector": "PNL"}} markers) with the vectors put back: a NaN or an
     * infinity is {@code null}, a whole number under 1e15 keeps no fraction.
     */
    public static String withVectors(String json, Map<String, double[]> vectors) {
        String out = json;
        for (Map.Entry<String, double[]> e : vectors.entrySet()) {
            StringBuilder a = new StringBuilder(e.getValue().length * 8).append('[');
            for (int i = 0; i < e.getValue().length; i++) {
                double x = e.getValue()[i];
                if (i > 0) {
                    a.append(',');
                }
                if (Double.isNaN(x) || Double.isInfinite(x)) {
                    a.append("null");
                } else if (x == Math.rint(x) && Math.abs(x) < 1e15) {
                    a.append((long) x);
                } else {
                    a.append(x);
                }
            }
            String array = a.append(']').toString();
            out = out.replace("{\"$vector\":\"" + e.getKey() + "\"}", array).replace("{\"$vector\": \"" + e.getKey() + "\"}", array);
        }
        return out;
    }
}
