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
import io.yupiik.asciidoc.model.CallOut;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Listing;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.OpenBlock;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.PassthroughBlock;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Table;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
        final var macros = ((Paragraph) parse("Press kbd:[Ctrl+Shift+T], kbd:[Ctrl++], kbd:[Ctrl,Shift] or kbd:[Ctrl + ,].")).children().stream()
                .filter(Macro.class::isInstance)
                .map(Macro.class::cast)
                .toList();
        assertEquals(List.of(List.of("Ctrl", "Shift", "T"), List.of("Ctrl", "+"), List.of("Ctrl", "Shift"), List.of("Ctrl", ",")),
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
    void shorthandCrossReferences() { // as asciidoctor reads <<target>>: a # ends a document name, else the target is an id
        final var extensions = List.of(".adoc", ".asciidoc");
        assertEquals(new VisitorSibling.CrossReference("install", null, null, ""), sibling.shorthandCrossReference("#install", extensions));
        assertEquals(new VisitorSibling.CrossReference("install", null, null, ""), sibling.shorthandCrossReference("install", extensions));
        assertEquals(new VisitorSibling.CrossReference("guide.adoc", null, null, ""), sibling.shorthandCrossReference("guide.adoc", extensions));
        assertEquals(new VisitorSibling.CrossReference(null, "guide.adoc", "guide", "part"), sibling.shorthandCrossReference("guide.adoc#part", extensions));
        assertEquals(new VisitorSibling.CrossReference(null, "cli-tooling", "cli-tooling", "dev"), sibling.shorthandCrossReference("cli-tooling#dev", extensions));
        assertEquals(new VisitorSibling.CrossReference(null, "guide.adoc", "guide", ""), sibling.shorthandCrossReference("guide.adoc#", extensions));
        assertEquals(new VisitorSibling.CrossReference(null, "a.adoc", "a", "s1#s2"), sibling.shorthandCrossReference("a.adoc#s1#s2", extensions));
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

    @Test
    void admonitionLevelByName() {
        for (final var level : Admonition.Level.values()) {
            assertSame(level, sibling.admonitionLevel(level.name()), level.name());
        }
        // a style that names no level, as the description blocks of a configuration table or a sidebar, is null and costs no exception
        assertNull(sibling.admonitionLevel(sibling.styleName(((OpenBlock) parse("[.description]\n--\nText.\n--\n")).options())));
        assertNull(sibling.admonitionLevel(sibling.styleName(((OpenBlock) parse("[sidebar]\n--\nText.\n--\n")).options())));
        assertNull(sibling.admonitionLevel("note")); // lower case is no admonition, as in asciidoctor
        assertNull(sibling.admonitionLevel(null));
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

    @Test
    void renderedCode() { // the parts whose conditional directives hold, as one text, with their callouts
        final var one = new CallOut(1, new Text(List.of(), "one", Map.of()));
        final var two = new CallOut(2, new Text(List.of(), "two", Map.of()));
        final var plain = new Code("a\nb\n", Map.of(), false, List.of());
        assertSame(plain, sibling.renderedCode(plain, key -> null));

        final var code = new Code(
                List.of(under(new ConditionalBlock.Ifdef("x"), part("a\n")), under(new ConditionalBlock.Ifndef("x"), part("b\n")), part("c\n")),
                Map.of("language", "text"), false, List.of(List.of(one), List.of(two), List.of()));
        assertEquals("a\nb\nc\n", code.value()); // every branch
        assertEquals(
                new Code("a\nc\n", Map.of("language", "text"), false, List.of(List.of(one), List.of())),
                sibling.renderedCode(code, key -> "x".equals(key) ? "" : null));
        assertEquals(
                new Code("b\nc\n", Map.of("language", "text"), false, List.of(List.of(two), List.of())),
                sibling.renderedCode(code, key -> null));

        // nested directives must all hold; a cell has no trailing line feed; without callout the list stays empty
        final var nested = new Code(
                List.of(under(new ConditionalBlock.Ifdef("x"), under(new ConditionalBlock.Ifdef("y"), part("a\n"))), part("b")),
                Map.of(), true, List.of());
        assertEquals(new Code("b", Map.of(), true, List.of()), sibling.renderedCode(nested, key -> "x".equals(key) ? "" : null));
        assertEquals(new Code("a\nb", Map.of(), true, List.of()), sibling.renderedCode(nested, key -> ""));

        // the entries the body defined before the block come first, a "!name" one hides the renderer's value
        final var defined = new Code(List.of(
                new ConditionalBlock(new ConditionalBlock.Ifndef("x"), List.of(part("a\n")), List.of(), Map.of(), Map.of("x", "")),
                new ConditionalBlock(new ConditionalBlock.Ifdef("y"), List.of(part("b\n")), List.of(), Map.of(), Map.of("x", "")),
                new ConditionalBlock(new ConditionalBlock.Ifdef("z"), List.of(part("c\n")), List.of(), Map.of(), Map.of("!z", ""))),
                Map.of(), false, List.of());
        assertEquals("", sibling.renderedCode(defined, key -> null).value());
        assertEquals("b\n", sibling.renderedCode(defined, key -> "").value());
    }

    @Test
    void renderedListingAndPassthroughBlock() { // the same for the parts of a literal and a passthrough block
        final var plain = new Listing("a\nb", Map.of());
        assertSame(plain, sibling.renderedListing(plain, key -> null));
        final var listing = new Listing(List.of(under(new ConditionalBlock.Ifdef("x"), part("a\n")), part("b")), Map.of());
        assertEquals("a\nb", listing.value());
        assertEquals(new Listing("b", Map.of()), sibling.renderedListing(listing, key -> null));
        assertEquals(new Listing("a\nb", Map.of()), sibling.renderedListing(listing, key -> ""));
        // the last line of a literal block has no line feed, so a kept part before a dropped last part loses its own
        final var tail = new Listing(List.of(part("a\n"), under(new ConditionalBlock.Ifdef("x"), part("b"))), Map.of());
        assertEquals(new Listing("a", Map.of()), sibling.renderedListing(tail, key -> null));

        final var block = new PassthroughBlock(List.of(under(new ConditionalBlock.Ifdef("x"), part("<a/>\n")), part("<b/>")), Map.of());
        assertEquals(new PassthroughBlock("<b/>", Map.of()), sibling.renderedPassthroughBlock(block, key -> null));
        assertEquals(new PassthroughBlock("<a/>\n<b/>", Map.of()), sibling.renderedPassthroughBlock(block, key -> ""));
        final var raw = new PassthroughBlock("<a/>", Map.of());
        assertSame(raw, sibling.renderedPassthroughBlock(raw, key -> null));
    }

    private static Text part(final String text) {
        return new Text(List.of(), text, Map.of());
    }

    private static ConditionalBlock under(final Predicate<ConditionalBlock.Context> condition, final Element... parts) {
        return new ConditionalBlock(condition, List.of(parts), List.of(), Map.of(), Map.of());
    }

    private Element parse(final String asciidoc) {
        return new Parser().parse(asciidoc, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))).body().children().get(0);
    }
}
