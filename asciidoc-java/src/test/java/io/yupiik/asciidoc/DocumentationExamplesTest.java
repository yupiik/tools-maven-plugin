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
package io.yupiik.asciidoc;

import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.SourceSpan;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer;
import io.yupiik.asciidoc.renderer.html.FontAwesome4Handler;
import io.yupiik.asciidoc.renderer.markdown.GithubFlavoredMarkdownRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java examples of the asciidoc-java documentation pages, which include the tagged regions.
 */
class DocumentationExamplesTest {
    private final Path base = Path.of("target/missing");

    @Test
    void quickStart() {
        final var content = "= Hello\n\nHello *AsciiDoc* from Java.\n";
        // tag::quickstart[]
        final var parser = new Parser();
        final var doc = parser.parse(content, new Parser.ParserContext(ContentResolver.of(base)));
        final var renderer = new AsciidoctorLikeHtmlRenderer();
        renderer.visit(doc);
        final var html = renderer.result();
        // end::quickstart[]
        assertEquals("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                 <meta charset="UTF-8">
                 <meta name="viewport" content="width=device-width, initial-scale=1.0">
                 <meta name="generator" content="Asciidoctor ">
                 <title>Hello</title>
                </head>
                <body class="article">
                 <div id="header">
                 <h1>Hello</h1>
                 </div>
                 <div id="content">
                 <div class="paragraph">
                 <p>Hello <strong>AsciiDoc</strong> from Java.</p>
                 </div>
                 </div>
                 <div id="footer">
                  <div id="footer-text">
                  </div>
                 </div>
                </body>
                </html>
                """, html);
    }

    @Test
    void htmlRendererUsage() {
        final var doc = new Parser().parse("== Intro\n\nHello world.\n", new Parser.ParserContext(ContentResolver.of(base)));
        // tag::html-usage[]
        final var renderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setAttributes(Map.of("noheader", "true", "icons", "font"))
                .setSectionTag("section"));
        renderer.visit(doc);
        final var html = renderer.result();
        // end::html-usage[]
        assertEquals("""
                 <section class="sect1">
                  <h2 id="_intro">Intro</h2>
                 <div class="sectionbody">
                 <div class="paragraph">
                 <p>
                Hello world.
                 </p>
                 </div>
                 </div>
                 </section>
                """, html);
    }

    @Test
    void markdownRendererUsage() {
        final var doc = new Parser().parse("""
                = Release notes

                == Version 1.3

                NOTE: The parser now reads `icon:lock[]` as asciidoctor does.

                * Faster parsing, see <<benchmark>>.
                * A `FontHandler` for the icon font of the site.

                [#benchmark]
                == Benchmark

                Run `GuideBench` on the Fusion documentation to measure a change.

                image::benchmark.png[Parse times]
                """, new Parser.ParserContext(ContentResolver.of(base)));
        // tag::markdown-usage[]
        final var renderer = new GithubFlavoredMarkdownRenderer(new GithubFlavoredMarkdownRenderer.Configuration()
                .setAttributes(Map.of("imagesdir", "/images")));
        renderer.visit(doc);
        final var markdown = renderer.result();
        // end::markdown-usage[]
        assertEquals("""
                # Release notes

                ## Version 1.3

                > [!NOTE]
                > The parser now reads `icon:lock[]` as asciidoctor does.

                - Faster parsing, see [Benchmark](#benchmark).
                - A `FontHandler` for the icon font of the site.

                <a id="benchmark"></a>
                ## Benchmark

                Run `GuideBench` on the Fusion documentation to measure a change.

                ![Parse times](/images/benchmark.png)
                """, markdown);
    }

    @Test
    void sourcePositions() {
        final var parser = new Parser();
        final var resolver = ContentResolver.of(base);
        final var content = "= Doc\n\nFirst paragraph.\n\nSecond paragraph.\n";
        // tag::source-positions[]
        final var spans = new IdentityHashMap<Element, SourceSpan>();
        final var doc = parser.parse(content, new Parser.ParserContext(resolver, spans::put));
        // end::source-positions[]
        assertEquals(new SourceSpan(null, 5, 5, "Second paragraph."), spans.get(doc.body().children().get(1)));
    }

    @Test
    void fontHandler() {
        final var body = new Parser().parseBody("Locked icon:lock[] here.\n", new Parser.ParserContext(ContentResolver.of(base)));
        // tag::font-handler[]
        final var configuration = new AsciidoctorLikeHtmlRenderer.Configuration()
                .setFontHandler(new FontAwesome4Handler());
        // end::font-handler[]
        final var renderer = new AsciidoctorLikeHtmlRenderer(configuration.setAttributes(Map.of("noheader", "true")));
        renderer.visitBody(body);
        assertEquals("""
                 <div class="paragraph">
                 <p>Locked <span class="icon"><i class="fa fa-lock"></i></span> here.</p>
                 </div>
                """, renderer.result());
    }

    @Test
    void rendererSubclass() {
        final var body = new Parser().parseBody("Set tooltip:foo[a hint] here.\n", new Parser.ParserContext(ContentResolver.of(base)));
        final var configuration = new AsciidoctorLikeHtmlRenderer.Configuration().setAttributes(Map.of("noheader", "true"));
        // tag::renderer-subclass[]
        final var renderer = new AsciidoctorLikeHtmlRenderer(configuration) {
            @Override
            protected void onMissingMacro(final Macro macro) {
                if (!"tooltip".equals(macro.name())) {
                    super.onMissingMacro(macro);
                    return;
                }
                builder.append("<span title=\"").append(escape(macro.options().getOrDefault("", ""))).append("\">")
                        .append(escape(macro.label()))
                        .append("</span>");
            }
        };
        // end::renderer-subclass[]
        renderer.visitBody(body);
        assertEquals("""
                 <div class="paragraph">
                 <p>Set <span title="a hint">foo</span> here.</p>
                 </div>
                """, renderer.result());
    }

    @Test
    void pagesIncludeExistingTags() throws IOException {
        final var minisite = Path.of("../_documentation/src/main/minisite");
        final var examples = Path.of("src/test/java/io/yupiik/asciidoc");
        final var source = examples.resolve("DocumentationExamplesTest.java");
        final var prefix = "include::{asciidoc-java-examples}/";
        final var included = new TreeSet<String>();
        try (final var pages = Files.newDirectoryStream(minisite.resolve("content"), "asciidoc-java*.adoc")) {
            for (final var page : pages) {
                for (final var line : Files.readAllLines(page)) {
                    if (!line.startsWith("include::") || !line.contains("DocumentationExamplesTest.java[")) {
                        continue;
                    }
                    assertTrue(line.startsWith(prefix), () -> page + " does not include from {asciidoc-java-examples}: " + line);
                    final var open = line.indexOf('[');
                    final var target = examples.resolve(line.substring(prefix.length(), open));
                    assertTrue(Files.exists(target) && Files.isSameFile(source, target), () -> page + " includes " + target);
                    final var tagAt = line.indexOf("tag=", open);
                    assertTrue(tagAt > 0, () -> page + " includes the whole file: " + line);
                    final var from = tagAt + "tag=".length();
                    final var comma = line.indexOf(',', from);
                    final var close = line.indexOf(']', from);
                    included.add(line.substring(from, comma < 0 || close < comma ? close : comma));
                }
            }
        }
        final var lines = Files.readAllLines(source);
        assertEquals(markers(lines, "// tag::"), included);
        assertEquals(markers(lines, "// end::"), included);
    }

    private Set<String> markers(final List<String> lines, final String marker) {
        final var names = new TreeSet<String>();
        for (final var line : lines) {
            final var text = line.strip();
            if (text.startsWith(marker) && text.endsWith("[]")) {
                names.add(text.substring(marker.length(), text.length() - "[]".length()));
            }
        }
        return names;
    }
}
