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

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.language.YupiikAsciidoc;
import io.yupiik.tools.minisite.test.MiniSiteConfigurationBuilderProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MiniSiteConfigurationBuilderProvider
class MiniSiteTest {
    @Test
    void indexCardGridCss(final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder,
                          final MiniSiteConfigurationBuilderProvider.Asserts asserts) {
        new MiniSite(builder
                .source(Paths.get("target/test-classes/sites/MiniSiteTest/blog")) // reuse blog fixture for the assets
                .addCodeCopyButton(true)
                .build())
                .run();
        asserts.assertThat(files -> {
            final String css = files.get("css/theme.css");
            assertTrue(css.contains("nth-last-child(2):nth-child(3n + 1)"), css);
            assertFalse(css.contains(".justify-content-center"), css);
            assertFalse(css.contains(".row > .col-lg-4 {\n    flex: none;"), css);
            assertFalse(css.contains("max-width: none"), css);

            // bundled, overridable syntax highlighting theme (probably brand tokens + dark/light aware)
            final String syntaxCss = files.get("css/syntax-highlighting.css");
            assertTrue(syntaxCss.contains("--sh-keyword"), syntaxCss);
            assertTrue(syntaxCss.contains("--sh-comment"), syntaxCss);
            assertTrue(syntaxCss.contains("--brand-purple"), syntaxCss);
            assertTrue(syntaxCss.contains(":root[data-theme='dark']"), syntaxCss);
            assertTrue(syntaxCss.contains(".page-content-body .hljs-keyword"), syntaxCss);

            // generated page wires the local syntax css and the highlight.js v11 + copy button
            final String page = files.get("blog1.html");
            assertTrue(page.contains("css/syntax-highlighting.css"), page);
            assertTrue(page.contains("@highlightjs/cdn-assets@11.11.2/highlight.min.js"), page);
            assertTrue(page.contains("copy-code-button"), page);
            assertTrue(page.contains("hljs.highlightElement"), page);
        });
    }

    @Test
    void pageNotFound404(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                         final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        new MiniSite(builder.createADefault404Page(true).build()).run();
        asserts.assertContains("404.html", "<p>Go back on <a href=\"/\">home</a>.</p>\n");
    }

    @Test
    void breadcrumb(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                    final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        new MiniSite(builder.build()).run();
        asserts.assertContains("page.html", "<nav aria-label=\"breadcrumb\" style=\"padding-left: 0;\">\n" +
                "  <ol class=\"breadcrumb\" style=\"margin-left: 0;background-color: unset;padding-left: 0;\">\n" +
                "    <li class=\"breadcrumb-item\"><a href=\"/\"><svg viewBox=\"0 0 24 24\" style=\"height: 1.4rem;position: relative;top: 1px;vertical-align: top;width: 1.1rem;\"><path d=\"M10 19v-5h4v5c0 .55.45 1 1 1h3c.55 0 1-.45 1-1v-7h1.7c.46 0 .68-.57.33-.87L12.67 3.6c-.38-.34-.96-.34-1.34 0l-8.36 7.53c-.34.3-.13.87.33.87H5v7c0 .55.45 1 1 1h3c.55 0 1-.45 1-1z\" fill=\"currentColor\"></path></svg></a></li>\n" +
                "    <li class=\"breadcrumb-item\"><a href=\"foo.html\">Foo</a></li>\n" +
                "    <li class=\"breadcrumb-item active\" aria-current=\"page\">Page 1</li>\n" +
                "  </ol>\n" +
                "</nav>");
    }

    @Test
    void footerNav(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                   final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        new MiniSite(builder.build()).run();
        asserts.assertContains("page.html", " <nav class=\"page-footer-nav\" aria-label=\"Docs pages\">\n" +
                "    <a href=\"introduction.html\" class=\"page-footer-nav-link-prev\">\n" +
                "    <div>Previous</div>\n" +
                "    <div>Introduction</div>\n" +
                "</a>\n" +
                "    <a href=\"getting-started.html\" class=\"page-footer-nav-link-next\">\n" +
                "    <div>Next</div>\n" +
                "    <div>Getting Started</div>\n" +
                "</a>\n" +
                "</nav>");
    }

