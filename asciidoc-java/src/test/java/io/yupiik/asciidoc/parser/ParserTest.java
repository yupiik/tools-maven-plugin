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
package io.yupiik.asciidoc.parser;

import io.yupiik.asciidoc.model.Admonition;
import io.yupiik.asciidoc.model.Anchor;
import io.yupiik.asciidoc.model.Attribute;
import io.yupiik.asciidoc.model.Author;
import io.yupiik.asciidoc.model.CallOut;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.DescriptionList;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.HorizontalRule;
import io.yupiik.asciidoc.model.LineBreak;
import io.yupiik.asciidoc.model.Link;
import io.yupiik.asciidoc.model.Listing;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.OpenBlock;
import io.yupiik.asciidoc.model.OrderedList;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.PassthroughBlock;
import io.yupiik.asciidoc.model.Quote;
import io.yupiik.asciidoc.model.Revision;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Table;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.model.UnOrderedList;
import io.yupiik.asciidoc.parser.internal.Reader;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.yupiik.asciidoc.model.Admonition.Level.NOTE;
import static io.yupiik.asciidoc.model.Admonition.Level.WARNING;
import static io.yupiik.asciidoc.model.Element.ElementType.ATTRIBUTE;
import static io.yupiik.asciidoc.model.Element.ElementType.HORIZONTAL_RULE;
import static io.yupiik.asciidoc.model.Element.ElementType.OPEN_BLOCK;
import static io.yupiik.asciidoc.model.Element.ElementType.PARAGRAPH;
import static io.yupiik.asciidoc.model.Element.ElementType.SECTION;
import static io.yupiik.asciidoc.model.Element.ElementType.TEXT;
import static io.yupiik.asciidoc.model.Text.Style.BOLD;
import static io.yupiik.asciidoc.model.Text.Style.EMPHASIS;
import static io.yupiik.asciidoc.model.Text.Style.ITALIC;
import static io.yupiik.asciidoc.model.Text.Style.MARK;
import static io.yupiik.asciidoc.model.Text.Style.STRIKETHROUGH;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ParserTest {
    @Test
    public void doubleDollarAndQuotingForMacro() {
        final var body = new Parser()
                .parseBody("xref:foo[$$bar$$]\n__link:https://foo.bar[$$dummy$$]__", new Parser.ParserContext(null));
        assertEquals(
                List.of(
                        new Paragraph(
                                List.of(
                                        new Macro("xref", "foo", Map.of("", "bar"), true),
                                        new Link("https://foo.bar", new Text(List.of(), "dummy", Map.of("nowrap", "true", "", "dummy")), Map.of("", "dummy", "nowrap", "true"))
                                ), Map.of())),
                body.children());
    }

    // crd-ref-docs uses this kind of formatting
    @Test
    public void tableWithContinuation() {
        var body = new Parser().parseBody(
                """
                        [cols="20a,50a,15a,15a", options="header"]
                        |===
                        | Field | Description | Default | Validation
                        | *`foo`* string | Foo. |  | MaxLength: 10
                        MinLength: 3
                        Pattern: `^[A-Z]$`
                        
                        | *`bar`* string | Bar. |  | MaxLength: 10
                        MinLength: 3
                        Pattern: `^[A-Z]$`
                        
                        |===
                        """,
                new Parser.ParserContext(null)
        );
        assertEquals(1, body.children().size());
        final var table = assertInstanceOf(Table.class, body.children().get(0));
        assertEquals(3, table.elements().size());
        for (final var it : table.elements()) {
            assertEquals(4, it.size());
        }
    }

    @Test
    void definitionList() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                generate-frisby-skeleton.output (env: `GENERATE_FRISBY_SKELETON_OUTPUT`)::
                Where to generate the skeleton. Default: `hcms-frisby`.
                hcms.database-init.enabled (env: `HCMS_DATABASE_INIT_ENABLED`)::
                Should database be initialized at startup. Default: `true`.""".split("\n"))), null);
        assertEquals(List.of(
                new DescriptionList(Map.of(
                        new Paragraph(List.of(
                                new Text(List.of(), "generate-frisby-skeleton.output (env: ", Map.of()),
                                new Code("GENERATE_FRISBY_SKELETON_OUTPUT", Map.of(), true, List.of()),
                                new Text(List.of(), ")", Map.of())), Map.of()),
                        new Paragraph(List.of(
                                new Text(List.of(), "Where to generate the skeleton. Default: ", Map.of()),
                                new Code("hcms-frisby", Map.of(), true, List.of()),
                                new Text(List.of(), ".", Map.of())), Map.of()),
                        new Paragraph(List.of(
                                new Text(List.of(), "hcms.database-init.enabled (env: ", Map.of()),
                                new Code("HCMS_DATABASE_INIT_ENABLED", Map.of(), true, List.of()),
                                new Text(List.of(), ")", Map.of())), Map.of()),
                        new Paragraph(List.of(
                                new Text(List.of(), "Should database be initialized at startup. Default: ", Map.of()),
                                new Code("true", Map.of(), true, List.of()),
                                new Text(List.of(), ".", Map.of())), Map.of())), Map.of())
        ), body.children());
    }

    @Test
    void listWithInlineCode() {
        final var body = new Parser().parseBody(
                new Reader(List.of(
                        "- `alveolus.name`: name of the alveolus the descriptor comes from,",
                        "- `descriptor.name`: name of the descriptor,")), null);
        assertEquals(List.of(
                new UnOrderedList(List.of(
                        new Paragraph(List.of(
                                new Code("alveolus.name", Map.of(), true, List.of()),
                                new Text(List.of(), ": name of the alveolus the descriptor comes from,", Map.of())),
                                Map.of()),
                        new Paragraph(List.of(
                                new Code("descriptor.name", Map.of(), true, List.of()),
                                new Text(List.of(), ": name of the descriptor,", Map.of())),
                                Map.of())), Map.of())
        ), body.children());
    }

    @Test
    void xrefInParenthesisInList() {
        final var body = new Parser().parseBody(
                new Reader(List.of("* *foo-bar-dummy* (xref:other.adoc[other]): some description.")), null);
        assertEquals(List.of(
                new UnOrderedList(List.of(
                        new Paragraph(List.of(
                                new Text(List.of(BOLD), "foo-bar-dummy", Map.of()),
                                new Text(List.of(), " (", Map.of()),
                                new Macro("xref", "other.adoc", Map.of("", "other"), true),
                                new Text(List.of(), "): some description.", Map.of())), Map.of())), Map.of())
        ), body.children());
    }

    @Test
    void parseHeader() {
        final var header = new Parser().parseHeader(new Reader(List.of("= Title", ":attr-1: v1", ":attr-2: v2", "", "content")));
        assertEquals("Title", header.title());
        assertEquals(Map.of("attr-1", "v1", "attr-2", "v2", "authorcount", "0"), header.attributes());
    }

    @Test
    void parseHeaderWithoutTitle() {
        final var header = new Parser().parseHeader(new Reader(List.of(":attr-1: v1", ":attr-2: v2", "", "content")));
        assertEquals("", header.title());
        assertEquals(Map.of("attr-1", "v1", "attr-2", "v2", "authorcount", "0"), header.attributes());
    }

    @Test
    void parseHeaderWithBlockAttributesBeforeTitle() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "[id=foo]",
                "= Fighter",
                "",
                "Yes the music band.")));
        assertEquals("Fighter", header.title());
        assertEquals(Map.of("id", "foo", "authorcount", "0"), header.attributes());
    }

    @Test
    void parseHeaderWithBlockAttributesAndClassBeforeTitle() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "[#foo]",
                "[.bar]",
                "= Fighter")));
        assertEquals("Fighter", header.title());
        assertEquals(Map.of("id", "foo", "role", "bar", "authorcount", "0"), header.attributes());
    }

    @Test
    void parseHeaderWithBlockAnchorBeforeTitle() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "[[foo]]",
                "= Fighter",
                "",
                "Yes the music band.")));
        assertEquals("Fighter", header.title());
        assertEquals(Map.of("id", "foo", "authorcount", "0"), header.attributes());
    }

    @Test
    void parseHeaderWithBlockAttributesBeforeTitleWithoutHeader() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "[id=foo]",
                "Yes the music band.")));
        assertEquals("", header.title());
        assertEquals(Map.of("authorcount", "0"), header.attributes());
    }

    @Test
    void parseHeaderWithConditionalBlocks() {
        final var content = List.of("""
                = Title
                :idprefix:
                :idseparator: -
                ifndef::env-github[]
                :toc: left
                :icons: font
                endif::[]
                ifdef::env-github[]
                :toc: macro
                :caution-caption: :fire:
                :important-caption: :exclamation:
                :note-caption: :paperclip:
                :tip-caption: :bulb:
                :warning-caption: :warning:
                endif::[]
                """.split("\n"));
        {
            final var header = new Parser().parseHeader(new Reader(content));
            assertEquals("Title", header.title());
            assertEquals(Map.of("idprefix", "", "idseparator", "-", "toc", "left", "icons", "font", "authorcount", "0"), header.attributes());
        }
        {
            final var header = new Parser(Map.of("env-github", "true")).parseHeader(new Reader(content));
            assertEquals("Title", header.title());
            assertEquals(Map.of(
                    "idprefix", "", "idseparator", "-",
                    "toc", "macro",
                    "caution-caption", ":fire:", "important-caption", ":exclamation:",
                    "note-caption", ":paperclip:", "tip-caption", ":bulb:", "warning-caption", ":warning:",
                    "authorcount", "0"), header.attributes());
        }
    }

    @Test
    void parseHeaderWithNamedEndif() { // the named endif did not close the block, so the rest of the document was part of it
        final var content = List.of("""
                = Title
                ifndef::env-github[]
                :toc: left
                endif::env-github[]
                ifdef::env-github[]
                :toc: macro
                endif::env-github[]
                :icons: font
                """.split("\n"));
        assertEquals(
                Map.of("toc", "left", "icons", "font", "authorcount", "0"),
                new Parser().parseHeader(new Reader(content)).attributes());
        assertEquals(
                Map.of("toc", "macro", "icons", "font", "authorcount", "0"),
                new Parser(Map.of("env-github", "true")).parseHeader(new Reader(content)).attributes());
    }

    @Test
    void parseHeaderWithInlineConditionals() { // ifdef::attr[content] has no endif, the content is a single line
        {   // condition is false, the line is dropped but the header parsing goes on
            final var header = new Parser().parseHeader(new Reader(List.of(
                    "= Title",
                    "ifdef::env-github[:imagesdir: ../assets/images/posts]",
                    ":page-layout: post")));
            assertEquals("Title", header.title());
            assertEquals(Map.of("page-layout", "post", "authorcount", "0"), header.attributes());
        }
        {   // condition is true, the content is evaluated as a header line
            final var header = new Parser(Map.of("env-github", "true")).parseHeader(new Reader(List.of(
                    "= Title",
                    "ifdef::env-github[:imagesdir: ../assets/images/posts]",
                    ":page-layout: post")));
            assertEquals("Title", header.title());
            assertEquals(Map.of(
                    "imagesdir", "../assets/images/posts",
                    "page-layout", "post",
                    "authorcount", "0"), header.attributes());
        }
        {   // the attribute the condition tests can be set by the header itself
            final var header = new Parser().parseHeader(new Reader(List.of(
                    "= Title",
                    ":my-flag:",
                    "ifdef::my-flag[:page-set-by-inline: yes]")));
            assertEquals(Map.of(
                    "my-flag", "",
                    "page-set-by-inline", "yes",
                    "authorcount", "0"), header.attributes());
        }
        {   // ifndef inline form
            final var header = new Parser().parseHeader(new Reader(List.of(
                    "= Title",
                    "ifndef::env-github[:imagesdir: ./images]",
                    ":page-layout: post")));
            assertEquals(Map.of(
                    "imagesdir", "./images",
                    "page-layout", "post",
                    "authorcount", "0"), header.attributes());
        }
    }

    @Test
    void parseHeaderWithMultipleAttributesConditionals() { // "," is an or, "+" is an and
        final var orForm = List.of(
                "= Title",
                "ifdef::env-github,env-browser[:imagesdir: ../assets/images/posts]",
                ":page-layout: post");
        assertEquals(
                Map.of("page-layout", "post", "authorcount", "0"),
                new Parser().parseHeader(new Reader(orForm)).attributes());
        assertEquals(
                Map.of("imagesdir", "../assets/images/posts", "page-layout", "post", "authorcount", "0"),
                new Parser(Map.of("env-browser", "true")).parseHeader(new Reader(orForm)).attributes());

        final var andForm = List.of(
                "= Title",
                "ifdef::env-github+env-browser[:imagesdir: ../assets/images/posts]",
                ":page-layout: post");
        assertEquals(
                Map.of("page-layout", "post", "authorcount", "0"),
                new Parser(Map.of("env-github", "true")).parseHeader(new Reader(andForm)).attributes());
        assertEquals(
                Map.of("imagesdir", "../assets/images/posts", "page-layout", "post", "authorcount", "0"),
                new Parser(Map.of("env-github", "true", "env-browser", "true")).parseHeader(new Reader(andForm)).attributes());

        // as of asciidoctor an empty name is an undefined attribute so a trailing separator is never true
        final var trailingSeparator = List.of(
                "= Title",
                "ifdef::env-github+[:imagesdir: ../assets/images/posts]",
                ":page-layout: post");
        assertEquals(
                Map.of("page-layout", "post", "authorcount", "0"),
                new Parser(Map.of("env-github", "true")).parseHeader(new Reader(trailingSeparator)).attributes());
    }

    @Test
    void parseHeaderAndContent() {
        final var doc = new Parser().parse(List.of("= Title", "", "++++", "pass", "++++"), new Parser.ParserContext(null));
        assertEquals("Title", doc.header().title());
        assertEquals(Map.of("authorcount", "0"), doc.header().attributes());
        assertEquals(List.of(new PassthroughBlock("pass", Map.of())), doc.body().children());
    }

    @Test
    void parseMultiLineAttributesHeader() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":attr-1: v1", ":attr-2: v2\\", "  and it continues", "", "content")));
        assertEquals("Title", header.title());
        assertEquals(Map.of("attr-1", "v1", "attr-2", "v2 and it continues", "authorcount", "0"), header.attributes());
    }

    @Test
    void parseAuthorLine() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", "firstname middlename lastname <email>", "revision number, revision date: revision revmark", ":attr: value")));
        assertEquals("Title", header.title());
        assertEquals(List.of(new Author(
                "firstname middlename lastname", "email",
                "firstname", "middlename", "lastname", "fml")), header.author());
        // as of asciidoctor "revision number" is dropped since a revision number without a date requires a "v" prefix
        assertEquals(new Revision("", "revision date", "revision revmark"), header.revision());
        assertEquals(Map.ofEntries(
                Map.entry("attr", "value"),
                Map.entry("authorcount", "1"),
                Map.entry("author", "firstname middlename lastname"),
                Map.entry("authors", "firstname middlename lastname"),
                Map.entry("email", "email"),
                Map.entry("firstname", "firstname"),
                Map.entry("middlename", "middlename"),
                Map.entry("lastname", "lastname"),
                Map.entry("authorinitials", "fml"),
                Map.entry("revnumber", ""),
                Map.entry("revdate", "revision date"),
                Map.entry("revremark", "revision revmark")), header.attributes());
    }

    @Test
    void parseMultipleAuthorsLine() { // sample of https://docs.asciidoctor.org/asciidoc/latest/document/multiple-authors/
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= The Intrepid Chronicles",
                "Kismet R. Lee <kismet@asciidoctor.org>; B. Steppenwolf; Pax Draeke <pax@asciidoctor.org>",
                "",
                "content")));
        assertEquals("The Intrepid Chronicles", header.title());
        assertEquals(List.of(
                new Author("Kismet R. Lee", "kismet@asciidoctor.org", "Kismet", "R.", "Lee", "KRL"),
                new Author("B. Steppenwolf", "", "B.", null, "Steppenwolf", "BS"),
                new Author("Pax Draeke", "pax@asciidoctor.org", "Pax", null, "Draeke", "PD")), header.author());
    }

    @Test
    void parseAuthorLineCommaIsNotAnAuthorSeparator() { // only ";" is, as of the specification
        final var header = new Parser().parseHeader(new Reader(List.of("= Title", "Doc Writer, Junior Writer", "", "content")));
        // the shape is unknown so the whole entry is the name and the firstname
        assertEquals(List.of(new Author(
                "Doc Writer, Junior Writer", "",
                "Doc Writer, Junior Writer", null, null, "D")), header.author());
    }

    @Test
    void parseAuthorLineWithAdjoinedNames() {
        final var header = new Parser().parseHeader(new Reader(List.of("= Title", "Mary_Sue Bronte", "", "content")));
        assertEquals(List.of(new Author("Mary Sue Bronte", "", "Mary Sue", null, "Bronte", "MB")), header.author());
    }

    @Test
    void parseAuthorAttributes() { // sample of https://docs.asciidoctor.org/asciidoc/latest/document/author-attribute-entries/
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title",
                ":author: Dave Grohl",
                ":email: grohl@foofighter.com",
                "",
                "Some content here.")));
        assertEquals("My Title", header.title());
        assertAuthors(List.of(new Author("Dave Grohl", "grohl@foofighter.com")), header.author());
        assertEquals(Map.of(
                "authorcount", "1", "author", "Dave Grohl", "authors", "Dave Grohl", "email", "grohl@foofighter.com",
                "firstname", "Dave", "lastname", "Grohl", "authorinitials", "DG"), header.attributes());
    }

    @Test
    void parseAuthorAttributeWithoutMail() {
        final var header = new Parser().parseHeader(new Reader(List.of("= My Title", ":author: Dave Grohl", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl", "")), header.author());
    }

    @Test
    void parseMailAttributeWithoutAuthorIsIgnored() { // no author means no author list
        final var header = new Parser().parseHeader(new Reader(List.of("= My Title", ":email: grohl@foofighter.com", "", "content")));
        assertEquals(List.of(), header.author());
    }

    @Test
    void parseAuthorAttributeWithMail() { // as of asciidoctor the mail is part of the name there, :email: is the way
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":author: Dave Grohl <grohl@foofighter.com>", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl <grohl@foofighter.com>", "")), header.author());
        assertEquals("Dave", header.attributes().get("firstname")); // deduced ignoring the mail
        assertEquals("Grohl", header.attributes().get("middlename"));
        assertEquals("DG", header.attributes().get("authorinitials"));
    }

    @Test
    void parseAuthorLineWithMailAndMoreThanThreeNames() { // as of asciidoctor the mail is kept in the name there too
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", "Al Bob Chuck Dave <ad@example.com>", "", "content")));
        assertAuthors(List.of(new Author("Al Bob Chuck Dave <ad@example.com>", "")), header.author());
    }

    @Test
    void parseAuthorAttributeCantDefineMultipleAuthors() { // as of the spec ";" is not a separator there
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":author: Dave Grohl; Taylor Hawkins", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl; Taylor Hawkins", "")), header.author());
    }

    @Test
    void parseAuthorsAttribute() { // asciidoctor extension of the spec: multiple authors with an attribute
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title",
                ":authors: Dave Grohl; Taylor Hawkins",
                ":email: grohl@foofighter.com",
                ":email_2: hawkins@foofighter.com",
                "",
                "content")));
        assertAuthors(List.of(
                new Author("Dave Grohl", "grohl@foofighter.com"),
                new Author("Taylor Hawkins", "hawkins@foofighter.com")), header.author());
    }

    @Test
    void parseAuthorAttributeOverridesAuthorLine() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", "Dave Grohl <grohl@foofighter.com>", ":author: Taylor Hawkins", "", "content")));
        assertAuthors(List.of(new Author("Taylor Hawkins", "grohl@foofighter.com")), header.author());
    }

    @Test
    void parseMailAttributeOverridesAuthorLine() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", "Dave Grohl <grohl@nirvana.com>", ":email: grohl@foofighter.com", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl", "grohl@foofighter.com")), header.author());
    }

    @Test
    void parseAuthorAttributeMatchingAuthorLineKeepsAllAuthors() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", "Dave Grohl; Taylor Hawkins", ":author: Dave Grohl", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl", ""), new Author("Taylor Hawkins", "")), header.author());
    }

    @Test
    void parseIndexedAuthorAttributes() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title",
                ":author_1: Dave Grohl",
                ":email_1: grohl@foofighter.com",
                ":author_2: Taylor Hawkins",
                ":email_2: hawkins@foofighter.com",
                "",
                "content")));
        assertAuthors(List.of(
                new Author("Dave Grohl", "grohl@foofighter.com"),
                new Author("Taylor Hawkins", "hawkins@foofighter.com")), header.author());
    }

    @Test
    void parseIndexedAuthorAttributesStopOnMissingIndex() { // author_3 is ignored since author_2 is missing
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":author_1: Dave Grohl", ":author_3: Nate Mendel", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl", "")), header.author());
    }

    @Test
    void parseIndexedAuthorAttributeIgnoredWithoutFirstOne() { // author_1 is required to start the list
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":author_2: Taylor Hawkins", "", "content")));
        assertEquals(List.of(), header.author());
    }

    @Test
    void parseIndexedAuthorAttributeOverridesAuthorLine() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", "Dave Grohl; Taylor Hawkins", ":author_2: Nate Mendel", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl", ""), new Author("Nate Mendel", "")), header.author());
    }

    @Test
    void parseIndexedAuthorAttributeCantCompleteASingleAuthorLine() { // a single author does not set author_1
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", "Dave Grohl", ":author_2: Taylor Hawkins", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl", "")), header.author());
    }

    @Test
    void parseAuthorLineAfterAttributes() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":toc: left", "Dave Grohl <grohl@foofighter.com>", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl", "grohl@foofighter.com")), header.author());
        assertEquals(Map.of(
                "toc", "left",
                "authorcount", "1", "author", "Dave Grohl", "authors", "Dave Grohl", "email", "grohl@foofighter.com",
                "firstname", "Dave", "lastname", "Grohl", "authorinitials", "DG"), header.attributes());
    }

    @Test
    void parseAuthorLineUsingAnAttribute() { // sample of https://docs.asciidoctor.org/asciidoc/latest/document/multiple-authors/
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":reg: (R)", "AsciiDoc{reg} WG; Another Author", "", "content")));
        assertAuthors(List.of(new Author("AsciiDoc(R) WG", ""), new Author("Another Author", "")), header.author());
        // as of asciidoctor the substitution makes the names be deduced as attribute values, ie not validated
        assertEquals("AsciiDoc(R)", header.attributes().get("firstname"));
        assertEquals("WG", header.attributes().get("lastname"));
    }

    @Test
    void parseSingleAuthorLineUsingAnAttribute() { // no indexed attribute there so the name is not re-deduced
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":reg: (R)", "AsciiDoc{reg} WG", "", "content")));
        assertAuthors(List.of(new Author("AsciiDoc(R) WG", "")), header.author());
        assertEquals("AsciiDoc(R) WG", header.attributes().get("firstname"));
        assertEquals("A", header.attributes().get("authorinitials"));
    }

    @Test
    void authorAttributeBeforeTheAuthorLineDoesNotOverrideIt() { // it is its reference value, as of asciidoctor
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":author: Taylor Hawkins", "Dave Grohl", "", "content")));
        assertAuthors(List.of(new Author("Taylor Hawkins", "")), header.author());
        assertEquals("Taylor Hawkins", header.attributes().get("author")); // kept as set
        assertEquals("Dave Grohl", header.attributes().get("authors")); // but deduced from the author line
        assertEquals("Dave", header.attributes().get("firstname"));
    }

    @Test
    void authorsAttributeBeforeTheAuthorLineDoesNotOverrideIt() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= My Title", ":authors: Taylor Hawkins; Nate Mendel", "Dave Grohl", "", "content")));
        assertAuthors(List.of(new Author("Dave Grohl", "")), header.author());
        assertEquals("1", header.attributes().get("authorcount"));
        assertEquals("Taylor Hawkins; Nate Mendel", header.attributes().get("authors"));
    }

    @Test
    void anyLineFollowingTheTitleIsTheAuthorLine() { // an empty line is required to end the header, as of asciidoctor
        final var doc = new Parser().parse(
                List.of("= Title", "* item", "", "content"), new Parser.ParserContext(null));
        assertAuthors(List.of(new Author("* item", "")), doc.header().author());
        assertEquals(List.of(new Text(List.of(), "content", Map.of())), doc.body().children());
    }

    @Test
    void blockMacroFollowingTheTitleIsTheAuthorLine() { // preprocessor macros (include, ifdef, ...) are not
        final var doc = new Parser().parse(
                List.of("= Title", "image::foo.png[]", "", "content"), new Parser.ParserContext(null));
        assertAuthors(List.of(new Author("image::foo.png[]", "")), doc.header().author());
        assertEquals(List.of(new Text(List.of(), "content", Map.of())), doc.body().children());
    }

    @Test
    void parseAuthorLineWithUnexpectedShapeKeepsItAsIs() { // more than 3 names, underscores are not adjoining names
        final var header = new Parser().parseHeader(new Reader(List.of("= Title", "A_1 B_2 C_3 D_4", "", "content")));
        assertAuthors(List.of(new Author("A_1 B_2 C_3 D_4", "")), header.author());
    }

    @Test
    void parseHeaderAttributesWithComments() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":attr-1: v1", "// a comment", "////", "a comment block", "////", ":attr-2: v2", "", "content")));
        assertEquals(Map.of("attr-1", "v1", "attr-2", "v2", "authorcount", "0"), header.attributes());
    }

    @Test
    void authorsExposeTheDeducedNames() { // they are read back from the attributes as asciidoctor Document#authors does
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", "Kismet R. Lee <kismet@asciidoctor.org>; Steppenwolf", "", "content")));
        assertEquals(List.of(
                new Author("Kismet R. Lee", "kismet@asciidoctor.org", "Kismet", "R.", "Lee", "KRL"),
                new Author("Steppenwolf", "", "Steppenwolf", null, null, "S")), header.author());
    }

    @Test
    void authorsExposeTheDeducedNamesOfAttributeEntries() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":author: Al Bob Chuck Dave", ":authorinitials: XX", "", "content")));
        // the third name holds the remaining ones and the explicit initials are kept
        assertEquals(List.of(new Author(
                "Al Bob Chuck Dave", "",
                "Al", "Bob", "Chuck Dave", "XX")), header.author());
    }

    @Test
    void parseAuthorLineAttributes() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", "Kismet R. Lee <kismet@asciidoctor.org>; B. Steppenwolf", "", "content")));
        assertEquals(Map.ofEntries(
                Map.entry("authorcount", "2"),
                Map.entry("authors", "Kismet R. Lee, B. Steppenwolf"),
                Map.entry("author", "Kismet R. Lee"),
                Map.entry("email", "kismet@asciidoctor.org"),
                Map.entry("firstname", "Kismet"),
                Map.entry("middlename", "R."),
                Map.entry("lastname", "Lee"),
                Map.entry("authorinitials", "KRL"),
                Map.entry("author_1", "Kismet R. Lee"),
                Map.entry("email_1", "kismet@asciidoctor.org"),
                Map.entry("firstname_1", "Kismet"),
                Map.entry("middlename_1", "R."),
                Map.entry("lastname_1", "Lee"),
                Map.entry("authorinitials_1", "KRL"),
                Map.entry("author_2", "B. Steppenwolf"),
                Map.entry("firstname_2", "B."),
                Map.entry("lastname_2", "Steppenwolf"),
                Map.entry("authorinitials_2", "BS")), header.attributes());
    }

    @Test
    void authorLineAttributesDoNotOverrideAttributeEntries() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":firstname: Zoe", ":authorinitials: XX", "Doc Writer", "", "content")));
        assertEquals("Zoe", header.attributes().get("firstname"));
        assertEquals("XX", header.attributes().get("authorinitials"));
        assertEquals("Writer", header.attributes().get("lastname"));
        assertEquals("Doc Writer", header.attributes().get("author"));
    }

    @Test
    void authorAttributeEntryOverridesTheDeducedAttributesButNotTheInitials() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":author: Doc Writer", ":firstname: Zoe", ":authorinitials: XX", "", "content")));
        assertEquals("Doc", header.attributes().get("firstname"));
        assertEquals("XX", header.attributes().get("authorinitials"));
    }

    @Test
    void authorsAttributeEntryOverridesTheDeducedInitials() { // contrarily to the author one, as of asciidoctor
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":authors: Doc Writer; Jane Doe", ":authorinitials: XX", "", "content")));
        assertEquals("DW", header.attributes().get("authorinitials"));
    }

    @Test
    void authorAttributeEntryKeepsTheAuthorLineMail() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", "Dave Grohl <grohl@nirvana.com>", ":author: Taylor Hawkins", "", "content")));
        assertEquals("grohl@nirvana.com", header.attributes().get("email"));
        assertEquals("Taylor Hawkins", header.attributes().get("author"));
    }

    @Test
    void mailAttributeFallsBackOnTheFirstIndexedOne() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":author_1: Dave Grohl", ":email_1: g@f.com", ":author_2: Taylor Hawkins", "", "content")));
        assertEquals("g@f.com", header.attributes().get("email"));
    }

    @Test
    void parseRevisionLines() {
        assertEquals(new Revision("1.0", "", ""), revision("v1.0"));
        assertEquals(new Revision("", "2013-01-01", ""), revision("2013-01-01"));
        assertEquals(new Revision("", "1.0", ""), revision("1.0")); // a revision number without a date needs a "v"
        assertEquals(new Revision("1.0", "2013-01-01", "Ring in the new year release"),
                revision("v1.0, 2013-01-01: Ring in the new year release"));
        assertEquals(new Revision("1.0", "Jan 01, 2013", ""), revision("1.0, Jan 01, 2013"));
        assertEquals(new Revision("1.0", "", ""), revision("v1.0,"));
        assertEquals(new Revision("", "1.0", "remark"), revision("1.0: remark"));
        assertEquals(new Revision("", "random text here", ""), revision("random text here"));
        assertEquals(new Revision("", "Some text", "with colon"), revision("Some text: with colon"));
        assertEquals(new Revision("1.0", "2013-01-01", "the remark"), revision("  v1.0 ,  2013-01-01 :  the remark"));
    }

    @Test
    void revisionLineAttributes() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", "Doc Writer", "v1.0, 2013-01-01: the remark", "", "content")));
        assertEquals("1.0", header.attributes().get("revnumber"));
        assertEquals("2013-01-01", header.attributes().get("revdate"));
        assertEquals("the remark", header.attributes().get("revremark"));
    }

    @Test
    void revisionAttributeEntriesWinOverTheRevisionLine() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":revdate: 2020-01-01", "Doc Writer", "v1.0, 2013-01-01", "", "content")));
        assertEquals("2020-01-01", header.attributes().get("revdate"));
        assertEquals("1.0", header.attributes().get("revnumber"));
    }

    @Test
    void notARevisionLineStaysInTheBody() { // only a line starting with ':' is not a revision line
        final var doc = new Parser().parse(
                List.of("= Title", "Doc Writer", ": remark", "", "content"), new Parser.ParserContext(null));
        assertEquals(new Revision("", "", ""), doc.header().revision());
        assertEquals(List.of(
                new Text(List.of(), ": remark", Map.of()),
                new Text(List.of(), "content", Map.of())), doc.body().children());
    }

    private void assertAuthors(final List<Author> expected, final List<Author> actual) { // names and mails only
        assertEquals(
                expected.stream().map(it -> new Author(it.name(), it.mail())).toList(),
                actual.stream().map(it -> new Author(it.name(), it.mail())).toList());
    }

    private Revision revision(final String revisionLine) {
        return new Parser().parseHeader(new Reader(List.of("= Title", "Doc Writer", revisionLine, "", "content"))).revision();
    }

    @Test
    void parseSectionRightAfterHeaderAttributes() {
        // as of asciidoctor the header ends on an empty line so this section is the author line, an empty line
        // is required after the header to get it in the body
        final var doc = new Parser().parse(
                List.of("= Title", ":attr: value", "== Section", "", "content"), new Parser.ParserContext(null));
        assertAuthors(List.of(new Author("== Section", "")), doc.header().author());
        assertEquals(List.of(new Text(List.of(), "content", Map.of())), doc.body().children());
    }

    @Test
    void parseAuthorAttribute() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title",
                ":author: Dave Grohl",
                ":email: grohl@foofighter.com")));
        assertEquals("Title", header.title());
        assertAuthors(List.of(new Author("Dave Grohl", "grohl@foofighter.com")), header.author());
        assertEquals(Map.of(
                "author", "Dave Grohl", "email", "grohl@foofighter.com",
                "authors", "Dave Grohl", "firstname", "Dave", "lastname", "Grohl", "authorinitials", "DG",
                "authorcount", "1"), header.attributes());
    }

    @Test
    void parseAuthorAttributeWithoutEmail() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title",
                ":author: Dave Grohl")));
        assertEquals("Title", header.title());
        assertAuthors(List.of(new Author("Dave Grohl", "")), header.author());
        assertEquals(Map.of(
                "author", "Dave Grohl",
                "authors", "Dave Grohl", "firstname", "Dave", "lastname", "Grohl", "authorinitials", "DG",
                "authorcount", "1"), header.attributes());
    }

    @Test
    void parseMultipleAuthorsWithAttributes() { // the comma is not an author separator so it stays a single author
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title",
                ":author: Dave Grohl, Taylor Hawkins",
                ":email: grohl@foofighter.com")));
        assertEquals("Title", header.title());
        assertAuthors(List.of(new Author("Dave Grohl, Taylor Hawkins", "grohl@foofighter.com")), header.author());
        assertEquals(Map.of(
                "author", "Dave Grohl, Taylor Hawkins", "email", "grohl@foofighter.com",
                "authors", "Dave Grohl, Taylor Hawkins",
                "firstname", "Dave", "middlename", "Grohl,", "lastname", "Taylor Hawkins", "authorinitials", "DGT",
                "authorcount", "1"), header.attributes());
    }

    @Test
    void parseAuthorLineAndAttributesCombined() { // the attribute entries are read after the author line so they win
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title",
                "John Doe <john@example.com>",
                ":author: Dave Grohl",
                ":email: grohl@foofighter.com")));
        assertEquals("Title", header.title());
        assertAuthors(List.of(new Author("Dave Grohl", "grohl@foofighter.com")), header.author());
        assertEquals(Map.of(
                "author", "Dave Grohl", "email", "grohl@foofighter.com",
                "authors", "Dave Grohl", "firstname", "Dave", "lastname", "Grohl", "authorinitials", "DG",
                "authorcount", "1"), header.attributes());
    }

    @Test
    void manPageTitle() {
        final var header = new Parser().parseHeader(new Reader(List.of("= ls(1)")));
        assertEquals("ls(1)", header.title());
        assertEquals("manpage", header.attributes().get("doctype"));
        assertEquals("ls", header.attributes().get("manname"));
        assertEquals("1", header.attributes().get("mansection"));
    }

    @Test
    void parseHeaderWhenMissing() {
        final var header = new Parser().parseHeader(new Reader(List.of("paragraph")));
        assertEquals("", header.title());
        assertEquals(Map.of("authorcount", "0"), header.attributes());
    }

    @Test
    void parseParagraph() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                Mark my words, #automation is essential#.
                                
                ##Mark##up refers to value that contains formatting ##mark##s.
                                
                Where did all the [.underline]#cores# go?
                                
                We need [.line-through]#ten# twenty VMs.
                                
                A [.myrole]#custom role# must be fulfilled by the theme.""".split("\n"))), null);
        assertEquals(
                List.of(
                        new Paragraph(List.of(
                                new Text(List.of(), "Mark my words, ", Map.of()),
                                new Text(List.of(MARK), "automation is essential", Map.of()),
                                new Text(List.of(), ".", Map.of())
                        ), Map.of()),
                        new Paragraph(List.of(
                                new Text(List.of(MARK), "Mark", Map.of()),
                                new Text(List.of(), "up refers to value that contains formatting ", Map.of()),
                                new Text(List.of(MARK), "mark", Map.of()),
                                new Text(List.of(), "s.", Map.of())
                        ), Map.of()),
                        new Paragraph(List.of(
                                new Text(List.of(), "Where did all the ", Map.of()),
                                new Text(List.of(MARK), "cores", Map.of("role", "underline")),
                                new Text(List.of(), " go?", Map.of())
                        ), Map.of()),
                        new Paragraph(List.of(
                                new Text(List.of(), "We need ", Map.of()),
                                new Text(List.of(MARK), "ten", Map.of("role", "line-through")),
                                new Text(List.of(), " twenty VMs.", Map.of())
                        ), Map.of()),
                        new Paragraph(List.of(
                                new Text(List.of(), "A ", Map.of()),
                                new Text(List.of(MARK), "custom role", Map.of("role", "myrole")),
                                new Text(List.of(), " must be fulfilled by the theme.", Map.of())
                        ), Map.of())
                ),
                body.children());
    }

    @Test
    void parseParagraphMultiline() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                Mark my words, #automation is essential#.
                                
                ##Mark##up refers to value that contains formatting ##mark##s.
                Where did all the [.underline]#cores# go?
                                
                end.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Paragraph(List.of(
                                new Text(List.of(), "Mark my words, ", Map.of()),
                                new Text(List.of(MARK), "automation is essential", Map.of()),
                                new Text(List.of(), ".", Map.of())
                        ), Map.of()),
                        new Paragraph(List.of(
                                new Text(List.of(MARK), "Mark", Map.of()),
                                new Text(List.of(), "up refers to value that contains formatting ", Map.of()),
                                new Text(List.of(MARK), "mark", Map.of()),
                                new Text(List.of(), "s. Where did all the ", Map.of()),
                                new Text(List.of(MARK), "cores", Map.of("role", "underline")),
                                new Text(List.of(), " go?", Map.of())
                        ), Map.of()),
                        new Text(List.of(), "end.", Map.of())
                ),
                body.children());
    }

    @Test
    void links() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                https://yupiik.io[Yupiik OSS,role=external,window=_blank]
                                
                This can be in a sentence about https://yupiik.io[Yupiik OSS].
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Link("https://yupiik.io", new Text(List.of(), "Yupiik OSS", Map.of("role", "external", "nowrap", "true", "window", "_blank", "", "Yupiik OSS")), Map.of("role", "external", "window", "_blank")),
                        new Paragraph(List.of(
                                new Text(List.of(), "This can be in a sentence about ", Map.of()),
                                new Link("https://yupiik.io", new Text(List.of(), "Yupiik OSS", Map.of("nowrap", "true", "", "Yupiik OSS")), Map.of()),
                                new Text(List.of(), ".", Map.of())
                        ), Map.of())
                ),
                body.children());
    }

    @Test
    void linkNoOpt() {
        assertEquals(
                List.of(new Link("https://yupiik.io", new Text(List.of(), "https://yupiik.io", Map.of("nowrap", "true")), Map.of())),
                new Parser().parseBody(new Reader(List.of("https://yupiik.io")), null).children());
        assertEquals(
                List.of(new Paragraph(
                        List.of(
                                new Text(List.of(), "in a sentence ", Map.of()),
                                new Link("https://yupiik.io", new Text(List.of(), "https://yupiik.io", Map.of("nowrap", "true")), Map.of()),
                                new Text(List.of(), " and multiple ", Map.of()),
                                new Link("https://www.yupiik.io", new Text(List.of(), "https://www.yupiik.io", Map.of("nowrap", "true")), Map.of()),
                                new Text(List.of(), " links.", Map.of())),
                        Map.of())),
                new Parser().parseBody(new Reader(List.of("in a sentence https://yupiik.io and multiple https://www.yupiik.io links.")), null).children());
    }

    @Test
    void linkTrailingPunctuation() { // as of asciidoctor the punctuation which follows a bare url is not part of it
        assertEquals(
                List.of(new Paragraph(List.of(
                        new Text(List.of(), "See ", Map.of()),
                        new Link("https://yupiik.io", new Text(List.of(), "https://yupiik.io", Map.of("nowrap", "true")), Map.of()),
                        new Text(List.of(), "; then ", Map.of()),
                        new Link("https://www.yupiik.io", new Text(List.of(), "https://www.yupiik.io", Map.of("nowrap", "true")), Map.of()),
                        new Text(List.of(), ", ok.", Map.of())), Map.of())),
                new Parser().parseBody(new Reader(List.of("See https://yupiik.io; then https://www.yupiik.io, ok.")), null).children());
        assertEquals(
                List.of(new Paragraph(List.of(
                        new Text(List.of(), "(see ", Map.of()),
                        new Link("https://yupiik.io", new Text(List.of(), "https://yupiik.io", Map.of("nowrap", "true")), Map.of()),
                        new Text(List.of(), ") and more", Map.of())), Map.of())),
                new Parser().parseBody(new Reader(List.of("(see https://yupiik.io) and more")), null).children());
    }

    @Test
    void linkInCode() {
        final var body = new Parser().parseBody(new Reader(List.of("`https://yupiik.io[Yupiik OSS]`")), null);
        assertEquals(
                List.of(new Link("https://yupiik.io", "Yupiik OSS", Map.of("role", "inline-code"))),
                body.children());
    }

    @Test
    void linkMacroWithRole() {
        assertEquals(
                List.of(new Link( "foo", new Text(List.of(), "foo", Map.of("role", "test", "nowrap", "true")), Map.of("role", "test", "nowrap", "true"))),
                new Parser().parseBody(new Reader(List.of("link:foo[role=\"test\"]")), null).children());
    }

    @Test
    void linksAttribute() {
        final var body = new Parser().parseBody(new Reader(List.of(":url: https://yupiik.io", "", "{url}[Yupiik OSS]")), null);
        assertEquals(
                List.of(new Link("https://yupiik.io", new Text(List.of(), "Yupiik OSS", Map.of("", "Yupiik OSS", "nowrap", "true")), Map.of())),
                body.children());
    }

    @Test
    void parseParagraphAndSections() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                == Section #1
                                
                ##Mark##up refers to value that contains formatting ##mark##s.
                Where did all the [.underline]#cores# go?
                                
                == Section #2
                                
                Something key.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Section(
                                2,
                                new Text(List.of(), "Section #1", Map.of()),
                                List.of(new Paragraph(List.of(
                                        new Text(List.of(MARK), "Mark", Map.of()),
                                        new Text(List.of(), "up refers to value that contains formatting ", Map.of()),
                                        new Text(List.of(MARK), "mark", Map.of()),
                                        new Text(List.of(), "s. Where did all the ", Map.of()),
                                        new Text(List.of(MARK), "cores", Map.of("role", "underline")),
                                        new Text(List.of(), " go?", Map.of())), Map.of())), Map.of()),
                        new Section(
                                2,
                                new Text(List.of(), "Section #2", Map.of()),
                                List.of(new Text(List.of(), "Something key.", Map.of())), Map.of())
                ),
                body.children());
    }

    @Test
    void options() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [.first]
                = Section #1
                                
                [.second]
                == Section #2
                                
                [.center]
                Something key.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Section(
                                1, new Text(List.of(), "Section #1", Map.of()),
                                List.of(new Section(
                                        2, new Text(List.of(), "Section #2", Map.of()),
                                        List.of(new Text(List.of(), "Something key.", Map.of("role", "center"))),
                                        Map.of("role", "second"))),
                                Map.of("role", "first"))
                ),
                body.children());
    }

    @Test
    void colonInTitle() {
        final var body = new Parser().parseBody(new Reader(List.of("== foo :: bar")), null);
        assertEquals(
                List.of(new Section(2, new Text(List.of(), "foo :: bar", Map.of()), List.of(), Map.of())),
                body.children());
    }

    @Test
    void plusInList() {
        final var body = new Parser().parseBody(new Reader(List.of("* foo++")), null);
        assertEquals(
                List.of(new UnOrderedList(List.of(new Text(List.of(), "foo++", Map.of())), Map.of())),
                body.children());
    }

    @Test
    void leadingDots() {
        final var body = new Parser().parseBody(new Reader(List.of("... foobar")), null);
        assertEquals(
                List.of(new Text(List.of(), "... foobar", Map.of())),
                body.children());
    }

    @Test
    void dataAttributes() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [.step,data-foo=bar,data-dummy="true"]
                == Section #1
                                
                first
                                
                [.step,data-foo=bar2,data-dummy="true"]
                == Section #2
                                
                === Nested section
                                
                Something key.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Section(
                                2, new Text(List.of(), "Section #1", Map.of()),
                                List.of(new Text(List.of(), "first", Map.of())),
                                Map.of("data-dummy", "true", "data-foo", "bar", "role", "step")),
                        new Section(
                                2, new Text(List.of(), "Section #2", Map.of()),
                                List.of(new Section(
                                        3, new Text(List.of(), "Nested section", Map.of()),
                                        List.of(new Text(List.of(), "Something key.", Map.of())),
                                        Map.of())),
                                Map.of("role", "step", "data-dummy", "true", "data-foo", "bar2"))),
                body.children());
    }

    @Test
    void blanksAroundAttributeNamesAndValues() { // as asciidoctor, they are not part of the name or of the value, except in quotes
        final var body = new Parser(Map.of("version", "1.0")).parseBody(new Reader(List.of("""
                [source, xml, subs="attributes+"]
                ----
                <version>{version}</version>
                ----

                [source,java,\ttitle = " Spaced title " ]
                ----
                run();
                ----

                [.configuration-reference, cols="1,1"]
                |===
                |Name|Value
                |===

                image::logo.png[Quarkus logo, width=100]
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Code("<version>1.0</version>\n", Map.of("language", "xml", "subs", "attributes+"), false, List.of()),
                        new Code("run();\n", Map.of("language", "java", "title", " Spaced title "), false, List.of()),
                        new Table(
                                List.of(List.of(new Text(List.of(), "Name", Map.of()), new Text(List.of(), "Value", Map.of()))),
                                Map.of("role", "configuration-reference", "cols", "1,1")),
                        new Macro("image", "logo.png", Map.of("", "Quarkus logo", "width", "100"), false)),
                body.children());
    }

    @Test
    void includeAttributesAfterABlank() { // the tag was ignored, so the whole file was included
        final var body = new Parser().parseBody(
                new Reader(List.of("include::snippet.adoc[leveloffset=+1, tag=snippet-a]")),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "Not included.",
                            "",
                            "// tag::snippet-a[]",
                            "== Included",
                            "// end::snippet-a[]"));
                    default -> Optional.empty();
                });
        assertEquals(List.of(new Section(3, new Text(List.of(), "Included", Map.of()), List.of(), Map.of())), body.children());
    }

    @Test
    void parseParagraphAndSectionsAndSubsections() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                == Section #1
                                
                first
                                
                == Section #2
                                
                === Nested section
                                
                Something key.
                                
                ==== And it can
                                
                go far
                                
                === Another nested section
                                
                === Even without content
                                
                yes
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Section(
                                2,
                                new Text(List.of(), "Section #1", Map.of()),
                                List.of(new Text(List.of(), "first", Map.of())), Map.of()),
                        new Section(
                                2,
                                new Text(List.of(), "Section #2", Map.of()),
                                List.of(
                                        new Section(
                                                3,
                                                new Text(List.of(), "Nested section", Map.of()),
                                                List.of(
                                                        new Text(List.of(), "Something key.", Map.of()),
                                                        new Section(
                                                                4,
                                                                new Text(List.of(), "And it can", Map.of()),
                                                                List.of(new Text(List.of(), "go far", Map.of())), Map.of())), Map.of()),
                                        new Section(
                                                3,
                                                new Text(List.of(), "Another nested section", Map.of()),
                                                List.of(), Map.of()),
                                        new Section(
                                                3,
                                                new Text(List.of(), "Even without content", Map.of()),
                                                List.of(new Text(List.of(), "yes", Map.of())), Map.of())), Map.of())),
                body.children());
    }

    @Test
    void code() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,java,.hljs]
                ----
                public record Foo() {
                }
                ----
                """.split("\n"))), null);
        assertEquals(
                List.of(new Code("public record Foo() {\n}\n", Map.of("language", "java", "role", "hljs"), false, List.of())),
                body.children());
    }

    @Test
    void codeIndented() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                    [source,xml]
                    ----
                    <dependency>
                        <groupId>io.quarkiverse.qute.web</groupId>
                        <artifactId>quarkus-qute-web</artifactId>
                    </dependency>
                    ----
                """.split("\n"))), null);
        assertEquals(
                List.of(new Code("    <dependency>\n        <groupId>io.quarkiverse.qute.web</groupId>\n        <artifactId>quarkus-qute-web</artifactId>\n    </dependency>\n", Map.of("language", "xml"), false, List.of())),
                body.children());
    }

    @Test
    void passthroughAttributeSubs() {
        final var body = new Parser(Map.of("foo-version", "1")).parseBody(new Reader(List.of("""
                [subs=attributes]
                ++++
                <script defer src="/js/test.js?v={foo-version}"></script>
                ++++
                """.split("\n"))), null);
        assertEquals(
                List.of(new PassthroughBlock("<script defer src=\"/js/test.js?v=1\"></script>", Map.of("subs", "attributes"))),
                body.children());
    }

    @Test
    void codeAttributeSubs() {
        final var body = new Parser(Map.of("foo-version", "1")).parseBody(new Reader(List.of("""
                [subs=attributes]
                ----
                <script defer src="/js/test.js?v={foo-version}"></script>
                ----
                """.split("\n"))), null);
        assertEquals(
                List.of(new Code("<script defer src=\"/js/test.js?v=1\"></script>\n", Map.of("subs", "attributes"), false, List.of())),
                body.children());
    }

    @Test
    void codeAttributeSubsFromDocumentAttributes() { // #120, the header attribute was ignored, only the parser ones were read
        final var doc = new Parser().parse("""
                = Title
                :foo: bar

                [source,java,subs="attributes"]
                ----
                foo("{foo}");
                ----
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        assertEquals(
                List.of(new Code("foo(\"bar\");\n", Map.of("language", "java", "subs", "attributes"), false, List.of())),
                doc.body().children());
    }

    @Test
    void passthroughAttributeSubsFromDocumentAttributes() {
        final var doc = new Parser().parse("""
                = Title
                :foo: bar

                [subs="attributes"]
                ++++
                <b>{foo}</b>
                ++++
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        assertEquals(
                List.of(new PassthroughBlock("<b>bar</b>", Map.of("subs", "attributes"))),
                doc.body().children());
    }

    @Test
    void passthroughIncludeWithDocumentAttributeInPath(@TempDir final Path work) throws IOException {
        Files.writeString(work.resolve("content.html"), "<b>included</b>");
        final var doc = new Parser().parse("= Title\n:partialsdir: " + work + "\n\n++++\ninclude::{partialsdir}/content.html[]\n++++\n",
                new Parser.ParserContext(ContentResolver.of(work)));
        assertEquals(
                List.of(new PassthroughBlock("<b>included</b>\n", Map.of())), // an include keeps its trailing line break, as in codeInclude()
                doc.body().children());
    }

    @Test
    void codeAfterListContinuation() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                * foo
                +
                [source,java,.hljs]
                ----
                public record Foo() {
                                
                }
                ----
                +
                * bar
                +
                ----
                public record Bar() {
                                
                }
                ----
                +
                * end
                """.split("\n"))), null);
        assertEquals(
                List.of(new UnOrderedList(
                        List.of(
                                new Paragraph(
                                        List.of(
                                                new Text(List.of(), "foo", Map.of()),
                                                new Code("public record Foo() {\n\n}\n", Map.of("language", "java", "role", "hljs"), false, List.of())
                                        ),
                                        Map.of()),
                                new Paragraph(
                                        List.of(
                                                new Text(List.of(), "bar", Map.of()),
                                                new Code("public record Bar() {\n\n}\n", Map.of(), false, List.of())
                                        ),
                                        Map.of()),
                                new Text(List.of(), "end", Map.of())),
                        Map.of()
                )),
                body.children());
    }

    @Test
    void codeInclude(@TempDir final Path work) throws IOException {
        final var code = "test = value\nmultiline = true\n";
        Files.writeString(work.resolve("content.properties"), code);
        final var body = new Parser(Map.of("partialsdir", work.toString())).parseBody(new Reader(List.of("""
                [source,properties,.hljs]
                ----
                include::{partialsdir}/content.properties[]
                ----
                """.split("\n"))), ContentResolver.of(work));
        assertEquals(
                List.of(new Code(code, Map.of("language", "properties", "role", "hljs"), false, List.of())),
                body.children());
    }

    @Test
    void codeEscapedInclude(@TempDir final Path work) throws IOException { // documenting the directive, not using it
        Files.writeString(work.resolve("content.properties"), "test = value\n");
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,asciidoc]
                ----
                before
                \\include::content.properties[]
                after
                ----
                """.split("\n"))), ContentResolver.of(work));
        assertEquals(
                List.of(new Code("before\ninclude::content.properties[]\nafter\n", Map.of("language", "asciidoc"), false, List.of())),
                body.children());
    }

    @Test
    void codeIncludeKeepsTheLinesAfterIt(@TempDir final Path work) throws IOException {
        Files.writeString(work.resolve("one.txt"), "one\n");
        Files.writeString(work.resolve("two.txt"), "two\n");
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,text]
                ----
                before
                include::one.txt[]
                middle
                include::two.txt[]
                after
                ----
                """.split("\n"))), ContentResolver.of(work));
        assertEquals(
                List.of(new Code("before\none\nmiddle\ntwo\nafter\n", Map.of("language", "text"), false, List.of())),
                body.children());
    }

    @Test
    void codeIncludeFollowedByACalloutIsCode(@TempDir final Path work) throws IOException { // as asciidoctor, text after ] is not a directive
        Files.writeString(work.resolve("attrs.adoc"), ":a: 1\n");
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,asciidoc]
                ----
                = Title
                include::attrs.adoc[] <1>
                :type: howto
                ----
                <1> The attributes.
                """.split("\n"))), ContentResolver.of(work));
        final var callOut = new CallOut(1, new Text(List.of(), "The attributes.", Map.of()));
        assertEquals(
                List.of(new Code("= Title\ninclude::attrs.adoc[]\n:type: howto\n", Map.of("language", "asciidoc"), false,
                        List.of(List.of(), List.of(callOut), List.of()))),
                body.children());
    }

    @Test
    void codeIncludeOnlyAsAWholeLine(@TempDir final Path work) throws IOException { // indented, inside a line, escaped with trailing text
        Files.writeString(work.resolve("one.txt"), "one\n");
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,text]
                ----
                  include::one.txt[]
                text include::one.txt[] more
                \\include::one.txt[] trailing
                ----
                """.split("\n"))), ContentResolver.of(work));
        assertEquals(
                List.of(new Code("  include::one.txt[]\ntext include::one.txt[] more\n\\include::one.txt[] trailing\n", Map.of("language", "text"), false, List.of())),
                body.children());
    }

    @Test
    void literalIncludeFollowedByTextIsText(@TempDir final Path work) throws IOException {
        Files.writeString(work.resolve("one.txt"), "one\n");
        final var body = new Parser().parseBody(new Reader(List.of("""
                ....
                include::one.txt[] trailing
                after
                ....
                """.split("\n"))), ContentResolver.of(work));
        assertEquals(List.of(new Listing("include::one.txt[] trailing\nafter", null)), body.children());
    }

    @Test
    void includeInTextOnlyAsAWholeLine(@TempDir final Path work) throws IOException { // text around it, single colon, space before the brackets
        Files.writeString(work.resolve("one.txt"), "one\n");
        final var body = new Parser().parseBody(new Reader(List.of("""
                text include::one.txt[] more

                include::one.txt[] trailing

                include:one.txt[]

                include::one.txt []
                """.split("\n"))), ContentResolver.of(work));
        assertEquals(
                List.of(
                        new Text(List.of(), "text include::one.txt[] more", Map.of()),
                        new Text(List.of(), "include::one.txt[] trailing", Map.of()),
                        new Text(List.of(), "include:one.txt[]", Map.of()),
                        new Text(List.of(), "include::one.txt []", Map.of())),
                body.children());
    }

    @Test
    void includesInACompleteDocument(@TempDir final Path work) throws IOException {
        // the document must parse as the same document with each directive replaced by what it includes,
        // the lines which are not a directive staying as they are: the flattened document is parsed without any file
        // so a line read as a directive by mistake fails
        Files.writeString(work.resolve("one.txt"), "one\n");
        Files.writeString(work.resolve("two.txt"), "two\n");
        Files.writeString(work.resolve("intro.adoc"), "Included paragraph.\n");
        Files.writeString(work.resolve("section.adoc"), "== Included\n\nSection text.\n");
        Files.writeString(work.resolve("code.java"), "a();\n// tag::body[]\nb();\n// end::body[]\nc();\n");
        Files.createDirectories(work.resolve("sub"));
        Files.writeString(work.resolve("sub/nested.txt"), "nested\ninclude::sibling.txt[]\n");
        Files.writeString(work.resolve("sub/sibling.txt"), "sibling\n");
        final var document = """
                = Include directives
                :partial: one.txt

                include::intro.adoc[]

                == Text

                A first line
                include::one.txt[]
                and a last line.

                text include::one.txt[] more

                include::one.txt[] trailing

                include:one.txt[]

                include::one.txt []

                include::[]

                include::{partial}[]

                * item
                +
                include::one.txt[]

                ====
                include::one.txt[]
                ====

                include::section.adoc[leveloffset=+1]

                == Code

                [source,java]
                ----
                include::one.txt[]
                middle
                include::two.txt[]  \s
                include::code.java[tag=body]
                  include::one.txt[]
                x(); <1>
                text include::one.txt[] more
                include::one.txt[] <2>
                include::{partial}[]
                include::sub/nested.txt[]
                include::one.txt []
                include::[]
                include::two.txt[]
                ----
                <1> A callout after includes.
                <2> A marker after a directive.

                ....
                include::one.txt[] trailing
                text include::one.txt[] more
                ....

                ++++
                include::one.txt[] trailing
                include::[]
                ++++
                """;
        final var flattened = """
                = Include directives
                :partial: one.txt

                Included paragraph.

                == Text

                A first line
                one
                and a last line.

                text include::one.txt[] more

                include::one.txt[] trailing

                include:one.txt[]

                include::one.txt []

                include::[]

                one

                * item
                +
                one

                ====
                one
                ====

                === Included

                Section text.

                == Code

                [source,java]
                ----
                one
                middle
                two
                b();
                  include::one.txt[]
                x(); <1>
                text include::one.txt[] more
                include::one.txt[] <2>
                one
                nested
                sibling
                include::one.txt []
                include::[]
                two
                ----
                <1> A callout after includes.
                <2> A marker after a directive.

                ....
                include::one.txt[] trailing
                text include::one.txt[] more
                ....

                ++++
                include::one.txt[] trailing
                include::[]
                ++++
                """;
        assertEquals(
                new Parser().parse(flattened, new Parser.ParserContext(ContentResolver.of(work.resolve("missing")))).body(),
                new Parser().parse(document, new Parser.ParserContext(ContentResolver.of(work))).body());
    }

    @Test
    void escapedIncludeInText(@TempDir final Path work) throws IOException { // as asciidoctor, only a whole line is a directive
        Files.writeString(work.resolve("content.properties"), "test = value\n");
        final var body = new Parser().parseBody(new Reader(List.of("a line with \\include::content.properties[] in it")), ContentResolver.of(work));
        assertEquals(
                List.of(new Text(List.of(), "a line with \\include::content.properties[] in it", Map.of())),
                body.children());
    }

    @Test
    void escapedIncludeLineInText(@TempDir final Path work) throws IOException { // documenting the directive: the backslash goes
        Files.writeString(work.resolve("content.properties"), "test = value\n");
        final var body = new Parser().parseBody(new Reader(List.of("before", "\\include::content.properties[]", "after")), ContentResolver.of(work));
        assertEquals(
                List.of(new Text(List.of(), "before include::content.properties[] after", Map.of())),
                body.children());
    }

    @Test
    void codeIncludeNested(@TempDir final Path work) throws IOException {
        final var code = "foo::\nbar\ndummy::\nsomething\n[source]\n----\ntest\n\n----\n\nother::\nend";
        Files.writeString(work.resolve("content.properties"), code);
        final var body = new Parser(Map.of("partialsdir", work.toString())).parseBody(new Reader(List.of("""
                include::{partialsdir}/content.properties[]
                """.split("\n"))), ContentResolver.of(work));
        assertEquals(
                List.of(new Paragraph(List.of(
                        new DescriptionList(Map.of(
                                new Text(List.of(), "foo", Map.of()),
                                new Text(List.of(), "bar", Map.of()),
                                new Text(List.of(), "dummy", Map.of()),
                                new Paragraph(List.of(
                                        new Text(List.of(), "something", Map.of()),
                                        new Code("test\n\n", Map.of(), false, List.of())
                                ), Map.of())
                        ), Map.of()),
                        new DescriptionList(Map.of(
                                new Text(List.of(), "other", Map.of()),
                                new Text(List.of(), "end", Map.of())
                        ), Map.of())
                ), Map.of())),
                body.children());
    }

    @Test
    void codeCalloutsWithAttachedBlocks() { // `+` attaches the next block to the item, read as for any list item
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,properties]
                ----
                a=b <1>
                c=d <2>
                ----
                <1> one, like
                +
                [source,json]
                ----
                {"a": "b"}

                {"c": "d"}
                ----
                <2> two, and
                +
                more text attached

                after
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Code("a=b\nc=d\n", Map.of("language", "properties"), false, List.of(
                                List.of(new CallOut(1, new Paragraph(List.of(
                                        new Text(List.of(), "one, like", Map.of()),
                                        new Code("{\"a\": \"b\"}\n\n{\"c\": \"d\"}\n", Map.of("language", "json"), false, List.of())), Map.of()))),
                                List.of(new CallOut(2, new Paragraph(List.of(
                                        new Text(List.of(), "two, and", Map.of()),
                                        new Text(List.of(), "more text attached", Map.of())), Map.of()))))),
                        new Text(List.of(), "after", Map.of())),
                body.children());
    }

    @Test
    void codeCalloutsMismatchFails() {
        final var reader = new Reader(List.of("""
                [source,properties]
                ----
                a=b <1>
                c=d <2>
                ----

                1. one
                2. two
                """.split("\n")));
        final var error = assertThrows(IllegalArgumentException.class, () -> new Parser().parseBody(reader, null));
        assertTrue(error.getMessage().startsWith("Invalid callout references"), error::getMessage);
    }

    @Test
    void codeCalloutsMismatchIgnoredOnDemand() { // :callout-mismatch: ignore keeps the document, as asciidoctor does
        final var body = new Parser().parseBody(new Reader(List.of("""
                :callout-mismatch: ignore

                [source,properties]
                ----
                a=b <1>
                c=d <2>
                ----

                1. one
                2. two

                after
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Code("a=b\nc=d\n", Map.of("language", "properties"), false, List.of(List.of(), List.of())),
                        new OrderedList(List.of(
                                new Text(List.of(), "one", Map.of()),
                                new Text(List.of(), "two", Map.of())), Map.of("style", "arabic")),
                        new Text(List.of(), "after", Map.of())),
                body.children());
    }

    @Test
    void codeCalloutItemWithoutMarkerFails() { // the opposite case of a marker without an item
        final var reader = new Reader(List.of("""
                [source,java]
                ----
                a(); <1>
                b();
                ----
                <1> One.
                <2> Two.
                """.split("\n")));
        final var error = assertThrows(IllegalArgumentException.class, () -> new Parser().parseBody(reader, null));
        assertTrue(error.getMessage().startsWith("Invalid callout references"), error::getMessage);
    }

    @Test
    void codeCalloutItemWithoutMarkerIgnoredOnDemand() { // the document is kept, the item has no line to sit on
        final var body = new Parser().parseBody(new Reader(List.of("""
                :callout-mismatch: ignore

                [source,java]
                ----
                a(); <1>
                b();
                ----
                <1> One.
                <2> Two.
                """.split("\n"))), null);
        final var one = new CallOut(1, new Text(List.of(), "One.", Map.of()));
        assertEquals(
                List.of(new Code("a();\nb();\n", Map.of("language", "java"), false, List.of(List.of(one), List.of()))),
                body.children());
    }

    @Test
    void codeWithCallout() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,java,.hljs]
                ----
                import anything;
                public record Foo( <1>
                  String name <2>
                ) {
                }
                ----
                                
                <1> Defines a record,
                <.> Defines an attribute of the record.
                """.split("\n"))), null);
        final var record = new CallOut(1, new Text(List.of(), "Defines a record,", Map.of()));
        final var attribute = new CallOut(2, new Text(List.of(), "Defines an attribute of the record.", Map.of()));
        assertEquals( // the markers are gone from the code, lineCallOuts says where they were
                List.of(new Code("""
                        import anything;
                        public record Foo(
                          String name
                        ) {
                        }
                        """, Map.of("language", "java", "role", "hljs"), false, List.of(List.of(), List.of(record), List.of(attribute), List.of(), List.of()))),
                body.children());
    }

    @Test
    void codeWithCalloutsDisabledBySubs() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,java,subs="-callouts"]
                ----
                foo(); // <1>
                ----

                <1> not a callout, the block dropped that substitution.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Code("foo(); // <1>\n", Map.of("language", "java", "subs", "-callouts"), false, List.of()),
                        new Text(List.of(), "<1> not a callout, the block dropped that substitution.", Map.of())),
                body.children());
    }

    @Test
    void codeWithCalloutsDisabledByASubsListWithoutThem() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,java,subs="attributes"]
                ----
                foo(); // <1>
                ----

                <1> not a callout, this list replaces the default substitutions.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Code("foo(); // <1>\n", Map.of("language", "java", "subs", "attributes"), false, List.of()),
                        new Text(List.of(), "<1> not a callout, this list replaces the default substitutions.", Map.of())),
                body.children());
    }

    @Test
    void codeWithCalloutsKeptByAnIncrementalSubs() { // +attributes adds to the verbatim defaults, so the callout stays and the attribute is replaced
        final var body = new Parser(Map.of("foo", "bar")).parseBody(new Reader(List.of("""
                [source,java,subs="+attributes"]
                ----
                foo("{foo}"); // <1>
                ----

                <1> a callout, the defaults are only extended.
                """.split("\n"))), null);
        assertEquals(
                List.of(new Code(
                        "foo(\"bar\"); //\n",
                        Map.of("language", "java", "subs", "+attributes"), false,
                        List.of(List.of(new CallOut(1, new Text(List.of(), "a callout, the defaults are only extended.", Map.of())))))),
                body.children());
    }

    @Test
    void codeWithNormalSubs() { // normal contains attributes but not callouts
        final var body = new Parser(Map.of("foo", "bar")).parseBody(new Reader(List.of("""
                [source,java,subs="normal"]
                ----
                foo("{foo}"); // <1>
                ----

                <1> not a callout, the normal group has no callout substitution.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Code("foo(\"bar\"); // <1>\n", Map.of("language", "java", "subs", "normal"), false, List.of()),
                        new Text(List.of(), "<1> not a callout, the normal group has no callout substitution.", Map.of())),
                body.children());
    }

    @Test
    void codeWithSeveralCalloutsOnTheSameLine() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [source,properties]
                ----
                quarkus.arc.exclude-types=org.acme.Foo,org.acme.*,Bar <1><2><3>
                ----
                                
                <1> a class,
                <2> a package,
                <3> a pattern.
                """.split("\n"))), null);
        assertEquals(
                List.of(new Code(
                        "quarkus.arc.exclude-types=org.acme.Foo,org.acme.*,Bar\n",
                        Map.of("language", "properties"), false,
                        List.of(List.of(
                                new CallOut(1, new Text(List.of(), "a class,", Map.of())),
                                new CallOut(2, new Text(List.of(), "a package,", Map.of())),
                                new CallOut(3, new Text(List.of(), "a pattern.", Map.of())))))),
                body.children());
    }

    @Test
    void unorderedList() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                * item 1
                * item 2
                """.split("\n"))), null);
        assertEquals(List.of(
                        new UnOrderedList(
                                List.of(
                                        new Text(List.of(), "item 1", Map.of()),
                                        new Text(List.of(), "item 2", Map.of())),
                                Map.of())),
                body.children());
    }

    @Test
    void unorderedListWithDot() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                * .item 1
                * .item 2
                """.split("\n"))), null);
        assertEquals(List.of(
                        new UnOrderedList(
                                List.of(
                                        new Text(List.of(), ".item 1", Map.of()),
                                        new Text(List.of(), ".item 2", Map.of())),
                                Map.of())),
                body.children());
    }

    @Test
    void unorderedListUnCommonFormatting() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                * something:
                  Some description.
                ** Parameters:
                  *** --resolve-provider: ...
                  *** --resolve-relaxed: ...
                """.split("\n"))), null);
        assertEquals(List.of(
                        new UnOrderedList(
                                List.of(
                                        new Paragraph(List.of(
                                                new Text(List.of(), "something: Some description.", Map.of()),
                                                new UnOrderedList(List.of(
                                                        new Paragraph(List.of(
                                                                new Text(List.of(), "Parameters:", Map.of()),
                                                                new UnOrderedList(List.of(
                                                                        new Text(List.of(), "--resolve-provider: ...", Map.of()),
                                                                        new Text(List.of(), "--resolve-relaxed: ...", Map.of())
                                                                ), Map.of())), Map.of())), Map.of())), Map.of())),
                                Map.of())),
                body.children());
    }

    @Test
    void orderedList() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                . item 1
                2. item 2
                """.split("\n"))), null);
        assertEquals(List.of(
                        new OrderedList(
                                List.of(
                                        new Text(List.of(), "item 1", Map.of()),
                                        new Text(List.of(), "item 2", Map.of())),
                                Map.of())),
                body.children());
    }

    @Test
    void orderedListWithCode() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                . item 1
                +
                [source,java]
                ----
                record Foo() {}
                ----
                +
                2. item 2
                +
                """.split("\n"))), null);
        assertEquals(List.of(
                        new OrderedList(List.of(
                                new Paragraph(
                                        List.of(
                                                new Text(List.of(), "item 1", Map.of()),
                                                new Code("record Foo() {}\n", Map.of("language", "java"), false, List.of())),
                                        Map.of()),
                                new Text(List.of(), "item 2", Map.of())), Map.of())),
                body.children());
    }

    @Test
    void orderedListNested() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                . item 1
                .. item 1 1
                .. item 1 2
                2. item 2
                .. item 2 1
                """.split("\n"))), null);
        assertEquals(List.of(
                        new OrderedList(
                                List.of(
                                        new Paragraph(List.of(
                                                new Text(List.of(), "item 1", Map.of()),
                                                new OrderedList(
                                                        List.of(
                                                                new Text(List.of(), "item 1 1", Map.of()),
                                                                new Text(List.of(), "item 1 2", Map.of())),
                                                        Map.of())),
                                                Map.of()),
                                        new Paragraph(List.of(
                                                new Text(List.of(), "item 2", Map.of()),
                                                new OrderedList(
                                                        List.of(
                                                                new Text(List.of(), "item 2 1", Map.of())),
                                                        Map.of())),
                                                Map.of())),
                                Map.of())),
                body.children());
    }

    @Test
    void unOrderedListNested() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [.iconed]
                * item 1
                ** item 1 1
                ** item 1 2
                * item 2
                ** item 2 1
                """.split("\n"))), null);
        assertEquals(List.of(
                        new UnOrderedList(
                                List.of(
                                        new Paragraph(List.of(
                                                new Text(List.of(), "item 1", Map.of()),
                                                new UnOrderedList(
                                                        List.of(
                                                                new Text(List.of(), "item 1 1", Map.of()),
                                                                new Text(List.of(), "item 1 2", Map.of())),
                                                        Map.of())),
                                                Map.of()),
                                        new Paragraph(List.of(
                                                new Text(List.of(), "item 2", Map.of()),
                                                new UnOrderedList(
                                                        List.of(
                                                                new Text(List.of(), "item 2 1", Map.of())),
                                                        Map.of())),
                                                Map.of())),
                                Map.of("role", "iconed"))),
                body.children());
    }

    @Test
    void orderedListMultiLine() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                . item 1
                with continuation
                2. item 2
                """.split("\n"))), null);
        assertEquals(List.of(
                        new OrderedList(
                                List.of(
                                        new Text(List.of(), "item 1 with continuation", Map.of()),
                                        new Text(List.of(), "item 2", Map.of())),
                                Map.of())),
                body.children());
    }

    @Test
    void orderedListLowerAlpha() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                a. item 1
                b. item 2""".split("\n"))), null);
        assertEquals(List.of(
                        new OrderedList(
                                List.of(
                                        new Text(List.of(), "item 1", Map.of()),
                                        new Text(List.of(), "item 2", Map.of())),
                                Map.of("style", "loweralpha"))),
                body.children());
    }

    @Test
    void orderedListUpperAlpha() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                A. item 1
                B. item 2""".split("\n"))), null);
        assertEquals(List.of(
                        new OrderedList(
                                List.of(
                                        new Text(List.of(), "item 1", Map.of()),
                                        new Text(List.of(), "item 2", Map.of())),
                                Map.of("style", "upperalpha"))),
                body.children());
    }

    @Test
    void orderedListLowerRoman() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                i) item 1
                ii) item 2""".split("\n"))), null);
        assertEquals(List.of(
                        new OrderedList(
                                List.of(
                                        new Text(List.of(), "item 1", Map.of()),
                                        new Text(List.of(), "item 2", Map.of())),
                                Map.of("style", "lowerroman"))),
                body.children());
    }

    @Test
    void orderedListUpperRoman() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                I) item 1
                II) item 2""".split("\n"))), null);
        assertEquals(List.of(
                        new OrderedList(
                                List.of(
                                        new Text(List.of(), "item 1", Map.of()),
                                        new Text(List.of(), "item 2", Map.of())),
                                Map.of("style", "upperroman"))),
                body.children());
    }

    @Test
    void unorderedListWithTitle() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                .Foo
                * item 1
                * item 2
                """.split("\n"))), null);
        assertEquals(List.of(
                        new UnOrderedList(
                                List.of(
                                        new Text(List.of(), "item 1", Map.of()),
                                        new Text(List.of(), "item 2", Map.of())),
                                Map.of("title", "Foo"))),
                body.children());
    }

    @Test
    void attributeReferencesInBlockTitlesAndAttributeLines() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                :product: Quarkus
                :lang: properties

                .{product} configuration
                [source,{lang}]
                ----
                quarkus.http.port=8081
                ----

                :product: Quarkus REST

                .{product} endpoints, see {missing}
                * /hello
                """.split("\n"))), null);
        assertEquals(List.of(
                        new Code("quarkus.http.port=8081\n", Map.of("language", "properties", "title", "Quarkus configuration"), false, List.of()),
                        new UnOrderedList(
                                List.of(new Text(List.of(), "/hello", Map.of())),
                                Map.of("title", "Quarkus REST endpoints, see {missing}"))),
                body.children());
    }

    @Test
    void descriptionList() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                CPU:: The brain of the computer.
                Hard drive:: Permanent storage for operating system and/or user files.
                RAM:: Temporarily stores information the CPU uses during operation.
                """.split("\n"))), null);
        assertEquals(List.of(
                        new DescriptionList(Stream.of(
                                        entry("CPU", new Text(List.of(), "The brain of the computer.", Map.of())),
                                        entry("Hard drive", new Text(List.of(), "Permanent storage for operating system and/or user files.", Map.of())),
                                        entry("RAM", new Text(List.of(), "Temporarily stores information the CPU uses during operation.", Map.of())))
                                .collect(toMap(e -> new Text(List.of(), e.getKey(), Map.of()), Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)),
                                Map.of())),
                body.children());
    }

    @Test
    void descriptionListWithSemicolons() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                CPU;; The brain of the computer.
                Hard drive;; Permanent storage for operating system and/or user files.
                RAM;; Temporarily stores information the CPU uses during operation.
                """.split("\n"))), null);
        assertEquals(List.of(
                        new DescriptionList(Stream.of(
                                        entry("CPU", new Text(List.of(), "The brain of the computer.", Map.of())),
                                        entry("Hard drive", new Text(List.of(), "Permanent storage for operating system and/or user files.", Map.of())),
                                        entry("RAM", new Text(List.of(), "Temporarily stores information the CPU uses during operation.", Map.of())))
                                .collect(toMap(e -> new Text(List.of(), e.getKey(), Map.of()), Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)),
                                Map.of())),
                body.children());
    }

    @Test
    void descriptionListWithList() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                Dairy::
                * Milk
                * Eggs
                Bakery::
                * Bread
                Produce::
                * Bananas""".split("\n"))), null);
        assertEquals(List.of(new DescriptionList(Stream.of(
                                entry("Dairy", new UnOrderedList(List.of(
                                        new Text(List.of(), "Milk", Map.of()),
                                        new Text(List.of(), "Eggs", Map.of())),
                                        Map.of()
                                )),
                                entry("Bakery", new UnOrderedList(List.of(
                                        new Text(List.of(), "Bread", Map.of())),
                                        Map.of())),
                                entry("Produce", new UnOrderedList(List.of(
                                        new Text(List.of(), "Bananas", Map.of())),
                                        Map.of()
                                )))
                        .collect(toMap(e -> new Text(List.of(), e.getKey(), Map.of()), Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)),
                        Map.of())),
                body.children());
    }

    @Test
    void descriptionListOptionsWithoutDocumentAttributes() {
        final var doc = new Parser().parse("""
                [id=user-guide]
                = User guide
                :title: Custom title
                :role: guide

                Terms:

                CPU:: The brain of the computer.
                RAM:: Temporary storage.

                .Shortcuts
                [horizontal,role=keys]
                Save:: Ctrl+S
                """, new Parser.ParserContext(null));
        assertEquals(
                List.of("user-guide", "Custom title", "guide"),
                Stream.of("id", "title", "role").map(doc.header().attributes()::get).toList());
        assertEquals(List.of(
                        new Text(List.of(), "Terms:", Map.of()),
                        new DescriptionList(Map.of(
                                new Text(List.of(), "CPU", Map.of()), new Text(List.of(), "The brain of the computer.", Map.of()),
                                new Text(List.of(), "RAM", Map.of()), new Text(List.of(), "Temporary storage.", Map.of())),
                                Map.of()),
                        new DescriptionList(Map.of(
                                new Text(List.of(), "Save", Map.of()), new Text(List.of(), "Ctrl+S", Map.of())),
                                Map.of("", "horizontal", "role", "keys", "title", "Shortcuts"))),
                doc.body().children());
    }

    @Test
    void image() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                image:test.png[Test]
                                
                It is inline like image:foo.svg[Bar] or
                                
                image::as-a-block.jpg[Foo,width="100%"]
                                
                """.split("\n"))), null);
        assertEquals(List.of(
                        new Macro("image", "test.png", Map.of("", "Test"), true),
                        new Paragraph(List.of(
                                new Text(List.of(), "It is inline like ", Map.of()),
                                new Macro("image", "foo.svg", Map.of("", "Bar"), true),
                                new Text(List.of(), " or", Map.of())), Map.of()),
                        new Macro("image", "as-a-block.jpg", Map.of("", "Foo", "width", "100%"), false)),
                body.children());
    }

    @Test
    void imageWithLink() {
        var body = new Parser().parseBody(new Reader(List.of("""
            image::test.jpg[Foo,link="www.website.com"]
            """.split("\n"))), null);

        assertEquals(List.of(
                       new Macro("image", "test.jpg", Map.of("", "Foo", "link", "www.website.com"), false)),
                     body.children());

        var body2 = new Parser().parseBody(new Reader(List.of("""
            [link="www.website.com"]
            image::test.jpg[Foo]
            """.split("\n"))), null);

        assertEquals(List.of(
                       new Macro("image", "test.jpg", Map.of("", "Foo", "link", "www.website.com"), false)),
                     body2.children());
    }

    @Test
    void imageWithLinkBlank() {
        var body = new Parser().parseBody(new Reader(List.of("""
            image::test.jpg[Foo,link="www.website.com",window=_blank]
            """.split("\n"))), null);
    }

    @Test
    void admonition() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                WARNING: Wolpertingers are known to nest in server racks.
                Enter at your own risk.
                """.split("\n"))), null);
        assertEquals(
                List.of(new Admonition(WARNING, new Text(
                        List.of(),
                        "Wolpertingers are known to nest in server racks. Enter at your own risk.",
                        Map.of()), Map.of())),
                body.children());
    }

    @Test
    void admonitionBlock() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [WARNING]
                ====
                Wolpertingers are known to nest in server racks.
                Enter at your own risk.
                ====
                """.split("\n"))), null);
        assertEquals(
                List.of(new Admonition(WARNING, new Text(
                        List.of(),
                        "Wolpertingers are known to nest in server racks. Enter at your own risk.",
                        Map.of()), Map.of())),
                body.children());
    }

    @Test
    void escapedAttributeReferences() { // as asciidoctor, \\{name} is written {name} and {name} is substituted
        final var body = new Parser().parseBody(new Reader(List.of("""
                :name: value

                Text \\{name} and {name}, `\\{name}/file` and `+\\{name}+`.

                * item \\{name} {name\\} \\{name\\} {name} `%X\\{mdc-key\\}` `\\{not.a.name}`

                link:https://yupiik.io/\\{name}[a \\{name}]

                .Title with \\{name} and {name}
                [source,text,subs="attributes+"]
                ----
                \\{name} {name}
                ----

                [role="\\{name}"]
                Role.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Paragraph(List.of(
                                new Text(List.of(), "Text {name} and value, ", Map.of()),
                                new Code("{name}/file", Map.of(), true, List.of()),
                                new Text(List.of(), " and ", Map.of()),
                                new Code("+\\{name}+", Map.of(), true, List.of()),
                                new Text(List.of(), ".", Map.of())), Map.of()),
                        new UnOrderedList(List.of(new Paragraph(List.of(
                                new Text(List.of(), "item {name} {name} {name} value ", Map.of()),
                                new Code("%X{mdc-key}", Map.of(), true, List.of()),
                                new Text(List.of(), " ", Map.of()),
                                new Code("\\{not.a.name}", Map.of(), true, List.of())), Map.of())), Map.of()),
                        new Link("https://yupiik.io/{name}", new Text(List.of(), "a {name}", Map.of("", "a \\{name}", "nowrap", "true")), Map.of("", "a \\{name}", "nowrap", "true")),
                        new Code("{name} value\n", Map.of("title", "Title with {name} and value", "language", "text", "subs", "attributes+"), false, List.of()),
                        new Text(List.of(), "Role.", Map.of("role", "{name}"))),
                body.children());
    }

    @Test
    void escapedAttributeReferenceInATitleAttribute() { // asciidoctor substitutes the title again when it writes the block
        final var body = new Parser().parseBody(new Reader(List.of("""
                :name: value

                [source,java,title="A \\{name} B {name}"]
                ----
                run();
                ----
                """.split("\n"))), null);
        assertEquals(
                List.of(new Code("run();\n", Map.of("title", "A value B value", "language", "java"), false, List.of())),
                body.children());
    }

    @Test
    void anchor() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                The section <<anchors>> describes how automatic anchors work.
                """.split("\n"))), null);
        assertEquals(
                List.of(new Paragraph(List.of(
                        new Text(List.of(), "The section ", Map.of()),
                        new Anchor("anchors", ""),
                        new Text(List.of(), " describes how automatic anchors work.", Map.of())
                ), Map.of())),
                body.children());
    }

    @Test
    void titleId() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                == Create a configuration model [[configuration_model]]
                                
                A configuration model is a record marked with RootConfiguration.
                """.split("\n"))), null);
        assertEquals(
                List.of(new Section(
                        2,
                        new Text(List.of(), "Create a configuration model", Map.of("id", "configuration_model")),
                        List.of(new Text(List.of(), "A configuration model is a record marked with RootConfiguration.", Map.of())),
                        Map.of())),
                body.children());
    }

    @Test
    void blockAnchors() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [[install]]
                == Install

                [[install-command, Install command]]
                [source,bash]
                ----
                quarkus create app
                ----

                [[configuration]]
                [NOTE]
                ====
                Configure it.
                ====

                [[1st]]
                == First steps

                Text.
                """.split("\n"))), null);
        assertEquals(
                new Section(
                        2,
                        new Text(List.of(), "Install", Map.of()),
                        List.of(
                                new Code("quarkus create app\n", Map.of("id", "install-command", "reftext", "Install command", "language", "bash"), false, List.of()),
                                new Admonition(NOTE, new Text(List.of(), "Configure it.", Map.of()), Map.of("id", "configuration"))),
                        Map.of("id", "install")),
                body.children().get(0));
        assertNull(((Section) body.children().get(1)).options().get("id")); // as asciidoctor, an id does not start with a digit
    }

    @Test
    void include() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        == My title
                                        
                        include::foo.adoc[]
                                        
                        include::bar.adoc[lines=2..3]
                                        
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "foo.adoc" -> Optional.of(List.of("This is foo."));
                    case "bar.adoc" ->
                            Optional.of(List.of("This is ignored.", "First included line.", "Last included line.", "Ignored again."));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Section(2, new Text(List.of(), "My title", Map.of()), List.of(
                        new Text(List.of(), "This is foo.", Map.of()),
                        new Text(List.of(), "First included line. Last included line.", Map.of())),
                        Map.of())),
                body.children());
    }

    @Test
    void includeAttributes() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        = My title
                        include::attributes.adoc[]
                                        
                        {url}[Yupiik]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "attributes.adoc" -> Optional.of(List.of(":url: https://yupiik.io"));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Section(
                        1, new Text(List.of(), "My title", Map.of()),
                        List.of(new Link("https://yupiik.io", "Yupiik", Map.of())), Map.of())),
                body.children());
    }

    @Test
    void includeAttributesAfterAttributes() {
        final var doc = new Parser().parse(
                """
                        = My title
                        :title: Yupiik
                        include::attributes.adoc[]
                        
                        {url}[{title}]
                        """,
                new Parser.ParserContext(
                        (ref, encoding) -> switch (ref) {
                            case "attributes.adoc" -> Optional.of(List.of(":url: https://yupiik.io"));
                            default -> Optional.empty();
                        }));
        assertEquals(Map.of("title", "Yupiik", "url", "https://yupiik.io", "authorcount", "0"), doc.header().attributes());

        assertEquals(
                List.of(new Link("https://yupiik.io", new Text(List.of(), "Yupiik", Map.of("", "Yupiik", "nowrap", "true")), Map.of())),
                doc.body().children());
    }

    @Test
    void includeAttributesBeforeAttributes() {
        final var doc = new Parser().parse(
                """
                        = My title
                        include::attributes.adoc[]
                        :title: Yupiik
                        
                        {url}[{title}]
                        """,
                new Parser.ParserContext(
                        (ref, encoding) -> switch (ref) {
                            case "attributes.adoc" -> Optional.of(List.of(":url: https://yupiik.io"));
                            default -> Optional.empty();
                        }));
        assertEquals(Map.of("title", "Yupiik", "url", "https://yupiik.io", "authorcount", "0"), doc.header().attributes());

        assertEquals(
                List.of(new Link("https://yupiik.io", "Yupiik", Map.of())),
                doc.body().children());
    }

    @Test
    void table() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        [cols="1,1"]
                        |===
                        |Cell in column 1, row 1
                        |Cell in column 2, row 1

                        |Cell in column 1, row 2
                        |Cell in column 2, row 2

                        |Cell in column 1, row 3
                        |Cell in column 2, row 3
                        |===                    
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Text(List.of(), "Cell in column 1, row 1", Map.of()),
                                new Text(List.of(), "Cell in column 2, row 1", Map.of())),
                        List.of(
                                new Text(List.of(), "Cell in column 1, row 2", Map.of()),
                                new Text(List.of(), "Cell in column 2, row 2", Map.of())),
                        List.of(
                                new Text(List.of(), "Cell in column 1, row 3", Map.of()),
                                new Text(List.of(), "Cell in column 2, row 3", Map.of()))
                ), Map.of("cols", "1,1"))),
                body.children());
    }

    @Test
    void tableOpts() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        [opts="header"]
                        |===
                        |c1
                        |===
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Table(List.of(List.of(new Text(List.of(), "c1", Map.of()))), Map.of("opts", "header"))),
                body.children());
    }

    @Test
    void tableMultiple() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        [cols="1a,1"]
                        |===
                        |Cell in column 1, row 1
                        [source,java]
                        ----
                        public class Foo {
                        }
                        ----
                        |Cell in column 2, row 1

                        |Cell in column 1, row 2
                        |Cell in column 2, row 2
                        |===                    
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Paragraph(List.of(
                                        new Text(List.of(), "Cell in column 1, row 1", Map.of()),
                                        new Code("public class Foo {\n}\n", Map.of("language", "java"), false, List.of())
                                ), Map.of()),
                                new Text(List.of(), "Cell in column 2, row 1", Map.of())),
                        List.of(
                                new Text(List.of(), "Cell in column 1, row 2", Map.of()),
                                new Text(List.of(), "Cell in column 2, row 2", Map.of()))
                ), Map.of("cols", "1a,1"))),
                body.children());
    }

    @Test
    void tableRowsInline() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        [cols="1,1"]
                        |===
                        |Cell in column 1, row 1|Cell in column 2, row 1
                        |Cell in column 1, row 2|Cell in column 2, row 2
                        |Cell in column 1, row 3|Cell in column 2, row 3
                        |===
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Text(List.of(), "Cell in column 1, row 1", Map.of()),
                                new Text(List.of(), "Cell in column 2, row 1", Map.of())),
                        List.of(
                                new Text(List.of(), "Cell in column 1, row 2", Map.of()),
                                new Text(List.of(), "Cell in column 2, row 2", Map.of())),
                        List.of(
                                new Text(List.of(), "Cell in column 1, row 3", Map.of()),
                                new Text(List.of(), "Cell in column 2, row 3", Map.of()))
                ), Map.of("cols", "1,1"))),
                body.children());
    }

    @Test
    void tableWithEscapedPipeInline() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        [cols="1,1"]
                        |===
                        |Red|cat \\| dog
                        |Blue|fish \\| turtle
                        |===
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Text(List.of(), "Red", Map.of()),
                                new Text(List.of(), "cat | dog", Map.of())),
                        List.of(
                                new Text(List.of(), "Blue", Map.of()),
                                new Text(List.of(), "fish | turtle", Map.of()))
                ), Map.of("cols", "1,1"))),
                body.children());
    }

    @Test
    void tableWithEscapedPipeNoColspec() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        |===
                        |Red|cat \\| dog
                        |Blue|fish \\| turtle
                        |===
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Text(List.of(), "Red", Map.of()),
                                new Text(List.of(), "cat | dog", Map.of())),
                        List.of(
                                new Text(List.of(), "Blue", Map.of()),
                                new Text(List.of(), "fish | turtle", Map.of()))
                ), Map.of())),
                body.children());
    }

    @Test
    void tableCellSpecifierStyles() { // the style of the cell specifier wins over the column style, as in asciidoctor
        final var body = new Parser().parseBody(new Reader(List.of("""
                [cols="3*"]
                |===
                a|*bold* text s|strong `code` e|emphasis
                h|Row header |plain d|*default*
                |===
                """.split("\n"))), null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Paragraph(List.of(new Text(List.of(BOLD), "bold", Map.of()), new Text(List.of(), " text", Map.of())), Map.of()),
                                new Paragraph(List.of(new Text(List.of(BOLD), "strong ", Map.of()), new Code("code", Map.of(), true, List.of())), Map.of()),
                                new Text(List.of(EMPHASIS), "emphasis", Map.of())),
                        List.of(
                                new Text(List.of(), "Row header", Map.of("role", "header")),
                                new Text(List.of(), "plain", Map.of()),
                                new Text(List.of(BOLD), "default", Map.of()))
                ), Map.of("cols", "3*"))),
                body.children());
    }

    @Test
    void tableCellSpecifierAlignmentSpanAndFactor() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [cols="3*"]
                |===
                2+^.>|spans two >|right
                3*|same
                |===
                """.split("\n"))), null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Text(List.of(), "spans two", Map.of("colspan", "2", "halign", "center", "valign", "bottom")),
                                new Text(List.of(), "right", Map.of("halign", "right"))),
                        List.of(
                                new Text(List.of(), "same", Map.of()),
                                new Text(List.of(), "same", Map.of()),
                                new Text(List.of(), "same", Map.of()))
                ), Map.of("cols", "3*"))),
                body.children());
    }

    @Test
    void tableCellSpecifierAfterABlank() { // in the middle of a line, a specifier follows a blank, so the word a before a pipe is one
        final var body = new Parser().parseBody(new Reader(List.of("""
                [cols="2*"]
                |===
                |data|x
                |this is a|*b*
                |x a\\|y |z
                |===
                """.split("\n"))), null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(new Text(List.of(), "data", Map.of()), new Text(List.of(), "x", Map.of())),
                        List.of(new Text(List.of(), "this is", Map.of()), new Text(List.of(BOLD), "b", Map.of())),
                        List.of(new Text(List.of(), "x a|y", Map.of()), new Text(List.of(), "z", Map.of()))
                ), Map.of("cols", "2*"))),
                body.children());
    }

    @Test
    void tableRowsByColumnCount() { // the shape of the generated configuration and build item tables
        final var body = new Parser().parseBody(new Reader(List.of("""
                [cols="2,1"]
                |===

                h|Property
                h|Type

                a|`quarkus.foo`

                [.description]
                --
                The description.
                --
                |boolean

                a|`quarkus.bar`
                [.description]
                --
                * one
                * two

                -- a|int
                |===
                """.split("\n"))), null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Text(List.of(), "Property", Map.of("role", "header")),
                                new Text(List.of(), "Type", Map.of("role", "header"))),
                        List.of(
                                new Paragraph(List.of(
                                        new Code("quarkus.foo", Map.of(), true, List.of()),
                                        new OpenBlock(List.of(new Text(List.of(), "The description.", Map.of())), Map.of("role", "description"))), Map.of()),
                                new Text(List.of(), "boolean", Map.of())),
                        List.of(
                                new Paragraph(List.of(
                                        new Code("quarkus.bar", Map.of(), true, List.of()),
                                        new OpenBlock(List.of(new UnOrderedList(List.of(
                                                new Text(List.of(), "one", Map.of()),
                                                new Text(List.of(), "two", Map.of())), Map.of())), Map.of("role", "description"))), Map.of()),
                                new Text(List.of(), "int", Map.of()))
                ), Map.of("cols", "2,1", "noheader-option", ""))),
                body.children());
    }

    @Test
    void tableRowspanTakesAPlaceInTheNextRow() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                [cols="3*"]
                |===
                .2+h|rows |x |y
                |z |w
                |===
                """.split("\n"))), null);
        assertEquals(
                List.of(new Table(List.of(
                        List.of(
                                new Text(List.of(), "rows", Map.of("role", "header", "rowspan", "2")),
                                new Text(List.of(), "x", Map.of()),
                                new Text(List.of(), "y", Map.of())),
                        List.of(new Text(List.of(), "z", Map.of()), new Text(List.of(), "w", Map.of()))
                ), Map.of("cols", "3*"))),
                body.children());
    }

    @Test
    void tableCellOptionsOnAList() { // no wrapper: the options of the cell go to the element of the cell, whatever it is
        final var body = new Parser().parseBody(new Reader(List.of("""
                [cols="2*"]
                |===
                2+a|
                * one
                * two
                |===
                """.split("\n"))), null);
        assertEquals(
                List.of(new Table(List.of(List.of(new UnOrderedList(List.of(
                        new Text(List.of(), "one", Map.of()),
                        new Text(List.of(), "two", Map.of())), Map.of("colspan", "2")))), Map.of("cols", "2*"))),
                body.children());
    }

    @Test
    void pipeTableWithEscapedPipe() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        | Animal | Description |
                        |---|---|
                        | cat \\| dog | bark \\| meow |
                        | fish \\| turtle | chirp \\| hop |
                        """.split("\n"))),
                null);
        final var table = (Table) body.children().get(0);
        assertEquals(3, table.elements().size());
        assertEquals("cat | dog", ((Text) table.elements().get(1).get(0)).value());
        assertEquals("bark | meow", ((Text) table.elements().get(1).get(1)).value());
        assertEquals("fish | turtle", ((Text) table.elements().get(2).get(0)).value());
        assertEquals("chirp | hop", ((Text) table.elements().get(2).get(1)).value());
    }

    @Test
    void simpleQuote() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        > Somebody said it.
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Quote(List.of(new Text(List.of(), "Somebody said it.", Map.of())), Map.of())),
                body.children());
    }

    @Test
    void simpleQuoteBlockWithCitetitle() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        [quote, Albert Einstein, Relativity]
                        ____
                        A man should look for what is, and not for what he thinks should be.
                        ____
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Quote(List.of(
                        new Text(List.of(), "A man should look for what is, and not for what he thinks should be.", Map.of())
                ), Map.of("role", "quoteblock", "attribution", "Albert Einstein", "citetitle", "Relativity"))),
                body.children());
    }

    @Test
    void simpleQuoteBlock() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        [quote,Monty Python and the Holy Grail]
                        ____
                        Dennis: Come and see the violence inherent in the system. Help! Help! I'm being repressed!
                                                
                        King Arthur: Bloody peasant!
                                                
                        Dennis: Oh, what a giveaway! Did you hear that? Did you hear that, eh? That's what I'm on about! Did you see him repressing me? You saw him, Didn't you?
                        ____
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Quote(List.of(
                        new Text(List.of(), "Dennis: Come and see the violence inherent in the system. Help! Help! I'm being repressed!", Map.of()),
                        new Text(List.of(), "King Arthur: Bloody peasant!", Map.of()),
                        new Text(List.of(), "Dennis: Oh, what a giveaway! Did you hear that? Did you hear that, eh? That's what I'm on about! Did you see him repressing me? You saw him, Didn't you?", Map.of())
                ), Map.of("role", "quoteblock", "attribution", "Monty Python and the Holy Grail"))),
                body.children());
    }

    @Test
    void quote() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
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
                        > Yep. AsciiDoc and Markdown share a lot of common syntax already.
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new Quote(List.of(
                        new Quote(List.of(new Text(List.of(), "What's new?", Map.of())), Map.of()),
                        new Text(List.of(), "I've got Markdown in my AsciiDoc!", Map.of()),
                        new Quote(List.of(new Text(List.of(), "Like what?", Map.of())), Map.of()),
                        new UnOrderedList(List.of(
                                new Text(List.of(), "Blockquotes", Map.of()),
                                new Text(List.of(), "Headings", Map.of()),
                                new Text(List.of(), "Fenced code blocks", Map.of())
                        ), Map.of()),
                        new Quote(List.of(new Text(List.of(), "Is there more?", Map.of())), Map.of()),
                        new Text(List.of(), "Yep. AsciiDoc and Markdown share a lot of common syntax already.", Map.of())),
                        Map.of())),
                body.children());
    }

    @Test
    void openBlock() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        [sidebar]
                        .Related information
                        --
                        This is aside value.
                                                
                        It is used to present information related to the main content.
                        --
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new OpenBlock(List.of(
                        new Text(List.of(), "This is aside value.", Map.of()),
                        new Text(List.of(), "It is used to present information related to the main content.", Map.of())
                ), Map.of("", "sidebar", "title", "Related information"))),
                body.children());
    }

    @Test
    void ifdef() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        ifdef::foo[]
                        This is value.
                        endif::[]
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new ConditionalBlock(
                        new ConditionalBlock.Ifdef("foo"),
                        List.of(new Text(List.of(), "This is value.", Map.of())),
                        Map.of())),
                body.children());
    }

    @Test
    void inlineIfdef() { // ifdef::attr[content] has no endif::[], the lines after it are not part of the block
        final var body = new Parser().parseBody(
                new Reader(List.of("ifdef::foo[This is value.]", "After.")),
                null);
        assertEquals(
                List.of(new Paragraph(List.of(
                        new ConditionalBlock(
                                new ConditionalBlock.Ifdef("foo"),
                                List.of(new Text(List.of(), "This is value.", Map.of())),
                                Map.of()),
                        new Text(List.of(), "After.", Map.of())), Map.of())),
                body.children());
    }

    @Test
    void ifndef() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        ifndef::foo[]
                        This is value.
                        endif::[]
                        """.split("\n"))),
                null);
        assertEquals(
                List.of(new ConditionalBlock(
                        new ConditionalBlock.Ifndef("foo"),
                        List.of(new Text(List.of(), "This is value.", Map.of())),
                        Map.of())),
                body.children());
    }

    @Test
    void namedEndifAfterText() { // the named endif did not close the block, so the next sections were part of it
        final var body = new Parser().parseBody(new Reader(List.of("""
                TLS Registry also provides automatic certificate reloading
                ifndef::no-lets-encrypt[]
                , integration with Let's Encrypt (ACME)
                endif::no-lets-encrypt[]
                and compatibility with various keystore formats.

                == Using the TLS registry

                Content.
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new Paragraph(List.of(
                                new Text(List.of(), "TLS Registry also provides automatic certificate reloading", Map.of()),
                                new ConditionalBlock(
                                        new ConditionalBlock.Ifndef("no-lets-encrypt"),
                                        List.of(new Text(List.of(), ", integration with Let's Encrypt (ACME)", Map.of())),
                                        Map.of()),
                                new Text(List.of(), "and compatibility with various keystore formats.", Map.of())), Map.of()),
                        new Section(2, new Text(List.of(), "Using the TLS registry", Map.of()), List.of(new Text(List.of(), "Content.", Map.of())), Map.of())),
                body.children());
    }

    @Test
    void namedEndifAfterABlock() { // the section after the block was hidden with the block
        final var body = new Parser().parseBody(new Reader(List.of("""
                ifdef::with-code[]
                [source,java]
                ----
                run();
                ----
                endif::with-code[]

                == Next section
                """.split("\n"))), null);
        assertEquals(
                List.of(
                        new ConditionalBlock(
                                new ConditionalBlock.Ifdef("with-code"),
                                List.of(new Code("run();\n", Map.of("language", "java"), false, List.of())),
                                Map.of()),
                        new Section(2, new Text(List.of(), "Next section", Map.of()), List.of(), Map.of())),
                body.children());
    }

    @Test
    void namedEndifIgnoresTheCase() { // as asciidoctor
        final var body = new Parser().parseBody(new Reader(List.of("ifdef::Flag[]", "Content.", "endif::flag[]", "", "After.")), null);
        assertEquals(
                List.of(
                        new ConditionalBlock(new ConditionalBlock.Ifdef("Flag"), List.of(new Text(List.of(), "Content.", Map.of())), Map.of()),
                        new Text(List.of(), "After.", Map.of())),
                body.children());
    }

    @Test
    void mismatchedNamedEndifFails() { // asciidoctor logs an error and keeps the block open
        final var reader = new Reader(List.of("""
                ifdef::first[]
                ifdef::second[]
                Content.
                endif::first[]
                endif::second[]
                """.split("\n")));
        final var error = assertThrows(IllegalArgumentException.class, () -> new Parser().parseBody(reader, null));
        assertEquals("Mismatched preprocessor directive: 'endif::first[]', expected 'endif::second[]'", error.getMessage());
    }

    @Test
    void textAfterEndifFails() { // asciidoctor logs an error and keeps the block open
        final var reader = new Reader(List.of("""
                ifdef::env-github1[]
                ifdef::env-github2[]
                Content.
                endif::env-github2[] endif::env-github1[]
                """.split("\n")));
        final var error = assertThrows(IllegalArgumentException.class, () -> new Parser().parseBody(reader, null));
        assertEquals("Malformed preprocessor directive, text not permitted: 'endif::env-github2[] endif::env-github1[]'", error.getMessage());
    }

    @Test
    void ifevalInsideAConditionalBlock() { // as asciidoctor, endif::[] closes an ifeval block, which has no name
        final var body = new Parser().parseBody(new Reader(List.of("""
                ifdef::flag[]
                ifeval::[1 == 1]
                Level one.
                endif::[]
                endif::flag[]

                After.
                """.split("\n"))), null);
        assertEquals(2, body.children().size());
        final var ifeval = assertInstanceOf(ConditionalBlock.class, assertInstanceOf(ConditionalBlock.class, body.children().get(0)).children().get(0));
        assertInstanceOf(ConditionalBlock.Ifeval.class, ifeval.evaluator());
        assertEquals(List.of(new Text(List.of(), "Level one.", Map.of())), ifeval.children());
        assertEquals(new Text(List.of(), "After.", Map.of()), body.children().get(1));

        final var reader = new Reader(List.of("ifdef::flag[]", "ifeval::[1 == 1]", "Level one.", "endif::flag[]", "endif::flag[]"));
        final var error = assertThrows(IllegalArgumentException.class, () -> new Parser().parseBody(reader, null));
        assertEquals("Mismatched preprocessor directive: 'endif::flag[]', expected 'endif::[]'", error.getMessage());
    }

    @Test
    void ifevalWithTextAfterItsBracketsDoesNotOpenABlock() { // as asciidoctor, a directive is a whole line, so the ifdef block ends at its endif
        final var body = new Parser().parseBody(new Reader(List.of("""
                ifdef::flag[]
                ifeval::[1 == 1] is text
                endif::flag[]

                After.
                """.split("\n"))), null);
        assertEquals(2, body.children().size());
        assertInstanceOf(ConditionalBlock.class, body.children().get(0));
        assertEquals(new Text(List.of(), "After.", Map.of()), body.children().get(1));
    }

    @Test
    void inlineIfdefInsideAConditionalBlock() { // ifdef::attr[content] has no endif, counting it as a block made the first block hold the second one
        final var body = new Parser().parseBody(new Reader(List.of("""
                ifndef::devtools-no-maven[]
                ifdef::devtools-wrapped[+]
                [source,bash]
                ----
                ./mvnw install
                ----
                endif::[]
                ifndef::devtools-no-gradle[]
                [source,bash]
                ----
                ./gradlew build
                ----
                endif::[]
                """.split("\n"))), null);
        assertEquals(
                List.of(new Paragraph(List.of(
                        new ConditionalBlock(
                                new ConditionalBlock.Ifndef("devtools-no-maven"),
                                List.of(
                                        new ConditionalBlock(new ConditionalBlock.Ifdef("devtools-wrapped"), List.of(new Text(List.of(), "+", Map.of())), Map.of()),
                                        new Code("./mvnw install\n", Map.of("language", "bash"), false, List.of())),
                                Map.of()),
                        new ConditionalBlock(
                                new ConditionalBlock.Ifndef("devtools-no-gradle"),
                                List.of(new Code("./gradlew build\n", Map.of("language", "bash"), false, List.of())),
                                Map.of())), Map.of())),
                body.children());
    }

    @Test
    void passthrough() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        ++++
                        This is value.
                        ++++
                        """.split("\n"))),
                null);
        assertEquals(List.of(new PassthroughBlock("This is value.", Map.of())), body.children());
    }

    @Test
    void attributes() {
        final var body = new Parser().parseBody(new Reader(List.of("This is {replaced} and not this \\{value}.")), null);
        final var children = body.children();
        assertEquals(1, children.size(), children::toString);
        assertEquals(PARAGRAPH, children.get(0).type(), children::toString);
        if (children.get(0) instanceof Paragraph p) {
            assertEquals(List.of(TEXT, ATTRIBUTE, TEXT), p.children().stream().map(Element::type).toList(), children::toString);
            assertEquals("replaced", ((Attribute) p.children().get(1)).attribute(), children::toString);
            assertEquals(" and not this {value}.", ((Text) p.children().get(2)).value(), children::toString);
        }
    }

    @Test
    void icon() {
        // more "complex" since it has a space in the label
        assertEquals(
                List.of(new Macro("icon", "fas fa-foo", Map.of("size", "2x"), true)),
                new Parser().parseBody(new Reader(List.of("icon:fas fa-foo[size=2x]")), null).children());

        // no space
        assertEquals(
                List.of(new Macro("icon", "heart", Map.of("size", "2x"), true)),
                new Parser().parseBody(new Reader(List.of("icon:heart[size=2x]")), null).children());
    }

    @Test
    void hardbreak() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                Rubies are red, +
                Topazes are blue.
                """.split("\n"))), null);
        assertEquals(
                List.of(new Text(List.of(), "Rubies are red,", Map.of()),
                        new LineBreak(),
                        new Text(List.of(), "Topazes are blue.", Map.of())),
                body.children());
    }

    @Test
    void markdownBold() {
        final var body = new Parser().parseBody(new Reader(List.of("This is **bold** text.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "This is ", Map.of()),
                new Text(List.of(BOLD), "bold", Map.of()),
                new Text(List.of(), " text.", Map.of())), Map.of())), body.children());
    }

    @Test
    void markdownBoldPrecedence() {
        final var body = new Parser().parseBody(new Reader(List.of("**bold** and *italic*")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(BOLD), "bold", Map.of()),
                new Text(List.of(), " and ", Map.of()),
                new Text(List.of(BOLD), "italic", Map.of())), Map.of())), body.children());
    }

    @Test
    void inlineAnchorInTheMiddleOfALine() { // as asciidoctor, [[id]] between two words is an anchor, not text
        final var body = new Parser().parseBody(new Reader(List.of("Text [[my-anchor]] more text.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "Text ", Map.of()),
                new Text(List.of(), "", Map.of("id", "my-anchor", "anchor", "")),
                new Text(List.of(), " more text.", Map.of())), Map.of())), body.children());
    }

    @Test
    void inlineAnchorAtTheStartOfALineStaysTheIdOfTheText() { // unchanged: only the middle of the line is new
        final var body = new Parser().parseBody(new Reader(List.of("[[my-anchor]] text after a space.")), null);
        assertEquals(List.of( // one text with no style, so the parser unwraps its paragraph
                new Text(List.of(), "text after a space.", Map.of("id", "my-anchor"))), body.children());
    }

    @Test
    void inlineAnchorWithAnInvalidIdStaysText() { // as asciidoctor, an id must not start with a digit
        final var body = new Parser().parseBody(new Reader(List.of("Text [[1bad]] more text.")), null);
        assertEquals(List.of(
                new Text(List.of(), "Text [[1bad]] more text.", Map.of())), body.children());
    }

    @Test
    void bibliographyEntryIsNotReadAsAnInlineAnchor() { // [[[ref]]] keeps its own branch
        final var body = new Parser().parseBody(new Reader(List.of("See [[[ref]]] for details.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "See ", Map.of()),
                new Text(List.of(), "", Map.of("id", "ref", "bibliography", "")),
                new Text(List.of(), " for details.", Map.of())), Map.of())), body.children());
    }

    @Test
    void constrainedMarkInsideAWord() { // as asciidoctor: snake_case_name and a*b*c keep their marks, they are not emphasis
        final var body = new Parser().parseBody(new Reader(List.of("Use snake_case_name and a*b*c here.")), null);
        assertEquals(List.of( // one text with no style, so the parser unwraps its paragraph
                new Text(List.of(), "Use snake_case_name and a*b*c here.", Map.of())), body.children());
    }

    @Test
    void constrainedMarkAroundAWord() { // the same marks still open and close between words
        final var body = new Parser().parseBody(new Reader(List.of("an _italic_ and a *bold* word")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "an ", Map.of()),
                new Text(List.of(ITALIC), "italic", Map.of()),
                new Text(List.of(), " and a ", Map.of()),
                new Text(List.of(BOLD), "bold", Map.of()),
                new Text(List.of(), " word", Map.of())), Map.of())), body.children());
    }

    @Test
    void constrainedMarkSkipsAClosingMarkBeforeAWordCharacter() { // as asciidoctor, the pair closes on the next mark that can close
        final var body = new Parser().parseBody(new Reader(List.of("_italic_word and more_ here")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(ITALIC), "italic_word and more", Map.of()),
                new Text(List.of(), " here", Map.of())), Map.of())), body.children());
    }

    @Test
    void unconstrainedItalicInsideAWord() { // __text__ is unconstrained, so it emphasizes inside a word
        final var body = new Parser().parseBody(new Reader(List.of("snake__case__name here.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "snake", Map.of()),
                new Text(List.of(ITALIC), "case", Map.of()),
                new Text(List.of(), "name here.", Map.of())), Map.of())), body.children());
    }

    @Test
    void constrainedMarkAfterAClosingBrace() { // as asciidoctor, '}' does not open a pair
        final var body = new Parser().parseBody(new Reader(List.of("see }*bold* and }_italic_ here.")), null);
        assertEquals(List.of( // one text with no style, so the parser unwraps its paragraph
                new Text(List.of(), "see }*bold* and }_italic_ here.", Map.of())), body.children());
    }

    @Test
    void constrainedMarkAfterASemicolonOrAColon() { // as asciidoctor, ';' and ':' do not open a pair either
        final var body = new Parser().parseBody(new Reader(List.of("see x;*bold* and x:*bold* here.")), null);
        assertEquals(List.of(
                new Text(List.of(), "see x;*bold* and x:*bold* here.", Map.of())), body.children());
    }

    @Test
    void constrainedMarkAfterAnOpeningBrace() { // '{' is not in that list, so the pair still opens, as asciidoctor has it
        final var body = new Parser().parseBody(new Reader(List.of("see {*bold* here.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "see {", Map.of()),
                new Text(List.of(BOLD), "bold", Map.of()),
                new Text(List.of(), " here.", Map.of())), Map.of())), body.children());
    }

    @Test
    void constrainedMarksStayLiteralInAListingBlock() { // a block is still not parsed for inline marks
        final var body = new Parser().parseBody(new Reader(List.of("----", "snake_case_name and *bold*", "----")), null);
        assertEquals(List.of(new Code("snake_case_name and *bold*\n", Map.of(), false, List.of())), body.children());
    }

    @Test
    void constrainedMarksStayLiteralInALiteralBlock() { // the same for a '....' block
        final var body = new Parser().parseBody(new Reader(List.of("....", "snake_case_name and *bold*", "....")), null);
        assertEquals(List.of(new Listing("snake_case_name and *bold*", null)), body.children());
    }

    @Test
    void constrainedMarkStaysLiteralInInlineCode() { // an underscore inside backticks is still code, not emphasis
        final var body = new Parser().parseBody(new Reader(List.of("use `snake_case_name` here.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "use ", Map.of()),
                new Code("snake_case_name", Map.of(), true, List.of()),
                new Text(List.of(), " here.", Map.of())), Map.of())), body.children());
    }

    @Test
    void markdownBoldStartingWithAListMarker() { // `1. ` is a list marker for a line, not inside a bold span
        final var body = new Parser().parseBody(new Reader(List.of("**1. Bold** text.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(BOLD), "1. Bold", Map.of()),
                new Text(List.of(), " text.", Map.of())), Map.of())), body.children());

        final var title = new Parser().parseBody(new Reader(List.of("===== **1. Memory Usage Metrics**")), null);
        assertEquals(
                List.of(new Section(5, new Text(List.of(BOLD), "1. Memory Usage Metrics", Map.of()), List.of(), Map.of())),
                title.children());
    }

    @Test
    void markdownStrikethrough() {
        final var body = new Parser().parseBody(new Reader(List.of("This is ~~deleted~~ text.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "This is ", Map.of()),
                new Text(List.of(STRIKETHROUGH), "deleted", Map.of()),
                new Text(List.of(), " text.", Map.of())), Map.of())), body.children());
    }

    @Test
    void markdownLink() {
        final var body = new Parser().parseBody(new Reader(List.of("Click [here](https://example.com) now.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "Click ", Map.of()),
                new Link("https://example.com", new Text(List.of(), "here", Map.of("nowrap", "true")), Map.of("nowrap", "true")),
                new Text(List.of(), " now.", Map.of())), Map.of())), body.children());
    }

    @Test
    void markdownImage() {
        final var body = new Parser().parseBody(new Reader(List.of("Look ![alt](image.png) here.")), null);
        assertEquals(List.of(new Paragraph(List.of(
                new Text(List.of(), "Look ", Map.of()),
                new Macro("image", "image.png", Map.of("", "alt"), true),
                new Text(List.of(), " here.", Map.of())), Map.of())), body.children());
    }

    @Test
    void markdownHeading() {
        final var body = new Parser().parseBody(new Reader(List.of(
                "# Title",
                "",
                "Some content.")), null);
        assertEquals(1, body.children().size());
        assertEquals(SECTION, body.children().get(0).type());
        final var section = (Section) body.children().get(0);
        assertEquals(1, section.level());
    }

    @Test
    void markdownHeadingLevels() {
        final var body = new Parser().parseBody(new Reader(List.of(
                "# H1",
                "## H2",
                "### H3",
                "#### H4",
                "##### H5",
                "###### H6")), null);
        assertEquals(1, body.children().size());
        final var h1 = assertInstanceOf(Section.class, body.children().get(0));
        assertEquals(1, h1.level());
        assertEquals(1, h1.children().size());
        final var h2 = assertInstanceOf(Section.class, h1.children().get(0));
        assertEquals(2, h2.level());
        assertEquals(1, h2.children().size());
        final var h3 = assertInstanceOf(Section.class, h2.children().get(0));
        assertEquals(3, h3.level());
        assertEquals(1, h3.children().size());
        final var h4 = assertInstanceOf(Section.class, h3.children().get(0));
        assertEquals(4, h4.level());
        assertEquals(1, h4.children().size());
        final var h5 = assertInstanceOf(Section.class, h4.children().get(0));
        assertEquals(5, h5.level());
        assertEquals(1, h5.children().size());
        final var h6 = assertInstanceOf(Section.class, h5.children().get(0));
        assertEquals(6, h6.level());
    }

    @Test
    void markdownHeadingNotMatching() {
        final var body = new Parser().parseBody(new Reader(List.of("#notheading")), null);
        assertEquals(1, body.children().size());
        assertEquals(TEXT, body.children().get(0).type());
    }

    @Test
    void horizontalRuleDashes() {
        final var body = new Parser().parseBody(new Reader(List.of("---")), null);
        assertEquals(List.of(new HorizontalRule(Map.of())), body.children());
    }

    @Test
    void horizontalRuleAsterisks() {
        final var body = new Parser().parseBody(new Reader(List.of("***")), null);
        assertEquals(List.of(new HorizontalRule(Map.of())), body.children());
    }

    @Test
    void horizontalRuleUnderscores() {
        final var body = new Parser().parseBody(new Reader(List.of("___")), null);
        assertEquals(List.of(new HorizontalRule(Map.of())), body.children());
    }

    @Test
    void horizontalRuleApostrophes() { // the asciidoc thematic break
        final var body = new Parser().parseBody(new Reader(List.of("'''")), null);
        assertEquals(List.of(new HorizontalRule(Map.of())), body.children());
    }

    @Test
    void horizontalRuleLonger() {
        final var body = new Parser().parseBody(new Reader(List.of("-----")), null);
        assertEquals(List.of(new HorizontalRule(Map.of())), body.children());
    }

    @Test
    void horizontalRuleStopsParagraph() {
        final var body = new Parser().parseBody(new Reader(List.of(
                "Some text",
                "---",
                "More text.")), null);
        assertEquals(3, body.children().size());
        assertEquals(TEXT, body.children().get(0).type());
        assertEquals(HORIZONTAL_RULE, body.children().get(1).type());
        assertEquals(TEXT, body.children().get(2).type());
    }

    @Test
    void trailingSpacesHardBreak() {
        final var body = new Parser().parseBody(new Reader(List.of(
                "First line  ",
                "Second line")), null);
        assertEquals(1, body.children().size());
        assertEquals(PARAGRAPH, body.children().get(0).type());
        final var p = (Paragraph) body.children().get(0);
        assertEquals(List.of(
                new Text(List.of(), "First line", Map.of()),
                new LineBreak(),
                new Text(List.of(), "Second line", Map.of())),
                p.children());
    }

    @Test
    void sidebarBlock() {
        final var body = new Parser().parseBody(new Reader(List.of(
                "[sidebar]",
                "****",
                "Sidebar content",
                "****")), null);
        assertEquals(1, body.children().size());
        assertEquals(OPEN_BLOCK, body.children().get(0).type());
        final var block = (OpenBlock) body.children().get(0);
        assertEquals("sidebar", block.options().get(""));
    }

    @Test
    void exampleBlock() {
        final var body = new Parser().parseBody(new Reader(List.of(
                "[example]",
                "====",
                "Example content",
                "====")), null);
        assertEquals(1, body.children().size());
        assertEquals(OPEN_BLOCK, body.children().get(0).type());
    }

    @Test
    void commentBlock() {
        final var body = new Parser().parseBody(new Reader(List.of(
                "Before",
                "",
                "////",
                "comment",
                "////",
                "",
                "After")), null);
        assertEquals(2, body.children().size());
        assertEquals(TEXT, body.children().get(0).type());
        assertEquals(TEXT, body.children().get(1).type());
    }

    @Test
    void unsetHeaderAttribute() {
        final var header = new Parser().parseHeader(new Reader(List.of("= Title", ":foo: bar", ":!foo:", "", "content")));
        assertEquals("Title", header.title());
        assertEquals(Map.of("authorcount", "0"), header.attributes());
    }

    @Test
    void unsetBodyAttribute() {
        final var doc = new Parser().parse(List.of(
                "= Title", ":foo: bar", "", "before {foo} after", ":!foo:", "after {foo} end"), new Parser.ParserContext(null));
        final var body = doc.body().children();
        assertEquals(2, body.size());
        if (body.get(0) instanceof Paragraph p0) {
            assertEquals(List.of(TEXT), p0.children().stream().map(Element::type).toList());
            assertEquals("before bar after", ((Text) p0.children().get(0)).value());
        }
        if (body.get(1) instanceof Paragraph p1) {
            assertEquals(List.of(TEXT, ATTRIBUTE, TEXT), p1.children().stream().map(Element::type).toList());
        }
    }

    @Test
    void headerTitleAttributes() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= {my-guide-title} for {missing}",
                ":my-guide-title: Some parameterized title",
                ":description: About {my-guide-title}",
                "",
                "Body.")));
        assertEquals("Some parameterized title for {missing}", header.title());
        assertEquals("About Some parameterized title", header.attributes().get("description"));
    }

    @Test
    void headerTitleAttributesAfterAuthorLine() {
        final var doc = new Parser(Map.of("product", "Quarkus")).parse(List.of(
                "= {product} {version}", "Jane Doe <jane@example.com>", ":version: 3.0", "", "Body."), new Parser.ParserContext(null));
        assertEquals("Quarkus 3.0", doc.header().title());
        assertEquals("Jane Doe", doc.header().author().get(0).name());
    }

    @Test
    void unsetHeaderAttributePreservesOthers() {
        final var header = new Parser().parseHeader(new Reader(List.of("= Title", ":foo: bar", ":baz: qux", ":!foo:", "", "content")));
        assertEquals("Title", header.title());
        assertEquals(Map.of("baz", "qux", "authorcount", "0"), header.attributes());
    }

    @Test
    void includeTags() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tag=snippet-a]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "# tag::snippet-a[]",
                            "included content",
                            "# end::snippet-a[]"));
                    default -> Optional.empty();
                });
        assertEquals(List.of(new Text(List.of(), "included content", Map.of())), body.children());
    }

    @Test
    void includeTagsWithDoubleSlashComment() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tag=snippet-a]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "// tag::snippet-a[]",
                            "included content",
                            "// end::snippet-a[]"));
                    default -> Optional.empty();
                });
        assertEquals(List.of(new Text(List.of(), "included content", Map.of())), body.children());
    }

    @Test
    void includeTagsWithSemicolonComment() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tag=snippet-a]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "; tag::snippet-a[]",
                            "included content",
                            "; end::snippet-a[]"));
                    default -> Optional.empty();
                });
        assertEquals(List.of(new Text(List.of(), "included content", Map.of())), body.children());
    }

    @Test
    void includeTagsNegation() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tag!=snippet-a]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "keep me",
                            "// tag::snippet-a[]",
                            "exclude me",
                            "// end::snippet-a[]",
                            "keep me too"));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Text(List.of(), "keep me keep me too", Map.of())),
                body.children());
    }

    @Test
    void includeTagsValueNegation() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tag=!snippet-a]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "keep me",
                            "// tag::snippet-a[]",
                            "exclude me",
                            "// end::snippet-a[]",
                            "keep me too"));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Text(List.of(), "keep me keep me too", Map.of())),
                body.children());
    }

    @Test
    void includeTagsSemicolonSeparator() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tags=snippet-a;snippet-b]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "// tag::snippet-a[]",
                            "content a",
                            "// end::snippet-a[]",
                            "// tag::snippet-b[]",
                            "content b",
                            "// end::snippet-b[]"));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Text(List.of(), "content a content b", Map.of())),
                body.children());
    }

    @Test
    void includeTagsDoubleWildcard() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tag=**]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "keep me",
                            "// tag::snippet-a[]",
                            "content a",
                            "// end::snippet-a[]",
                            "keep me too"));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Text(List.of(), "keep me content a keep me too", Map.of())),
                body.children());
    }

    @Test
    void includeTagsSingleWildcard() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tag=*]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "ignore me",
                            "// tag::snippet-a[]",
                            "content a",
                            "// end::snippet-a[]",
                            "ignore me too"));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Text(List.of(), "content a", Map.of())),
                body.children());
    }

    @Test
    void includeTagsNegateSingleWildcard() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tags=!*]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "keep me",
                            "// tag::snippet-a[]",
                            "exclude me",
                            "// end::snippet-a[]",
                            "keep me too"));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Text(List.of(), "keep me keep me too", Map.of())),
                body.children());
    }

    @Test
    void includeTagsWithCommaAndOnlyContent() {
        final var body = new Parser().parseBody(
                new Reader(List.of("""
                        include::snippet.adoc[tag=snippet-b]
                        """.split("\n"))),
                (ref, encoding) -> switch (ref) {
                    case "snippet.adoc" -> Optional.of(List.of(
                            "text a",
                            "",
                            "tag::snippet-b[]",
                            "snippet b",
                            "end::snippet-b[]",
                            "",
                            "text c"));
                    default -> Optional.empty();
                });
        assertEquals(
                List.of(new Text(List.of(), "snippet b", Map.of())),
                body.children());
    }

    @Test
    void attributeValueSubstitutedWhenAssigned() { // #122, as asciidoctor the value is resolved before the assignment, not when referenced
        final var doc = new Parser().parse(List.of(
                ":a: 1", ":b: {a}", ":a: 2", "", "b is {b}, a is {a}."), new Parser.ParserContext(null));
        assertEquals("1", doc.header().attributes().get("b"));
        assertEquals(List.of(new Text(List.of(), "b is 1, a is 2.", Map.of())), doc.body().children());
    }

    @Test
    void attributeValueSubstitutedWhenAssignedInTheBody() {
        final var doc = new Parser().parse(List.of(
                "= Title", "", ":a: 1", ":b: {a}", ":a: 2", "", "b is {b}, a is {a}."), new Parser.ParserContext(null));
        assertEquals(List.of(new Text(List.of(), "b is 1, a is 2.", Map.of())), doc.body().children());
    }

    @Test
    void attributeValueSubstitutedAcrossTheAuthorLine() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":a: 1", "Dave Grohl", ":b: {a}", "", "content")));
        assertEquals("1", header.attributes().get("b"));
    }

    @Test
    void attributeValueKeepsUnknownReferences() { // #122, asciidoctor keeps a reference it can not resolve as is
        final var header = new Parser().parseHeader(new Reader(List.of(
                ":a: ${a}", ":b: x{c}y", "", "content")));
        assertEquals("${a}", header.attributes().get("a"));
        assertEquals("x{c}y", header.attributes().get("b"));
    }
}
