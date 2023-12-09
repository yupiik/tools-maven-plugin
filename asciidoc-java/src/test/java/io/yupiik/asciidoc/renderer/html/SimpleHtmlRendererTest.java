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
import io.yupiik.asciidoc.parser.internal.LocalContextResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleHtmlRendererTest {
    @Test
    void renderHtml() {
        final var doc = new Parser().parse("""
                = Main title
                                
                Some text.
                                
                == Second part
                                
                This is a snippet:
                                
                [source,java]
                ----
                public record Foo() {}
                ----
                """, new Parser.ParserContext(new LocalContextResolver(Path.of("target/missing"))));
        final var renderer = new SimpleHtmlRenderer();
        renderer.visit(doc);
        assertEquals(
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
                        """,
                renderer.result());
    }
}
