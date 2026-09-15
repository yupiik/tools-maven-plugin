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
package io.yupiik.asciidoc.renderer;

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitorStateTest {
    @Test
    void indexesTheRenderedBranches() {
        final var document = new Parser().parse("""
                = Doc
                :toc:
                :toclevels: 1

                See <<_details>> and xref:#_more_details[].

                [#install]
                == Install   the  [CLI]

                === Details

                === More details

                === Unlinked

                ifdef::with-extras[]
                == Extras
                endif::[]

                [discrete]
                === Floating

                == The end
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var state = new VisitorState(new VisitorSibling(), key -> null);
        state.visit(document);
        state.visitBody(document.body());

        assertEquals(List.of(
                        new VisitorState.TocSection(1, "install", "Install the [CLI]"),
                        new VisitorState.TocSection(2, "_details", "Details"),
                        new VisitorState.TocSection(2, "_more_details", "More details"),
                        new VisitorState.TocSection(2, "_unlinked", "Unlinked"),
                        new VisitorState.TocSection(1, "_the_end", "The end")),
                state.tocSections());
        assertEquals("Install the [CLI]", state.sectionTitle("install"));
        assertEquals("Floating", state.sectionTitle("_floating"));
        assertNull(state.sectionTitle("_extras")); // not rendered
        assertTrue(state.isReferenced("_details")); // linked with <<_details>>
        assertTrue(state.isReferenced("_more_details")); // linked with xref:#_more_details[]
        assertFalse(state.isReferenced("_unlinked")); // deeper than toclevels, not linked
        assertTrue(state.isReferenced("install")); // listed in the table of contents
        assertTrue(state.isReferenced("_the_end"));
    }

    @Test
    void readsTheFallbackAttributesBeforeAnyDocument() {
        final var state = new VisitorState(new VisitorSibling(), Map.of("imagesdir", "img")::get);
        assertEquals("img", state.attribute("imagesdir", null));
        assertEquals("default", state.attribute("missing", "default"));
        assertTrue(state.document().header().attributes().isEmpty());

        state.visit(new Parser().parse("= Doc\n:imagesdir: images\n", new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))));
        assertEquals("images", state.attribute("imagesdir", null)); // the document wins
    }

    @Test
    void footnotes() {
        final var state = new VisitorState(new VisitorSibling(), key -> null);
        final var anonymous = state.footnote("", "First.");
        final var reference = state.footnote("note", ""); // a reference before the definition
        assertEquals(0, reference.index());
        final var definition = state.footnote("note", "Named.");
        final var again = state.footnote("note", "");
        final var undefined = state.footnote("later", "");
        assertEquals("1", anonymous.label());
        assertEquals(1, anonymous.index());
        assertNull(anonymous.id());
        assertEquals("note", definition.label());
        assertEquals("note", definition.id());
        assertEquals(2, definition.index());
        assertSame(reference, definition);
        assertSame(definition, again);
        assertEquals(List.of(anonymous, definition), state.footnotes());
        assertEquals("Named.", definition.text());
        assertNull(undefined.text());
        assertEquals(0, undefined.index());
        assertSame(definition, state.footnote("note"));
        assertNull(state.footnote("nothing"));

        state.visit(new Parser().parse("= Other\n", new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))));
        assertTrue(state.footnotes().isEmpty()); // a new document starts without footnotes
        assertEquals("1", state.footnote("", "Other.").label());
    }
}
