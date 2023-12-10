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

import java.nio.file.Path;
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
                         <span>
                        Some text.
                         </span>
                         <div>
                          <h2>Second part</h2>
                         <span>
                        This is a snippet:
                         </span>
                        <pre data-lang="java" class="language-java">
                          <code>
                        public record Foo() {}
                          </code>
                         </pre>
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
                   <tr>
                    <td class="icon">
                NOTE:
                     </td>
                    <td class="content">
                 <span>
                this is an important note.
                 </span>
                    </td>
                   </tr>
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
                   <col width="1">
                   <col width="1">
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

    private void assertRendering(final String adoc, final String html) {
        final var doc = new Parser().parse(adoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new SimpleHtmlRenderer();
        renderer.visit(doc);
        assertEquals(html, renderer.result());
    }

    private void assertRenderingContent(final String adoc, final String html) {
        final var doc = new Parser().parseBody(adoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new SimpleHtmlRenderer(Map.of("noheader", "true"));
        renderer.visitBody(doc);
        assertEquals(html, renderer.result());
    }
}
