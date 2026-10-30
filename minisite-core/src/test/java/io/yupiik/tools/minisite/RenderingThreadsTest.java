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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.language.Asciidoc;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import org.asciidoctor.Asciidoctor;
import org.junit.jupiter.api.Test;

class RenderingThreadsTest {
    private static final Logger LOGGER = Logger.getLogger(RenderingThreadsTest.class.getName());
    private static final List<String> ERRORS = new CopyOnWriteArrayList<>();

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

    @Test
    void pageFailuresFailTheRun() {
        // left-menu-item.hb misses its {{/if}}, so both page renders throw, on a worker thread in the parallel modes
        final Path source = Paths.get("target/test-classes/sites/MiniSiteTest/renderingFailure");
        for (final int renderingThreads : List.of(0, 2, -1)) {
            final String mode = "renderingThreads=" + renderingThreads;
            final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> render(source, renderingThreads), mode);
            assertEquals("Minisite rendering failed, see the suppressed exceptions", failure.getMessage(), mode);
            final Throwable[] pages = failure.getSuppressed();
            assertEquals(2, pages.length, mode + ": " + List.of(pages));
            for (final Throwable page : pages) {
                assertTrue(page.getMessage().contains("Missing {{/if}}"), mode + ": " + page);
            }
        }
    }

    @Test
    void onePageFailureKeepsTheOtherPages() {
        // only b.adoc carries the :minisite-test-fail: attribute which makes the wrapped converter throw
        final Path source = Paths.get("target/test-classes/sites/MiniSiteTest/renderingFailureOnePage");
        for (final int renderingThreads : List.of(0, 2, -1)) {
            final String mode = "renderingThreads=" + renderingThreads;
            final Path work = newWorkDirectory(renderingThreads);
            ERRORS.clear();
            final IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> run(work, source, renderingThreads, RenderingThreadsTest::failingOnMarkedPages), mode);
            final Throwable[] pages = failure.getSuppressed();
            assertEquals(1, pages.length, mode + ": " + List.of(pages));
            assertEquals("page marked minisite-test-fail", pages[0].getMessage(), mode);
            assertEquals(List.of("Rendering failed: java.lang.IllegalArgumentException: page marked minisite-test-fail"), ERRORS, mode);
            assertTrue(Files.exists(work.resolve("output/a.html")), mode + ": a.html must be rendered");
            assertFalse(Files.exists(work.resolve("output/b.html")), mode + ": b.html must not be rendered");
        }
    }

    @Test
    void interruptedAwaitThrowsTheFailures() throws InterruptedException {
        final CountDownLatch pageFailed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Throwable[] thrown = new Throwable[1];
        final boolean[] interrupted = new boolean[1];
        try (final MiniSite.RenderingExecutor executor = new MiniSite.RenderingExecutor(1, LOGGER::warning, error -> pageFailed.countDown())) {
            executor.route(() -> {
                throw new IllegalArgumentException("page");
            });
            assertTrue(pageFailed.await(30, TimeUnit.SECONDS), "the page failure must be recorded first");
            executor.route(() -> {
                try {
                    release.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            final Thread awaiting = new Thread(() -> {
                try {
                    executor.await();
                } catch (final Throwable e) {
                    thrown[0] = e;
                }
                interrupted[0] = Thread.currentThread().isInterrupted();
            });
            awaiting.start();
            awaiting.interrupt();
            awaiting.join(TimeUnit.SECONDS.toMillis(30));
            release.countDown();
        }
        assertNotNull(thrown[0], "await() must throw when interrupted");
        assertEquals("Minisite rendering failed, see the suppressed exceptions", thrown[0].getMessage());
        final Throwable[] suppressed = thrown[0].getSuppressed();
        assertEquals(2, suppressed.length, List.of(suppressed).toString());
        assertEquals("page", suppressed[0].getMessage());
        assertEquals(InterruptedException.class, suppressed[1].getClass());
        assertTrue(interrupted[0], "the interrupt flag must be restored");
    }

    private static Map<String, String> render(final Path source, final int renderingThreads) {
        return collect(run(newWorkDirectory(renderingThreads), source, renderingThreads, UnaryOperator.identity()));
    }

    private static Path newWorkDirectory(final int renderingThreads) {
        try {
            return Files.createTempDirectory("minisite-rt-" + renderingThreads + "-");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Delegates to Asciidoctor, except that the conversion of a page holding {@code :minisite-test-fail:} throws. */
    private static Asciidoc failingOnMarkedPages(final Asciidoc delegate) {
        return new Asciidoc() {
            @Override
            public Object createOptions(final MiniSiteConfiguration configuration) {
                return delegate.createOptions(configuration);
            }

            @Override
            public <T> T withInstance(final AsciidoctorConfiguration asciidoctorConfiguration, final Function<AsciidocInstance, T> function) {
                return delegate.withInstance(asciidoctorConfiguration, instance -> function.apply(new AsciidocInstance() {
                    @Override
                    public Header header(final String content, final Object options) {
                        return instance.header(content, options);
                    }

                    @Override
                    public String convert(final String content, final Object options) {
                        if (content.contains(":minisite-test-fail:")) {
                            throw new IllegalArgumentException("page marked minisite-test-fail");
                        }
                        return instance.convert(content, options);
                    }
                }));
            }
        };
    }

    private static Path run(final Path work, final Path source, final int renderingThreads, final UnaryOperator<Asciidoc> asciidoc) {
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
                .asciidoc(asciidoc.apply(new AsciidoctorAsciidoc((c, a) -> a.apply(asciidoctor))))
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
        return output;
    }

    private static Map<String, String> collect(final Path output) {
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