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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.asciidoctor.Asciidoctor;
import org.junit.jupiter.api.Test;

class RenderingThreadsTest {
    private static final Logger LOGGER = Logger.getLogger(RenderingThreadsTest.class.getName());

    @Test
    void pagesAndBlogParityAcrossThreadModes() throws IOException {
        // source reusable fixture with a normal page + a blog section to exercise both render paths
        final Path source = Paths.get("target/test-classes/sites/MiniSiteTest/blog");

        final Map<String, String> sequential = render(source, 0);
        final Map<String, String> fixed = render(source, 2);
        final Map<String, String> auto = render(source, -1);

        assertFalse(sequential.isEmpty(), "sequential render should produce files");
        assertEquals(sequential.keySet(), fixed.keySet(), "fixed pool must produce the same pages");
        assertEquals(sequential.keySet(), auto.keySet(), "auto/virtual must produce the same pages");
        for (final Map.Entry<String, String> e : sequential.entrySet()) {
            final String page = e.getKey();
            assertEquals(e.getValue(), fixed.get(page), "fixed pool differs on " + page);
            assertEquals(e.getValue(), auto.get(page), "auto/virtual differs on " + page);
        }
        // blog pages present (proves blog generation ran concurrently too)
        assertTrue(sequential.containsKey("blog/page-1.html"), "blog page should have been generated");
        assertTrue(sequential.containsKey("blog1.html") || sequential.containsKey("blog/index.html"), "blog files expected");
    }

    @Test
    void activeMenuIsPerPageUnderConcurrency() {
        final Path source = Paths.get("target/test-classes/sites/MiniSiteTest/leftMenuActivePage");
        final Map<String, String> parallel = render(source, 2);
        final String pageA = parallel.get("a.html");
        final String pageB = parallel.get("b.html");
        assertNotNull(pageA, "a.html");
        assertNotNull(pageB, "b.html");
        assertFalse(pageA.contains("<minisite-menu-placeholder/>"), pageA);
        assertFalse(pageB.contains("<minisite-menu-placeholder/>"), pageB);
        assertTrue(hasItem(pageA, "a.html", "active"), pageA);
        assertFalse(hasItem(pageA, "b.html", "active"), pageA);
        assertFalse(hasItem(pageB, "a.html", "active"), pageB);
        assertTrue(hasItem(pageB, "b.html", "active"), pageB);
    }

    private static Map<String, String> render(final Path source, final int renderingThreads) {
        final Path work;
        try {
            work = Files.createTempDirectory("minisite-rt-" + renderingThreads + "-");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final Path output = work.resolve("output");
        final Asciidoctor asciidoctor = Asciidoctor.Factory.create();
        final MiniSiteConfiguration config = MiniSiteConfiguration.builder()
                .actionClassLoader(null)
                .source(source)
                .target(output)
                .skipRendering(false)
                .useDefaultAssets(true)
                .generateIndex(true)
                .generateSiteMap(true)
                .generateBlog(true)
                .templateAddLeftMenu(true)
                .blogPageSize(2)
                .title("Test Site")
                .description("The test rendering.")
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
                .renderingThreads(renderingThreads)
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
                        return LOGGER::severe;
                    }
                })
                .build();
        new MiniSite(config).run();

        final Map<String, String> generated = new TreeMap<>();
        try {
            Files.walkFileTree(output, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final String name = file.getFileName().toString();
                    if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".ico") || name.endsWith(".svg"))) {
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

    private static boolean hasItem(final String html, final String page, final String clazz) {
        final String marker = "href=\"/" + page + "\"";
        final int href = html.indexOf(marker);
        if (href < 0) {
            return false;
        }
        final int open = html.lastIndexOf("<li", href);
        final int close = html.indexOf('>', open);
        final String liTag = open >= 0 && close > open ? html.substring(open, close) : "";
        return clazz == null || clazz.isEmpty() ? !liTag.contains("active") : liTag.contains(clazz);
    }
}