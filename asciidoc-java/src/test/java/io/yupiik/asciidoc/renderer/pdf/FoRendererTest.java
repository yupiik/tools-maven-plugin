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
package io.yupiik.asciidoc.renderer.pdf;

import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Listing;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.Table;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.UnknownMacro;
import io.yupiik.asciidoc.renderer.uri.DataResolver;
import io.yupiik.asciidoc.renderer.uri.DataUri;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoRendererTest {
    @Test
    void coverInheritsHeaderMetadataAndCanBeOverridden() throws Exception {
        final var source = """
                = A & B
                Alice Example <alice@example.org>; Bob Example <bob@example.org>
                v2.1, 2026-11-24: Reviewed edition
                :subtitle: A practical guide

                Body only.
                """;
        final var root = xmlRoot(render(source));
        final var sequences = root.getElementsByTagNameNS(root.getNamespaceURI(), "page-sequence");
        assertEquals(2, sequences.getLength());
        final var cover = (Element) sequences.item(0);
        assertEquals("title-page", cover.getAttribute("master-reference"));
        for (final var expected : List.of("A & B", "Alice Example", "Bob Example", "A practical guide", "2.1", "2026-11-24", "Reviewed edition")) {
            assertTrue(cover.getTextContent().contains(expected), cover.getTextContent());
        }
        assertFalse(cover.getTextContent().contains("alice@example.org"));
        final var authorLinks = cover.getElementsByTagNameNS(root.getNamespaceURI(), "basic-link");
        assertEquals(2, authorLinks.getLength());
        assertEquals("mailto:alice@example.org", ((Element) authorLinks.item(0)).getAttribute("external-destination"));
        assertEquals("mailto:bob@example.org", ((Element) authorLinks.item(1)).getAttribute("external-destination"));
        final var coverBlock = (Element) cover.getElementsByTagNameNS(root.getNamespaceURI(), "block").item(0);
        assertEquals(PdfTheme.defaults().margin(), coverBlock.getAttribute("start-indent"));
        assertEquals(PdfTheme.defaults().margin(), coverBlock.getAttribute("end-indent"));
        assertFalse(cover.getTextContent().contains("Body only"));
        assertEquals(0, cover.getElementsByTagNameNS(root.getNamespaceURI(), "static-content").getLength());
        assertEquals("1", ((Element) sequences.item(1)).getAttribute("initial-page-number"));

        final var overridden = xmlRoot(render(source, new PdfConfiguration().setAttributes(Map.of(
                "pdf-title-page-title", "Replacement", "pdf-title-page-authors", "Editorial team",
                "pdf-title-page-subtitle", "Custom subtitle", "pdf-title-page-revision", "Release edition"))));
        final var text = overridden.getElementsByTagNameNS(root.getNamespaceURI(), "page-sequence").item(0).getTextContent();
        for (final var expected : List.of("Replacement", "Editorial team", "Custom subtitle", "Release edition")) {
            assertTrue(text.contains(expected), text);
        }
        assertFalse(text.contains("Alice Example"));
        final var literalAuthors = xmlRoot(render(source, new PdfConfiguration().setAttributes(Map.of(
                "pdf-title-page-authors", "Alice Example, Bob Example"))));
        assertEquals(0, literalAuthors.getElementsByTagNameNS(root.getNamespaceURI(), "basic-link").getLength());
    }

    @Test
    void titlePageAndFooterOptionsHonorDocumentThenConfigurationThenTypedDefaults() throws Exception {
        final var disabled = new PdfConfiguration().setTitlePage(false).setSectionFooter(false);
        final var source = "= Guide\n\n== Current section\n\nBody.";
        final var root = xmlRoot(render(source, disabled));
        assertEquals(1, root.getElementsByTagNameNS(root.getNamespaceURI(), "page-sequence").getLength());
        assertEquals(0, root.getElementsByTagNameNS(root.getNamespaceURI(), "retrieve-marker").getLength());
        final var configured = new PdfConfiguration().setTitlePage(false).setSectionFooter(false)
                .setAttributes(Map.of("pdf-title-page", "yes", "pdf-section-footer", "on"));
        final var enabled = xmlRoot(render(source, configured));
        assertEquals(2, enabled.getElementsByTagNameNS(enabled.getNamespaceURI(), "page-sequence").getLength());
        assertEquals(1, enabled.getElementsByTagNameNS(enabled.getNamespaceURI(), "retrieve-marker").getLength());
        final var document = xmlRoot(render("= Guide\n:pdf-title-page: false\n:pdf-section-footer: off\n\nBody.", configured));
        assertEquals(1, document.getElementsByTagNameNS(document.getNamespaceURI(), "page-sequence").getLength());
        assertEquals(0, document.getElementsByTagNameNS(document.getNamespaceURI(), "retrieve-marker").getLength());
        assertFalse(render("Plain body.").contains("master-reference=\"title-page\""), "Untitled documents have no blank cover");
        assertThrows(IllegalArgumentException.class, () -> render("= Guide\n:pdf-title-page: invalid\n\nBody."));
    }

    @Test
    void codeHighlightingPreservesSourceEscapingAndMultilineState() throws Exception {
        final var source = "public class Example {\n\t/* multiline\ncomment */ String value = \"<x>&\";\n}\n";
        final var header = new Parser().parse("= Guide\n\n", new Parser.ParserContext(null)).header();
        final var document = new Document(header, new Body(List.of(new Code(source, Map.of("language", "java"), false, List.of()))));
        final var renderer = new FoRenderer();
        renderer.visit(document);
        final var literal = literal(renderer.result());
        assertEquals(source, literal.getTextContent());
        assertEquals(PdfTheme.defaults().primary(), attribute(renderer.result(), "inline", "public", "color"));
        assertEquals("#B8B8B8", attribute(renderer.result(), "inline", "comment */", "color"));
        assertEquals("#212121", literal.getAttribute("background-color"));
        assertEquals("#F4F4F4", literal.getAttribute("color"));
        assertEquals("#46F72F", attribute(renderer.result(), "inline", "\"<x>&\"", "color"));
        assertTrue(literal.getElementsByTagNameNS(literal.getNamespaceURI(), "inline").getLength() > 3);
        for (final var configuration : List.of(new PdfConfiguration().setSyntaxHighlighting(false),
                new PdfConfiguration().setAttributes(Map.of("pdf-syntax-highlighting", "false")))) {
            final var plain = new FoRenderer(configuration);
            plain.visit(document);
            assertEquals(source, literal(plain.result()).getTextContent());
            assertEquals(0, literal(plain.result()).getElementsByTagNameNS(literal.getNamespaceURI(), "inline").getLength());
        }
        final var custom = new FoRenderer(new PdfConfiguration().setAttributes(Map.of("pdf-code-keyword-color", "#123456")));
        custom.visit(document);
        assertEquals("#123456", attribute(custom.result(), "inline", "public", "color"));
    }

    @Test
    void markupTagsHaveTheirOwnColorOverrideAndKeepTheExistingPalette() throws Exception {
        final var source = "[source,xml]\n----\n<root name=\"value\">body</root>\n----\n";
        final var defaultFo = render(source);
        assertEquals("#02ABEA", attribute(defaultFo, "inline", "root", "color"));
        assertEquals("#02ABEA", attribute(defaultFo, "inline", "name", "color"));
        assertEquals("<root name=\"value\">body</root>\n", literal(defaultFo).getTextContent());
        final var customized = render(source, new PdfConfiguration().setAttributes(Map.of(
                "pdf-code-tag-color", "#123456", "pdf-code-attribute-color", "#654321")));
        assertEquals("#123456", attribute(customized, "inline", "root", "color"));
        assertEquals("#654321", attribute(customized, "inline", "name", "color"));
    }

    @Test
    void codeCalloutsRemainOnTheirSourceLinesAndUnknownLanguagesStayPlain() throws Exception {
        final var fo = render("""
                = Guide

                [source,java]
                ----
                final var first = 1; <1>
                // explanation
                final var second = 2; <2>
                ----
                <1> First explanation.
                <2> Second explanation.
                """);
        final var text = literal(fo).getTextContent();
        final var lines = text.split("\n");
        assertTrue(lines[0].contains("(1)"), text);
        assertFalse(lines[1].contains("(1)"), text);
        assertTrue(lines[2].contains("(2)"), text);
        final var root = xmlRoot(fo);
        final var lists = root.getElementsByTagNameNS(root.getNamespaceURI(), "list-block");
        assertEquals(1, lists.getLength());
        final var items = ((Element) lists.item(0)).getElementsByTagNameNS(root.getNamespaceURI(), "list-item");
        assertEquals(2, items.getLength());
        for (int i = 0; i < items.getLength(); i++) {
            final var item = (Element) items.item(i);
            final var labels = item.getElementsByTagNameNS(root.getNamespaceURI(), "list-item-label");
            final var bodies = item.getElementsByTagNameNS(root.getNamespaceURI(), "list-item-body");
            assertEquals(1, labels.getLength());
            assertEquals(1, bodies.getLength());
            assertEquals(item, labels.item(0).getParentNode());
            assertEquals(item, bodies.item(0).getParentNode());
            assertEquals("(" + (i + 1) + ")", labels.item(0).getTextContent());
            assertEquals(i == 0 ? "First explanation." : "Second explanation.", bodies.item(0).getTextContent());
            assertEquals("label-end()", ((Element) labels.item(0)).getAttribute("end-indent"));
            assertEquals("body-start()", ((Element) bodies.item(0)).getAttribute("start-indent"));
        }
        final var unknown = literal(render("= Guide\n\n[source,not-a-language]\n----\npublic <tag> & text\n----\n"));
        assertEquals(0, unknown.getElementsByTagNameNS(unknown.getNamespaceURI(), "inline").getLength());
        assertTrue(unknown.getTextContent().contains("public <tag> & text"));
    }

    @Test
    void inlineCodeHasIndependentCompactStylingAndCanBeCustomized() throws Exception {
        final var source = "= Guide\n\nUse `sample.value` here.";
        final var fo = render(source);
        assertEquals("90%", attribute(fo, "inline", "sample.value", "font-size"));
        assertEquals("#7D2493", attribute(fo, "inline", "sample.value", "color"));
        assertEquals("#F7EDFC", attribute(fo, "inline", "sample.value", "background-color"));
        final var inline = element(xmlRoot(fo), "inline", "sample.value");
        for (final var name : List.of("padding", "padding-left", "padding-right", "padding-start", "padding-end")) {
            assertFalse(inline.hasAttribute(name), name);
        }
        assertEquals("0.5pt solid #E9C7F3", inline.getAttribute("border"));
        final var custom = render(source, new PdfConfiguration().setAttributes(Map.of(
                "pdf-inline-code-color", "#111111", "pdf-inline-code-background", "#EEEEEE",
                "pdf-inline-code-border-color", "#222222")));
        assertEquals("#111111", attribute(custom, "inline", "sample.value", "color"));
        assertEquals("#EEEEEE", attribute(custom, "inline", "sample.value", "background-color"));
        assertEquals("0.5pt solid #222222", attribute(custom, "inline", "sample.value", "border"));
    }

    @Test
    void inlineHeadSnippetHasNoPaddingOrExtraTextBeforeFollowingProse() throws Exception {
        final var root = xmlRoot(render("Use `<head>` here."));
        final var inline = element(root, "inline", "<head>");
        assertEquals("Use <head> here.", inline.getParentNode().getTextContent());
        for (int i = 0; i < inline.getAttributes().getLength(); i++) {
            assertFalse(inline.getAttributes().item(i).getNodeName().startsWith("padding"));
        }
        assertEquals("#F7EDFC", inline.getAttribute("background-color"));
        assertEquals("0.5pt solid #E9C7F3", inline.getAttribute("border"));
    }

    @Test
    void tablesWithMoreThanTwoRowsInheritCompactFontSize() throws Exception {
        for (final int rows : List.of(1, 2, 3, 4)) {
            final var root = xmlRoot(render("[cols=\"1\",options=\"noheader\"]\n|===\n" + "|Value\n".repeat(rows) + "|===\n"));
            final var table = bodyTable(root);
            assertEquals(rows, table.getElementsByTagNameNS(root.getNamespaceURI(), "table-row").getLength());
            assertEquals(rows > 2 ? "9.5pt" : "10.5pt", inheritedAttribute(table, "font-size"));
            if (rows > 2) {
                assertEquals("9.5pt", table.getAttribute("font-size"));
            }
            final var cells = table.getElementsByTagNameNS(root.getNamespaceURI(), "table-cell");
            for (int i = 0; i < cells.getLength(); i++) {
                assertEquals(rows > 2 ? "9.5pt" : "10.5pt", inheritedAttribute((Element) cells.item(i), "font-size"));
            }
        }
    }

    @Test
    void monospacedAndLiteralColumnHeadersUseNormalSansHeaderStyling() throws Exception {
        final var root = xmlRoot(render("""
                [cols="m,l",options="header"]
                |===
                |Method |Literal
                |first |second
                |third |fourth
                |===
                """));
        final var header = (Element) root.getElementsByTagNameNS(root.getNamespaceURI(), "table-header").item(0);
        assertEquals("MethodLiteral", header.getTextContent().replace("\u200B", "").replace(" ", ""));
        assertPlainTableHeaders(header);

        // Listing is also a supported direct cell model, independently of how cols are parsed.
        final var renderer = new FoRenderer();
        final var documentHeader = new Parser().parse("", new Parser.ParserContext(null)).header();
        renderer.visit(new Document(documentHeader, new Body(List.of(new Table(List.of(
                List.of(new Listing("Literal", Map.of())),
                List.of(new Text(List.of(), "Body", Map.of()))), Map.of("header-option", ""))))));
        final var listingRoot = xmlRoot(renderer.result());
        final var listingHeader = (Element) listingRoot.getElementsByTagNameNS(listingRoot.getNamespaceURI(), "table-header").item(0);
        assertEquals("Literal", listingHeader.getTextContent().replace("\u200B", ""));
        assertPlainTableHeaders(listingHeader);
    }

    @Test
    void explicitInlineCodeInTablesInheritsTextColorWithoutDecorations() throws Exception {
        final var root = xmlRoot(render("[cols=\"1\",options=\"noheader\"]\n|===\n|Use `sample.value` here.\n|===\n",
                new PdfConfiguration().setAttributes(Map.of("pdf-inline-code-color", "#FF0000",
                        "pdf-inline-code-background", "#00FF00", "pdf-inline-code-border-color", "#0000FF"))));
        final var inline = element(root, "inline", "s\u200Ba\u200Bm\u200Bp\u200Bl\u200Be\u200B.\u200Bv\u200Ba\u200Bl\u200Bu\u200Be");
        assertEquals(PdfTheme.defaults().mono(), inline.getAttribute("font-family"));
        assertEquals("90%", inline.getAttribute("font-size"));
        assertEquals(PdfTheme.defaults().text(), inheritedAttribute(inline, "color"));
        for (int i = 0; i < inline.getAttributes().getLength(); i++) {
            final var name = inline.getAttributes().item(i).getNodeName();
            assertFalse(name.startsWith("background") || name.startsWith("border") || name.startsWith("padding"), name);
        }
    }

    @Test
    void tableBreakOpportunitiesOnlySeparateAdjacentNonWhitespaceGraphemes() throws Exception {
        final var value = "ab cd\te\u0301f \uD83D\uDC69\u200D\uD83D\uDCBBx ";
        final var renderer = new FoRenderer();
        final var header = new Parser().parse("", new Parser.ParserContext(null)).header();
        renderer.visit(new Document(header, new Body(List.of(new Table(
                List.of(List.of(new Text(List.of(), value, Map.of())),
                        List.of(new Text(List.of(), "xy", Map.of()))), Map.of())))));
        final var root = xmlRoot(renderer.result());
        final var cells = bodyTable(root).getElementsByTagNameNS(root.getNamespaceURI(), "table-cell");
        final var cell = cells.item(0);
        assertEquals("a\u200Bb c\u200Bd\te\u0301\u200Bf \uD83D\uDC69\u200D\uD83D\uDCBB\u200Bx ", cell.getTextContent());
        assertEquals("x\u200By", cells.item(1).getTextContent());
        final var outside = xmlRoot(render("ab cd"));
        assertFalse(outside.getTextContent().contains("\u200B"));
    }

    @Test
    void rootAndLiteralBlocksHaveExplicitLineHeightAndRetainedSpacing() throws Exception {
        for (final var source : List.of("[source,java]\n----\nint value = 1;\n----\n", "....\nliteral text\n....\n")) {
            final var fo = render(source);
            assertEquals("1.2", xmlRoot(fo).getAttribute("line-height"));
            final var block = literal(fo);
            assertEquals("1.25", block.getAttribute("line-height"));
            assertEquals("4pt", block.getAttribute("space-before"));
            assertEquals("7pt", block.getAttribute("space-after"));
            assertEquals("retain", block.getAttribute("space-before.conditionality"));
            assertEquals("retain", block.getAttribute("space-after.conditionality"));
        }
    }

    @Test
    void tocRowsUseMutedThemeColor() throws Exception {
        final var root = xmlRoot(render("= Guide\n:toc:\n\n== First\n\nText.\n\n=== Nested\n\nMore."));
        final var citations = root.getElementsByTagNameNS(root.getNamespaceURI(), "page-number-citation");
        assertEquals(2, citations.getLength());
        for (int i = 0; i < citations.getLength(); i++) {
            final var link = (Element) citations.item(i).getParentNode();
            final var row = (Element) link.getParentNode();
            assertEquals("block", row.getLocalName());
            assertEquals(PdfTheme.defaults().muted(), row.getAttribute("color"));
            assertEquals(PdfTheme.defaults().muted(), inheritedAttribute(link, "color"));
        }
    }

    private Element bodyTable(final Element root) {
        final var flows = root.getElementsByTagNameNS(root.getNamespaceURI(), "flow");
        final var content = (Element) flows.item(flows.getLength() - 1);
        return (Element) content.getElementsByTagNameNS(root.getNamespaceURI(), "table").item(0);
    }

    private void assertPlainTableHeaders(final Element header) {
        final var cells = header.getElementsByTagNameNS(header.getNamespaceURI(), "table-cell");
        assertTrue(cells.getLength() > 0);
        for (int i = 0; i < cells.getLength(); i++) {
            final var cell = (Element) cells.item(i);
            assertEquals("bold", inheritedAttribute(cell, "font-weight"));
            final var descendants = cell.getElementsByTagNameNS(header.getNamespaceURI(), "*");
            for (int j = 0; j < descendants.getLength(); j++) {
                final var descendant = (Element) descendants.item(j);
                assertEquals(PdfTheme.defaults().sans(), inheritedAttribute(descendant, "font-family"));
                for (final var name : List.of("background-color", "border", "padding", "white-space-collapse")) {
                    assertFalse(descendant.hasAttribute(name), name);
                }
            }
        }
    }

    private Element element(final Element root, final String localName, final String text) {
        final var elements = root.getElementsByTagNameNS(root.getNamespaceURI(), localName);
        for (int i = 0; i < elements.getLength(); i++) {
            final var element = (Element) elements.item(i);
            if (text.equals(element.getTextContent())) {
                return element;
            }
        }
        throw new AssertionError("No " + localName + " with text: " + text);
    }

    private String inheritedAttribute(final Element element, final String name) {
        for (var node = element; node != null; node = node.getParentNode() instanceof Element parent ? parent : null) {
            if (node.hasAttribute(name)) {
                return node.getAttribute(name);
            }
        }
        return "";
    }

    private Element literal(final String fo) throws Exception {
        final var root = xmlRoot(fo);
        final var blocks = root.getElementsByTagNameNS(root.getNamespaceURI(), "block");
        for (int i = 0; i < blocks.getLength(); i++) {
            final var block = (Element) blocks.item(i);
            if ("false".equals(block.getAttribute("white-space-collapse"))) {
                return block;
            }
        }
        throw new AssertionError("No literal block");
    }

    private Element xmlRoot(final String fo) throws Exception {
        final var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(fo))).getDocumentElement();
    }

    @Test
    void producesWellFormedFoWithEscapedContentAndTheme() throws Exception {
        final var fo = render("= A & B\n\n== Section\n\nA < B & \"quoted\".");
        final var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        final var xml = factory.newDocumentBuilder().parse(new InputSource(new StringReader(fo)));
        assertEquals("http://www.w3.org/1999/XSL/Format", xml.getDocumentElement().getNamespaceURI());
        assertTrue(xml.getDocumentElement().getTextContent().contains("A < B & \"quoted\"."));
        assertTrue(fo.contains(PdfTheme.defaults().primary()));
        assertTrue(fo.contains(PdfTheme.defaults().sans()));
        assertFalse(xml.getDocumentElement().getTextContent().contains("Table of Contents"), "No implicit table of contents");
    }

    @Test
    void conditionalsAttributesAndReferencesShareTheSectionIndex() {
        final var fo = render("""
                = Guide
                :enabled:
                :product: Yupiik

                ifdef::enabled[]
                == {product} details

                Visible content.
                endif::[]

                ifndef::enabled[]
                == Hidden

                Hidden content.
                endif::[]

                See <<_yupiik_details>>.
                """);
        assertTrue(fo.contains("Yupiik details"));
        assertTrue(fo.contains("Visible content"));
        assertFalse(fo.contains("Hidden content"));
        assertFalse(fo.contains("Hidden</fo:bookmark-title>"));
        assertTrue(fo.contains("internal-destination=\"_yupiik_details\""));
    }

    @Test
    void rejectsUnresolvedReferencesDuplicateIdsAndRemoteImages() {
        assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\n<<absent>>"));
        assertThrows(IllegalArgumentException.class, () -> render("""
                = Guide

                [#same]
                == First

                [#same]
                == Second
                """));
        assertTrue(render("= Guide\n\nvideo::movie.mp4[]").contains("video::movie.mp4[]"));
        assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\nimage::https://example.org/image.png[]"));
        assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\nimage::data:text/plain;base64,AQID[]"));
        assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\nimage::jar:https://example.org/images.jar!/image.png[]"));
    }

    @Test
    void resolvesImagesAgainstBaseAndImagesdir(@TempDir final Path base) throws Exception {
        Files.createDirectories(base.resolve("images"));
        final var image = base.resolve("images/local.svg");
        Files.writeString(image, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>");
        final var renderer = new FoRenderer(new PdfConfiguration().setAssetsBase(base));
        renderer.visit(new Parser().parse("= Guide\n:imagesdir: images\n\nimage::local.svg[]",
                new Parser.ParserContext(null)));
        assertTrue(renderer.result().contains(image.toUri().toASCIIString()));
    }

    @Test
    void normalizesEmbeddedImageDataWithoutTreatingPlusAsSpace() {
        final var svg = "<svg xmlns='http://www.w3.org/2000/svg'><text>A+B 100% &amp; C</text></svg>";
        final var encoded = svg.replace("%", "%25").replace("<", "%3C").replace(">", "%3E");
        final var base64 = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        assertTrue(renderImage("data:image/svg+xml," + encoded).contains("src=\"" + base64 + "\""));
        assertTrue(renderImage(base64).contains("src=\"" + base64 + "\""));
        assertTrue(renderImage("data:image/png,%00%FF+").contains("src=\"data:image/png;base64,AP8r\""));
        assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\nimage::data:image/png,%XY[]"));
        assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\nimage::data:image/png;base64,![]"));
    }

    @Test
    void linksIncludedPagesWithoutChangingTheirContent(@TempDir final Path base) throws Exception {
        final var mojo = Files.createDirectories(base.resolve("mojo"));
        Files.writeString(mojo.resolve("index.adoc"), """
                = Plugin Goals

                xref:asciidoc.adoc[asciidoc goal]
                xref:asciidoc.adoc#_configuration[goal configuration]
                <<asciidoc#_configuration,shorthand configuration>>
                xref:../intro.adoc[overview]
                """);
        Files.writeString(mojo.resolve("asciidoc.adoc"), """
                = Asciidoc Goal

                == Configuration

                xref:#_configuration[local configuration]
                link:#_configuration[local link]
                xref:./index.adoc[goal index]

                [#sample]
                Example.
                """);
        final var source = """
                = Guide

                [pdf-source="intro.adoc"]
                == Overview

                xref:mojo/index.adoc[Maven goals]
                xref:mojo/asciidoc.adoc#sample[example]

                === Configuration

                General configuration.

                [pdf-source="mojo/index.adoc"]
                == Maven Goal Reference

                include::mojo/index.adoc[lines=3..-1,leveloffset=+1]

                [pdf-source="mojo/asciidoc.adoc"]
                === Asciidoc

                include::mojo/asciidoc.adoc[lines=3..-1,leveloffset=+2]
                """;
        final var renderer = new FoRenderer();
        renderer.visit(new Parser().parse(source, new Parser.ParserContext(ContentResolver.of(base))));
        final var fo = renderer.result();
        assertEquals(attribute(fo, "block", "Maven Goal Reference", "id"), attribute(fo, "basic-link", "Maven goals", "internal-destination"));
        assertEquals(attribute(fo, "block", "Asciidoc", "id"), attribute(fo, "basic-link", "asciidoc goal", "internal-destination"));
        final var configurationId = attribute(fo, "basic-link", "goal configuration", "internal-destination");
        assertEquals(configurationId, attribute(fo, "basic-link", "shorthand configuration", "internal-destination"));
        assertEquals(configurationId, attribute(fo, "basic-link", "local configuration", "internal-destination"));
        assertEquals(configurationId, attribute(fo, "basic-link", "local link", "internal-destination"));
        assertEquals(attribute(fo, "block", "Overview", "id"), attribute(fo, "basic-link", "overview", "internal-destination"));
        assertEquals(attribute(fo, "*", "Example.", "id"), attribute(fo, "basic-link", "example", "internal-destination"));
        assertEquals(attribute(fo, "block", "Maven Goal Reference", "id"), attribute(fo, "basic-link", "goal index", "internal-destination"));
        assertFalse(fo.contains("external-destination="));
        assertValidDestinations(fo);
    }

    @Test
    void includedDocumentScopeIsRestoredAfterNestedChapters() throws Exception {
        final var fo = render("""
                = Guide

                [pdf-source="outer/index.adoc"]
                == Outer

                [pdf-source="nested/index.adoc"]
                === Inner

                xref:target.adoc[inner target]

                === Outer Continuation

                xref:target.adoc[outer target]

                [pdf-source="outer/target.adoc"]
                == Outer Target

                [pdf-source="nested/target.adoc"]
                == Inner Target
                """);
        assertEquals(attribute(fo, "block", "Inner Target", "id"), attribute(fo, "basic-link", "inner target", "internal-destination"));
        assertEquals(attribute(fo, "block", "Outer Target", "id"), attribute(fo, "basic-link", "outer target", "internal-destination"));
    }

    @Test
    void rejectsMissingIncludedPagesFragmentsAndDuplicateSources() {
        final var source = """
                = Guide

                [pdf-source="page.adoc"]
                == Page

                === Present

                xref:%s[link]
                """;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> render(source.formatted("absent.adoc")))
                .getMessage().contains("Unsupported AsciiDoc PDF cross-document reference"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> render(source.formatted("page.adoc#absent")))
                .getMessage().contains("Unresolved AsciiDoc PDF cross-document reference"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> render(source.formatted("#_present") + """

                [pdf-source="./page.adoc"]
                == Duplicate
                """)).getMessage().contains("Duplicate AsciiDoc PDF source document"));
    }

    @Test
    void includedDocumentMappingsAreResetBetweenVisits() throws Exception {
        final var renderer = new FoRenderer();
        renderer.visit(new Parser().parse("""
                = First

                [pdf-source="page.adoc"]
                == Page

                xref:page.adoc[self]
                """, new Parser.ParserContext(null)));
        assertEquals(attribute(renderer.result(), "block", "Page", "id"),
                attribute(renderer.result(), "basic-link", "self", "internal-destination"));
        assertThrows(IllegalArgumentException.class, () -> renderer.visit(new Parser().parse(
                "= Second\n\nxref:page.adoc[old page]", new Parser.ParserContext(null))));
        assertThrows(IllegalStateException.class, renderer::result);
    }

    @Test
    void includedDocumentsHaveIndependentDestinationNamespaces() throws Exception {
        final var fo = render("""
                = Guide
                :toc:

                [#_pdf_source_1__parser_attributes]
                Reserved user destination.

                [pdf-source="overview.adoc"]
                == Overview
                === Overview


                * See [[_parser_attributes]]xref:syntax.adoc#_parser_attributes[syntax attributes]
                * xref:overview.adoc#_parser_attributes[overview alias]

                [#example]
                Overview example.

                [pdf-source="syntax.adoc"]
                == Syntax

                === Parser attributes

                xref:#_parser_attributes[local attributes]
                <<Parser attributes,title reference>>
                xref:#example[local example]
                xref:overview.adoc#example[overview example]

                [#example]
                Syntax example.
                """);
        assertEquals(attribute(fo, "block", "Parser attributes", "id"),
                attribute(fo, "basic-link", "syntax attributes", "internal-destination"));
        assertEquals(attribute(fo, "basic-link", "syntax attributes", "internal-destination"),
                attribute(fo, "basic-link", "local attributes", "internal-destination"));
        assertEquals(attribute(fo, "basic-link", "syntax attributes", "internal-destination"),
                attribute(fo, "basic-link", "title reference", "internal-destination"));
        assertNotEquals(attribute(fo, "basic-link", "syntax attributes", "internal-destination"),
                attribute(fo, "basic-link", "overview alias", "internal-destination"));
        assertEquals(attribute(fo, "*", "Syntax example.", "id"),
                attribute(fo, "basic-link", "local example", "internal-destination"));
        assertEquals(attribute(fo, "*", "Overview example.", "id"),
                attribute(fo, "basic-link", "overview example", "internal-destination"));
        assertValidDestinations(fo);
        assertThrows(IllegalArgumentException.class, () -> render("""
                = Guide

                [pdf-source="one.adoc"]
                == One

                [#duplicate]
                First.

                [#duplicate]
                Second.
                """));
    }

    @Test
    void titleReferencesStayWithinTheirIncludedDocument() throws Exception {
        final var fo = render("""
                = Guide

                [pdf-source="one.adoc"]
                == One

                <<Partials,first>>

                === Partials

                [pdf-source="two.adoc"]
                == Two

                <<Partials,second>>

                === Partials
                """);
        assertNotEquals(attribute(fo, "basic-link", "first", "internal-destination"),
                attribute(fo, "basic-link", "second", "internal-destination"));
        assertValidDestinations(fo);
        assertValidDestinations(render("= Guide\n\n<<Partials>>\n\n== Partials\n\nContent."));
    }

    @Test
    void resolvesClasspathAndWebJarImagesWithoutProvisionedAssets(@TempDir final Path temp) throws Exception {
        final var resource = "META-INF/resources/webjars/pdf-test/1.0/images/logo.svg";
        final var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>";
        final var jar = temp.resolve("images.jar");
        try (final var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(resource));
            output.write(svg.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        try (final var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            final var url = loader.getResource(resource);
            final var source = "= Guide\n:imagesdir: META-INF/resources/webjars/pdf-test/1.0/images\n\nimage::logo.svg[]";
            final var configuration = new PdfConfiguration().setClassLoader(loader);
            assertTrue(render(source, configuration).contains("src=\"" + url.toExternalForm() + "\""));

            final var absentBase = temp.resolve("not-provisioned");
            configuration.setAssetsBase(absentBase);
            assertTrue(render(source, configuration).contains("src=\"" + url.toExternalForm() + "\""));
            assertTrue(render("= Guide\n:imagesdir: ignored\n\nimage::classpath:/" + resource + "[]", configuration)
                    .contains("src=\"" + url.toExternalForm() + "\""));
            assertTrue(render("= Guide\n\nimage::" + url.toExternalForm() + "[]", configuration)
                    .contains("src=\"" + url.toExternalForm() + "\""));
            assertFalse(Files.exists(absentBase), "Classpath images must not be extracted or require an assets directory");
            assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\nimage::missing.svg[]", configuration));
            assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\nimage::classpath:/missing.svg[]", configuration));

            final var local = temp.resolve(resource);
            Files.createDirectories(local.getParent());
            Files.writeString(local, svg);
            configuration.setAssetsBase(temp);
            final var localFo = render(source, configuration);
            assertTrue(localFo.contains("src=\"" + local.toUri().toASCIIString() + "\""));
            assertFalse(localFo.contains(url.toExternalForm()), "Local files take priority over implicit classpath images");
            assertTrue(render("= Guide\n\nimage::classpath:/" + resource + "[]", configuration)
                    .contains("src=\"" + url.toExternalForm() + "\""), "Explicit classpath images bypass local files");
            try (final var input = loader.getResourceAsStream(resource)) {
                assertEquals(svg, new String(input.readAllBytes(), StandardCharsets.UTF_8), "The classloader remains caller-owned");
            }
        }
    }

    @Test
    void resetsBetweenDocuments() {
        final var renderer = new FoRenderer();
        renderer.visit(new Parser().parse("= First\n\n== Old section\n\nOld content.", new Parser.ParserContext(null)));
        renderer.visit(new Parser().parse("= Second\n\nNew content.", new Parser.ParserContext(null)));
        assertFalse(renderer.result().contains("Old"));
        assertTrue(renderer.result().contains("New content"));
    }

    @Test
    void unknownMacrosAreEscapedInlineOrWrappedAsBlocks() {
        final var renderer = new FoRenderer();
        final var header = new Parser().parse("= Guide\n\n", new Parser.ParserContext(null)).header();
        renderer.visit(new Document(header, new Body(List.of(
                new Paragraph(List.of(new Macro("custom", "<inline>&", Map.of(), true)), Map.of()),
                new Macro("custom", "<block>&", Map.of(), false)))));
        assertTrue(renderer.result().contains("custom:&lt;inline&gt;&amp;[]"));
        assertTrue(renderer.result().contains("<fo:block space-after=\"7pt\">custom::&lt;block&gt;&amp;[]</fo:block>"));
        assertFalse(renderer.result().contains("<fo:block space-after=\"7pt\">custom:&lt;inline"));
    }

    @Test
    void unknownMacroModesAndAttributePrecedence() {
        final var source = "= Guide\n\nvideo::movie.mp4[]";
        final var fail = new PdfConfiguration().setUnknownMacro(UnknownMacro.FAIL);
        final var error = assertThrows(IllegalArgumentException.class, () -> render(source, fail));
        assertTrue(error.getMessage().contains("video"));
        assertTrue(error.getMessage().contains(FoRenderer.UNKNOWN_MACRO_ATTRIBUTE));
        assertFalse(render(source, new PdfConfiguration().setUnknownMacro(UnknownMacro.IGNORE)).contains("movie.mp4"));

        final var attributes = new PdfConfiguration().setUnknownMacro(UnknownMacro.FAIL)
                .setAttributes(Map.of(FoRenderer.UNKNOWN_MACRO_ATTRIBUTE, " TeXt "));
        assertTrue(render(source, attributes).contains("video::movie.mp4[]"));
        final var documentOverride = "= Guide\n:" + FoRenderer.UNKNOWN_MACRO_ATTRIBUTE + ": ignore\n\nvideo::movie.mp4[]";
        assertFalse(render(documentOverride, attributes).contains("movie.mp4"));
        assertThrows(IllegalArgumentException.class, () -> render(
                "= Guide\n:" + FoRenderer.UNKNOWN_MACRO_ATTRIBUTE + ": fail\n\nvideo::movie.mp4[]", attributes));
    }

    @Test
    void validatesUnknownMacroPolicyWithoutMacros() {
        final var invalid = new PdfConfiguration().setAttributes(Map.of(FoRenderer.UNKNOWN_MACRO_ATTRIBUTE, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> render("= Guide\n\nPlain text.", invalid));
        assertThrows(IllegalArgumentException.class, () -> render(
                "= Guide\n:" + FoRenderer.UNKNOWN_MACRO_ATTRIBUTE + ": invalid\n\nPlain text."));
    }

    @Test
    void configurationAttributesParticipateInContextIndexAndTocWithDocumentOverrides() {
        final var configuration = new PdfConfiguration().setAttributes(Map.of(
                "enabled", "", "product", "Configured", "toc", "", "toc-title", "Configured contents"));
        final var renderer = new FoRenderer(configuration);
        assertEquals("Configured", renderer.context().attribute("product"));
        // Whole-line conditionals are preprocessed by the parser, before renderer attributes are available.
        renderer.visit(new Parser(Map.of("enabled", "")).parse("""
                = Guide
                :product: Document
                :toc-title: Document contents

                ifdef::enabled[]
                == {product} details

                Visible content.
                endif::[]

                ifndef::enabled[]
                == Hidden

                Hidden content.
                endif::[]

                See <<_document_details>>.
                """, new Parser.ParserContext(null)));
        final var fo = renderer.result();
        assertEquals("Document", renderer.context().attribute("product"));
        assertTrue(fo.contains("<fo:bookmark-title>Document details</fo:bookmark-title>"));
        assertTrue(fo.contains("ref-id=\"_document_details\""));
        assertTrue(fo.contains("internal-destination=\"_document_details\""));
        assertTrue(fo.contains("Document contents"));
        assertTrue(fo.contains("Visible content"));
        assertFalse(fo.contains("Configured"));
        assertFalse(fo.contains("Hidden"));
    }

    @Test
    void customResolverReceivesSubstitutedImagesdirAndRemainsCallerOwned() {
        final var targets = new ArrayList<String>();
        final var closed = new ArrayList<Boolean>();
        try (final var resolver = new DataResolver(Path.of(".")) {
            @Override
            public DataUri apply(final String target) {
                targets.add(target);
                return new DataUri(() -> new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png");
            }

            @Override
            public void close() {
                closed.add(true);
                super.close();
            }
        }) {
            final var configuration = new PdfConfiguration().setResolver(resolver)
                    .setAttributes(Map.of("imagesdir", "{root}/images", "root", "https://example.org", "picture", "local.png"));
            final var fo = render("= Guide\n\nimage::{picture}[]", configuration);
            assertEquals(List.of("https://example.org/images/local.png"), targets);
            assertTrue(fo.contains("src=\"data:image/png;base64,AQID\""));
            assertTrue(closed.isEmpty());
        }
        assertEquals(List.of(true), closed);
    }

    @Test
    void toStringUsesTheResultContract() {
        final var renderer = new FoRenderer();
        assertThrows(IllegalStateException.class, renderer::result);
        assertThrows(IllegalStateException.class, renderer::toString);
        renderer.visit(new Parser().parse("= Guide\n\nContent.", new Parser.ParserContext(null)));
        assertEquals(renderer.result(), renderer.toString());
        assertThrows(IllegalArgumentException.class, () -> renderer.visit(new Parser().parse(
                "= Guide\n\n<<missing>>", new Parser.ParserContext(null))));
        assertThrows(IllegalStateException.class, renderer::toString);
    }

    private String attribute(final String fo, final String localName, final String text, final String attribute) throws Exception {
        final var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        final var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(fo)));
        // Markers are retrieved in static content, not displayed inside the section heading.
        final var markers = document.getElementsByTagNameNS("http://www.w3.org/1999/XSL/Format", "marker");
        while (markers.getLength() > 0) {
            final var marker = markers.item(0);
            marker.getParentNode().removeChild(marker);
        }
        final var nodes = document.getElementsByTagNameNS("http://www.w3.org/1999/XSL/Format", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            final var element = (Element) nodes.item(i);
            if (element.getTextContent().equals(text) && element.hasAttribute(attribute)) {
                return element.getAttribute(attribute);
            }
        }
        throw new AssertionError("No " + localName + " with " + attribute + " and text: " + text);
    }

    private void assertValidDestinations(final String fo) throws Exception {
        final var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        final var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(fo)));
        final var nodes = document.getElementsByTagNameNS("http://www.w3.org/1999/XSL/Format", "*");
        final var ids = new HashSet<String>();
        final var references = new ArrayList<String>();
        for (int i = 0; i < nodes.getLength(); i++) {
            final var element = (Element) nodes.item(i);
            if (element.hasAttribute("id")) {
                assertTrue(ids.add(element.getAttribute("id")), "Duplicate destination: " + element.getAttribute("id"));
            }
            for (final var attribute : List.of("internal-destination", "ref-id")) {
                if (element.hasAttribute(attribute)) {
                    references.add(element.getAttribute(attribute));
                }
            }
        }
        assertFalse(references.isEmpty());
        references.forEach(reference -> assertTrue(ids.contains(reference), "Missing destination: " + reference));
    }

    private String renderImage(final String target) {
        final var renderer = new FoRenderer();
        final var header = new Parser().parse("= Guide\n\n", new Parser.ParserContext(null)).header();
        renderer.visit(new Document(header, new Body(List.of(new Macro("image", target, Map.of(), false)))));
        return renderer.result();
    }

    private static String render(final String source) {
        return render(source, new PdfConfiguration());
    }

    private static String render(final String source, final PdfConfiguration configuration) {
        final var renderer = new FoRenderer(configuration);
        renderer.visit(new Parser().parse(source, new Parser.ParserContext(null)));
        return renderer.result();
    }
}