    @Test
    void customTemplates(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                         final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        new MiniSite(builder
                .templateExtensionPoints(Map.of("socialLinks", "this is the replacement"))
                .build())
                .run();
        asserts.assertNotContains("page.html", "{{{socialLinks}}}");
        asserts.assertContains("page.html", "this is the replacement");
    }

    @Test
    void customHeadBasePlaceholder(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                                   final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        new MiniSite(builder
                .customHead("<link rel=\"stylesheet\" href=\"{{base}}/css/site.css\">")
                .customMenu("<li id=\"custom-menu\"><a href=\"{{base}}/custom\">Custom</a></li>")
                .customScripts("<script src=\"{{base}}/js/site.js\"></script>")
                .build())
                .run();
        asserts.assertContains("page.html", "<link rel=\"stylesheet\" href=\"/css/site.css\">");
        asserts.assertContains("page.html", "<a href=\"/custom\">Custom</a>");
        asserts.assertContains("page.html", "<script src=\"/js/site.js\"></script>");
        asserts.assertNotContains("page.html", "{{base}}");
    }

    @Test
    void leftMenuPositions(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                           final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        final List<String> warnings = warnings(builder);
        new MiniSite(builder.attributes(MENU).build()).run();
        assertEquals(List.of(), warnings);
        assertLeftMenuPositions(asserts);
    }

    @Test
    void leftMenuPositionsYupiikAsciidoc(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                                        final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        new MiniSite(builder
                .source(Paths.get("target/test-classes/sites/MiniSiteTest/leftMenuPositions")) // same site, the other engine
                .attributes(MENU)
                .asciidoc(new YupiikAsciidoc())
                .build())
                .run();
        assertLeftMenuPositions(asserts);
    }

    private static final Map<String, Object> MENU = Map.ofEntries(
                Map.entry("minisite-menu-1", "/parent.html"),
                Map.entry("minisite-menu-1-icon", "fa-book"),
                Map.entry("minisite-menu-1-1", "/child-a.html"),
                Map.entry("minisite-menu-1-1-title", "First"),
                Map.entry("minisite-menu-1-2", "/child-b"),
                Map.entry("minisite-menu-1-2-icon", "file"),
                Map.entry("minisite-menu-2", "/other.html"),
                Map.entry("minisite-menu-3", "/api/index.html?v=2"),
                Map.entry("minisite-menu-3-title", "API"),
                Map.entry("minisite-menu-3-icon", "code"),
                Map.entry("minisite-menu-4", "https://github.com/yupiik/tools-maven-plugin"),
                Map.entry("minisite-menu-4-title", "GitHub"));

    private void assertLeftMenuPositions(final MiniSiteConfigurationBuilderProvider.Asserts asserts) {
        final String menu = "    <ul>\n" +
                "<li>\n" +
                "    <a title=\"Parent\" href=\"/parent.html\">\n" +
                "        <i class=\"fas fa-book\"></i>Parent\n" +
                "    </a>\n" +
                "<ul>\n" +
                "<li>\n" +
                "    <a title=\"First child\" href=\"/child-a.html\">\n" +
                "        <i class=\"\"></i>First\n" +
                "    </a>\n" +
                "</li><li>\n" +
                "    <a title=\"Second child\" href=\"/child-b.html\">\n" +
                "        <i class=\"fas fa-file\"></i>Second child\n" +
                "    </a>\n" +
                "</li></ul>\n" +
                "</li><li>\n" +
                "    <a title=\"Other\" href=\"/other.html\">\n" +
                "        <i class=\"fas fa-robot\"></i>Other\n" +
                "    </a>\n" +
                "</li><li>\n" +
                "    <a title=\"API\" href=\"/api/index.html?v=2\">\n" +
                "        <i class=\"fas fa-code\"></i>API\n" +
                "    </a>\n" +
                "</li><li>\n" +
                "    <a title=\"GitHub\" href=\"https://github.com/yupiik/tools-maven-plugin\">\n" +
                "        <i class=\"\"></i>GitHub\n" +
                "    </a>\n" +
                "</li>\n" +
                "    </ul>\n";
        asserts.assertContains("other.html", menu);
        asserts.assertContains("child-a.html", menu);
        asserts.assertThat(files -> { // the index tiles still come from minisite-index
            final String index = files.get("index.html");
            assertTrue(index.contains("Parent"), index);
            assertTrue(index.contains("Other"), index);
            assertFalse(index.contains("First child"), index);
            assertFalse(index.contains("Second child"), index);
        });
    }

