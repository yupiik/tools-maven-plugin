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
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.UnknownMacro;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer;
import io.yupiik.asciidoc.renderer.html.ShowerRenderer;
import io.yupiik.asciidoc.renderer.markdown.GithubFlavoredMarkdownRenderer;
import io.yupiik.asciidoc.renderer.pdf.PdfConfiguration;
import io.yupiik.asciidoc.renderer.pdf.PdfRenderer;
import io.yupiik.asciidoc.renderer.pdf.PdfTheme;
import io.yupiik.asciidoc.renderer.uri.DataResolver;
import io.yupiik.tools.common.http.StaticHttpServer;
import io.yupiik.tools.common.watch.Watch;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Err;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Options;
import org.tomitribe.crest.api.Out;
import org.tomitribe.crest.api.Required;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class AsciidocCommand {
    private static final Pattern URI_SCHEME = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*:.*", Pattern.DOTALL);

    @Command(usage = "Renders an AsciiDoc file as HTML, Markdown or PDF.")
    public static void asciidoc(
            @Option(value = "input", description = "Input AsciiDoc file.") @Required final Path input,
            @Option(value = "output", description = "Output file.") @Required final Path output,
            @Option(value = "renderer", description = "html/asciidoctor, shower, markdown/md, pdf or a visitor FQCN.") @Default("html") final String renderer,
            @Option(value = "base", description = "Include base directory (defaults to input parent).") final Path base,
            @Option(value = "assetsBase", description = "Image and FOP resource base (defaults to include base).") final Path assetsBase,
            @Option(value = "attributes", description = "Parser and renderer attributes (properties syntax).") final Map<String, String> attributes,
            @Option(value = "unknownMacro", description = "Unknown macro policy: TEXT, IGNORE or FAIL.") @Default("TEXT") final String unknownMacro,
            @Option(value = "dataResolver", description = "DataResolver FQCN with a public constructor(Path); owned and closed by the command.") final String dataResolver,
            @Option(value = "sectionTag", description = "HTML section tag.") @Default("div") final String sectionTag,
            @Option(value = "sectionIdOnTitle", description = "Write section ids on their title tags.") @Default("true") final boolean sectionIdOnTitle,
            @Option(value = "dataUriForAscii2Svg", description = "Render ascii2svg diagrams as data URI images.") @Default("true") final boolean dataUriForAscii2Svg,
            @Option(value = "supportsDataAttributes", description = "Support HTML data attributes.") @Default("true") final boolean supportsDataAttributes,
            @Option(value = "skipSectionBody", description = "Skip HTML section body wrappers.") @Default("false") final boolean skipSectionBody,
            @Option(value = "skipGlobalContentWrapper", description = "Skip the global HTML content wrapper.") @Default("false") final boolean skipGlobalContentWrapper,
            final Pdf pdfOptions,
            @Option(value = "assets", description = "Files or directories to copy next to the output before rendering.") final List<Path> assets,
            @Option(value = "watch", description = "Polling delay in milliseconds; negative disables watch mode.") @Default("-1") final long watch,
            @Option(value = "openBrowser", description = "Open the browser in watch mode.") @Default("true") final boolean openBrowser,
            @Option(value = "port", description = "Watch HTTP port; nonpositive disables the server.") @Default("4200") final int port,
            @Out final PrintStream stdout,
            @Err final PrintStream stderr) {
        final var includeBase = base == null ? input.toAbsolutePath().getParent().normalize() : base;
        final var resourceBase = assetsBase == null ? includeBase : assetsBase;
        final var globals = attributes == null ? Map.<String, String>of() : attributes;
        final var policy = UnknownMacro.valueOf(unknownMacro.strip().toUpperCase(Locale.ROOT));
        try (final var imageResolver = newDataResolver(dataResolver, resourceBase)) {
            final Function<Map<String, String>, Visitor<?>> rendererFactory = merged -> newRenderer(
                    renderer, merged, policy, output, resourceBase, imageResolver, pdfOptions,
                    sectionTag, sectionIdOnTitle, dataUriForAscii2Svg, supportsDataAttributes,
                    skipSectionBody, skipGlobalContentWrapper);
            final var parser = new Parser(new Parser.Configuration().setGlobalAttributes(globals)
                    .setWarning(message -> stderr.println("[WARN] " + message)));
            final var resolver = ContentResolver.of(includeBase);
            final Runnable render = () -> {
                try {
                    render(input, output, parser, resolver, globals, rendererFactory, assets);
                    stdout.println("[INFO] Rendered '" + input + "'");
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            };
            if (watch < 0) {
                render.run();
                return;
            }
            final var sources = new ArrayList<Path>();
            sources.add(input);
            if (assets != null) {
                sources.addAll(assets);
            }
            final var server = new AtomicReference<StaticHttpServer>();
            final var watcher = new Watch<>(stdout::println, message -> { },
                    (message, error) -> error.printStackTrace(stderr), stderr::println,
                    sources, null, null, Math.max(1, watch), (options, unused) -> render.run(), () -> {
                        if (openBrowser && server.get() != null) {
                            try {
                                server.get().open(true);
                            } catch (final RuntimeException e) {
                                stderr.println("[ERROR] Can't open browser, ignoring: " + e);
                            }
                        }
                    });
            if (port > 0) {
                final var http = new StaticHttpServer(stdout::println, (message, error) -> {
                    stderr.println(message);
                    error.printStackTrace(stderr);
                }, port,
                        output.toAbsolutePath().getParent().normalize(), output.getFileName().toString(), watcher);
                server.set(http);
                http.run();
            } else {
                watcher.run();
            }
        }
    }

    @Options
    public static class Pdf {
        private final String fopConfiguration;
        private final boolean titlePage;
        private final boolean syntaxHighlighting;
        private final boolean sectionFooter;
        private Theme theme = new Theme();

        public Pdf(
                @Option(value = "pdf-fop-configuration", description = "FOP XML local path or URI, including classpath:/ resources.") final String fopConfiguration,
                @Option("pdf-theme-") final Theme theme,
                @Option(value = "pdf-title-page", description = "Render a separate PDF title page.") @Default("true") final boolean titlePage,
                @Option(value = "pdf-syntax-highlighting", description = "Highlight PDF source code.") @Default("true") final boolean syntaxHighlighting,
                @Option(value = "pdf-section-footer", description = "Show the current section in PDF footers.") @Default("true") final boolean sectionFooter) {
            this.fopConfiguration = fopConfiguration;
            this.titlePage = titlePage;
            this.syntaxHighlighting = syntaxHighlighting;
            this.sectionFooter = sectionFooter;
            if (theme != null) {
                this.theme = theme;
            }
        }

        @Options
        public static class Theme {
            private static final PdfTheme DEFAULTS = PdfTheme.defaults();

            private String primary = DEFAULTS.primary();
            private String secondary = DEFAULTS.secondary();
            private String text = DEFAULTS.text();
            private String muted = DEFAULTS.muted();
            private String background = DEFAULTS.background();
            private String border = DEFAULTS.border();
            private String sans = DEFAULTS.sans();
            private String mono = DEFAULTS.mono();
            private String pageWidth = DEFAULTS.pageWidth();
            private String pageHeight = DEFAULTS.pageHeight();
            private String margin = DEFAULTS.margin();

            private Theme() {
                // Keep Java defaults even when no nested options are supplied.
            }

            public Theme(
                    @Option(value = "primary", description = "PDF primary color.") final String primary,
                    @Option(value = "secondary", description = "PDF secondary color.") final String secondary,
                    @Option(value = "text", description = "PDF text color.") final String text,
                    @Option(value = "muted", description = "PDF muted color.") final String muted,
                    @Option(value = "background", description = "PDF background color.") final String background,
                    @Option(value = "border", description = "PDF border color.") final String border,
                    @Option(value = "sans", description = "PDF sans font family.") final String sans,
                    @Option(value = "mono", description = "PDF monospace font family.") final String mono,
                    @Option(value = "page-width", description = "PDF page width in FO units.") final String pageWidth,
                    @Option(value = "page-height", description = "PDF page height in FO units.") final String pageHeight,
                    @Option(value = "margin", description = "PDF page margin in FO units.") final String margin) {
                this.primary = primary == null ? this.primary : primary;
                this.secondary = secondary == null ? this.secondary : secondary;
                this.text = text == null ? this.text : text;
                this.muted = muted == null ? this.muted : muted;
                this.background = background == null ? this.background : background;
                this.border = border == null ? this.border : border;
                this.sans = sans == null ? this.sans : sans;
                this.mono = mono == null ? this.mono : mono;
                this.pageWidth = pageWidth == null ? this.pageWidth : pageWidth;
                this.pageHeight = pageHeight == null ? this.pageHeight : pageHeight;
                this.margin = margin == null ? this.margin : margin;
            }

            private PdfTheme toTheme() {
                return new PdfTheme(primary, secondary, text, muted, background, border, sans, mono,
                        pageWidth, pageHeight, margin);
            }
        }
    }

    private static void render(final Path input, final Path output,
                               final Parser parser, final ContentResolver resolver, final Map<String, String> globals,
                               final Function<Map<String, String>, Visitor<?>> rendererFactory,
                               final List<Path> assets) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        if (assets != null) {
            for (final var asset : assets) {
                copy(asset, output.toAbsolutePath().getParent());
            }
        }
        final Document document;
        try (final var reader = Files.newBufferedReader(input)) {
            document = parser.parse(reader, new Parser.ParserContext(resolver));
        }
        final var merged = new HashMap<>(globals);
        document.header().attributes().forEach((key, value) -> merged.put(key, value == null ? "" : value));
        final var visitor = rendererFactory.apply(merged);
        visitor.visit(document);
        final var result = visitor.result();
        if (result instanceof String text) {
            Files.writeString(output, text);
        } else if (!(result instanceof Path path) || !output.equals(path)) {
            throw new IllegalStateException("Renderer must return text or the configured output path: " + result);
        }
    }

    private static Visitor<?> newRenderer(final String renderer, final Map<String, String> attributes,
                                          final UnknownMacro policy, final Path output, final Path assetsBase,
                                          final DataResolver resolver, final Pdf pdfOptions,
                                          final String sectionTag, final boolean sectionIdOnTitle,
                                          final boolean dataUriForAscii2Svg, final boolean supportsDataAttributes,
                                          final boolean skipSectionBody, final boolean skipGlobalContentWrapper) {
        final var selected = renderer.strip();
        final var alias = selected.toLowerCase(Locale.ROOT);
        if ("pdf".equals(alias) || PdfRenderer.class.getName().equals(selected)) {
            final var options = pdfOptions == null ? new Pdf(null, null, true, true, true) : pdfOptions;
            final var configuration = new PdfConfiguration().setOutput(output).setAssetsBase(assetsBase)
                    .setAttributes(attributes).setResolver(resolver).setUnknownMacro(policy)
                    .setTitlePage(options.titlePage).setSyntaxHighlighting(options.syntaxHighlighting)
                    .setSectionFooter(options.sectionFooter)
                    .setTheme(options.theme.toTheme()).setClassLoader(AsciidocCommand.class.getClassLoader());
            if (options.fopConfiguration != null && !options.fopConfiguration.isBlank()) {
                configuration.setFopConfiguration(URI_SCHEME.matcher(options.fopConfiguration).matches() ?
                        URI.create(options.fopConfiguration) : Path.of(options.fopConfiguration).toUri());
            }
            return new PdfRenderer(configuration);
        }
        if ("markdown".equals(alias) || "md".equals(alias) || GithubFlavoredMarkdownRenderer.class.getName().equals(selected)) {
            return new GithubFlavoredMarkdownRenderer(new GithubFlavoredMarkdownRenderer.Configuration()
                    .setAttributes(attributes).setUnknownMacro(policy));
        }
        if ("html".equals(alias) || "asciidoctor".equals(alias) || AsciidoctorLikeHtmlRenderer.class.getName().equals(selected)) {
            return new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                    .setAssetsBase(assetsBase).setResolver(resolver).setUnknownMacro(policy).setAttributes(attributes)
                    .setSectionTag(sectionTag).setSectionIdOnTitle(sectionIdOnTitle)
                    .setDataUriForAscii2Svg(dataUriForAscii2Svg).setSupportDataAttributes(supportsDataAttributes)
                    .setSkipSectionBody(skipSectionBody).setSkipGlobalContentWrapper(skipGlobalContentWrapper));
        }
        if ("shower".equals(alias) || ShowerRenderer.class.getName().equals(selected)) {
            final var showerAttributes = new HashMap<>(attributes);
            showerAttributes.putIfAbsent(AsciidoctorLikeHtmlRenderer.UNKNOWN_MACRO_ATTRIBUTE, policy.name());
            return new ShowerRenderer(new ShowerRenderer.Configuration().setAttributes(showerAttributes)
                    .setAssetsBase(assetsBase).setResolver(resolver)
                    .setDataUriForAscii2Svg(dataUriForAscii2Svg).setSupportDataAttributes(supportsDataAttributes));
        }
        try {
            return AsciidocCommand.class.getClassLoader().loadClass(selected).asSubclass(Visitor.class)
                    .getConstructor().newInstance();
        } catch (final InvocationTargetException e) {
            throw new IllegalStateException(e.getTargetException());
        } catch (final ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("Can't create visitor '" + selected + "'; provide a public no-arg constructor", e);
        }
    }

    private static DataResolver newDataResolver(final String name, final Path base) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (name.equals(DataResolver.class.getName())) {
            return new DataResolver(base);
        }
        try {
            return AsciidocCommand.class.getClassLoader().loadClass(name.strip()).asSubclass(DataResolver.class)
                    .getConstructor(Path.class).newInstance(base);
        } catch (final ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("Can't create DataResolver '" + name + "'; provide a public constructor(Path)", e);
        }
    }

    private static void copy(final Path source, final Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final var destination = target.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.copy(source, target.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
