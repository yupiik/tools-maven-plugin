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
                          <div class="details">
                           <span id="revnumber">Some text.</span>
                          </div>
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
