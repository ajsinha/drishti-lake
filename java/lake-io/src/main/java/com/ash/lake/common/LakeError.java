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
package com.ash.lake.common;

/** Drishti Lake's error codes ({@code LK-}), each with the HTTP status a host that serves the lake answers with. Codes are never reused. */
public enum LakeError {

    /** A request the lake refuses as given (a bad name, an unread format, a node that does not own the inbox). */
    BAD_REQUEST("LK-1001", 400),
    /** A file or table the request names is not there. */
    NOT_FOUND("LK-1002", 404),
    /** A file over the limit, or not enough room to stage it. */
    TOO_LARGE("LK-1003", 413),
    /** A data file and its {@code .ready} refused by the checks, or a file of the same name already waiting in the inbox. */
    READY_REFUSED("LK-1004", 409),
    /** Memory-only data the place it would go cannot take (a disk, a cache, an outside service): only a download may leave. */
    MEMORY_ONLY("LK-1005", 403);

    private final String code;
    private final int status;

    LakeError(String code, int status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
