/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.asciidoc.renderer.html;

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleHtmlRendererTest {
    @Test
    void renderHtml() {
        assertRendering("""
                        = Main title
                                        
                        Some text.
                                        
                        == Second part
                                        
                        This is a snippet:
                                        
                        [source,java]
                        ----
                        public record Foo() {}
                        ----
                        """,
                """
                        <!DOCTYPE html>
                        <html lang="en">
                        <head>
                         <meta charset="UTF-8">
                         <meta http-equiv="X-UA-Compatible" content="IE=edge">
                        </head>
                        <body>
                         <div id="content">
                         <h1>Main title</h1>
                         <div id="preamble">
                         <div class="sectionbody">
                         <p>
                         <div class="paragraph">
                         <p>
                        Some text.
                         </p>
                         </div>
                         </p>
                         </div>
                         </div>
                         <div class="sect1">
                          <h2>Second part</h2>
                         <div class="sectionbody">
                         <div class="paragraph">
                         <p>
                        This is a snippet:
                         </p>
                         </div>
                         <div class="listingblock">
                         <div class="content">
                         <pre class="highlightjs highlight"><code class="language-java hljs" data-lang="java">public record Foo() {}</code></pre>
                         </div>
                         </div>
                         </div>
                         </div>
                         </div>
                        </body>
                        </html>
                        """);
    }

    @Test
    void ol() {
        assertRenderingContent("""
                . first
                . second
                . third""", """
                 <ol>
                  <li>
                 <span>
                first
                 </span>
                  </li>
                  <li>
                 <span>
                second
                 </span>
                  </li>
                  <li>
                 <span>
                third
                 </span>
                  </li>
                 </ol>
                """);
    }

    @Test
    void ul() {
        assertRenderingContent("""
                * first
                * second
                * third""", """
                 <ul>
                  <li>
                 <span>
                first
                 </span>
                  </li>
                  <li>
                 <span>
                second
                 </span>
                  </li>
                  <li>
                 <span>
                third
                 </span>
                  </li>
                 </ul>
                """);
    }

    @Test
    void dl() {
        assertRenderingContent("""
                first:: one
                second:: two""", """
                 <dl>
                  <dt>first</dt>
                  <dd>
                 <span>
                one
                 </span>
                </dd>
                  <dt>second</dt>
                  <dd>
                 <span>
                two
                 </span>
                </dd>
                 </dl>
                """);
    }

    @Test
    void admonition() {
        assertRenderingContent("NOTE: this is an important note.", """
                 <div class="admonitionblock note">
                  <table>
                    <tbody>
                     <tr>
                      <td class="icon">
                     <div class="title">NOTE</div>
                       </td>
                      <td class="content">
                 <span>
                this is an important note.
                 </span>
                    </td>
                   </tr>
                      </tbody>
                  </table>
                 </div>
                """);
    }

    @Test
    void table() {
        assertRenderingContent("""
                [cols="1,1"]
                |===
                |Cell in column 1, header row |Cell in column 2, header row\s
                                
                |Cell in column 1, row 2
                |Cell in column 2, row 2
                                
                |Cell in column 1, row 3
                |Cell in column 2, row 3
                                
                |Cell in column 1, row 4
                |Cell in column 2, row 4
                |===""", """
                 <table class="tableblock frame-all grid-all stretch">
                  <colgroup>
                   <col width="50%">
                   <col width="50%">
                  </colgroup>
                  <thead>
                   <tr>
                    <th>
                 <span>
                Cell in column 1, header row
                 </span>
                    </th>
                    <th>
                 <span>
                Cell in column 2, header row
                 </span>
                    </th>
                   </tr>
                  </thead>
                  <tbody>
                   <tr>
                    <td>
                 <span>
                Cell in column 1, row 2
                 </span>
                    </td>
                    <td>
                 <span>
                Cell in column 2, row 2
                 </span>
                    </td>
                   </tr>
                   <tr>
                    <td>
                 <span>
                Cell in column 1, row 3
                 </span>
                    </td>
                    <td>
                 <span>
                Cell in column 2, row 3
                 </span>
                    </td>
                   </tr>
                   <tr>
                    <td>
                 <span>
                Cell in column 1, row 4
                 </span>
                    </td>
                    <td>
                 <span>
                Cell in column 2, row 4
                 </span>
                    </td>
                   </tr>
                  </tbody>
                 </table>
                """);
    }

    @Test
    void quote() {
        assertRenderingContent("""
                > > What's new?
                >
                > I've got Markdown in my AsciiDoc!
                >
                > > Like what?
                >
                > * Blockquotes
                > * Headings
                > * Fenced code blocks
                >
                > > Is there more?
                >
                > Yep. AsciiDoc and Markdown share a lot of common syntax already.""", """
                 <div>
                  <blockquote>
                 <div>
                  <blockquote>
                 <span>
                What's new?
                 </span>
                  </blockquote>
                 </div> <span>
                I've got Markdown in my AsciiDoc!
                 </span>
                 <div>
                  <blockquote>
                 <span>
                Like what?
                 </span>
                  </blockquote>
                 </div> <ul>
                  <li>
                 <span>
                Blockquotes
                 </span>
                  </li>
                  <li>
                 <span>
                Headings
                 </span>
                  </li>
                  <li>
                 <span>
                Fenced code blocks
                 </span>
                  </li>
                 </ul>
                 <div>
                  <blockquote>
                 <span>
                Is there more?
                 </span>
                  </blockquote>
                 </div> <span>
                Yep. AsciiDoc and Markdown share a lot of common syntax already.
                 </span>
                  </blockquote>
                 </div>""");
    }

    @Test
    void passthrough() {
        assertRenderingContent("""
                ++++
                <div id="test">Content</div>
                ++++""", """
                                
                <div id="test">Content</div>
                """);
    }

    @Test
    void xref() {
        assertRenderingContent("xref:foo.adoc[Bar]", " <a href=\"foo.html\">Bar</a>\n");
    }

    @Test
    void embeddedImage(@TempDir final Path work) throws IOException {
        final var base64 = "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";
        Files.createDirectories(work);
        Files.write(work.resolve("img.png"), Base64.getDecoder().decode(base64));
        final var doc = new Parser().parseBody("""
                = Test
                                
                image::img.png[logo]
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new SimpleHtmlRenderer(new SimpleHtmlRenderer.Configuration()
                .setAssetsBase(work)
                .setAttributes(Map.of("noheader", "true", "data-uri", "")));
        renderer.visitBody(doc);
        assertEquals("""
                 <div class="sect0">
                  <h1>Test</h1>
                 <div class="sectionbody">
                 <div class="imageblock">
                 <div class="content">
                 <img src="data:image/png;base64,$base64" alt="logo">
                 </div>
                 </div>
                 </div>
                 </div>
                """.replace("$base64", base64), renderer.result());
    }

    @Test
    void carbonNowImage() {
        final var doc = new Parser().parseBody("""
                = Test
                                
                [source,carbonNowBaseUrl="auto",alt="image"]
                ----
                public record UserId(String name) {}
                ----
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new SimpleHtmlRenderer(new SimpleHtmlRenderer.Configuration()
                .setAttributes(Map.of("noheader", "true", "data-uri", "false"/*true would mean we depend on the http url at test time, we don't want that*/)));
        renderer.visitBody(doc);
        assertEquals("""
                 <div class="sect0">
                  <h1>Test</h1>
                 <div class="sectionbody">
                                
                  <iframe
                    src="https://carbon.now.sh/embed?bg=rgba%28171%2C184%2C195%2C100%29&t=vscode&wt=none&l=text%2Fx-java&width=680&ds=true&dsyoff=20px&dsblur=68px&wc=true&wa=true&pv=48px&ph=32px&ln=true&fl=1&fm=Droid+Sans+Mono&fs=13px&lh=133%25&si=false&es=2x&wm=false&code=public+record+UserId%28String+name%29+%7B%7D%0A"
                    style="width: 1024px; height: 473px; border:0; transform: scale(1); overflow:hidden;"
                    sandbox="allow-scripts allow-same-origin">
                  </iframe>
                 </div>
                 </div>
                """, renderer.result());
    }

    private void assertRendering(final String adoc, final String html) {
        final var doc = new Parser().parse(adoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new SimpleHtmlRenderer();
        renderer.visit(doc);
        assertEquals(html, renderer.result());
    }

    private void assertRenderingContent(final String adoc, final String html) {
        final var doc = new Parser().parseBody(adoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new SimpleHtmlRenderer(new SimpleHtmlRenderer.Configuration().setAttributes(Map.of("noheader", "true")));
        renderer.visitBody(doc);
        assertEquals(html, renderer.result());
    }
}
