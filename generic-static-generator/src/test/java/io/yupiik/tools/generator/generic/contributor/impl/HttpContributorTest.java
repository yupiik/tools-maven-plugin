/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.tools.generator.generic.contributor.impl;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpContributorTest {
    private HttpServer server;
    private ExecutorService executor;
    private int port;
    private JsonMapper mapper;
    private SharedHttpClient httpClient;
    private HttpContributor contributor;

    @BeforeEach
    void startServer() throws Exception {
        mapper = new JsonMapperImpl(List.of(), Configuration.of(Map.of()));
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/test", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());

            final var body = """
                    {
                      "foo": "bar",
                      "nested": {
                        "value": 123
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (final var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.start();
        executor = Executors.newSingleThreadExecutor();

        httpClient = new SharedHttpClient();
        contributor = new HttpContributor(httpClient, mapper);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop(0);
        executor.shutdownNow();
        httpClient.close();
        mapper.close();
    }

    @Test
    void callHttpServerAndExtractJson() {
        final var configuration = Map.of(
                "request.uri", "http://localhost:" + port + "/test",
                "request.method", "GET",
                "response.expectedStatus", "200",
                "response.dataJsonPointer", "/nested"
        );
        final var result = contributor
                .contribute(configuration, executor)
                .toCompletableFuture()
                .join();
        assertEquals(1, result.size());
        assertEquals(BigDecimal.valueOf(123), result.get("value"));
    }

    @Test
    void wrapPrimitiveJsonWhenNoPointerIsProvided() {
        server.removeContext("/test");
        server.createContext("/test", exchange -> {
            final var body = "42".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (final var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        final var configuration = Map.of(
                "request.uri", "http://localhost:" + port + "/test",
                "response.expectedStatus", "200"
        );

        final var result = contributor
                .contribute(configuration, executor)
                .toCompletableFuture()
                .join();
        assertEquals(BigDecimal.valueOf(42), result.get("value"));
    }

    @Test
    void failOnUnexpectedStatus() {
        server.removeContext("/test");
        server.createContext("/test", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        final var configuration = Map.of(
                "request.uri", "http://localhost:" + port + "/test",
                "response.expectedStatus", "200"
        );

        final var ex = assertThrows(
                CompletionException.class,
                () -> contributor
                        .contribute(configuration, executor)
                        .toCompletableFuture()
                        .join()
        ).getCause();
        assertTrue(ex.getMessage().contains("Invalid result"));
    }
}
