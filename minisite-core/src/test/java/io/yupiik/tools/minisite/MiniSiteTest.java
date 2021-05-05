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

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

@MiniSiteConfigurationBuilderProvider
class MiniSiteTest {
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
                "              <i class=\"fas fa-download-alt\"></i>\n" +
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
                "              <i class=\"fas fa-download-alt\"></i>\n" +
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
                "              <i class=\"fas fa-download-alt\"></i>\n" +
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
