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
package io.yupiik.maven.mojo;

import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.uri.DataResolver;
import io.yupiik.asciidoc.renderer.uri.DataUri;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.component.configurator.BasicComponentConfigurator;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsciidocMojoTest {
    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"40\">" +
            "<rect width=\"100\" height=\"40\" fill=\"#00ff00\"/></svg>";

    @ParameterizedTest
    @ValueSource(strings = {"HTML", "asciidoctor", "io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer", "shower"})
    void html(final String renderer, @TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Title\n\n== Section\n\nHello *world*.\n");
        final var output = dir.resolve("nested/document.html");
        mojo(input, output, "<renderer>" + renderer + "</renderer>").execute();
        final var html = Files.readString(output);
        assertTrue(html.contains("<strong>world</strong>"), html);
        assertTrue(html.contains("Section"), html);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MARKDOWN", "md", "io.yupiik.asciidoc.renderer.markdown.GithubFlavoredMarkdownRenderer"})
    void markdown(final String renderer, @TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Title\n\nHello *{name}*.\n");
        final var output = dir.resolve("nested/document.md");
        mojo(input, output, "<renderer>" + renderer + "</renderer><attributes><name>world</name></attributes>").execute();
        final var markdown = Files.readString(output);
        assertTrue(markdown.contains("**world**"), markdown);
        assertFalse(markdown.contains("<strong>"), markdown);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PDF", "io.yupiik.asciidoc.renderer.pdf.PdfRenderer"})
    void pdf(final String renderer, @TempDir final Path dir) throws Exception {
        final var includes = Files.createDirectories(dir.resolve("includes"));
        write(includes.resolve("body.adoc"), "== Details\n\nHello {name}.\n");
        final var input = write(dir.resolve("document.adoc"), "= Title\n:name: document\n\n" +
                "ifdef::enabled[]\ninclude::body.adoc[]\nendif::[]\n");
        final var output = dir.resolve("nested/document.pdf");
        mojo(input, output, "<renderer>" + renderer + "</renderer><base>" + includes + "</base>" +
                "<attributes><enabled/><name>configuration</name><toc/></attributes>").execute();
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            final var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Hello document."), text);
            assertTrue(text.contains("Details"), text);
            assertFalse(text.contains("Hello configuration."), text);
            final var resources = pdf.getPage(0).getResources();
            var inter = false;
            for (final var name : resources.getFontNames()) {
                final var font = resources.getFont(name);
                inter |= font.isEmbedded() && font.getName().contains("Inter");
            }
            assertTrue(inter, "Default fonts must be embedded from plugin dependencies");
        }
    }

    @Test
    void pdfThemeFopAndSeparateAssetBase(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Custom title\n\nimage::diagram.svg[]\n");
        final var assets = Files.createDirectories(dir.resolve("assets"));
        write(assets.resolve("diagram.svg"), SVG);
        final var fop = write(dir.resolve("fop config.xconf"), "<fop version=\"1.0\"><strict-configuration>true</strict-configuration></fop>");
        final var output = dir.resolve("document.pdf");
        mojo(input, output, "<renderer>pdf</renderer><assetsBase>" + assets + "</assetsBase>" +
                "<pdf><titlePage>false</titlePage><fopConfiguration>" + fop + "</fopConfiguration>" +
                "<theme><primary>#ff0000</primary><secondary>#0000ff</secondary><text>#212121</text>" +
                "<muted>#656565</muted><background>#f4f4f4</background><border>#e3e3e3</border>" +
                "<sans>Helvetica</sans><mono>Courier</mono><pageWidth>180mm</pageWidth>" +
                "<pageHeight>240mm</pageHeight><margin>15mm</margin></theme></pdf>").execute();
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(180 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), 0.1);
            assertEquals(240 * 72 / 25.4, pdf.getPage(0).getMediaBox().getHeight(), 0.1);
            assertTrue(new PDFTextStripper().getText(pdf).contains("Custom title"));
            final var image = new PDFRenderer(pdf).renderImage(0);
            var green = 0;
            var red = 0;
            for (var y = 0; y < image.getHeight(); y++) {
                for (var x = 0; x < image.getWidth(); x++) {
                    final var rgb = image.getRGB(x, y) & 0xffffff;
                    if (rgb == 0x00ff00) {
                        green++;
                    }
                    if (rgb == 0xff0000) {
                        red++;
                    }
                }
            }
            assertTrue(green > 500, "SVG must be loaded from assetsBase");
            assertTrue(red > 10, "Theme title color must be applied");
        }
    }

    @Test
    void pdfTitlePageAndSectionFooterOptions(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Cover title\n\n== Body section\n\nContent marker.\n");
        final var output = dir.resolve("document.pdf");
        mojo(input, output, "<renderer>pdf</renderer>").execute();
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
        mojo(input, output, "<renderer>pdf</renderer><pdf><titlePage>false</titlePage>" +
                "<syntaxHighlighting>false</syntaxHighlighting><sectionFooter>false</sectionFooter></pdf>").execute();
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(1, pdf.getNumberOfPages());
            final var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Content marker."), text);
            assertEquals(1, text.split("Body section", -1).length - 1, text);
            assertEquals(3, text.split("Cover title", -1).length - 1, text);
        }
    }

    @Test
    void pdfSyntaxHighlightingOption(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"),
                "= Code\n\n[source,java]\n----\npublic class Example { int value = 42; }\n----\n");
        final var output = dir.resolve("document.pdf");
        mojo(input, output, "<renderer>pdf</renderer><pdf><titlePage>false</titlePage></pdf>").execute();
        final int[] highlighted;
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            final var image = new PDFRenderer(pdf).renderImage(0);
            highlighted = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        }
        mojo(input, output, "<renderer>pdf</renderer><pdf><titlePage>false</titlePage>" +
                "<syntaxHighlighting>false</syntaxHighlighting></pdf>").execute();
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("public class Example"));
            final var image = new PDFRenderer(pdf).renderImage(0);
            final var plain = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            assertFalse(Arrays.equals(highlighted, plain), "Disabling highlighting must change code rendering");
        }
    }

    @Test
    void unusedPdfOptionsDoNotAffectHtml(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Title\n\nContent.\n");
        final var output = dir.resolve("document.html");
        mojo(input, output, "<renderer>html</renderer>").execute();
        final var expected = Files.readString(output);
        mojo(input, output, "<renderer>html</renderer><pdf><titlePage>false</titlePage>" +
                "<syntaxHighlighting>false</syntaxHighlighting><sectionFooter>false</sectionFooter>" +
                "<fopConfiguration>classpath:/invalid uri</fopConfiguration>" +
                "<theme><primary> </primary></theme></pdf>").execute();
        assertEquals(expected, Files.readString(output));
    }

    @Test
    void classpathFopAndPartialTheme(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Classpath\n\nText.\n");
        final var output = dir.resolve("document.pdf");
        mojo(input, output, "<renderer>pdf</renderer>" +
                "<pdf><fopConfiguration>classpath:/io/yupiik/maven/mojo/fop.xconf</fopConfiguration>" +
                "<theme><sans>Helvetica</sans><mono>Courier</mono></theme></pdf>").execute();
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            assertEquals(210 * 72 / 25.4, pdf.getPage(0).getMediaBox().getWidth(), 0.1);
            assertTrue(new PDFTextStripper().getText(pdf).contains("Classpath"));
        }
    }

    @Test
    void customResolverIsOwnedByTheGoal(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Resolver\n:imagesdir: icons\n\nimage::diagram.svg[]\n");
        final var output = dir.resolve("document.pdf");
        mojo(input, output, "<renderer>pdf</renderer><dataResolver>" + ImageResolver.class.getName() + "</dataResolver>").execute();
        assertEquals("icons/diagram.svg", Files.readString(dir.resolve("resolver-target")));
        assertTrue(Files.exists(dir.resolve("resolver-closed")));
        assertTrue(Files.size(output) > 1000);
    }

    @Test
    void unknownMacroPolicyAndResolverCleanupOnFailure(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Macros\n\ntooltip:foo[hint]\n");
        final var output = write(dir.resolve("document.pdf"), "preserved");
        final var mojo = mojo(input, output, "<renderer>pdf</renderer><unknownMacro>FAIL</unknownMacro>" +
                "<dataResolver>" + ImageResolver.class.getName() + "</dataResolver>");
        assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals("preserved", Files.readString(output));
        assertTrue(Files.exists(dir.resolve("resolver-closed")));

        write(input, "= Macros\n:yupiik-renderer-pdf-unknownMacro: ignore\n\nText. tooltip:foo[hint]\n");
        mojo.execute();
        try (final var pdf = Loader.loadPDF(output.toFile())) {
            final var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Text."), text);
            assertFalse(text.contains("tooltip"), text);
        }
    }

    @Test
    void omittedSettingsUseHtmlDefaults(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Title\n\n== Section\n\nContent.\n");
        final var output = dir.resolve("document.html");
        mojo(input, output, "").execute();
        assertTrue(Files.readString(output).contains("Content."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<pdf/>", "<pdf><theme/></pdf>", "<pdf><theme><pageWidth>200mm</pageWidth></theme></pdf>"})
    void nestedPdfDefaults(final String settings, @TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Title\n\n== Section\n\nContent.\n");
        final var output = dir.resolve("document.pdf");
        mojo(input, output, "<renderer>pdf</renderer>" + settings).execute();
        try (final var document = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(document).contains("Content."));
            final var expectedWidth = settings.contains("200mm") ? 200f : 210f;
            assertEquals(expectedWidth * 72 / 25.4f, document.getPage(0).getMediaBox().getWidth(), 0.1);
        }
    }

    @Test
    void copyAssetsToNewOutputDirectory(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Title\n\nContent.\n");
        final var css = write(dir.resolve("site.css"), "body { color: red; }");
        final var output = dir.resolve("nested/document.html");
        mojo(input, output, "<renderer>html</renderer><assets><asset>" + css + "</asset></assets>").execute();
        assertEquals(Files.readString(css), Files.readString(output.getParent().resolve("site.css")));
    }

    @Test
    void customVisitorStillWorks(@TempDir final Path dir) throws Exception {
        final var input = write(dir.resolve("document.adoc"), "= Title\n\nContent.\n");
        final var output = dir.resolve("document.txt");
        mojo(input, output, "<renderer>" + TextVisitor.class.getName() + "</renderer>").execute();
        assertEquals("custom visitor", Files.readString(output));
    }

    private AsciidocMojo mojo(final Path input, final Path output, final String configuration) throws Exception {
        final var mojo = new AsciidocMojo();
        final var xml = "<configuration><input>" + input + "</input><output>" + output + "</output>" +
                configuration + "</configuration>";
        try (final var world = new ClassWorld("test", getClass().getClassLoader())) {
            new BasicComponentConfigurator().configureComponent(mojo,
                    new XmlPlexusConfiguration(Xpp3DomBuilder.build(new StringReader(xml))), world.getRealm("test"));
        }
        return mojo;
    }

    private Path write(final Path path, final String text) throws IOException {
        Files.writeString(path, text);
        return path;
    }

    public static class ImageResolver extends DataResolver {
        private final Path base;

        public ImageResolver(final Path base) {
            super(base);
            this.base = base;
        }

        @Override
        public DataUri apply(final String target) {
            try {
                Files.writeString(base.resolve("resolver-target"), target);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            return new DataUri(() -> new ByteArrayInputStream(SVG.getBytes(StandardCharsets.UTF_8)), "image/svg+xml");
        }

        @Override
        public void close() {
            super.close();
            try {
                Files.writeString(base.resolve("resolver-closed"), "closed");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static class TextVisitor implements Visitor<String> {
        @Override
        public void visit(final Document document) {
            // The goal must still support explicitly configured custom visitors.
        }

        @Override
        public String result() {
            return "custom visitor";
        }
    }
}
