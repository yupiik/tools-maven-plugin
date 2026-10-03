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

import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.VisitorSibling;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TocVisitorTest {
    @Test
    void repeatedTitlesAreNumberedAsOnThePage() {
        final var tocVisitor = new TocVisitor(2, 1);
        tocVisitor.visitBody(new Body(List.of(
                new Section(2, new Text(List.of(), "Same", Map.of()), List.of(), Map.of()),
                new Section(2, new Text(List.of(), "Same", Map.of()), List.of(), Map.of()))));
        assertEquals("""
                 <ul class="sectlevel1">
                 <li><a href="#_same">Same</a>
                 </li>
                 <li><a href="#_same_2">Same</a>
                 </li>
                 </ul>
                """, tocVisitor.result().toString());
    }

    @Test
    void run() {
        final var tocVisitor = new TocVisitor(2, 1);
        tocVisitor.visitBody(new Body(List.of(
                new Section(2, new Text(List.of(), "S1", Map.of()), List.of(), Map.of()),
                new Section(2, new Text(List.of(), "S2", Map.of()), List.of(), Map.of()),
                new Section(2, new Text(List.of(), "S3", Map.of()), List.of(
                        new Section(3, new Text(List.of(), "S31", Map.of()), List.of(), Map.of()),
                        new Section(3, new Text(List.of(), "S32", Map.of()), List.of(), Map.of()),
                        new Section(3, new Text(List.of(), "S33", Map.of()), List.of(
                                new Section(4, new Text(List.of(), "S331", Map.of()), List.of(), Map.of())
                        ), Map.of())
                ), Map.of()),
                new Section(2, new Text(List.of(), "S4", Map.of()), List.of(), Map.of()),
                new Section(2, new Text(List.of(), "S5", Map.of()), List.of(), Map.of())
        )));
        assertEquals("""
                 <ul class="sectlevel1">
                 <li><a href="#_s1">S1</a>
                 </li>
                 <li><a href="#_s2">S2</a>
                 </li>
                 <li><a href="#_s3">S3</a>
                 <ul class="sectlevel2">
                 <li><a href="#_s31">S31</a></li>
                 <li><a href="#_s32">S32</a></li>
                 <li><a href="#_s33">S33</a></li>
                 </ul>
                 </li>
                 <li><a href="#_s4">S4</a>
                 </li>
                 <li><a href="#_s5">S5</a>
                 </li>
                 </ul>
                """, tocVisitor.result().toString());
    }

    @Test
    void linksReadTheIdsOfTheRenderer() { // the same ids as the headings the HTML renderer writes
        final var tocVisitor = new TocVisitor(new VisitorSibling(), key -> switch (key) {
            case "idprefix" -> "";
            case "idseparator" -> "-";
            default -> null;
        }, 2, 1);
        tocVisitor.visitBody(new Parser().parseBody("""
                == Café Société

                === Using `Uni<T>`

                text
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))));
        assertEquals("""
                 <ul class="sectlevel1">
                 <li><a href="#café-société">Caf&eacute; Soci&eacute;t&eacute;</a>
                 <ul class="sectlevel2">
                 <li><a href="#using-unit">Using <code>Uni&lt;T&gt;</code></a></li>
                 </ul>
                 </li>
                 </ul>
                """, tocVisitor.result().toString());
    }

    @Test
    void sectionsOfAConditionalFollowTheAttributes() { // as the page renders them; a conditional is read at body level only
        final var body = new Parser().parseBody("""
                ifdef::foo[]
                == Only with foo
                endif::[]

                ifndef::foo[]
                == Only without foo
                endif::[]

                == Always
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var withFoo = new TocVisitor(new VisitorSibling(), key -> "foo".equals(key) ? "" : null, 2, 1);
        withFoo.visitBody(body);
        assertEquals("""
                 <ul class="sectlevel1">
                 <li><a href="#_only_with_foo">Only with foo</a>
                 </li>
                 <li><a href="#_always">Always</a>
                 </li>
                 </ul>
                """, withFoo.result().toString());
        final var withoutFoo = new TocVisitor(new VisitorSibling(), key -> null, 2, 1);
        withoutFoo.visitBody(body);
        assertEquals("""
                 <ul class="sectlevel1">
                 <li><a href="#_only_without_foo">Only without foo</a>
                 </li>
                 <li><a href="#_always">Always</a>
                 </li>
                 </ul>
                """, withoutFoo.result().toString());
    }
}
