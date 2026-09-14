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

import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void crossReferencesToEveryAsciiDocExtension() {
        assertEquals("See [B](b.md), [C](c.md#x) and [D](d.md).\n", md("See xref:b.asciidoc[B], xref:c.ad#x[C] and xref:d.asc[D]."));
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
        assertEquals("<a id=\"snippet\"></a>\n\n```\nplain text\n```\n\nSee [snippet](#snippet).\n", md("""
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
