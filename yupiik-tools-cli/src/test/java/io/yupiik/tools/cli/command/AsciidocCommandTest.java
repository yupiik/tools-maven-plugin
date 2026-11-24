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
package io.yupiik.tools.cli.command;

import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.uri.DataResolver;
import io.yupiik.asciidoc.renderer.uri.DataUri;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static io.yupiik.tools.cli.launcher.Main.main;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsciidocCommandTest {
    @Test
    void unusedPdfConfigurationDoesNotAffectOtherRenderers(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("output.txt");
        Files.writeString(input, "= Deck\n\n== Slide\n\nContent.\n");
        for (final var renderer : new String[]{"html", "asciidoctor", "shower", "markdown", "md", TitleVisitor.class.getName()}) {
            for (final var option : new String[]{"--pdf-fop-configuration=classpath:/invalid uri", "--pdf-theme-primary= ",
                    "--pdf-title-page=false", "--pdf-syntax-highlighting=false", "--pdf-section-footer=false"}) {
                main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=" + renderer, option);
                final var rendered = Files.readString(output);
                assertTrue(rendered.contains(renderer.equals(TitleVisitor.class.getName()) ? "Visited Deck" : "Content."),
                        () -> renderer + " with " + option + ": " + rendered);
            }
        }
    }

    @Test
    void pdfTitlePageAndSectionFooterOptions(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("output.pdf");
        Files.writeString(input, "= Cover title\n\n== Body section\n\nContent marker.\n");
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf");
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(2, pdf.getNumberOfPages());
            final var stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            final var cover = stripper.getText(pdf);
            assertTrue(cover.contains("Cover title"), cover);
            assertFalse(cover.contains("Content marker."), cover);
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            final var body = stripper.getText(pdf);
            assertTrue(body.contains("Content marker."), body);
            assertEquals(2, body.split("Body section", -1).length - 1, body);
        }
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf",
                "--pdf-title-page=false", "--pdf-syntax-highlighting=false", "--pdf-section-footer=false");
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            final var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Content marker."), text);
            assertEquals(1, text.split("Body section", -1).length - 1, text);
            assertEquals(3, text.split("Cover title", -1).length - 1, text);
        }
    }

    @Test
    void pdfSyntaxHighlightingOption(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("output.pdf");
        Files.writeString(input, "= Code\n\n[source,java]\n----\npublic class Example { int value = 42; }\n----\n");
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf", "--pdf-title-page=false");
        final int[] highlighted;
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            final var image = new PDFRenderer(pdf).renderImage(0);
            highlighted = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        }
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf",
                "--pdf-title-page=false", "--pdf-syntax-highlighting=false");
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("public class Example"));
            final var image = new PDFRenderer(pdf).renderImage(0);
            final var plain = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            assertFalse(Arrays.equals(highlighted, plain), "Disabling highlighting must change code rendering");
        }
    }

    @Test
    void selectedPdfConfigurationIsStillValidated(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("output.pdf");
        Files.writeString(input, "= Guide\n\nContent.\n");
        Files.writeString(output, "preserved");
        for (final var option : new String[]{"--pdf-fop-configuration=classpath:/invalid uri", "--pdf-theme-primary= "}) {
            assertThrows(IllegalArgumentException.class, () -> main("asciidoc", "--input=" + input,
                    "--output=" + output, "--renderer=pdf", option));
            assertEquals("preserved", Files.readString(output));
        }
    }

    @Test
    void htmlAliasesAttributesIncludesAndAssets(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        Files.writeString(input, """
                = Example
                :name: document

                == Section

                include::part.adoc[]
                """);
        Files.writeString(temp.resolve("part.adoc"), """
                ifdef::enabled[]
                Hello {name} and {fallback}.
                endif::[]
                """);
        final var assets = Files.createDirectories(temp.resolve("assets/css"));
        Files.writeString(assets.resolve("site.css"), "body { color: red; }");
        for (final var renderer : new String[]{"HtMl", "ASCIIDOCTOR"}) {
            final var output = temp.resolve(renderer + "/index.html");
            main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=" + renderer,
                    "--attributes=enabled=\nname=cli\nfallback=global", "--assets=" + assets.getParent(),
                    "--sectionTag=section", "--sectionIdOnTitle=false", "--skipSectionBody=true",
                    "--skipGlobalContentWrapper=true", "--supportsDataAttributes=false", "--dataUriForAscii2Svg=false");
            final var html = Files.readString(output);
            assertTrue(html.contains("Hello document and global."), html);
            assertTrue(html.contains("<section"), html);
            assertFalse(html.contains("class=\"sectionbody\""), html);
            assertEquals("body { color: red; }", Files.readString(output.getParent().resolve("css/site.css")));
        }
    }

    @Test
    void markdownAliasesAndExplicitIncludeBase(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        Files.writeString(input, "= Title\n\ninclude::part.adoc[]\n");
        final var includes = Files.createDirectories(temp.resolve("includes"));
        Files.writeString(includes.resolve("part.adoc"), "A *bold* {name}.\n");
        for (final var renderer : new String[]{"MaRkDoWn", "MD"}) {
            final var output = temp.resolve(renderer + ".md");
            main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=" + renderer,
                    "--base=" + includes, "--attributes=name=example");
            final var markdown = Files.readString(output);
            assertTrue(markdown.contains("A **bold** example."), markdown);
        }
    }

    @Test
    void showerAndCustomVisitor(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        Files.writeString(input, "= Deck\n\n== Slide\n\nContent.\n");
        final var output = temp.resolve("output.html");
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=ShOwEr");
        assertTrue(Files.readString(output).contains("shower list"));
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=" + TitleVisitor.class.getName());
        assertEquals("Visited Deck", Files.readString(output));
    }

    @Test
    void macroPolicy(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("output.md");
        Files.writeString(input, "= Macros\n\nBefore tooltip:foo[a hint] after.\n");
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=md", "--unknownMacro=text");
        assertTrue(Files.readString(output).contains("tooltip:foo[a hint]"));
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=md", "--unknownMacro=ignore");
        assertFalse(Files.readString(output).contains("tooltip"));
        assertThrows(IllegalArgumentException.class, () -> main("asciidoc", "--input=" + input,
                "--output=" + output, "--renderer=md", "--unknownMacro=fail"));
    }

    @Test
    void realPdfWithDefaultFontsAndParserAttributes(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("nested/output.pdf");
        Files.writeString(input, """
                = PDF guide
                :name: document

                ifdef::enabled[]
                Hello {name} and {fallback}.
                endif::[]
                """);
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=PdF",
                "--attributes=enabled=\nname=cli\nfallback=global");
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Hello document and global."));
            assertEquals(210 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), 1);
            for (final var font : pdf.getPage(0).getResources().getFontNames()) {
                assertTrue(pdf.getPage(0).getResources().getFont(font).isEmbedded());
            }
        }
    }

    @Test
    void pdfThemeFopPathAndUri(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        Files.writeString(input, "= Custom PDF\n\nCustom typography and page size.\n\n`code`\n");
        final var configuration = temp.resolve("fop config.xconf");
        Files.writeString(configuration, "<fop version=\"1.0\"><strict-validation>true</strict-validation></fop>");
        for (final var location : new String[]{configuration.toString(), configuration.toUri().toString()}) {
            final var output = temp.resolve("custom.pdf");
            main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf",
                    "--pdf-fop-configuration=" + location,
                    "--pdf-theme-primary=#123456", "--pdf-theme-secondary=#234567", "--pdf-theme-text=#345678",
                    "--pdf-theme-muted=#456789", "--pdf-theme-background=#567890", "--pdf-theme-border=#678901",
                    "--pdf-theme-sans=Helvetica", "--pdf-theme-mono=Courier",
                    "--pdf-theme-page-width=180mm", "--pdf-theme-page-height=240mm", "--pdf-theme-margin=15mm");
            try (final var pdf = Loader.loadPDF(output.toFile())) {
                assertTrue(new PDFTextStripper().getText(pdf).contains("Custom typography and page size."));
                assertEquals(180 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), 1);
                assertEquals(240 * 72 / 25.4, pdf.getPage(0).getMediaBox().getHeight(), 1);
            }
        }
    }

    @Test
    void pdfWithFopOnlyKeepsDefaultPageGeometry(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("default-theme.pdf");
        final var configuration = temp.resolve("fop.xconf");
        Files.writeString(input, "= Default theme\n\nDefault typography.\n");
        Files.writeString(configuration, "<fop version=\"1.0\"/>");
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf",
                "--pdf-fop-configuration=" + configuration);
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Default typography."));
            assertEquals(210 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), 1);
            assertEquals(297 * 72 / 25.4, pdf.getPage(0).getMediaBox().getHeight(), 1);
        }
    }

    @Test
    void partialPdfThemeKeepsOtherDefaults(@TempDir final Path temp) throws IOException {
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("partial-theme.pdf");
        Files.writeString(input, "= Partial theme\n\nDefault typography.\n");
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf",
                "--pdf-theme-page-width=180mm");
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Default typography."));
            assertEquals(180 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), 1);
            assertEquals(297 * 72 / 25.4, pdf.getPage(0).getMediaBox().getHeight(), 1);
            for (final var font : pdf.getPage(0).getResources().getFontNames()) {
                assertTrue(pdf.getPage(0).getResources().getFont(font).isEmbedded());
            }
        }
    }

    @Test
    void classpathFopAndImages(@TempDir final Path temp) throws Exception {
        // Only Java sources are needed for this test fixture: create and remove resources in the test classpath.
        final var root = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        final var configuration = Files.createTempFile(root, "cli-fop-", ".xconf");
        final var image = Files.createTempFile(root, "cli-image-", ".svg");
        try {
            Files.writeString(configuration, "<fop version=\"1.0\"/>");
            Files.writeString(image, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"20\">" +
                    "<rect width=\"100\" height=\"20\" fill=\"#46F72F\"/></svg>");
            final var input = temp.resolve("input.adoc");
            final var output = temp.resolve("classpath.pdf");
            Files.writeString(input, "= Classpath\n\nClasspath configuration.\n\nimage::classpath:/" + image.getFileName() + "[]\n");
            main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf",
                    "--pdf-fop-configuration=classpath:/" + configuration.getFileName(), "--pdf-theme-sans=Helvetica",
                    "--pdf-title-page=false");
            try (final var pdf = Loader.loadPDF(output.toFile())) {
                assertTrue(new PDFTextStripper().getText(pdf).contains("Classpath configuration."));
                final var rendered = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
                int green = 0;
                for (int y = 0; y < rendered.getHeight(); y++) {
                    for (int x = 0; x < rendered.getWidth(); x++) {
                        if ((rendered.getRGB(x, y) & 0xffffff) == 0x46f72f) {
                            green++;
                        }
                    }
                }
                assertTrue(green > 500, "Classpath image must be rendered");
            }
        } finally {
            Files.deleteIfExists(configuration);
            Files.deleteIfExists(image);
        }
    }

    @Test
    void assetsBaseIsSeparateFromIncludesAndResolverIsClosed(@TempDir final Path temp) throws IOException {
        final var includes = Files.createDirectories(temp.resolve("includes"));
        final var images = Files.createDirectories(temp.resolve("images"));
        Files.writeString(includes.resolve("part.adoc"), "Included content.\n\nimage::diagram.svg[]\n");
        Files.writeString(images.resolve("diagram.svg"), """
                <svg xmlns="http://www.w3.org/2000/svg" width="100" height="20">
                <rect width="100" height="20" fill="#46F72F"/></svg>
                """);
        final var input = temp.resolve("input.adoc");
        final var output = temp.resolve("resolved.pdf");
        Files.writeString(input, "= Resolved\n\ninclude::part.adoc[]\n");
        main("asciidoc", "--input=" + input, "--output=" + output, "--renderer=pdf",
                "--base=" + includes, "--assetsBase=" + images, "--dataResolver=" + RecordingResolver.class.getName());
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Included content."));
        }
        assertEquals("diagram.svg", Files.readString(images.resolve("requested.txt")));
        assertTrue(Files.exists(images.resolve("closed.txt")));

        Files.delete(images.resolve("closed.txt"));
        Files.writeString(input, "= Failure\n\nUnknown tooltip:foo[hint].\n");
        assertThrows(IllegalArgumentException.class, () -> main("asciidoc", "--input=" + input,
                "--output=" + output, "--renderer=pdf", "--assetsBase=" + images,
                "--dataResolver=" + RecordingResolver.class.getName(), "--unknownMacro=fail"));
        assertTrue(Files.exists(images.resolve("closed.txt")), "Resolver must close on rendering failure too");
    }

    public static class TitleVisitor implements Visitor<String> {
        private String title;

        @Override
        public void visit(final Document document) {
            title = document.header().title();
        }

        @Override
        public String result() {
            return "Visited " + title;
        }
    }

    public static class RecordingResolver extends DataResolver {
        private final Path base;

        public RecordingResolver(final Path base) {
            super(base);
            this.base = base;
        }

        @Override
        public DataUri apply(final String path) {
            try {
                Files.writeString(base.resolve("requested.txt"), path);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            return super.apply(path);
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                try {
                    Files.writeString(base.resolve("closed.txt"), "closed");
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
