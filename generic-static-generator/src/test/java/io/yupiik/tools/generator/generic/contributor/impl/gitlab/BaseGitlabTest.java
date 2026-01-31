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
package io.yupiik.tools.generator.generic.contributor.impl.gitlab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;
import io.yupiik.tools.generator.generic.contributor.impl.SharedHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

abstract class BaseGitlabTest {
    private HttpServer server;
    private SharedHttpClient httpClient;

    protected int port;
    protected ExecutorService executor;
    protected JsonMapper mapper;
    protected ContextContributor contributor;

    protected abstract ContextContributor newGitlabContributor(GitlabService gitlabService);

    protected abstract void initServer(HttpServer server);

    @BeforeEach
    void startServer() throws Exception {
        mapper = new JsonMapperImpl(List.of(), Configuration.of(Map.of()));
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        initServer(server);

        server.start();
        executor = Executors.newSingleThreadExecutor();

        httpClient = new SharedHttpClient(new SharedHttpClient.HttpConfiguration(true, -1));
        contributor = newGitlabContributor(new GitlabService(httpClient, mapper));
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop(0);
        executor.shutdownNow();
        httpClient.close();
        mapper.close();
    }

    protected void validateAndWrite(final HttpExchange exchange, final String body) throws IOException {
        assertEquals("GET", exchange.getRequestMethod());
        assertEquals("junit5", exchange.getRequestHeaders().getFirst("private-token"));

        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (final var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected void assertMatchesJson(final String expected, final Object value) {
        assertEquals(
                mapper.fromString(Object.class, expected),
                mapper.fromString(Object.class, mapper.toString(value)));
    }
}
