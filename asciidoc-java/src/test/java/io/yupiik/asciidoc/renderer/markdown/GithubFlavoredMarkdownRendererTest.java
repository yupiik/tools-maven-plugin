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
package io.yupiik.asciidoc.renderer.markdown;

import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.VisitorSibling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubFlavoredMarkdownRendererTest {
    @Test
    void titleAndSections() {
        assertEquals("""
                        # Page Title

                        Intro paragraph.

                        ## First section

                        Body text.

                        ### Nested

                        More text.
                        """,
                md("""
                        = Page Title

                        Intro paragraph.

                        == First section

                        Body text.

                        === Nested

                        More text.
                        """));
    }

    @Test
    void noHeaderAttributeSkipsTheTitle() {
        assertEquals("Just a paragraph.\n", md("""
                = Title

                Just a paragraph.
                """, Map.of("noheader", "true")));
    }

    @Test
    void inlineStyles() {
        assertEquals("Some **bold** and *italic* and `mono` text.\n", md("Some *bold* and _italic_ and `mono` text."));
    }

    @Test
    void links() {
        assertEquals("See [the site](https://yupiik.io) or <https://example.org> for more.\n",
                md("See https://yupiik.io[the site] or https://example.org for more."));
    }

    @Test
    void crossReferences() {
        final var md = md("""
                = Doc

                [#pages]
                == Pages

                See xref:advanced.adoc#editor[the editor], xref:advanced.adoc[], <<pages,the pages section>> and <<pages>>.
                """);
        assertContains("[the editor](advanced.md#editor)", md);
        assertContains("[advanced.md](advanced.md)", md);
        assertContains("[the pages section](#pages)", md);
        assertContains("[Pages](#pages)", md);
    }

    @Test
    void crossReferencesUseRelFileAttributes() {
        final var md = md("See xref:advanced.adoc#editor[the editor].",
                Map.of("relfileprefix", "../", "relfilesuffix", "/index.md"));
        assertContains("[the editor](../advanced/index.md#editor)", md);
    }

    @Test
    void crossReferencesToAsciiDocExtensions() { // .adoc and .asciidoc by default, another extension names a file
        assertEquals("See [B](b.md), [C](c.ad#x) and [D](d.asc).\n", md("See xref:b.asciidoc[B], xref:c.ad#x[C] and xref:d.asc[D]."));
    }

    @Test
    void crossReferencesToConfiguredAsciiDocExtensions() {
        assertEquals("# Doc\n\nSee [B](b.asciidoc), [C](c.md#x) and [D](d.md).\n", md("""
                = Doc
                :asciidoc-extensions: adoc, .ad,asc

                See xref:b.asciidoc[B], xref:c.ad#x[C] and xref:d.asc[D].
                """));
        assertEquals("See [B](b.md) and [C](c.ad#x).\n",
                md("See xref:b.adoc[B] and xref:c.ad#x[C].", Map.of("asciidoc-extensions", "adoc")));
    }

    @Test
    void crossReferencesWithoutExtensionOrToAnotherFile() { // as asciidoctor reads the xref macro
        assertEquals("See [Dev mode](../cli-tooling/index.md#development-mode), [the guide](../guide.html), " +
                        "[the section](#dir/page) and [Two](../a/index.md#s1#s2).\n",
                md("See xref:cli-tooling#development-mode[Dev mode], xref:guide.html[the guide], xref:dir/page[the section] " +
                        "and xref:a.adoc#s1#s2[Two].", Map.of("relfileprefix", "../", "relfilesuffix", "/index.md")));
    }

    @Test
    void imagesUseImagesDir() {
        final var md = md("""
                = Doc
                :imagesdir: {site-path}images/docs

                image::layouts.webp[Layouts]

                Inline image:icon.svg[] here.
                """, Map.of("site-path", "/"));
        assertContains("![Layouts](/images/docs/layouts.webp)", md);
        assertContains("![icon](/images/docs/icon.svg)", md);
    }

    @Test
    void sourceBlockWithCallouts() {
        assertEquals("""
                        **A title**

                        ```yaml
                        site:
                          title: Yupiik <1>
                          url: https://yupiik.io <2>
                        ```

                        1. The site title.
                        2. The site URL.
                        """,
                md("""
                        .A title
                        [source,yaml]
                        ----
                        site:
                          title: Yupiik <1>
                          url: https://yupiik.io <2>
                        ----
                        <1> The site title.
                        <2> The site URL.
                        """));
    }

    @Test
    void severalCalloutsOnTheSameLine() {
        assertEquals("""
                        ```properties
                        quarkus.arc.exclude-types=org.acme.Foo,org.acme.*,Bar <1><2><3>
                        ```

                        1. a class,
                        2. a package,
                        3. a pattern.
                        """,
                md("""
                        [source,properties]
                        ----
                        quarkus.arc.exclude-types=org.acme.Foo,org.acme.*,Bar <1><2><3>
                        ----
                        <1> a class,
                        <2> a package,
                        <3> a pattern.
                        """));
    }

    @Test
    void bareSourceBlockHasNoLanguage() {
        assertEquals("```\nplain text\n```\n", md("""
                [source]
                ----
                plain text
                ----
                """));
    }

    @Test
    void lists() {
        final var md = md("""
                * first
                * second
                ** nested

                . one
                . two

                * [x] done
                * [ ] todo
                """);
        assertContains("- first\n- second\n  - nested\n", md);
        assertContains("1. one\n2. two\n", md);
        assertContains("- [x] done\n- [ ] todo\n", md);
    }

    @Test
    void descriptionList() {
        assertEquals("**CPU**\\\nThe brain.\n\n**RAM**\\\nThe memory.\n", md("""
                CPU:: The brain.
                RAM:: The memory.
                """));
    }

    @Test
    void descriptionListTitle() {
        assertEquals("**Terms**\n\n**CPU**\\\nThe brain.\n", md("""
                .Terms
                CPU:: The brain.
                """));
    }

    @Test
    void descriptionListIgnoresATitleAttribute() { // the parser copies the document attributes into the list options
        assertEquals("# Doc\n\n**CPU**\\\nThe brain.\n", md("""
                = Doc
                :title: Override

                CPU:: The brain.
                """));
    }

    @Test
    void admonitions() {
        final var md = md("""
                NOTE: Mind the gap.

                [TIP]
                ====
                Use the plugin.
                ====

                [WARNING]
                --
                Open block style.
                --
                """);
        assertContains("> [!NOTE]\n> Mind the gap.\n", md);
        assertContains("> [!TIP]\n> Use the plugin.\n", md);
        assertContains("> [!WARNING]\n> Open block style.\n", md);
    }

    @Test
    void table() {
        assertEquals("""
                        **Options**

                        | Name | Value |
                        | --- | --- |
                        | a | 1 \\| one |
                        | b | 2 |
                        """,
                md("""
                        .Options
                        [cols="1,1",options="header"]
                        |===
                        |Name |Value

                        |a
                        |1 \\| one

                        |b
                        |2
                        |===
                        """));
    }

    @Test
    void quoteAndCollapsible() {
        final var md = md("""
                [quote, Someone, Somewhere]
                ____
                Words of wisdom.
                ____

                .Show me
                [%collapsible]
                ====
                Hidden text.
                ====
                """);
        assertContains("> Words of wisdom.\n>\n> — Someone, Somewhere\n", md);
        assertContains("<details>\n<summary>Show me</summary>\n\nHidden text.\n\n</details>\n", md);
    }

    @Test
    void attributesResolveOrStayLiteral() {
        assertEquals("# Doc\n\nYupiik lives at <https://yupiik.io>; {missing} stays and so does {page.title}.\n", md("""
                = Doc
                :product: Yupiik

                {product} lives at {site-url}; {missing} stays and so does {page.title}.
                """, Map.of("site-url", "https://yupiik.io")));
    }

    @Test
    void conditionals() {
        final var md = md("""
                ifdef::absent[]
                hidden text
                endif::[]
                ifndef::absent[]
                shown text
                endif::[]
                ifdef::present[]
                present text
                endif::[]
                """, Map.of("present", ""));
        assertContains("shown text", md);
        assertContains("present text", md);
        assertFalse(md.contains("hidden text"), md);
    }

    @Test
    void passthroughHorizontalRuleAndKbd() {
        assertEquals("<b>raw html</b>\n\n---\n\nPress <kbd>Ctrl</kbd>+<kbd>C</kbd> now.\n", md("""
                ++++
                <b>raw html</b>
                ++++

                '''

                Press kbd:[Ctrl+C] now.
                """));
    }

    @Test
    void footnotes() {
        assertEquals("Some text [^1] and [^mynote] and [^mynote] again.\n\n[^1]: A note.\n[^mynote]: A note with id.\n",
                md("Some text footnote:[A note.] and footnote:mynote[A note with id.] and footnote:mynote[] again."));
    }

    @Test
    void footnoteInATitledSectionIsRegisteredOnce() {
        assertEquals("""
                        # Doc

                        <a id="sec"></a>
                        ## Intro [^1] here

                        Body [^2].

                        See [Intro here](#sec).

                        [^1]: first note
                        [^2]: second note
                        """,
                md("""
                        = Doc

                        [#sec]
                        == Intro footnote:[first note] here

                        Body footnote:[second note].

                        See <<sec>>.
                        """));
    }

    @Test
    void sectionInAConditionalBlockNotRenderedIsNotIndexed() {
        assertEquals("# Doc\n\nReal [^1].\n\n[^1]: real\n", md("""
                = Doc

                ifdef::never-set[]
                [#hidden]
                == Hidden footnote:[ghost]
                endif::[]

                Real footnote:[real].
                """));
    }

    @Test
    void sectionTitleWithMarkupIsPlainTextInReferences() {
        assertContains("See [A bold link title](#sec).", md("""
                [#sec]
                == A *bold* https://example.org[link] title

                See <<sec>>.
                """));
    }

    @Test
    void crossReferenceTextComesFromTheTarget() { // its reftext, else its title, else the id between escaped brackets
        assertEquals("""
                        <a id="intro"></a>
                        Some **bold** text.

                        <a id="ports"></a>

                        **Ports of the service**

                        | a | b |
                        | --- | --- |

                        <a id="plain"></a>
                        Some **other** text.

                        See [The introduction](#intro), [Ports of the service](#ports), [\\[plain\\]](#plain) and [\\[plain\\]](#plain).
                        """,
                md("""
                        [[intro,The introduction]]
                        Some *bold* text.

                        .Ports of the service
                        [[ports]]
                        |===
                        | a | b
                        |===

                        [[plain]]
                        Some *other* text.

                        See <<intro>>, <<ports>>, <<plain>> and xref:plain[].
                        """));
    }

    @Test
    void aCrossReferenceTextIsNotSubstitutedASecondTime() { // the parser already resolved it, escape included
        assertEquals("<a id=\"t\"></a>Some text.\n\nSee [My {name} title](#t).\n", md("""
                :name: value

                .My \\{name} title
                [[t]]
                Some text.

                See <<t>>.
                """));
    }

    @Test
    void paragraphsOfADelimitedAdmonitionStayApart() {
        assertEquals("""
                        > [!NOTE]
                        > line one line two
                        >
                        > Some paragraph with **bold**
                        """,
                md("""
                        [NOTE]
                        ====
                        line one
                        line two

                        Some paragraph with *bold*
                        ====
                        """));
    }

    @Test
    void unconstrainedStyleInAWordStaysInItsParagraph() {
        assertEquals("foo**bar**baz\n", md("foo**bar**baz"));
    }

    @Test
    void listItemContinuationKeepsSourceOrder() {
        assertEquals("""
                        - first

                          ```
                          code
                          ```

                          trailing text
                        """,
                md("""
                        * first
                        +
                        [source]
                        ----
                        code
                        ----
                        +
                        trailing text
                        """));
    }

    @Test
    void fenceIsLongerThanTheBackticksInTheCode() {
        assertEquals("`````text\n````\nnested\n```\n`````\n", md("""
                [source,text]
                ----
                ````
                nested
                ```
                ----
                """));
    }

    @Test
    void keyboardKeys() {
        assertEquals("Press <kbd>Ctrl</kbd>+<kbd>+</kbd>, <kbd>+</kbd> and <kbd>Ctrl</kbd>+<kbd>T</kbd>.\n",
                md("Press kbd:[Ctrl++], kbd:[+] and kbd:[Ctrl,T]."));
    }

    @Test
    void passAndMenuMacros() {
        assertEquals("A *raw* B <b>x</b> C **File > Save > As** D.\n",
                md("A pass:q[*raw*] B pass:[<b>x</b>] C menu:File[Save > As] D."));
    }

    @Test
    void blockAnchorIsNotAFenceLanguage() {
        // the literal block carries no title, so the reference shows the id between brackets as asciidoctor does
        assertEquals("<a id=\"snippet\"></a>\n\n```\nplain text\n```\n\nSee [\\[snippet\\]](#snippet).\n", md("""
                [[snippet]]
                ....
                plain text
                ....

                See <<snippet>>.
                """));
    }

    @Test
    void diagramBlockKeepsItsStyle() {
        assertEquals("```mermaid\ngraph TD;\n```\n", md("""
                [mermaid]
                ----
                graph TD;
                ----
                """));
    }

    @Test
    void footnoteLabelsNeverCollide() {
        assertEquals("Alpha [^2] and beta [^1] and gamma [^3].\n\n[^2]: A\n[^1]: B\n[^3]: C\n",
                md("Alpha footnote:2[A] and beta footnote:[B] and gamma footnote:[C]."));
    }

    @Test
    void namedFootnoteReusedOrReferencedBeforeItsDefinition() {
        assertEquals("A [^n] B [^n] C [^later] D [^later].\n\n[^n]: text\n[^later]: def\n",
                md("A footnote:n[text] B footnote:n[text] C footnote:later[] D footnote:later[def]."));
    }

    @Test
    void noHeaderFalseKeepsTheTitle() {
        assertEquals("# Doc\n\nBody.\n", md("""
                = Doc
                :noheader: false

                Body.
                """));
    }

    @Test
    void hardLineBreak() {
        assertEquals("first **line**\\\nsecond line\n\nThird para\n", md("""
                first *line* +
                second line

                Third para
                """));
    }

    @Test
    void nullTitleIsIgnored() {
        final var renderer = new GithubFlavoredMarkdownRenderer();
        renderer.visitTitle(null);
        assertEquals("\n", renderer.result());
    }

    @Test
    void bareLinkBetweenParagraphsStaysAParagraph() {
        assertEquals("Go to:\n\n<http://127.0.0.1:3000/hello>\n\nIn the console.\n", md("""
                Go to:

                http://127.0.0.1:3000/hello

                In the console.
                """));
    }

    @Test
    void hardLineBreakWithInlineCodeBeforePunctuation() {
        assertEquals("Use `x`.\\\nthen `y`\n\nNext paragraph.\n", md("""
                Use `x`. +
                then `y`

                Next paragraph.
                """));
    }

    @Test
    void calloutWithAnAttachedBlock() {
        assertEquals("""
                        ```java
                        call(); <1>
                        ```

                        1. Produces output like below

                           ```json
                           {"id": 1}
                           ```

                        """.stripTrailing() + "\n",
                md("""
                        [source,java]
                        ----
                        call(); <1>
                        ----
                        <1> Produces output like below
                        +
                        [source,json]
                        ....
                        {"id": 1}
                        ....
                        """));
    }

    @Test
    void calloutWithStyledTextAndAnAttachedBlock() {
        assertContains("1. Call `toNdJson()` to produce\n\n   ```json\n", md("""
                [source,java]
                ----
                call(); <1>
                ----
                <1> Call `toNdJson()` to produce
                +
                [source,json]
                ----
                {"id": 1}
                ----
                """));
    }

    @Test
    void crossReferenceToASiblingWithDotSlash() {
        assertContains("[TLS](../tls/index.md)", md("See xref:./tls.adoc[TLS].",
                Map.of("relfileprefix", "../", "relfilesuffix", "/index.md")));
    }

    @Test
    void subclassOverridesApplyInNestedBlocks() {
        final var document = new Parser().parse("""
                        image::top.png[Top]

                        NOTE: Inside image:note.png[Note].

                        * item image:item.png[Item]
                        +
                        ----
                        code
                        ----

                        |===
                        |Cell

                        |image:cell.png[Cell]
                        |===
                        """,
                new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new GithubFlavoredMarkdownRenderer() {
            @Override
            protected String image(final Macro macro) {
                return "![" + macro.options().getOrDefault("", "") + "](https://cdn.example.org/" + macro.label() + ")";
            }
        };
        renderer.visit(document);
        final var md = renderer.result();
        assertContains("![Top](https://cdn.example.org/top.png)", md);
        assertContains("> Inside ![Note](https://cdn.example.org/note.png).", md);
        assertContains("- item ![Item](https://cdn.example.org/item.png)", md);
        assertFalse(md.contains("](note.png)") || md.contains("](item.png)"), md);
    }

    @Test
    void rendererCanRenderSeveralDocuments() {
        final var renderer = new GithubFlavoredMarkdownRenderer();
        renderer.visit(new Parser().parse("= First\n\nOne footnote:[first note].\n", new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))));
        assertEquals("# First\n\nOne [^1].\n\n[^1]: first note\n", renderer.result());
        renderer.visit(new Parser().parse("= Second\n\nTwo footnote:[second note].\n", new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))));
        assertEquals("# Second\n\nTwo [^1].\n\n[^1]: second note\n", renderer.result());
    }

    @Test
    void visitBodyAloneKeepsSectionTitlesInLinks() {
        final var document = new Parser().parse("""
                = Doc

                [#sec]
                == Section title

                See <<sec>>.
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new GithubFlavoredMarkdownRenderer();
        renderer.visitBody(document.body());
        assertEquals("<a id=\"sec\"></a>\n## Section title\n\nSee [Section title](#sec).\n", renderer.result());
    }

    @Test
    void visitBodyAloneReadsTheConfigurationAttributes() { // without visit(Document) there is no document attribute to read
        final var body = new Parser().parse("""
                = Doc
                :imagesdir: ignored

                image::logo.png[]

                See xref:other.adoc[the other page] footnote:[A note.].
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))).body();
        final var renderer = new GithubFlavoredMarkdownRenderer(new GithubFlavoredMarkdownRenderer.Configuration()
                .setAttributes(Map.of("imagesdir", "img", "relfilesuffix", "/")));
        renderer.visitBody(body);
        assertEquals("![logo](img/logo.png)\n\nSee [the other page](other/) [^1].\n\n[^1]: A note.\n", renderer.result());
    }

    @Test
    void siblingReadingAppliesToTheIndexToo() { // the table of contents, the anchor and the link text use the same id
        final var renderer = new GithubFlavoredMarkdownRenderer(new GithubFlavoredMarkdownRenderer.Configuration(), new VisitorSibling() {
            @Override
            public String generatedId(final Element title, final ConditionalBlock.Context context) {
                return "custom-" + super.generatedId(title, context).substring(1);
            }
        });
        renderer.visit(new Parser().parse("""
                = Doc
                :toc:

                See <<custom-install>>.

                == Install

                Text.
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))));
        assertEquals("""
                # Doc

                **Table of Contents**

                - [Install](#custom-install)

                See [Install](#custom-install).

                <a id="custom-install"></a>
                ## Install

                Text.
                """, renderer.result());
    }

    @Test
    void contextOverrideAppliesToTheIndexToo() {
        final var renderer = new GithubFlavoredMarkdownRenderer() {
            @Override
            public ConditionalBlock.Context context() {
                final var attributes = super.context();
                return key -> switch (key) {
                    case "toc" -> "";
                    case "idprefix" -> "sec-";
                    default -> attributes.attribute(key);
                };
            }
        };
        renderer.visit(new Parser().parse("""
                = Doc

                See <<sec-install>>.

                == Install

                Text.

                ifdef::toc[]
                == With a table of contents
                endif::[]
                """, new Parser.ParserContext(ContentResolver.of(Path.of("target/missing")))));
        assertEquals("""
                # Doc

                **Table of Contents**

                - [Install](#sec-install)
                - [With a table of contents](#sec-with_a_table_of_contents)

                See [Install](#sec-install).

                <a id="sec-install"></a>
                ## Install

                Text.

                <a id="sec-with_a_table_of_contents"></a>
                ## With a table of contents
                """, renderer.result());
    }

    @Test
    void escapedAttributeReferenceInABlockTitle() { // as asciidoctor, the escaped reference stays literal without its backslash
        assertEquals("**Title with {name} and value**\n\n```java\nrun();\n```\n", md("""
                :name: value

                .Title with \\{name} and {name}
                [source,java]
                ----
                run();
                ----
                """));
    }

    @Test
    void escapedAttributeReferenceInALinkTarget() { // the parser substitutes a link url, so the renderer must not do it again
        assertEquals("[label]({name})\n", md(":name: value\n\nlink:\\{name}[label]\n"));
        assertEquals("[label](value)\n", md(":name: value\n\nlink:{name}[label]\n"));
        assertEquals("[label](https://x.org/{name})\n", md(":name: value\n\nhttps://x.org/\\{name}[label]\n"));
        assertEquals("[label](https://x.org/value)\n", md(":name: value\n\nhttps://x.org/{name}[label]\n"));
        // an image target and a link= option are still raw in the model, so they are substituted here
        assertEquals("![{name}]({name}.png)\n", md(":name: value\n\nimage::\\{name}.png[]\n"));
        assertEquals("[![a](a.png)]({name})\n", md(":name: value\n\nimage::a.png[link=\\{name}]\n"));
    }

    @Test
    void tableWithoutHeaderRowAmongOtherOptions() { // the parser reads [%autowidth%noheader] as one option key
        final var table = """
                |===
                |a |b
                |c |d
                |===
                """;
        final var expected = "|  |  |\n| --- | --- |\n| a | b |\n| c | d |\n";
        assertEquals(expected, md("[%noheader]\n" + table));
        assertEquals(expected, md("[%autowidth%noheader]\n" + table));
    }

    @Test
    void blockIdsGetAnchors() {
        final var md = md("""
                [#tbl]
                |===
                |A

                |1
                |===

                [[list]]
                * a
                * b

                [#q]
                ____
                Words.
                ____

                [#note]
                [NOTE]
                ====
                Mind the gap.
                ====

                [TIP#tip]
                --
                Use the plugin.
                --

                [#ex]
                ====
                Example text.
                ====

                [#img]
                image::a.png[Alt]

                See <<tbl>>, <<list>>, <<q>>, <<note>>, <<tip>>, <<ex>> and <<img>>.
                """);
        assertContains("<a id=\"tbl\"></a>\n\n| A |", md);
        assertContains("<a id=\"list\"></a>\n\n- a\n- b\n", md);
        assertContains("<a id=\"q\"></a>\n\n> Words.", md);
        assertContains("<a id=\"note\"></a>\n\n> [!NOTE]\n> Mind the gap.", md);
        assertContains("<a id=\"tip\"></a>\n\n> [!TIP]\n> Use the plugin.", md);
        assertContains("<a id=\"ex\"></a>\n\nExample text.", md);
        assertContains("<a id=\"img\"></a>\n\n![Alt](a.png)", md);
    }

    @Test
    void generatedSectionIdIsAnchoredWhenTheDocumentLinksToIt() {
        assertEquals("""
                        <a id="_linked_section"></a>
                        ## Linked section

                        ## Other section

                        See [Linked section](#_linked_section) and [Linked section](#_linked_section).
                        """,
                md("""
                        == Linked section

                        == Other section

                        See <<_linked_section>> and xref:_linked_section[].
                        """));
    }

    @Test
    void generatedSectionIdUsesIdPrefix() { // same generator as the HTML renderer, so the two renderers agree
        assertContains("<a id=\"sec_my_section\"></a>\n## My Section", md("""
                = Doc
                :idprefix: sec_

                == My Section

                See <<sec_my_section>>.
                """));
    }

    @Test
    void indexTerms() {
        assertEquals("A  B visible C\n", md("A indexterm:[hidden term] B indexterm2:[visible] C"));
    }

    @Test
    void iconsAreWrittenAsAsciidoctorDoesWithoutAnIconFont() {
        assertEquals("""
                        - [lock] `quarkus.http.port` is fixed at build time
                        - [question circle] see the duration format
                        - [Love] and [[check]](https://example.org) and [my icon]
                        """,
                md("""
                        * icon:lock[title=Fixed at build time] `quarkus.http.port` is fixed at build time
                        * icon:question-circle[] see the duration format
                        * icon:heart[alt=Love] and icon:check[link=https://example.org] and icon:my_icon[2x]
                        """));
    }

    @Test
    void tableOfContentsAtTheMacro() {
        assertEquals("""
                        # Doc

                        Intro.

                        **Table of Contents**

                        - [One](#_one)
                          - [Two](#_two)
                        - [Four](#_four)

                        <a id="_one"></a>
                        ## One

                        <a id="_two"></a>
                        ### Two

                        #### Three

                        <a id="_four"></a>
                        ## Four
                        """,
                md("""
                        = Doc
                        :toc: macro

                        Intro.

                        toc::[]

                        == One

                        === Two

                        ==== Three

                        == Four
                        """));
        assertEquals("""
                        **Sections**

                        - [One](#_one)
                          - [Two](#_two)
                            - [Three](#_three)

                        <a id="_one"></a>
                        ## One

                        <a id="_two"></a>
                        ### Two

                        <a id="_three"></a>
                        #### Three
                        """,
                md("""
                        = Doc
                        :toc: macro

                        .Sections
                        toc::[levels=3]

                        == One

                        === Two

                        ==== Three
                        """, Map.of("noheader", "true")));
    }

    @Test
    void tableOfContentsPlacements() {
        assertEquals("""
                        # Doc

                        **Table of Contents**

                        - [One](#_one)

                        Preamble.

                        <a id="_one"></a>
                        ## One
                        """,
                md("""
                        = Doc
                        :toc:

                        Preamble.

                        toc::[]

                        == One
                        """), "at the top, the macro being ignored unless the toc attribute is macro");
        assertEquals("""
                        # Doc

                        Preamble.

                        **Contents**

                        - [One](#custom)

                        <a id="custom"></a>
                        ## One

                        ### Two
                        """,
                md("""
                        = Doc
                        :toc: preamble
                        :toc-title: Contents
                        :toclevels: 1

                        Preamble.

                        [#custom]
                        == One

                        === Two
                        """), "after the preamble");
        assertEquals("# Doc\n\n## One\n", md("""
                = Doc

                toc::[]

                == One
                """), "no toc attribute, no table of contents");
        assertEquals("# Doc\n\nJust text.\n", md("""
                = Doc
                :toc:

                Just text.
                """), "no section, no table of contents");
    }

    @Test
    void includeReachingTheRendererFails() {
        final var block = assertThrows(IllegalArgumentException.class, () -> new GithubFlavoredMarkdownRenderer()
                .visitBody(new Body(List.of(new Macro("include", "chapter.adoc", Map.of(), false)))));
        assertEquals("Unresolved include: 'chapter.adoc', the parser resolves includes before rendering", block.getMessage());
        final var inline = assertThrows(IllegalArgumentException.class, () -> new GithubFlavoredMarkdownRenderer()
                .visitBody(new Body(List.of(new Paragraph(List.of(
                        new Text(List.of(), "See ", Map.of()), new Macro("include", "part.adoc", Map.of(), true)), Map.of())))));
        assertEquals("Unresolved include: 'part.adoc', the parser resolves includes before rendering", inline.getMessage());
    }

    @Test
    void admonitionStyleIsUpperCase() { // as in asciidoctor, [note] is no admonition
        assertEquals("> [!NOTE]\n> Upper case.\n\nLower case.\n", md("""
                [NOTE]
                --
                Upper case.
                --

                [note]
                --
                Lower case.
                --
                """));
    }

    @Test
    void imageTargetsThatLookLikeAUri() { // a scheme has two characters at least, so C: starts a path
        assertEquals("![a](img/C:/images/a.png)\n\nA ![x:y](img/x:y.png) B ![a](https://example.org/a.png) C ![ab:c](ab:c.png) " +
                        "D ![a](/abs/a.png) E ![my logo file](img/my_logo-file.png)\n",
                md("""
                        image::C:/images/a.png[]

                        A image:x:y.png[] B image:https://example.org/a.png[] C image:ab:c.png[] D image:/abs/a.png[] E image:my_logo-file.png[]
                        """, Map.of("imagesdir", "img")));
    }

    @Test
    void inlineCodeDelimiterIsLongerThanTheBackticksInTheCode() {
        final var renderer = new GithubFlavoredMarkdownRenderer();
        renderer.visitBody(new Body(List.of(new Paragraph(List.of(
                new Text(List.of(), "Use ", Map.of()),
                new Code("a``b", Map.of(), true, List.of()),
                new Text(List.of(), " or ", Map.of()),
                new Code("`c", Map.of(), true, List.of()),
                new Text(List.of(), " but not ", Map.of()),
                new Code("d", Map.of(), true, List.of()),
                new Text(List.of(), ".", Map.of())), Map.of()))));
        assertEquals("Use ``` a``b ``` or `` `c `` but not `d`.\n", renderer.result());
    }

    @Test
    void listingAndLiteralStylesNameNoLanguage() {
        assertEquals("```\ny\n```\n\n```\nz\n```\n", md("""
                [listing]
                ----
                y
                ----

                [literal]
                ....
                z
                ....
                """));
    }

    @Test
    void linkTargetsWithSpaces() {
        assertEquals("See [the file](<my file.pdf>) and ![Alt](<my image.png>).\n",
                md("See link:my file.pdf[the file] and image:my image.png[Alt]."));
    }

    @Test
    void urlInAngleBracketsKeepsTheClosingBracketOutOfTheLink() { // from the Quarkus guide building-native-image.adoc
        assertEquals("- Download from <<https://github.com/graalvm/mandrel/releases>>, and unpack it.\n",
                md("* Download from <https://github.com/graalvm/mandrel/releases>, and unpack it."));
    }

    @Test
    void linkWithoutSchemeShowingItsTarget() { // an autolink needs a scheme, native-reference.adoc
        assertEquals("The [mallocstacks.py](mallocstacks.py) script.\n", md("The link:mallocstacks.py[mallocstacks.py] script."));
    }

    @Test
    void urlInBackticksIsCode() { // security-openid-connect-dev-services.adoc
        assertEquals("You add `http://localhost:8080/q/dev-ui/quarkus-oidc/<providerName>-provider` as a URL.\n",
                md("You add `http://localhost:8080/q/dev-ui/quarkus-oidc/<providerName>-provider` as a URL."));
    }

    @Test
    void labelledLinkInBackticksKeepsItsTarget() { // spring-web.adoc
        assertEquals("Types in the Spring [`ExceptionHandler javadoc`](https://docs.spring.io/ExceptionHandler.html) are not supported.\n",
                md("Types in the Spring `https://docs.spring.io/ExceptionHandler.html[ExceptionHandler javadoc]` are not supported."));
    }

    @Test
    void balancedParenthesesStayInAPlainDestination() {
        assertEquals("See the [parse](https://docs.oracle.com/Duration.html#parse(java.lang.CharSequence)).\n",
                md("See the https://docs.oracle.com/Duration.html#parse(java.lang.CharSequence)[parse]."));
    }

    @Test
    void negativeListStartIsWrittenFromZero() {
        assertEquals("0. a\n1. b\n", md("""
                [start=-2]
                . a
                . b
                """));
    }

    @Test
    void collapsibleBlocks() {
        final var md = md("""
                .Show me
                [example,opts=collapsible]
                ====
                Hidden.
                ====

                .Shown
                [%collapsible%open]
                ====
                Visible.
                ====
                """);
        assertContains("<details>\n<summary>Show me</summary>\n\nHidden.\n\n</details>\n", md);
        assertContains("<details open>\n<summary>Shown</summary>\n\nVisible.\n\n</details>\n", md);
    }

    @Test
    void collapsibleOnlyOnAnExampleBlock() { // asciidoctor collapses an example block, not an open block or a sidebar
        assertEquals("Hidden.\n", md("""
                        [%collapsible]
                        --
                        Hidden.
                        --
                        """));
        assertEquals("Hidden.\n", md("""
                        [sidebar%collapsible]
                        ****
                        Hidden.
                        ****
                        """));
    }

    @Test
    void includesAreRenderedInPlace(@TempDir final Path srcPath) throws IOException {
        final var partials = Files.createDirectories(srcPath.resolve("_partials"));
        Files.writeString(partials.resolve("attributes.adoc"), """
                :doc-name: basics
                """);
        Files.writeString(partials.resolve("structure.adoc"), """
                == Directory Structure

                NOTE: Edit link:https://example.org/{doc-name}.adoc[this document].

                [source,yaml]
                ----
                content: pages <1>
                ----
                <1> The pages.
                """);
        final var doc = new Parser().parse("""
                        = The basics
                        include::_partials/attributes.adoc[]

                        By default, your site files live in the project root.

                        include::_partials/structure.adoc[]
                        """,
                new Parser.ParserContext(ContentResolver.of(srcPath)));
        final var renderer = new GithubFlavoredMarkdownRenderer();
        renderer.visit(doc);
        assertEquals("""
                        # The basics

                        By default, your site files live in the project root.

                        ## Directory Structure

                        > [!NOTE]
                        > Edit [this document](https://example.org/basics.adoc).

                        ```yaml
                        content: pages <1>
                        ```

                        1. The pages.
                        """,
                renderer.result());
    }

    private static String md(final String asciidoc) {
        return md(asciidoc, Map.of());
    }

    private static String md(final String asciidoc, final Map<String, String> attributes) {
        final var document = new Parser(new HashMap<>(attributes)).parse(asciidoc,
                new Parser.ParserContext(ContentResolver.of(Path.of("target/missing"))));
        final var renderer = new GithubFlavoredMarkdownRenderer(new GithubFlavoredMarkdownRenderer.Configuration()
                .setAttributes(attributes));
        renderer.visit(document);
        return renderer.result();
    }

    private static void assertContains(final String expected, final String actual) {
        assertTrue(actual.contains(expected), () -> "expected to contain:\n" + expected + "\nbut was:\n" + actual);
    }
}