    @Test
    void leftMenuPrefix(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                        final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        // the attributes of the page headers, with the prefix written with and without its trailing slash; the configuration
        // attributes win over them attribute by attribute, as with asciidoctor: the first title and the second entry come from it,
        // the soft set ones (value@) are overridden by the page (the first entry) or read without the @ (its icon)
        new MiniSite(builder
                .attributes(Map.of(
                        "minisite-menu-1", "/elsewhere.html@", "minisite-menu-1-title", "Global", "minisite-menu-1-icon", "book@",
                        "minisite-menu-2", "https://yupiik.io", "minisite-menu-2-title", "Yupiik"))
                .build())
                .run();
        asserts.assertContains("docs/child.html", "<a title=\"Yupiik\" href=\"https://yupiik.io\">");
        asserts.assertNotContains("docs/child.html", "elsewhere");
        final String menu = "<li>\n" +
                "    <a title=\"Parent\" href=\"/docs/parent.html\">\n" +
                "        <i class=\"fas fa-book\"></i>Global\n" +
                "    </a>\n" +
                "<ul>\n" +
                "<li>\n" +
                "    <a title=\"Child\" href=\"/docs/child.html\">\n" +
                "        <i class=\"\"></i>Child\n" +
                "    </a>\n" +
                "</li></ul>\n" +
                "</li>";
        asserts.assertContains("docs/child.html", menu);
        asserts.assertContains("docs/parent.html", menu);
    }

    @Test
    void leftMenuLegacy(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                        final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        final List<String> warnings = warnings(builder);
        new MiniSite(builder.build()).run();
        assertEquals(List.of("The left menu built from the minisite-index attributes is deprecated, " +
                "define it with minisite-menu-<position> attributes in the configuration or in the page headers; " +
                "2 page(s) have none, such as /first.html, /second.html"), warnings);
        asserts.assertContains("first.html", "    <ul>\n" +
                "<li>\n" +
                "    <a title=\"First\" href=\"/first.html\">\n" +
                "        <i class=\"fas fa-download\"></i>First\n" +
                "    </a>\n" +
                "</li><li>\n" +
                "    <a title=\"Second\" href=\"/second.html\">\n" +
                "        <i class=\"fas fa-download\"></i>Second\n" +
                "    </a>\n" +
                "</li>\n" +
                "    </ul>\n");
    }

    @Test
    void leftMenuPrefixInConfiguration(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                                       final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        // a prefix alone, without minisite-menu-1, defines no menu
        new MiniSite(builder
                .source(Paths.get("target/test-classes/sites/MiniSiteTest/leftMenuLegacy"))
                .attributes(Map.of("minisite-left-menu-prefix", "/docs"))
                .build())
                .run();
        asserts.assertContains("first.html", "    <a title=\"Second\" href=\"/second.html\">\n");
    }

