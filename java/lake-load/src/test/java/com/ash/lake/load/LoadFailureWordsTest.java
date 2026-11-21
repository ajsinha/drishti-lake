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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A failed load ends with one clear line, never a stack trace (the Iceberg loader's words, kept when it was folded into the one loader): a
 * REST catalog whose bucket is missing names the bucket, the endpoint and what to check; an unreachable catalog says so in words, never
 * ": null".
 */
class LoadFailureWordsTest {

    @TempDir
    Path tmp;

    @Test
    void aMissingBucketNamesTheBucketTheEndpointAndWhatToCheck() {
        Throwable qa = new UncheckedIOException(new IOException("createTable book: HTTP 500 NoSuchBucketException: The specified bucket does not exist"));
        String line = LakeLoad.oneLine(qa, Map.of("catalog", "rest", "uri", "http://localhost:8181", "warehouse", "s3://warehouse/",
                "s3.endpoint", "http://localhost:9090"));
        assertThat(line).startsWith("the bucket 'warehouse' does not exist at http://localhost:9090 (said by the REST catalog at http://localhost:8181)")
                .contains("create it, or check the warehouse, s3.endpoint").contains("NoSuchBucketException").doesNotContain("\n");
        assertThat(LakeLoad.oneLine(qa, Map.of("root", "s3a://risk-lake/iceberg"))).startsWith("the bucket 'risk-lake' does not exist at AWS S3");
        assertThat(LakeLoad.oneLine(new IllegalStateException("3 rows not loaded"), Map.of())).startsWith("the load failed: 3 rows not loaded");
    }

    @Test
    void aFailureWithoutAMessageIsSaidInWords() {
        var refused = new java.net.ConnectException();
        refused.initCause(new java.nio.channels.ClosedChannelException());
        Throwable qa = new UncheckedIOException(new IOException(refused));
        assertThat(LakeLoad.oneLine(qa, Map.of("catalog", "rest", "uri", "http://127.0.0.1:1")))
                .startsWith("cannot reach the REST catalog at http://127.0.0.1:1 (the connection was refused or closed before an answer "
                        + "(ClosedChannelException)): check its uri").doesNotContain("null");
        assertThat(LakeLoad.oneLine(new java.nio.channels.ClosedChannelException())).startsWith("the load failed: the connection was closed")
                .doesNotContain("null");
    }

    @Test
    void anUnreachableRestCatalogEndsTheLoadWithOneLine() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();                                          // nothing listens once it is closed
        }
        Path in = Files.createDirectories(tmp.resolve("in"));
        Files.writeString(in.resolve("book.2026-09-30.jsonl"),
                "{\"domain\":\"credit\",\"kind\":\"book\",\"id\":\"B-1\",\"date\":\"2026-09-30\",\"doc\":\"{}\",\"columns\":{}}\n");
        String yaml = """
                source:
                  files: '%s/*'
                  format: loader-rows
                target:
                  format: iceberg
                  lake: '%s'
                  domain: credit
                  kind: book
                  iceberg:
                    catalog: rest
                    uri: http://127.0.0.1:%d
                  storage-options:
                    s3.endpoint: http://127.0.0.1:%d
                """.formatted(in, tmp.resolve("lake"), port, port);
        JsonNode s = LakeLoadTest.load(tmp, yaml);
        String error = s.path("error").asText();
        assertThat(s.path("exit").asInt()).as(s.toString()).isNotZero();
        assertThat(error).as(s.toString()).contains("127.0.0.1:" + port).doesNotContain("\n").doesNotContain(": null").doesNotContain("(null");
        assertThat(s.path("said").asText()).as("no stack trace").doesNotContain("Exception in thread").doesNotContain("\tat ");
    }
}
