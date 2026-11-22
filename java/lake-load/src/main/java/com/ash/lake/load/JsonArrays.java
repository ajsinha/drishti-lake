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
package com.ash.lake.load;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;

/**
 * JSON arrays as loader input (LOADER.md "Input"): a {@code .json} file or archive member (plain, {@code .gz} or {@code .zst}) whose first
 * character (after a byte-order mark and white space) is {@code [} is a JSON array whose elements are the rows; read as a stream (never
 * the whole file in memory), each element given to the row parser as the line a JSON Lines file would hold, so an array and the same
 * rows as JSON Lines load alike (the same rejects, an element that is not an object among them, numbered by element). A {@code .json}
 * file that does not start with {@code [} is JSON Lines, as before. An array that breaks off (a syntax error, a missing {@code ]}) fails
 * the load, naming the file and the element; text after the closing {@code ]} is refused too.
 */
final class JsonArrays {

    private static final JsonFactory JSON = JsonFactory.builder().build();

    private JsonArrays() {}

    /** Whether the name is one an array may have: {@code .json}, plain or compressed. */
    static boolean named(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".json") || n.endsWith(".json.gz") || n.endsWith(".json.zst");
    }

    /** {@code in} as JSON Lines: unchanged unless {@code name} may be an array and the content starts with {@code [}. */
    static InputStream lines(String name, InputStream in) throws IOException {
        if (!named(name)) {
            return in;
        }
        PushbackInputStream p = new PushbackInputStream(in, 4096);
        byte[] head = new byte[4096];
        int n = 0;
        int first = -1;
        while (n < head.length) {
            int b = p.read();
            if (b < 0) {
                break;
            }
            head[n++] = (byte) b;
            boolean bom = n <= 3 && (n == 1 && b == 0xef || n == 2 && b == 0xbb || n == 3 && b == 0xbf) && (head[0] & 0xff) == 0xef;
            if (!bom && b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                first = b;
                break;
            }
        }
        p.unread(head, 0, n);
        return first == '[' ? new Elements(name, p) : p;
    }

    /** The array's elements, one compact line each. */
    private static final class Elements extends InputStream {
        private final String name;
        private final JsonParser parser;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(1 << 12);
        private byte[] buf = new byte[0];
        private int at;
        private long element;
        private boolean done;

        Elements(String name, InputStream in) throws IOException {
            this.name = name;
            this.parser = JSON.createParser(in);
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException(name + ": not a JSON array");
            }
        }

        private boolean fill() throws IOException {
            if (done) {
                return false;
            }
            try {
                JsonToken t = parser.nextToken();
                if (t == JsonToken.END_ARRAY) {
                    done = true;
                    if (parser.nextToken() != null) {
                        throw new IOException(name + ": text after the JSON array's closing ]: nothing was loaded");
                    }
                    return false;
                }
                if (t == null) {
                    throw new IOException(name + ": the JSON array ends without its closing ] after element " + element + ": nothing was loaded");
                }
                element++;
                line.reset();
                try (JsonGenerator g = JSON.createGenerator(line)) {
                    g.copyCurrentStructureExact(parser);
                }
                line.write('\n');
                buf = line.toByteArray();
                at = 0;
                return true;
            } catch (JsonProcessingException e) {
                throw new IOException(name + ": element " + (element + 1) + " of the JSON array is not JSON (" + e.getOriginalMessage()
                        + "): nothing was loaded", e);
            }
        }

        @Override
        public int read() throws IOException {
            if (at >= buf.length && !fill()) {
                return -1;
            }
            return buf[at++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int n = 0;
            while (n < len) {
                if (at >= buf.length && !fill()) {
                    break;
                }
                int k = Math.min(len - n, buf.length - at);
                System.arraycopy(buf, at, b, off + n, k);
                at += k;
                n += k;
            }
            return n == 0 ? -1 : n;
        }

        @Override
        public void close() throws IOException {
            parser.close();
        }
    }
}
