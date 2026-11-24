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

import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.UnknownMacro;
import io.yupiik.asciidoc.renderer.uri.DataResolver;
import io.yupiik.asciidoc.renderer.uri.DataUri;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRendererTest {
    @Test
    void tableCodeHasNoInvisibleAdvanceAndCalloutDescriptionsShareTheLabelBaseline(@TempDir final Path directory) throws Exception {
        final var renderer = new PdfRenderer(new PdfConfiguration().setOutput(directory.resolve("spacing.pdf")));
        renderer.visit(parse("""
                [cols="1",options="noheader"]
                |===
                |`<head>`. After.
                |===

                [source,java]
                ----
                int value = 1; <1>
                ----
                <1> Explanation stays beside its number.
                """));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            final var glyphs = new ArrayList<TextPosition>();
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(final TextPosition text) {
                    if (text.getFontSizeInPt() > 0 && !"\u200B".equals(text.getUnicode())) {
                        glyphs.add(text);
                    }
                    super.processTextPosition(text);
                }
            }.getText(pdf);
            final var endOfCode = glyphs.stream().filter(glyph -> ">".equals(glyph.getUnicode())).findFirst().orElseThrow();
            final var period = glyphs.get(glyphs.indexOf(endOfCode) + 1);
            assertEquals(".", period.getUnicode());
            final float gap = period.getXDirAdj() - endOfCode.getXDirAdj() - endOfCode.getWidthDirAdj();
            assertTrue(gap >= -0.1 && gap < 1, "Code must meet its following punctuation, gap=" + gap);
            final var label = glyphs.stream().filter(glyph -> ")".equals(glyph.getUnicode())).reduce((first, next) -> next).orElseThrow();
            final var explanation = glyphs.stream().filter(glyph -> "E".equals(glyph.getUnicode())).findFirst().orElseThrow();
            assertEquals(label.getYDirAdj(), explanation.getYDirAdj(), 0.1, "Callout label and description baseline");
            assertTrue(explanation.getXDirAdj() > label.getXDirAdj(), "Description belongs to the right of its label");
        }
    }

    @Test
    void coverMetadataAndSectionFootersRenderWithIndependentContentNumbering(@TempDir final Path directory) throws Exception {
        final var renderer = new PdfRenderer(new PdfConfiguration().setOutput(directory.resolve("cover.pdf")));
        renderer.visit(parse("""
                = Section guide
                Alice Example <alice@example.org>
                v2.0, 2026-11-24: Published edition
                :subtitle: Practical examples

                == First section

                === Configuration

                First content.

                <<<

                Continuation content.

                <<<

                == Second section

                Final content.
                """));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            assertEquals(4, pdf.getNumberOfPages());
            final var cover = pageText(pdf, 1);
            for (final var expected : List.of("Section guide", "Alice Example", "Practical examples", "2.0", "2026-11-24", "Published edition")) {
                assertTrue(cover.contains(expected), cover);
            }
            assertFalse(cover.contains("alice@example.org"));
            assertFalse(cover.contains("First content"));
            assertFalse(cover.contains("Page "));
            assertTextInsidePageMargins(pdf, 1);
            assertTrue(pdf.getPage(0).getAnnotations().stream()
                    .filter(PDAnnotationLink.class::isInstance).map(PDAnnotationLink.class::cast)
                    .map(PDAnnotationLink::getAction).filter(PDActionURI.class::isInstance).map(PDActionURI.class::cast)
                    .anyMatch(action -> "mailto:alice@example.org".equals(action.getURI())));
            final var first = pageText(pdf, 2);
            assertEquals(2, first.split("First section", -1).length - 1, "Section heading and footer: " + first);
            assertTrue(first.contains("Page 1 / 3"), first);
            final var continuation = pageText(pdf, 3);
            assertTrue(continuation.contains("First section / Configuration"), "Carry-over section marker: " + continuation);
            assertTrue(continuation.contains("Page 2 / 3"), continuation);
            final var last = pageText(pdf, 4);
            assertEquals(2, last.split("Second section", -1).length - 1, last);
            assertFalse(last.contains("First section"), last);
            assertTrue(last.contains("Page 3 / 3"), last);
        }
    }

    @Test
    void titlePageCanBeDisabledAndUntitledDocumentsDoNotGetEmptyCovers(@TempDir final Path directory) throws Exception {
        final var renderer = new PdfRenderer(new PdfConfiguration().setTitlePage(false)
                .setOutput(directory.resolve("no-cover.pdf")));
        renderer.visit(parse("= Compact guide\n\nContent."));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(pageText(pdf, 1).contains("Content."));
            assertTrue(pageText(pdf, 1).contains("Page 1 / 1"));
        }
        final var untitled = new PdfRenderer(new PdfConfiguration().setOutput(directory.resolve("untitled.pdf")));
        untitled.visit(parse("Content without a title."));
        try (final var pdf = Loader.loadPDF(untitled.result().toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(pageText(pdf, 1).contains("Content without a title."));
        }
    }

    @Test
    void coverLogoAndDarkVariantUseTheConfiguredAssetResolverAndFillThePage(@TempDir final Path directory) throws Exception {
        Files.writeString(directory.resolve("logo.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"20\">" +
                "<rect width=\"100\" height=\"20\" fill=\"#46F72F\"/></svg>");
        final var renderer = new PdfRenderer(new PdfConfiguration().setAssetsBase(directory)
                .setOutput(directory.resolve("dark-cover.pdf"))
                .setAttributes(Map.of("pdf-title-page-logo", "logo.svg", "pdf-title-page-background", "#056EBB",
                        "pdf-title-page-text-color", "#FFFFFF", "pdf-title-page-title-top", "10mm")));
        renderer.visit(parse("= Branded guide\n\nBody content."));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            assertEquals(2, pdf.getNumberOfPages());
            final var cover = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertEquals(0x056ebb, cover.getRGB(2, 2) & 0xffffff, "Cover background extends to the page edge");
            int greenPixels = 0;
            for (int y = 0; y < cover.getHeight(); y++) {
                for (int x = 0; x < cover.getWidth(); x++) {
                    if ((cover.getRGB(x, y) & 0xffffff) == 0x46f72f) {
                        greenPixels++;
                    }
                }
            }
            assertTrue(greenPixels > 500, "Configured logo must be visible on the cover");
            final var content = new PDFRenderer(pdf).renderImageWithDPI(1, 72);
            assertEquals(0xffffff, content.getRGB(2, 2) & 0xffffff, "Content remains white");
        }
    }

    @Test
    void sourceHighlightingProducesColoredSearchablePdfText(@TempDir final Path directory) throws Exception {
        final var renderer = new PdfRenderer(new PdfConfiguration().setTitlePage(false)
                .setOutput(directory.resolve("highlighted.pdf")));
        renderer.visit(parse("= Source\n\n[source,java]\n----\npublic class Example {\n  String value = \"<tag>&\";\n}\n----\n"));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            final var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("public class Example"), text);
            assertTrue(text.contains("\"<tag>&\""), text);
            final var image = new PDFRenderer(pdf).renderImageWithDPI(0, 144);
            int keywordPixels = 0;
            int stringPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    final int color = image.getRGB(x, y) & 0xffffff;
                    if (color == 0xd858ff) {
                        keywordPixels++;
                    } else if (color == 0x46f72f) {
                        stringPixels++;
                    }
                }
            }
            assertTrue(keywordPixels > 10, "Keyword color must be visibly rendered");
            assertTrue(stringPixels > 10, "String color must be visibly rendered");
        }
    }

    @Test
    void longInlineCodeWrapsInsideItsTableColumnWithoutClipping(@TempDir final Path directory) throws Exception {
        final var renderer = new PdfRenderer(new PdfConfiguration().setTitlePage(false)
                .setOutput(directory.resolve("table-wrapping.pdf")));
        renderer.visit(parse("= Table\n\n[cols=\"1,4\",options=\"header\"]\n|===\n|Key |Description\n|`" +
                "x".repeat(180) + "` |Remaining column.\n|===\n"));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            final var count = new int[1];
            final var margin = 20f * 72 / 25.4f;
            final var cellEnd = margin + (pdf.getPage(0).getMediaBox().getWidth() - 2 * margin) / 5 - 4;
            final var stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(final TextPosition text) {
                    if ("x".equals(text.getUnicode())) {
                        count[0]++;
                        assertTrue(text.getXDirAdj() >= margin + 4, "Text starts within the first cell");
                        assertTrue(text.getXDirAdj() + text.getWidthDirAdj() <= cellEnd,
                                "Long token must not overflow into the next cell: " + text.getXDirAdj());
                    }
                    super.processTextPosition(text);
                }
            };
            stripper.getText(pdf);
            assertEquals(180, count[0], "Wrapping must not discard any source glyphs");
        }
    }

    private void assertTextInsidePageMargins(final PDDocument pdf, final int page) throws Exception {
        final var margin = 20f * 72 / 25.4f;
        final var width = pdf.getPage(page - 1).getMediaBox().getWidth();
        final var stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(final TextPosition text) {
                assertTrue(text.getXDirAdj() >= margin - 1, "Text respects the left cover margin: " + text.getUnicode());
                assertTrue(text.getXDirAdj() + text.getWidthDirAdj() <= width - margin + 1,
                        "Text respects the right cover margin: " + text.getUnicode());
                super.processTextPosition(text);
            }
        };
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(pdf);
    }

    private String pageText(final PDDocument pdf, final int page) throws Exception {
        final var text = new PDFTextStripper();
        text.setStartPage(page);
        text.setEndPage(page);
        return text.getText(pdf);
    }

    @Test
    void rendersPercentEncodedEmbeddedSvg(@TempDir final Path directory) throws Exception {
        final var svg = "<!DOCTYPE svg PUBLIC '-//W3C//DTD SVG 1.1//EN' 'http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd'>" +
                "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='20'>" +
                "<rect width='100' height='20' fill='#46F72F'/></svg>";
        final var data = "data:image/svg+xml," + svg.replace("%", "%25").replace("<", "%3C").replace(">", "%3E").replace("#", "%23");
        final var renderer = new PdfRenderer(new PdfConfiguration().setTitlePage(false).setOutput(directory.resolve("embedded.pdf")));
        renderer.visit(new Document(parse("= Embedded SVG\n\n").header(),
                new Body(List.of(new Macro("image", data, Map.of(), false)))));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            final var image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            int greenPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) & 0xffffff) == 0x46f72f) {
                        greenPixels++;
                    }
                }
            }
            assertTrue(greenPixels > 500, "Embedded SVG must render, not just produce a valid PDF");
        }
    }

    @Test
    void classpathConfigurationFontsAndWebjarImagesNeedNoAssetDirectory(@TempDir final Path directory) throws Exception {
        final var jar = directory.resolve("assets.jar");
        final var imagePath = "META-INF/resources/webjars/example/1.0/diagram.svg";
        try (final var stream = new JarOutputStream(Files.newOutputStream(jar))) {
            stream.putNextEntry(new JarEntry(imagePath));
            stream.write(("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"20\">" +
                    "<rect width=\"100\" height=\"20\" fill=\"#46F72F\"/></svg>").getBytes(StandardCharsets.UTF_8));
            stream.closeEntry();
            final var config = new StringBuilder("<fop version=\"1.0\"><renderers><renderer mime=\"application/pdf\"><fonts>");
            for (final var style : new String[]{"Regular", "Bold"}) {
                final var name = "webfonts/Inter-" + style + ".otf";
                stream.putNextEntry(new JarEntry(name));
                try (final var input = getClass().getClassLoader().getResourceAsStream("com/formdev/flatlaf/fonts/inter/Inter-" + style + ".otf")) {
                    assertNotNull(input);
                    input.transferTo(stream);
                }
                stream.closeEntry();
                config.append("<font embed-url=\"classpath:/").append(name).append("\"><font-triplet name=\"Inter\" style=\"normal\" weight=\"")
                        .append("Bold".equals(style) ? "bold" : "normal").append("\"/></font>");
            }
            stream.putNextEntry(new JarEntry("pdf/fop.xconf"));
            stream.write(config.append("</fonts></renderer></renderers></fop>").toString().getBytes(StandardCharsets.UTF_8));
            stream.closeEntry();
        }
        try (final var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            final var missingBase = directory.resolve("not-provisioned");
            final var renderer = new PdfRenderer(new PdfConfiguration().setTitlePage(false).setClassLoader(loader)
                    .setAssetsBase(missingBase).setOutput(directory.resolve("classpath.pdf"))
                    .setFopConfiguration(URI.create("classpath:/pdf/fop.xconf"))
                    .setAttributes(Map.of("imagesdir", "META-INF/resources/webjars/example/1.0")));
            renderer.visit(parse("= Packaged\n\nPackaged content.\n\nimage::diagram.svg[]"));
            try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
                assertTrue(new PDFTextStripper().getText(pdf).contains("Packaged content."));
                final var image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
                int greenPixels = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        if ((image.getRGB(x, y) & 0xffffff) == 0x46f72f) {
                            greenPixels++;
                        }
                    }
                }
                assertTrue(greenPixels > 500, "WebJar image must render from its jar URL");
                for (final var fontName : pdf.getPage(0).getResources().getFontNames()) {
                    assertTrue(pdf.getPage(0).getResources().getFont(fontName).isEmbedded());
                }
            }
            assertFalse(Files.exists(missingBase), "No assets must be extracted");
        }
    }

    @Test
    void missingFontDependencyHasAnExplicitHintBeforeOpeningOutput(@TempDir final Path directory) throws Exception {
        final var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public URL getResource(final String name) {
                return name.startsWith("com/formdev/flatlaf/fonts/") ? null : super.getResource(name);
            }
        };
        final var output = directory.resolve("existing.pdf");
        Files.writeString(output, "keep me");
        final var renderer = new PdfRenderer(new PdfConfiguration().setClassLoader(loader).setOutput(output));
        renderer.visit(parse("= Guide\n\nContent."));
        final var error = assertThrows(IllegalStateException.class, renderer::result);
        assertTrue(error.getMessage().contains("com.formdev:flatlaf-fonts-inter:4.1"), error.getMessage());
        assertEquals("keep me", Files.readString(output));
    }

    @Test
    void configurationAttributesMacroPolicyAndCallerOwnedResolver(@TempDir final Path directory) throws Exception {
        final var requested = new StringBuilder();
        final var resolver = new DataResolver(directory) {
            @Override
            public DataUri apply(final String target) {
                requested.append(target);
                return new DataUri(() -> new ByteArrayInputStream(("<svg xmlns=\"http://www.w3.org/2000/svg\" " +
                        "width=\"100\" height=\"20\"><rect width=\"100\" height=\"20\" fill=\"#46F72F\"/></svg>")
                        .getBytes(StandardCharsets.UTF_8)), "image/svg+xml");
            }

            @Override
            public void close() {
                throw new AssertionError("Caller owns the resolver");
            }
        };
        final var renderer = new PdfRenderer(new PdfConfiguration().setTitlePage(false).setResolver(resolver)
                .setOutput(directory.resolve("resolved.pdf"))
                .setUnknownMacro(UnknownMacro.TEXT)
                .setAttributes(Map.of("imagesdir", "virtual", "product", "Configured product")));
        renderer.visit(parse("= Guide\n\n{product}\n\nimage::diagram.svg[]\n\ncustom::asset[caption]"));
        assertEquals("virtual/diagram.svg", requested.toString());
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            final var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Configured product"), text);
            assertTrue(text.contains("custom::asset[caption]"), text);
            final var image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            int greenPixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) & 0xffffff) == 0x46f72f) {
                        greenPixels++;
                    }
                }
            }
            assertTrue(greenPixels > 500, "Resolver's SVG must be embedded in the PDF");
        }
    }

    @Test
    void customThemeAndFopConfiguration(@TempDir final Path directory) throws Exception {
        final var configuration = directory.resolve("fop.xconf");
        Files.writeString(configuration, "<fop version=\"1.0\"/>");
        final var theme = new PdfTheme("#D858FF", "#056EBB", "#212121", "#656565", "#F4F4F4", "#E3E3E3",
                "Helvetica", "Courier", "150mm", "200mm", "15mm");
        final var output = directory.resolve("custom.pdf");
        final var renderer = new PdfRenderer(new PdfConfiguration().setAssetsBase(directory).setTheme(theme)
                .setFopConfiguration(configuration).setOutput(output));
        renderer.visit(parse("= Custom\n\nCustom page."));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            assertEquals(150 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), 0.1);
            assertEquals(200 * 72 / 25.4, pdf.getPage(0).getMediaBox().getHeight(), 0.1);
            assertTrue(new PDFTextStripper().getText(pdf).contains("Custom page."));
        }
    }

    @Test
    void tableHeadersRepeatAcrossPages(@TempDir final Path directory) throws Exception {
        final var source = new StringBuilder("= Large table\n\n[options=\"header\"]\n|===\n|Repeated heading |Value\n");
        for (int i = 0; i < 120; i++) {
            source.append("|Row ").append(i).append(" |Value ").append(i).append('\n');
        }
        source.append("|===\n");
        final var renderer = new PdfRenderer(new PdfConfiguration().setTitlePage(false).setOutput(directory.resolve("table.pdf")));
        renderer.visit(parse(source.toString()));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            assertTrue(pdf.getNumberOfPages() > 1);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                final var text = new PDFTextStripper();
                text.setStartPage(page);
                text.setEndPage(page);
                assertTrue(text.getText(pdf).contains("Repeated heading"), "Header on page " + page);
            }
        }
    }

    @Test
    void localSvgAndNestedBlockContent(@TempDir final Path directory) throws Exception {
        Files.writeString(directory.resolve("diagram.svg"), """
                <svg xmlns="http://www.w3.org/2000/svg" width="100" height="20">
                  <rect width="100" height="20" fill="#D858FF"/>
                </svg>
                """);
        final var renderer = new PdfRenderer(new PdfConfiguration().setTitlePage(false).setAssetsBase(directory)
                .setOutput(directory.resolve("blocks.pdf")));
        renderer.visit(parse("""
                = Blocks

                image::diagram.svg[]

                Term:: Definition text.

                . Ordered first
                . Ordered second

                [quote]
                ____
                Quoted content.
                ____

                --
                Open block content.
                --

                '''

                Last line.
                """));
        try (final var pdf = Loader.loadPDF(renderer.result().toFile())) {
            final var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Page 1 / " + pdf.getNumberOfPages()), text);
            for (final var expected : new String[]{"Definition text", "Ordered first", "Quoted content",
                    "Open block content", "Last line"}) {
                assertTrue(text.contains(expected), text);
            }
            final var image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            int purplePixels = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) & 0xffffff) == 0xd858ff) {
                        purplePixels++;
                    }
                }
            }
            assertTrue(purplePixels > 500, "SVG rectangle must be visible in the PDF");
        }
    }

    @Test
    void rendersRealPdfWithTextBookmarksLinksAndEmbeddedFonts(@TempDir final Path directory) throws Exception {
        final var destination = directory.resolve("nested/guide.pdf");
        final Visitor<Path> renderer = new PdfRenderer(new PdfConfiguration().setOutput(destination));
        renderer.visit(parse("""
                = Yupiik guide
                :toc:

                Introduction with *bold* and _italic_, `code` and https://www.yupiik.com[Yupiik].

                See <<details>>.

                [#details]
                == Details

                NOTE: Helpful information.

                * First item
                * Second item

                [source,java]
                ----
                final var text = "Bonjour";
                ----

                [cols="1,2",options="header"]
                |===
                |Name |Value
                |Language |Fran\u00e7ais
                |===

                <<<

                == Final page

                Last paragraph.
                """));
        assertFalse(Files.exists(destination), "visit must not write the PDF");
        assertEquals(destination, renderer.result());
        try (final var pdf = Loader.loadPDF(destination.toFile())) {
            assertEquals("Yupiik guide", pdf.getDocumentInformation().getTitle());
            assertEquals("Yupiik AsciiDoc Java", pdf.getDocumentInformation().getCreator());
            assertTrue(pdf.getNumberOfPages() >= 2);
            final var text = new PDFTextStripper().getText(pdf);
            for (final var expected : new String[]{"Yupiik guide", "Details", "Helpful information", "First item",
                    "Bonjour", "Language", "Fran\u00e7ais", "Final page", "Last paragraph"}) {
                assertTrue(text.contains(expected), () -> "Missing '" + expected + "' in " + text);
            }
            assertNotNull(pdf.getDocumentCatalog().getDocumentOutline());
            assertTrue(pdf.getPage(1).getAnnotations().size() > 0);
            boolean sans = false;
            boolean mono = false;
            for (final var page : pdf.getPages()) {
                for (final var name : page.getResources().getFontNames()) {
                    final var font = page.getResources().getFont(name);
                    assertTrue(font.isEmbedded(), font.getName());
                    sans |= font.getName().contains("Inter");
                    mono |= font.getName().contains("JetBrainsMono");
                }
            }
            assertTrue(sans, "Bundled document font");
            assertTrue(mono, "Bundled code font");
        }
    }

    @Test
    void resultIsLazyIdempotentAndRendererCanBeReused(@TempDir final Path directory) throws Exception {
        final var output = directory.resolve("reused.pdf");
        final var renderer = new PdfRenderer(new PdfConfiguration().setOutput(output));
        assertThrows(IllegalStateException.class, renderer::result);
        renderer.visit(parse("= First\n\nFirst content."));
        assertFalse(Files.exists(output));
        try (final var first = Loader.loadPDF(renderer.result().toFile())) {
            assertTrue(new PDFTextStripper().getText(first).contains("First content"));
        }
        // An existing result is returned without touching the destination again.
        Files.writeString(output, "unchanged");
        assertEquals(output, renderer.result());
        assertEquals("unchanged", Files.readString(output));
        renderer.visit(parse("= Second\n\nSecond content."));
        try (final var second = Loader.loadPDF(renderer.result().toFile())) {
            final var text = new PDFTextStripper().getText(second);
            assertTrue(text.contains("Second content"));
            assertFalse(text.contains("First content"));
        }
    }

    @Test
    void invalidDocumentDoesNotTruncateDestination(@TempDir final Path directory) throws Exception {
        final var output = directory.resolve("existing.pdf");
        Files.writeString(output, "keep me");
        final var renderer = new PdfRenderer(new PdfConfiguration().setOutput(output));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.visit(parse("= Invalid\n\nSee <<missing>>.")));
        assertThrows(IllegalStateException.class, renderer::result);
        assertEquals("keep me", Files.readString(output));
    }

    @Test
    void optionalDependencyFailsOnlyWhenPdfIsRequested(@TempDir final Path directory) throws Exception {
        final var classes = Path.of(PdfRenderer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final var tests = Path.of(PdfRendererTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final var log = directory.resolve("without-fop.log");
        final var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes + File.pathSeparator + tests, MissingFopProbe.class.getName(),
                directory.resolve("missing.pdf").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    private static Document parse(final String input) {
        return new Parser().parse(input, new Parser.ParserContext(null));
    }

    /** Runs in a JVM whose classpath deliberately contains no optional dependencies. */
    public static final class MissingFopProbe {
        public static void main(final String[] args) {
            final var document = new Parser().parse("= Guide\n\nContent.", new Parser.ParserContext(null));
            final var fo = new FoRenderer();
            fo.visit(document);
            if (!fo.result().contains("Content.")) {
                throw new AssertionError("FO must work without FOP");
            }
            final var plainCode = new FoRenderer(new PdfConfiguration().setSyntaxHighlighting(false));
            plainCode.visit(new Parser().parse("= Guide\n\n[source,java]\n----\npublic class Example {}\n----\n",
                    new Parser.ParserContext(null)));
            if (!plainCode.result().contains("public class Example {}")) {
                throw new AssertionError("Disabled syntax highlighting must not require the tokenizer module");
            }
            final var output = Path.of(args[0]);
            final var renderer = new PdfRenderer(new PdfConfiguration().setOutput(output));
            renderer.visit(document);
            try {
                renderer.result();
                throw new AssertionError("Missing FOP must fail");
            } catch (final IllegalStateException expected) {
                if (!expected.getMessage().contains("org.apache.xmlgraphics:fop:2.12")) {
                    throw expected;
                }
                if (Files.exists(output)) {
                    throw new AssertionError("Missing FOP must not open the destination");
                }
            }
        }
    }
}
