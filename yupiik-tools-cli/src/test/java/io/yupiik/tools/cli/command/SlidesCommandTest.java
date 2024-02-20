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
package io.yupiik.tools.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.yupiik.tools.cli.launcher.Main.main;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class SlidesCommandTest {
    @Test
    void render(@TempDir final Path temp) throws IOException {
        final var resultDir = temp.resolve("output");
        try {
            main(
                    "slides",
                    "--workdir=" + temp,
                    "--source=src/test/resources/src/main/slides/bespoke.adoc",
                    "--target=" + resultDir);
        } catch (final IllegalArgumentException iae) {
            // log extracted files on error
            try (final var w = Files.walk(temp)) {
                fail(w.map(it -> temp.relativize(it).toString()).sorted().collect(joining("\n")), iae);
            }
        }
        assertEquals("" +
                "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>My Awesome Presentation</title>" +
                "<meta name=\"author\" content=\"Yupiik\">" +
                "<meta name=\"generator\" content=\"Asciidoctor 2.0.16 (Bespoke.js converter)\">" +
                "<link rel=\"shortcut icon\" href=\"img/favicon.ico\" type=\"image/x-icon\">" +
                "<meta name=\"mobile-web-app-capable\" content=\"yes\">" +
                "<link rel=\"stylesheet\" href=\"//cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.min.css\" integrity=\"sha256-l85OmPOjvil/SOvVt3HnSSjzF1TUMyT9eV0c2BzEGzU=\" crossorigin=\"anonymous\" />\n" +
                "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/idea.min.css\" integrity=\"sha512-jxbAYisMjIOokHq0YnYxWqTUfJRe8s1U2F1lp+se3vv0CS8floaFL3Mc3GEpG3HCG2s6lxHb3QvQdmUOT1ZzKw==\" crossorigin=\"anonymous\" />\n" +
                "<link rel=\"stylesheet\" href=\"css/slides.generated.1463448110.css\">\n" +
                "</head><body><article class=\"deck\"><section class=\"title\" data-title=\"\"><h1>My Awesome Presentation</h1><p>Additional content for the title slide.</p><footer><p class=\"author\"><span class=\"personname\"><span class=\"firstname\">Yupiik</span> <span class=\"surname\"></span></span><span class=\"affiliation\"><span class=\"position\">Company</span> <span class=\"organization\">Yupiik</span></span><span class=\"contact\"><span class=\"twitter\">@yupiik</span> <span class=\"url\">yupiik.com</span></span></p></footer></section>\n" +
                "<section><h2>First Topic</h2></section></article>\n" +
                "<script src=\"//cdnjs.cloudflare.com/ajax/libs/bespoke.js/1.1.0/bespoke.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-classes@1.0.0/dist/bespoke-classes.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-bullets@1.1.0/dist/bespoke-bullets.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-fullscreen@1.0.0/dist/bespoke-fullscreen.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-hash@1.1.0/dist/bespoke-hash.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-nav@1.0.2/dist/bespoke-nav.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-overview@1.0.5/dist/bespoke-overview.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-scale@1.0.1/dist/bespoke-scale.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-title@1.0.0/dist/bespoke-title.min.js\"></script>\n" +
                "<script src=\"//unpkg.com/bespoke-progress@1.0.0/dist/bespoke-progress.min.js\"></script>\n" +
                "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/highlight.min.js\" integrity=\"sha512-d00ajEME7cZhepRqSIVsQVGDJBdZlfHyQLNC6tZXYKTG7iwcF8nhlFuppanz8hYgXr8VvlfKh4gLC25ud3c90A==\" crossorigin=\"anonymous\"></script>\n" +
                "<script src=\"//unpkg.com/highlightjs-badge@0.1.9/highlightjs-badge.min.js\"></script>\n" +
                "\n" +
                "<script src=\"js/yupiik.bespoke.extended.js\"></script>\n" +
                "\n" +
                "<script type=\"text/x-mathjax-config\">MathJax.Hub.Config({\n" +
                "  messageStyle: \"none\",\n" +
                "  tex2jax: {\n" +
                "    inlineMath: [[\"\\\\(\", \"\\\\)\"]],\n" +
                "    displayMath: [[\"\\\\[\", \"\\\\]\"]],\n" +
                "    ignoreClass: \"nostem|nolatexmath\"\n" +
                "  },\n" +
                "  asciimath2jax: {\n" +
                "    delimiters: [[\"\\\\$\", \"\\\\$\"]],\n" +
                "    ignoreClass: \"nostem|noasciimath\"\n" +
                "  }\n" +
                "})</script><script src=\"https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.6.0/MathJax.js?config=TeX-MML-AM_HTMLorMML\"></script></body></html>" +
                "", Files.readString(resultDir.resolve("bespoke.html")));
    }
}
