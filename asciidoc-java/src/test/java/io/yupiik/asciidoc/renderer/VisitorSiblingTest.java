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

import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.OpenBlock;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Table;
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

class VisitorSiblingTest { // the options come from the parser, so a change of the parser shows here
    private final VisitorSibling sibling = new VisitorSibling();

    @Test
    void idAndStyleShorthands() {
        final var note = ((OpenBlock) parse("""
                [NOTE#note.important%collapsible]
                ====
                Text.
                ====
                """)).options();
        assertEquals("note", sibling.id(note));
        assertEquals("NOTE", sibling.styleName(note));
        assertTrue(sibling.hasOption(note, "collapsible"));

        final var anchored = ((Code) parse("""
                [[snippet]]
                ----
                code
                ----
                """)).options();
        assertEquals("snippet", sibling.id(anchored)); // the parser keeps [[snippet]] as the style [snippet]
        assertEquals("", sibling.styleName(anchored));

        final var details = ((OpenBlock) parse("""
                [%collapsible%open]
                .Details
                ====
                Text.
                ====
                """)).options();
        assertNull(sibling.id(details));
        assertTrue(sibling.hasOption(details, "collapsible")); // the parser keeps one collapsible%open-option key
        assertTrue(sibling.hasOption(details, "open"));

        final var table = ((Table) parse("""
                [cols="1,1",options="header,footer"]
                |===
                |a |b
                |===
                """)).options();
        assertTrue(sibling.hasOption(table, "footer"));
        assertFalse(sibling.hasOption(table, "autowidth"));
    }

    @Test
    void kbdKeys() {
        final var macros = ((Paragraph) parse("Press kbd:[Ctrl+Shift+T], kbd:[Ctrl++] or kbd:[Ctrl,Shift].")).children().stream()
                .filter(Macro.class::isInstance)
                .map(Macro.class::cast)
                .toList();
        assertEquals(List.of(List.of("Ctrl", "Shift", "T"), List.of("Ctrl", "+"), List.of("Ctrl", "Shift")),
                macros.stream().map(sibling::kbdKeys).toList());
    }

    @Test
    void attributeReferences() { // as asciidoctor substitutes them
        final ConditionalBlock.Context context = Map.of("name", "value", "other-name", "other")::get;
        assertEquals("value and other", sibling.substitute("{name} and {other-name}", context));
        assertEquals("{name} and {name} and value", sibling.substitute("\\{name} and {name\\} and {name}", context));
        assertEquals("{missing} stays", sibling.substitute("{missing} stays", context));
        final var plain = "no reference {here or { name}";
        assertSame(plain, sibling.substitute(plain, context)); // nothing to replace, so nothing is copied
    }

    @Test
    void titleText() {
        final var title = ((Section) parse("== Install   the\t [CLI]\n\nText.\n")).title();
        assertEquals("Install the [CLI]", sibling.titleText(title, key -> null));
    }

    @Test
    void crossReferences() { // as asciidoctor reads the xref macro
        final var extensions = sibling.asciidocExtensions(key -> null);
        assertEquals(List.of(".adoc", ".asciidoc"), extensions);
        assertEquals(new VisitorSibling.CrossReference("install", null, null, ""), sibling.crossReference("#install", extensions));
        assertEquals(new VisitorSibling.CrossReference("install", null, null, ""), sibling.crossReference("install", extensions));
        assertEquals(new VisitorSibling.CrossReference(null, "guide.adoc", "guide", "part"), sibling.crossReference("guide.adoc#part", extensions));
        assertEquals(new VisitorSibling.CrossReference(null, "cli-tooling", "cli-tooling", "dev"), sibling.crossReference("cli-tooling#dev", extensions));
        assertEquals(new VisitorSibling.CrossReference(null, "notes.txt", null, ""), sibling.crossReference("notes.txt", extensions));
        assertEquals("../guide/index.md", sibling.documentPath("guide", Map.of("relfileprefix", "../", "relfilesuffix", "/index.md")::get, ".html"));
        assertEquals("guide.html", sibling.documentPath("guide", key -> null, ".html"));
    }

    private Element parse(final String asciidoc) {
        return new Parser().parse(asciidoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))).body().children().get(0);
    }
}
