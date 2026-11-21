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
package com.ash.lake.delta.write;

import io.delta.kernel.defaults.engine.fileio.PositionOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** An output stream that knows its position (Parquet writes its footer offsets from it), with an optional step after it is closed. */
final class CountingOutput extends PositionOutputStream {

    /** What to do when the stream is closed. */
    interface OnClose {
        void run() throws IOException;
    }

    private final OutputStream out;
    private final OnClose onClose;
    private long pos;

    CountingOutput(OutputStream out, OnClose onClose) {
        this.out = out;
        this.onClose = onClose;
    }

    @Override
    public long getPos() {
        return pos;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        pos++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        pos += len;
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
        if (onClose != null) {
            onClose.run();
        }
    }
}
