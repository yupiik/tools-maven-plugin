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
package io.yupiik.maven.service.confluence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfluenceServiceTest {
    @Test
    void rewrite(@TempDir final Path tmp) throws Exception {
        final var html = Files.createDirectories(tmp.resolve("html"));
        createRenderedHtmlWebSite(html);

        final var service = new ConfluenceService();
        final var confluence = new Confluence();
        confluence.setIgnore(false);
        confluence.setSkipIndex(false);
        confluence.setServerId("ignored");
        confluence.setSpace("MYSPACE");
        confluence.setAuthorization("Foo");

        final var captures = new HashMap<String, ConfluenceService.Content>();
        final var http = HttpServer.create(new InetSocketAddress(0), 64);
        final var searchCounter = new AtomicInteger();
        try (final var jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            http.createContext("/").setHandler(exchange -> onConfluenceRequest(captures, jsonb, exchange, searchCounter));
            http.start();
            confluence.setUrl("http://localhost:" + http.getAddress().getPort() + "/wiki/");
            service.upload(confluence, html, info -> {
            }, "//yupiik.github.io/uship", new SystemStreamLog());
        } finally {
            http.stop(0);
        }

        assertEquals(2, searchCounter.get());
        assertEquals(2, captures.size()); // if we use a list we would get 3 since index it rewritten

        final var index = captures.values().stream().collect(toMap(ConfluenceService.Content::getTitle, c -> c.getBody().getStorage().getValue()));
        assertEquals(
                Map.of(
                        "index.html", "" +
                                "<div class=\"page\">\n" +
                                "    <div class=\"page-content\">\n" +
                                "        <div class=\"container\">\n" +
                                "            <h1 class=\"page-heading mx-auto\">Yupiik µShip Documentation</h1>\n" +
                                "            <div>This is the index</div>\n" +
                                "            <a title=\"Client Usage\" class=\"card-link-mask\" href=\"/wiki/spaces/MYSPACE/pages/httpclient-id-value/HTTP+Client\">Client</a>        </div>\n" +
                                "    </div>\n" +
                                "    \n" +
                                "    </div>" +
                                "",

                        "HTTP Client", "" +
                                "<div class=\"page\"><div id=\"http-client-html-container\" class=\"container page-content http-client-html\">\n" +
                                "                        <div class=\"page-header\">\n" +
                                "                <h1>HTTP Client</h1>\n" +
                                "            </div>\n" +
                                "        </div>\n" +
                                "    </div>" +
                                ""
                ),
                index
        );
    }

    private void createRenderedHtmlWebSite(final Path html) throws IOException {
        Files.writeString(html.resolve("index.html"), "" +
                "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<body>\n" +
                "<head>\n" +
                "    <title>Index</title>\n" +
                "</head>\n" +
                "    <header class=\"header fixed-top\"></header>\n" +
                "  <div class=\"page\">\n" +
                "    <div class=\"page-content\">\n" +
                "        <div class=\"container\">\n" +
                "            <h1 class=\"page-heading mx-auto\">Yupiik µShip Documentation</h1>\n" +
                "            <div>This is the index</div>\n" +
                "            <a title=\"Client Usage\" class=\"card-link-mask\" href=\"//yupiik.github.io/uship/http-client.html\">Client</a>" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    </div>\n" +
                "    <hr />\n" +
                "    <footer class=\"footer pb-5\">\n" +
                "      <div class=\"footer-bottom text-center\">\n" +
                "        <ul class=\"social-list list-unstyled\">\n" +
                "                <li class=\"list-inline-item\"><a href=\"https://www.github.com/yupiik/\"><i class=\"fab fa-github fa-fw\"></i></a></li><li class=\"list-inline-item\"><a href=\"https://www.linkedin.com/company/yupiik/\"><i class=\"fab fa-linkedin fa-fw\"></i></a></li>\n" +
                "          </ul>\n" +
                "            <small class=\"copyright\">Yupiik &copy;</small> | <a href=\"https://www.yupiik.com/terms-of-service/\">Terms of service</a> | <a href=\"https://www.yupiik.com/privacy-policy/\">Privacy policy</a>\n" +
                "      </div>\n" +
                "    </footer>\n" +
                "</div>\n" +
                "</body>\n" +
                "</html>" +
                "");
        Files.writeString(html.resolve("http-client.html"), "" +
                "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<body>\n" +
                "<head>\n" +
                "    <title>HTTP Client</title>\n" +
                "</head>\n" +
                "    <header class=\"header fixed-top\"></header>\n" +
                "  <div class=\"page\">\n" +
                "        <div class=\"page-navigation-left\">\n" +
                "            <h3>Menu</h3>\n" +
                "            <ul>\n" +
                "                <li>\n" +
                "                    <a title=\"Getting Started\" href=\"//yupiik.github.io/uship/getting-started.html\">\n" +
                "                        <i class=\"fas fa-play\"></i>Getting Started\n" +
                "                    </a>\n" +
                "            </ul>\n" +
                "        </div>\n" +
                "        <div id=\"http-client-html-container\" class=\"container page-content http-client-html\">\n" +
                "                        <div class=\"page-header\">\n" +
                "                <h1>HTTP Client</h1>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    <hr />\n" +
                "    <footer class=\"footer pb-5\"></footer>\n" +
                "</div>\n" +
                "</body>\n" +
                "</html>\n" +
                "");
    }

    private void onConfluenceRequest(final Map<String, ConfluenceService.Content> captures, final Jsonb jsonb,
                                     final HttpExchange exchange, final AtomicInteger searchCount) throws IOException {
        switch (exchange.getRequestURI().getPath()) {
            case "/wiki/rest/api/content/search":
                assertEquals("GET", exchange.getRequestMethod());
                final var contents = (searchCount.incrementAndGet() == 2 ?
                        "{\"results\":[{\"id\":\"httpclient-id-value\",\"title\":\"HTTP Client\"," +
                                "\"_links\":{\"webui\":\"/spaces/MYSPACE/pages/httpclient-id-value/HTTP+Client\"}," +
                                "\"body\":{\"storage\":{\"value\":\"<div class=\\\"container page-content http-client-html\\\"></div>\"}}}],\"_links\":{}}" :
                        "{\"results\":[],\"_links\":{}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, contents.length);
                exchange.getResponseBody().write(contents);
                break;
            case "/wiki/rest/api/content":
                assertEquals("POST", exchange.getRequestMethod());
                final ConfluenceService.Content content;
                try (final var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    content = jsonb.fromJson(reader, ConfluenceService.Content.class);
                    captures.put(content.getTitle(), content);
                }
                final var json = jsonb.toJson(new ConfluenceService.Content(
                        content.getType(),
                        Integer.toString(captures.size()),
                        content.getTitle(),
                        content.getSpace(),
                        content.getBody(),
                        new ConfluenceService.Version(1),
                        Map.of("webui", "somewhere")
                )).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
                break;
            default:
                exchange.sendResponseHeaders(404, 0);
        }
        exchange.close();
    }
}
