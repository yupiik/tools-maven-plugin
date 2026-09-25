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

import io.yupiik.asciidoc.model.Admonition;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitorSiblingTest { // the options come from the parser, so a change of the parser shows here
    private final VisitorSibling sibling = new VisitorSibling();

    @Test
    void idAndStyleShorthands() {
        // the same note, written four ways: the shorthand in either order, and the long form with the option first
        // or last. All four give a NOTE admonition with the id note and the collapsible option.
        assertNoteShorthand("[NOTE#note.important%collapsible]");
        assertNoteShorthand("[NOTE.important#note%collapsible]");
        assertNoteShorthand("[NOTE%collapsible,id=note,role=important]");
        assertNoteShorthand("[NOTE,id=note,role=important,%collapsible]");

        // a %option written against a key=value attribute is part of that value, not an option: as asciidoctor,
        // the shorthand is read on the style, never on a named attribute
        final var glued = ((Admonition) parse("""
                [NOTE,id=note,role=important%collapsible]
                ====
                Text.
                ====
                """)).options();
        assertEquals("important%collapsible", glued.get("role"));
        assertFalse(sibling.hasOption(glued, "collapsible"));

        final var anchored = ((Code) parse("""
                [[snippet]]
                ----
                code
                ----
                """)).options();
        assertEquals("snippet", sibling.id(anchored)); // #138 made the parser write the anchor into the id option
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
    void macroSource() { // what the HTML renderer writes for a macro it does not know
        assertEquals("tooltip:foo[a hint,role=x]",
                sibling.macroSource(new Macro("tooltip", "foo", Map.of("", "a hint", "role", "x"), true)));
        assertEquals("config_property_copy_button:quarkus.http.port[]",
                sibling.macroSource(new Macro("config_property_copy_button", "quarkus.http.port", Map.of(), true)));
        assertEquals("foo::bar[baz]", sibling.macroSource(new Macro("foo", "bar", Map.of("", "baz"), false)));
        assertEquals("foo::[id=a,role=b]", sibling.macroSource(new Macro("foo", "", Map.of("role", "b", "id", "a"), false)));
        assertEquals("foo:[]", sibling.macroSource(new Macro("foo", null, null, true)));
    }

    @Test
    void unknownMacro() { // the attribute of a renderer, in any case, else the option of its configuration
        final ConditionalBlock.Context none = key -> null;
        assertSame(UnknownMacro.FAIL, sibling.unknownMacro("x-unknownMacro", none, UnknownMacro.FAIL));
        assertSame(UnknownMacro.TEXT, sibling.unknownMacro("x-unknownMacro", none, UnknownMacro.TEXT));
        assertSame(UnknownMacro.IGNORE, sibling.unknownMacro("x-unknownMacro", key -> "x-unknownMacro".equals(key) ? " Ignore " : null, UnknownMacro.FAIL));
        assertSame(UnknownMacro.TEXT, sibling.unknownMacro("x-unknownMacro", key -> "text", UnknownMacro.IGNORE));
        assertEquals("Unknown value 'foo' for the attribute x-unknownMacro, expected fail, ignore or text",
                assertThrows(IllegalArgumentException.class, () -> sibling.unknownMacro("x-unknownMacro", key -> "foo", UnknownMacro.FAIL)).getMessage());
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

    private void assertNoteShorthand(final String attributeLine) {
        final var element = parse(attributeLine + "\n====\nText.\n====\n");
        final var options = element instanceof Admonition admonition ? admonition.options() : ((OpenBlock) element).options();
        // the parser makes the admonition itself when the style is a bare NOTE, else the style still names the level
        final var level = element instanceof Admonition admonition ?
                admonition.level() : sibling.admonitionLevel(sibling.styleName(options));
        assertEquals(Admonition.Level.NOTE, level, attributeLine);
        assertEquals("note", sibling.id(options), attributeLine);
        assertTrue(sibling.hasOption(options, "collapsible"), attributeLine);
    }

    private Element parse(final String asciidoc) {
        return new Parser().parse(asciidoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))).body().children().get(0);
    }
}
