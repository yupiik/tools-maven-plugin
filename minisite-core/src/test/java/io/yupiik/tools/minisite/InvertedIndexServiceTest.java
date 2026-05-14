/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.tools.minisite;

import jakarta.json.bind.JsonbBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvertedIndexServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void indexSinglePage(@TempDir final Path tempDir) throws Exception {
        final var htmlFile = tempDir.resolve("page.html");
        Files.write(htmlFile, Collections.singletonList(
                "<html><head>" +
                        "<title>Test Page</title>" +
                        "<meta name=\"description\" value=\"A test description\"/>" +
                        "<meta name=\"keywords\" value=\"test, demo\"/>" +
                        "</head><body>" +
                        "<div class=\"page-content-body\">" +
                        "<h1>Main Header</h1>" +
                        "<p>This is the paragraph content for testing search indexing.</p>" +
                        "</div></html>"
        ));

        final var json = indexToJson(tempDir);
        try (final var jsonb = JsonbBuilder.create()) {
            final var root = jsonb.fromJson(json, Map.class);
            assertEquals(1, root.get("v"));
            assertEquals(1, ((List<?>) root.get("d")).size());

            final var doc = (Map<String, Object>) ((List<?>) root.get("d")).get(0);
            assertEquals("/page.html", doc.get("u"));
            assertEquals("Test Page", doc.get("t"));
            assertEquals("A test description", doc.get("m"));
            assertTrue(String.valueOf(doc.get("x")).contains("test description"));
            assertTrue(((Map<?, ?>) root.get("i")).size() > 0);
        }
    }

    @Test
    void indexSkipMarker(@TempDir final Path tempDir) throws Exception {
        final var htmlFile = tempDir.resolve("skip.html");
        Files.write(htmlFile, Arrays.asList(
                "<html><head><title>Skip Page</title></head><body>",
                ":minisite-index-skip: true",
                "<p>This content should not be indexed.</p></body></html>"
        ));

        final var json = indexToJson(tempDir);
        try (final var jsonb = JsonbBuilder.create()) {
            final var root = jsonb.fromJson(json, Map.class);
            assertEquals(0, ((List<?>) root.get("d")).size(),
                    "Pages with :minisite-index-skip: true should be excluded");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexMultiplePages(@TempDir final Path tempDir) throws Exception {
        Files.write(tempDir.resolve("a.html"), Collections.singletonList(
                "<html><head><title>Page A</title></head><body>" +
                        "<div class=\"page-content-body\"><p>Alpha content.</p></div></html>"
        ));
        Files.write(tempDir.resolve("b.html"), Collections.singletonList(
                "<html><head><title>Page B</title></head><body>" +
                        "<div class=\"page-content-body\"><p>Beta content.</p></div></html>"
        ));

        final var json = indexToJson(tempDir);
        try (final var jsonb = JsonbBuilder.create()) {
            final var root = jsonb.fromJson(json, Map.class);
            final var docs = (List<Map<String, Object>>) (List<?>) root.get("d");
            assertEquals(2, docs.size());
            assertEquals("/a.html", docs.get(0).get("u"));
            assertEquals("/b.html", docs.get(1).get("u"));
        }
    }

    @Test
    void testJsonOutput(@TempDir final Path tempDir) throws Exception {
        Files.write(tempDir.resolve("test.html"), Collections.singletonList(
                "<html><head><title>JSON Test</title></head><body>" +
                        "<div class=\"page-content-body\"><p>Some unique content words here.</p></div></html>"
        ));

        final var json = indexToJson(tempDir);
        assertTrue(json.contains("\"v\":1"), "version");
        assertTrue(json.contains("\"d\":"), "documents");
        assertTrue(json.contains("\"s\":"), "stop words");
        assertTrue(json.contains("\"t\":"), "all terms");
        assertTrue(json.contains("\"i\":"), "inverted index");
        assertTrue(json.contains("\"u\":\"/test.html\""), "url");
        assertTrue(json.contains("\"t\":\"JSON Test\""), "title");
        assertTrue(json.startsWith("{") && json.endsWith("}"), "should be valid JSON object");
    }

    private String indexToJson(final Path base) throws IOException {
        final var service = new InvertedIndexService();
        final var out = base.resolve("__search_test.json");
        service.write(service.index(base, "", p -> p.getFileName().toString().endsWith(".html")), out);
        final var json = Files.readString(out);
        Files.delete(out);
        return json;
    }
}
