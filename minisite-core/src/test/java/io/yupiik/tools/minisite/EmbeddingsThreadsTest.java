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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.language.AsciidoctorAsciidoc;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.asciidoctor.Asciidoctor;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the {@code embeddingsThreads} modes (-2 inherit / 0 sequential / &gt; 0 dedicated) produce the exact
 * same rendered site and LLM embeddings, and that the executor lifecycle (shared rendering pool vs dedicated pool) does
 * not throw or deadlock. The models are present on the test classpath (see LlmEmbeddingGenerator/llmChatEnabled).
 */
class EmbeddingsThreadsTest {
    private static final Logger LOGGER = Logger.getLogger(EmbeddingsThreadsTest.class.getName());
    private static final List<String> ERRORS = new CopyOnWriteArrayList<>();

    @Test
    void embeddingsParityAcrossModes() throws IOException {
        final Path source = Paths.get("target/test-classes/sites/MiniSiteTest/llmSite");
        final int renderingThreads = 1; // keep it single-threaded so the embeddings thread mode is what varies

        final Map<String, String> inherited = render(source, renderingThreads, -2); // share the rendering pool
        final Map<String, String> sequential = render(source, renderingThreads, 0); // sequential (previous behaviour)
        final Map<String, String> dedicated = render(source, renderingThreads, 2); // dedicated fixed pool

        assertTrue(inherited.containsKey("index.html"), "index.html must be rendered");
        assertTrue(inherited.containsKey("guide.html"), "guide.html must be rendered");
        assertTrue(inherited.containsKey("assets/llm/embeddings/chunk-0001.json"),
                "the LLM embedding pass must have run and emitted chunks");

        assertEquals(sequential.keySet(), inherited.keySet(), "sequential must produce the same files");
        assertEquals(dedicated.keySet(), inherited.keySet(), "dedicated must produce the same files");
        for (final Map.Entry<String, String> e : inherited.entrySet()) {
            final String page = e.getKey();
            assertEquals(e.getValue(), sequential.get(page), "sequential differs on " + page);
            assertEquals(e.getValue(), dedicated.get(page), "dedicated differs on " + page);
        }
        assertTrue(ERRORS.isEmpty(), "no rendering/embedding error should have been reported: " + ERRORS);
    }

    private static Map<String, String> render(final Path source, final int renderingThreads, final int embeddingsThreads) {
        final Path work;
        final Path output;
        try {
            work = Files.createTempDirectory("minisite-emb-" + embeddingsThreads + "-");
            output = work.resolve("output");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final Asciidoctor asciidoctor = Asciidoctor.Factory.create();
        final MiniSiteConfiguration config = MiniSiteConfiguration.builder()
                .actionClassLoader(null)
                .source(source)
                .target(output)
                .skipRendering(false)
                .useDefaultAssets(true)
                .generateIndex(true)
                .generateSiteMap(true)
                .generateBlog(false)
                .templateAddLeftMenu(true)
                .title("LLM Site")
                .description("The llm rendering.")
                .logoText("The logo")
                .logo("foo logo")
                .indexText("Index text")
                .indexSubTitle("Index sub title")
                .copyright("Test Copyright")
                .linkedInCompany("test linkedin")
                .siteBase("")
                .searchIndexName("search.json")
                .templatePrefixes(List.of("header.hb", "menu.hb"))
                .templateSuffixes(List.of("footer-top.hb", "footer-end.hb"))
                .projectVersion("1.0.0")
                .projectName("test project")
                .projectArtifactId("test-artifact")
                .llmChatEnabled(true)
                .renderingThreads(renderingThreads)
                .embeddingsThreads(embeddingsThreads)
                .asciidoc(new AsciidoctorAsciidoc((c, a) -> a.apply(asciidoctor)))
                .asciidoctorConfiguration(new AsciidoctorConfiguration() {
                    @Override
                    public Path gems() {
                        return work.resolve("gems");
                    }

                    @Override
                    public String customGems() {
                        return null;
                    }

                    @Override
                    public List<String> requires() {
                        return null;
                    }

                    @Override
                    public Consumer<String> info() {
                        return LOGGER::info;
                    }

                    @Override
                    public Consumer<String> debug() {
                        return LOGGER::finest;
                    }

                    @Override
                    public Consumer<String> warn() {
                        return LOGGER::warning;
                    }

                    @Override
                    public Consumer<String> error() {
                        return message -> {
                            ERRORS.add(message);
                            LOGGER.severe(message);
                        };
                    }
                })
                .build();
        new MiniSite(config).run();
        return collect(output);
    }

    private static Map<String, String> collect(final Path output) {
        final Map<String, String> generated = new TreeMap<>();
        try {
            Files.walkFileTree(output, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    // only text content matters here (rendered pages, index/sitemap, embedding JSON); skip binary assets
                    final String name = file.getFileName().toString();
                    if (name.endsWith(".html") || name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".txt")) {
                        generated.put(output.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                                Files.readString(file, UTF_8));
                    }
                    return super.visitFile(file, attrs);
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return generated;
    }
}