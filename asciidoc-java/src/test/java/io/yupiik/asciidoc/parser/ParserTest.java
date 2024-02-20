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
import io.yupiik.asciidoc.model.Link;
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

import static io.yupiik.asciidoc.model.Admonition.Level.WARNING;
import static io.yupiik.asciidoc.model.Element.ElementType.ATTRIBUTE;
import static io.yupiik.asciidoc.model.Element.ElementType.PARAGRAPH;
import static io.yupiik.asciidoc.model.Element.ElementType.TEXT;
import static io.yupiik.asciidoc.model.Text.Style.MARK;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ParserTest {
    @Test
    void parseHeader() {
        final var header = new Parser().parseHeader(new Reader(List.of("= Title", ":attr-1: v1", ":attr-2: v2", "", "content")));
        assertEquals("Title", header.title());
        assertEquals(Map.of("attr-1", "v1", "attr-2", "v2"), header.attributes());
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
            assertEquals(Map.of("idprefix", "", "idseparator", "-", "toc", "left", "icons", "font"), header.attributes());
        }
        {
            final var header = new Parser(Map.of("env-github", "true")).parseHeader(new Reader(content));
            assertEquals("Title", header.title());
            assertEquals(Map.of(
                    "idprefix", "", "idseparator", "-",
                    "toc", "macro",
                    "caution-caption", ":fire:", "important-caption", ":exclamation:",
                    "note-caption", ":paperclip:", "tip-caption", ":bulb:", "warning-caption", ":warning:"), header.attributes());
        }
    }

    @Test
    void parseHeaderAndContent() {
        final var doc = new Parser().parse(List.of("= Title", "", "++++", "pass", "++++"), new Parser.ParserContext(null));
        assertEquals("Title", doc.header().title());
        assertEquals(Map.of(), doc.header().attributes());
        assertEquals(List.of(new PassthroughBlock("pass", Map.of())), doc.body().children());
    }

    @Test
    void parseMultiLineAttributesHeader() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", ":attr-1: v1", ":attr-2: v2\\", "  and it continues", "", "content")));
        assertEquals("Title", header.title());
        assertEquals(Map.of("attr-1", "v1", "attr-2", "v2 and it continues"), header.attributes());
    }

    @Test
    void parseAuthorLine() {
        final var header = new Parser().parseHeader(new Reader(List.of(
                "= Title", "firstname middlename lastname <email>", "revision number, revision date: revision revmark", ":attr: value")));
        assertEquals("Title", header.title());
        assertEquals(new Author("firstname middlename lastname", "email"), header.author());
        assertEquals(new Revision("revision number", "revision date", "revision revmark"), header.revision());
        assertEquals(Map.of("attr", "value"), header.attributes());
    }

    @Test
    void parseHeaderWhenMissing() {
        final var header = new Parser().parseHeader(new Reader(List.of("paragraph")));
        assertEquals("", header.title());
        assertEquals(Map.of(), header.attributes());
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
                        new Link("https://yupiik.io", "Yupiik OSS", Map.of("role", "external", "window", "_blank")),
                        new Paragraph(List.of(
                                new Text(List.of(), "This can be in a sentence about ", Map.of()),
                                new Link("https://yupiik.io", "Yupiik OSS", Map.of()),
                                new Text(List.of(), ".", Map.of())
                        ), Map.of())
                ),
                body.children());
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
                List.of(new Macro("link", "foo", Map.of("role", "test"), true)),
                new Parser().parseBody(new Reader(List.of("link:foo[role=\"test\"]")), null).children());
    }

    @Test
    void linksAttribute() {
        final var body = new Parser().parseBody(new Reader(List.of(":url: https://yupiik.io", "", "{url}[Yupiik OSS]")), null);
        assertEquals(
                List.of(new Link("https://yupiik.io", "Yupiik OSS", Map.of())),
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
                List.of(new Code("public record Foo() {\n}\n", List.of(), Map.of("language", "java", "role", "hljs"), false)),
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
                List.of(new Code(code, List.of(), Map.of("language", "properties", "role", "hljs"), false)),
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
        assertEquals(
                List.of(new Code("""
                        import anything;
                        public record Foo( (1)
                          String name (2)
                        ) {
                        }
                        """,
                        List.of(
                                new CallOut(1, new Text(List.of(), "Defines a record,", Map.of())),
                                new CallOut(2, new Text(List.of(), "Defines an attribute of the record.", Map.of()))),
                        Map.of("language", "java", "role", "hljs"), false)),
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
                                                new Code("record Foo() {}\n", List.of(), Map.of("language", "java"), false)),
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
    void admonition() {
        final var body = new Parser().parseBody(new Reader(List.of("""
                WARNING: Wolpertingers are known to nest in server racks.
                Enter at your own risk.
                """.split("\n"))), null);
        assertEquals(
                List.of(new Admonition(WARNING, new Text(
                        List.of(),
                        "Wolpertingers are known to nest in server racks. Enter at your own risk.",
                        Map.of()))),
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
                                        new Code("public class Foo {\n}\n", List.of(), Map.of("language", "java"), false)
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
}
