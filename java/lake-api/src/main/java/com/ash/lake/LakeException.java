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
package com.ash.lake;

/**
 * Drishti Lake's error: a {@link LakeError} code and a message. The message reads {@code "<LK-code> <message>"}; a host maps the code
 * once at its edge (its own codes, its HTTP status from {@link LakeError#status()}).
 */
public class LakeException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final transient LakeError error;

    public LakeException(LakeError error, String message) {
        super(message);
        this.error = error;
    }

    public LakeException(LakeError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    /** The code this error carries. */
    public LakeError error() {
        return error;
    }

    @Override
    public String getMessage() {
        return error.code() + " " + super.getMessage();
    }
}
