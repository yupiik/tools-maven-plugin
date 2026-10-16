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
package io.yupiik.asciidoc.renderer.html;

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShowerRendererTest {
    @Test
    void iconFontHandler() { // the slides configuration reaches the HTML renderer
        final var renderer = new ShowerRenderer(new ShowerRenderer.Configuration()
                .setFontHandler(new FontAwesome6Handler())
                .setAttributes(Map.of("noheader", "true")));
        renderer.visit(new Parser().parse("== Slide\n\nicon:lock[] text", new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))));
        assertEquals("""
                 <section class="sect1" id="_slide">
                  <h2>Slide</h2>
                 <div class="paragraph">
                <span class="icon"><i class="fa-solid fa-lock"></i></span> text </div>
                 </section>
                """, renderer.result());
    }
}
