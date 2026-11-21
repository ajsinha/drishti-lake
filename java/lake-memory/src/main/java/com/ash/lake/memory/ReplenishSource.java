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
package com.ash.lake.memory;

import com.ash.lake.spi.RowSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * What a replenish job reads: a connector's documents of a kind (any connector that can list a kind: the
 * lakes, Kafka's topic state, JDBC, files, the vendor feeds), a JSON Lines file, or a URL answering JSON Lines or a JSON array. Everything
 * is read into memory and nothing is written anywhere: the source's own bytes stay where they were.
 */
public final class ReplenishSource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private final RowSource rows;
    private volatile java.util.function.Function<String, List<String>> idsOf = ref -> List.of();

    /** @param rows the host's connectors ({@link RowSource#NONE}: files and URLs only) */
    public ReplenishSource(RowSource rows) {
        this.rows = rows == null ? RowSource.NONE : rows;
    }

    /** How {@code ids-from} ({@code <table>.<FIELD>}) is read: the lake answers it from its own memory tables. */
    void idsOf(java.util.function.Function<String, List<String>> f) {
        this.idsOf = f;
    }

    /** The documents of {@code source}, each a JSON object. */
    List<ObjectNode> read(MemoryLakeSettings.Source source) throws Exception {
        if (source == null) {
            throw new IllegalArgumentException("the table names no source");
        }
        if (source.connector() != null && !source.connector().isBlank()) {
            if (!source.ids().isEmpty() || (source.idsFrom() != null && !source.idsFrom().isBlank())) {
                List<String> ids = new ArrayList<>(source.ids());
                if (source.idsFrom() != null && !source.idsFrom().isBlank()) {
                    ids.addAll(idsOf.apply(source.idsFrom().trim()));
                }
                return byIds(source.connector(), source.kind(), ids.stream().distinct().toList());
            }
            return fromConnector(source.connector(), source.kind());
        }
        if (source.file() != null && !source.file().isBlank()) {
            Path p = Path.of(source.file());
            try (InputStream in = Files.newInputStream(p)) {
                return lines(source.file().endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in);
            }
        }
        if (source.url() != null && !source.url().isBlank()) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(source.url())).timeout(Duration.ofMinutes(2)).GET();
            source.headers().forEach(b::header);
            HttpResponse<InputStream> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() / 100 != 2) {
                    throw new IOException("the feed answered HTTP " + r.statusCode());
                }
                return lines(in);
            }
        }
        throw new IllegalArgumentException("the source names no connector, file or url");
    }

    /** A marker of the source's state, so a change can start a run (a file's time and size; a connector's last update), or null. */
    String mark(MemoryLakeSettings.Source source) {
        try {
            if (source.file() != null && !source.file().isBlank()) {
                Path p = Path.of(source.file());
                return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p) : null;
            }
            if (source.connector() != null && !source.connector().isBlank()) {
                return rows.mark(source.connector(), source.kind());
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;                                              // a URL has no cheap marker: its schedule refreshes it
    }

    private List<ObjectNode> fromConnector(String name, String kind) throws Exception {
        return objects(rows.documents(name, kind));
    }

    /** Each id fetched from the connector (one that computes a document when asked, such as a news feed); a miss is skipped. */
    private List<ObjectNode> byIds(String name, String kind, List<String> ids) throws Exception {
        return objects(rows.fetch(name, kind, ids));
    }

    private static List<ObjectNode> objects(List<java.util.Map<String, Object>> docs) {
        List<ObjectNode> out = new ArrayList<>();
        for (java.util.Map<String, Object> d : docs) {
            if (d != null && JSON.valueToTree(d) instanceof ObjectNode o) {
                out.add(o);
            }
        }
        return out;
    }

    /** JSON Lines, or one JSON array of objects. */
    static List<ObjectNode> lines(InputStream in) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        r.mark(1 << 16);
        int first;
        do {
            first = r.read();
        } while (first >= 0 && Character.isWhitespace(first));
        r.reset();
        if (first == '[') {
            JsonNode all = JSON.readTree(r);
            all.forEach(n -> {
                if (n instanceof ObjectNode o) {
                    out.add(o);
                }
            });
            return out;
        }
        String line;
        while ((line = r.readLine()) != null) {
            if (!line.isBlank()) {
                JsonNode n = JSON.readTree(line);
                if (n instanceof ObjectNode o) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}
