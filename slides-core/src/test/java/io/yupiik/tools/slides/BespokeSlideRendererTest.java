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
package io.yupiik.tools.slides;

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BespokeSlideRendererTest {
    @Test
    void styledSpan() { // the style of a run holding a link or code stays around all its children, as in the other renderers
        final var renderer = new BespokeSlideRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setSkipGlobalContentWrapper(true)
                .setSkipSectionBody(true)
                .setSectionTag("section"));
        renderer.visitBody(new Parser().parse("""
                == Slide

                *see link:https://a.io[y] now*

                * *item `x`*
                """, new Parser.ParserContext(null)).body());
        assertEquals("""
                 <section>
                  <h2>Slide</h2>
                <strong>see <a href="https://a.io">y</a> now</strong> <ul>
                  <li>
                <strong>item <code>x</code></strong>  </li>
                 </ul>
                 </section>
                """, renderer.result());
    }
}
