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

import io.yupiik.tools.minisite.test.MiniSiteConfigurationBuilderProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

@MiniSiteConfigurationBuilderProvider
class MiniSiteTest {
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
    void notIndexedPages(final MiniSiteConfigurationBuilderProvider.Asserts asserts,
              final MiniSiteConfiguration.MiniSiteConfigurationBuilder builder) {
        // ignore blog pages and keep index.html only
        new MiniSite(builder
                .source(Paths.get("target/test-classes/sites/MiniSiteTest/blog")) // reuse blog for this test
                .notIndexedPages(List.of("regex:blog\\p{Digit}.html"))
                .build()).run();
        asserts.assertThat(files -> assertEquals("" +
                "[{\"lang\":\"en\",\"text\":\"\",\"title\":\"Test Site\",\"url\":\"/index.html\"}]" +
                "", files.get("search.json")));
    }

    @Test
    void blog(final MiniSiteConfigurationBuilderProvider.Asserts asserts) {
        asserts.assertThat(files -> assertEquals(
                List.of(
                        "blog/author/index.html", "blog/author/romain-manni-bucau/index.html", "blog/author/romain-manni-bucau/page-1.html",
                        "blog/category/index.html",
                        "blog/category/others/index.html", "blog/category/others/page-1.html",
                        "blog/category/simple/index.html", "blog/category/simple/page-1.html",
                        "blog/index.html", "blog/page-1.html", "blog/page-2.html",
                        "blog1.html", "blog2.html", "blog3.html",
                        "css/theme.css", "index.html", "js/minisite.js", "search.json", "sitemap.xml"),
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