    @Test
    void leftMenuGaps(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                      final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        new MiniSite(builder.build()).run();
        // minisite-menu-3 comes after a gap, minisite-menu-1-1-title has no minisite-menu-1-1 and minisite-menu-x is not a position
        asserts.assertContains("page.html", "    <ul>\n" +
                "<li>\n" +
                "    <a title=\"Page\" href=\"/page.html\">\n" +
                "        <i class=\"fas fa-download\"></i>Page\n" +
                "    </a>\n" +
                "</li>\n" +
                "    </ul>\n");
        asserts.assertNotContains("page.html", "After a gap");
        asserts.assertNotContains("page.html", "Orphan");
    }

    @Test
    void leftMenuCustomItemTemplate(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                                    final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        // a custom left-menu template reading the entries as a model instead of the rendered listItems
        new MiniSite(builder.build()).run();
        asserts.assertContains("parent.html", "<nav><a href=\"/parent.html\">Parent</a><a class=\"sub\" href=\"/child.html\">Child</a></nav>");
    }

    @Test
    void leftMenuOldItemTemplate(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                                 final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        // the left-menu-item template of the previous releases has no {{{children}}}, the nested entries are not rendered
        final List<String> warnings = warnings(builder);
        new MiniSite(builder.build()).run();
        assertEquals(List.of("The left-menu-item template writes neither {{{children}}} nor the items, the nested menu entries are not rendered"), warnings);
        asserts.assertContains("parent.html", "<li>\n" +
                "    <a title=\"Parent\" href=\"/parent.html\">\n" +
                "        <i class=\"fas fa-download\"></i>Parent\n" +
                "    </a>\n" +
                "</li>");
        asserts.assertNotContains("parent.html", "child.html");
    }

    private List<String> warnings(final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        final AsciidoctorConfiguration delegate = builder.build().getAsciidoctorConfiguration();
        final List<String> warnings = new ArrayList<>();
        builder.asciidoctorConfiguration(new AsciidoctorConfiguration() {
            @Override
            public Path gems() {
                return delegate.gems();
            }

            @Override
            public String customGems() {
                return delegate.customGems();
            }

            @Override
            public List<String> requires() {
                return delegate.requires();
            }

            @Override
            public Consumer<String> info() {
                return delegate.info();
            }

            @Override
            public Consumer<String> debug() {
                return delegate.debug();
            }

            @Override
            public Consumer<String> warn() {
                return warnings::add;
            }

            @Override
            public Consumer<String> error() {
                return delegate.error();
            }
        });
        return warnings;
    }

    @Test
    void notIndexedPages(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
                         final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        // ignore blog pages and keep index.html only
        new MiniSite(builder
                .source(Paths.get("target/test-classes/sites/MiniSiteTest/blog")) // reuse blog for this test
                .notIndexedPages(List.of("regex:blog\\p{Digit}.html"))
                .build()).run();
        asserts.assertThat(files -> {
            final String searchJson = files.get("search.json");
            assertTrue(searchJson.contains("\"v\":1"), "bad prefix: " + searchJson);
            assertTrue(searchJson.contains("\"u\":\"/index.html\""), "should contain index.html URL");
            assertTrue(searchJson.contains("\"t\":\"Test Site\""), "should contain Test Site title");
            assertTrue(searchJson.contains("\"i\":"), "should contain inverted index");
            // only one document should be indexed
            assertEquals(1, countOccurrences(searchJson, "\"u\":"), "should have exactly 1 doc");
        });
    }

