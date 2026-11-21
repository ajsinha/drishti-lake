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

import java.util.Objects;

/**
 * One table of a lake: the lake's root (absolute, or a URI with a scheme: {@code s3://}, {@code mem://}), the domain and the table's name.
 * Its resolved place is {@link #location()} ({@code <root>/<domain>/<table>}), never a path relative to the working folder.
 */
public record TableRef(String root, String domain, String table) {

    public TableRef {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(table, "table");
        root = root.replace('\\', '/');
        if (root.length() > 1 && root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (!(root.startsWith("/") || root.contains("://") || root.matches("^[A-Za-z]:/.*"))) {
            throw new IllegalArgumentException("a lake root is absolute or a URI (explicit paths), not " + root);
        }
        if (domain.isBlank() || table.isBlank() || domain.contains("/") || table.contains("/")) {
            throw new IllegalArgumentException("a table is <domain>/<table>, each one name: " + domain + "/" + table);
        }
    }

    /** The table's place: {@code <root>/<domain>/<table>}. */
    public String location() {
        return root + "/" + domain + "/" + table;
    }

    /** {@code <domain>/<table>}. */
    public String name() {
        return domain + "/" + table;
    }
}