    private static int countOccurrences(final String text, final String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) >= 0) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    @Test
    void blog(final MiniSiteConfigurationBuilderProvider.Asserts asserts) {
        asserts.assertThat(files -> assertEquals(
                List.of(
                        "404.html", "blog/author/index.html",
                        "blog/author/romain-manni-bucau/index.html", "blog/author/romain-manni-bucau/page-1.html",
                        "blog/category/index.html",
                        "blog/category/others/index.html", "blog/category/others/page-1.html",
                        "blog/category/simple/index.html", "blog/category/simple/page-1.html",
                        "blog/index.html", "blog/page-1.html", "blog/page-2.html",
                        "blog1.html", "blog2.html", "blog3.html",
                        "css/syntax-highlighting.css", "css/theme.css", "images/logo.svg", "index.html", "js/minisite.js", "search.json", "sitemap.xml"),
                files.keySet().stream().sorted().collect(toList())));
        asserts.assertContains("blog/page-1.html", "" +
                "<div class=\"card shadow-sm\">\n" +
                "  <div class=\"card-body\">\n" +
                "      <h5 class=\"card-title mb-3\">\n" +
                "          <span class=\"theme-icon-holder card-icon-holder mr-2 category-others\">\n" +
                "              <i class=\"fas fa-download\"></i>\n" +
                "          </span>\n" +
                "          <span class=\"card-title-text\">My First Post</span>\n" +
                "          <span class=\"card-subtitle-text\">, 2021-02-15</span>\n" +
                "      </h5>\n" +
                "      <div class=\"card-text\">\n" +
                "<div class=\"paragraph\">\n" +
                "<p>First post.</p>\n" +
                "</div>\n" +
                "          </div>\n" +
                "      <a class=\"card-link-mask\" href=\"/blog1.html\"></a>\n" +
                "  </div>\n" +
                "</div>\n" +
                "<div class=\"card shadow-sm\">\n" +
                "  <div class=\"card-body\">\n" +
                "      <h5 class=\"card-title mb-3\">\n" +
                "          <span class=\"theme-icon-holder card-icon-holder mr-2 category-default\">\n" +
                "              <i class=\"fas fa-download\"></i>\n" +
                "          </span>\n" +
                "          <span class=\"card-title-text\">My Second Post</span>\n" +
                "          <span class=\"card-subtitle-text\">, 2021-02-16</span>\n" +
                "      </h5>\n" +
                "      <div class=\"card-text\">\n" +
                "          </div>\n" +
                "      <a class=\"card-link-mask\" href=\"/blog3.html\"></a>\n" +
                "  </div>\n" +
                "</div>\n" +
                "<div class=\"ulist blog-links blog-links-page\">\n" +
                "<ul>\n" +
                "<li>\n" +
                "<p><a href=\"/blog/index.html\" class=\"blog-link-all\">All posts</a></p>\n" +
                "</li>\n" +
                "<li>\n" +
                "<p><a href=\"/blog/page-2.html\" class=\"blog-link-next\">Next</a></p>\n" +
                "</li>\n" +
                "</ul>\n" +
                "</div>");
        asserts.assertContains("blog/page-2.html", "" +
                "                <div class=\"card shadow-sm\">\n" +
                "  <div class=\"card-body\">\n" +
                "      <h5 class=\"card-title mb-3\">\n" +
                "          <span class=\"theme-icon-holder card-icon-holder mr-2 category-others\">\n" +
                "              <i class=\"fas fa-download\"></i>\n" +
                "          </span>\n" +
                "          <span class=\"card-title-text\">My Third Post</span>\n" +
                "          <span class=\"card-subtitle-text\">by Romain Manni-Bucau, 2021-02-16</span>\n" +
                "      </h5>\n" +
                "      <div class=\"card-text\">\n" +
                "<div class=\"paragraph\">\n" +
                "<p>Second post.</p>\n" +
                "</div>\n" +
                "          </div>\n" +
                "      <a class=\"card-link-mask\" href=\"/blog2.html\"></a>\n" +
                "  </div>\n" +
                "</div>\n" +
                "<div class=\"ulist blog-links blog-links-page\">\n" +
                "<ul>\n" +
                "<li>\n" +
                "<p><a href=\"/blog/page-1.html\" class=\"blog-link-previous\">Previous</a></p>\n" +
                "</li>\n" +
                "<li>\n" +
                "<p><a href=\"/blog/index.html\" class=\"blog-link-all\">All posts</a></p>\n" +
                "</li>\n" +
                "</ul>\n" +
                "</div>");
        // todo: assert home, authors, category etc page contents
    }

    @Test
    void llmsTxt(final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder, final MiniSiteConfigurationBuilderProvider.Asserts asserts) {
        new MiniSite(builder.createLlmsTxt(true).siteBase("https://foo.test.yupiik.com").build()).run();
        asserts.assertThat(files -> assertEquals(
                List.of(
                        "404.html", "blog/category/index.html", "blog/category/others/index.html", "blog/category/others/page-1.html",
                        "blog/index.html", "blog/page-1.html", "blog/post.html", "css/syntax-highlighting.css", "css/theme.css", "images/logo.svg", "index.html",
                        "js/minisite.js", "llms.txt", "page.html", "search.json", "sitemap.xml"),
                files.keySet().stream().sorted().collect(toList())));
        asserts.assertContains("llms.txt", "" +
                "# Description: The test rendering.\n" +
                "# Canonical: https://foo.test.yupiik.com\n" +
                "\n" +
                "/blog/post.html\n" +
                "/page.html");
    }

    @Test
    void rss(final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder, final MiniSiteConfigurationBuilderProvider.Asserts asserts) {
        new MiniSite(builder.rssFeedFile("rss.xml").siteBase("https://foo.test.yupiik.com").build()).run();
        asserts.assertThat(files -> assertEquals(
                "" +
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                        "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n" +
                        "  <channel>\n" +
                        "   <atom:link href=\"https://foo.test.yupiik.com/rss.xml\" rel=\"self\" type=\"application/rss+xml\" />\n" +
                        "   <title>Test Site</title>\n" +
                        "   <description>Index sub title</description>\n" +
                        "   <link>https://foo.test.yupiik.com/rss.xml</link>\n" +
                        "   <lastBuildDate>Tue, 16 Feb 2021 16:00:00 GMT</lastBuildDate>\n" +
                        "   <pubDate>Tue, 16 Feb 2021 16:00:00 GMT</pubDate>\n" +
                        "   <ttl>1800</ttl>\n" +
                        "   <item>\n" +
                        "    <title>My Third Post</title>\n" +
                        "    <description>Second post.</description>\n" +
                        "    <link>https://foo.test.yupiik.com/blog2.html</link>\n" +
                        "    <guid isPermaLink=\"false\">/blog2.html</guid>\n" +
                        "    <pubDate>Tue, 16 Feb 2021 16:00:00 GMT</pubDate>\n" +
                        "   </item>\n" +
                        "   <item>\n" +
                        "    <title>My First Post</title>\n" +
                        "    <description>First post.</description>\n" +
                        "    <link>https://foo.test.yupiik.com/blog1.html</link>\n" +
                        "    <guid isPermaLink=\"false\">/blog1.html</guid>\n" +
                        "    <pubDate>Mon, 15 Feb 2021 00:00:00 GMT</pubDate>\n" +
                        "   </item>\n" +
                        "  </channel>\n" +
                        "</rss>\n" +
                        "",
                files.get("rss.xml")));
    }

    @Test
    void blogReadingTime(final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder, final MiniSiteConfigurationBuilderProvider.Asserts asserts) {
        new MiniSite(builder
                .injectBlogMeta(true)
                .build())
                .run();
        asserts.assertContains("blog1.html", "" +
                "<div class=\"paragraph metadata\">\n" +
                "<p><span class=\"metadata-authors\"><a href=\"/blog/author/some-body/page-1.html\">Some Body</a></span>, <span class=\"metadata-published\">2021-02-15</span>, <span class=\"metadata-readingtime\">4 sec read</span></p>\n" +
                "</div>\n");
    }

    @Test
    void blogPublicationDate(final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder, final MiniSiteConfigurationBuilderProvider.Asserts asserts) {
        new MiniSite(builder
                .blogPublicationDate("2021-02-14")
                .build())
                .run();
        asserts.assertThat(m -> assertEquals(0, m.keySet().stream().filter(it -> it.startsWith("blog")).count(), m.keySet()::toString));
    }
}
