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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Uses the Yupiik asciidoc implementation to render an asciidoc file as HTML, Markdown or PDF.
 */
@Mojo(name = "asciidoc", threadSafe = true)
public class AsciidocMojo extends AbstractMojo {
    private static final Pattern URI_SCHEME = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*:.*", Pattern.DOTALL);
    /**
     * The file to render.
     */
    @Parameter(required = true, property = "yupiik.asciidoc.input")
    private String input;

    /**
     * Where to write the output.
     */
    @Parameter(required = true, property = "yupiik.asciidoc.output")
    private String output;

    /**
     * include resolution base directory.
     */
    @Parameter(property = "yupiik.asciidoc.base")
    private String base;

    /** Resource base for images and FOP; defaults to the include base or the input's parent directory. */
    @Parameter(property = "yupiik.asciidoc.assetsBase")
    private File assetsBase;

    /**
     * Optional image DataResolver implementation with a public constructor taking a Path base directory.
     * The goal owns and closes the resolver. Classes and resources are loaded from the plugin dependencies.
     */
    @Parameter(property = "yupiik.asciidoc.dataResolver")
    private String dataResolver;

    /** PDF settings, including an optional FOP configuration and the nested print theme. */
    @Parameter
    private Pdf pdf = new Pdf();

    /**
     * section tag for default asciidoctor like renderer.
     */
    @Parameter(property = "yupiik.asciidoc.sectionTag", defaultValue = "div")
    private String sectionTag = "div";

    /**
     * should the id of a section be written on its title tag ({@code h2}...) as asciidoctor does, instead of on the section tag.
     */
    @Parameter(property = "yupiik.asciidoc.sectionIdOnTitle", defaultValue = "true")
    private boolean sectionIdOnTitle = true;

    /**
     * should ascii2svg diagram be rendered as {@code svg} or {@code img} tag.
     */
    @Parameter(property = "yupiik.asciidoc.dataUriForAscii2Svg", defaultValue = "true")
    private boolean dataUriForAscii2Svg = true;

    /**
     * should html data attributes be supported.
     */
    @Parameter(property = "yupiik.asciidoc.supportsDataAttributes", defaultValue = "true")
    private boolean supportsDataAttributes = true;

    /**
     * should section body div wrappers be skipped (can be useful for some theming).
     */
    @Parameter(property = "yupiik.asciidoc.skipSectionBody", defaultValue = "false")
    private boolean skipSectionBody;

    /**
     * should the div.content wrapper for the whole rendering be skipped.
     */
    @Parameter(property = "yupiik.asciidoc.skipGlobalContentWrapper", defaultValue = "false")
    private boolean skipGlobalContentWrapper;

    /**
     * What the renderer does with a macro it has no method for, such as {@code tooltip:foo[a hint]}, which is most often
     * a mistake in the document: {@code FAIL} fails the rendering naming the macro, {@code IGNORE} writes nothing,
     * {@code TEXT} writes the macro as text, as asciidoctor writes a macro no extension registers (the default).
     * The selected renderer's unknownMacro attribute, in the document or in {@code attributes}, wins over it.
     */
    @Parameter(property = "yupiik.asciidoc.unknownMacro", defaultValue = "TEXT")
    private UnknownMacro unknownMacro = UnknownMacro.TEXT;

    /**
     * Attributes given to the parser and to the renderer: they resolve the conditional directives the parser evaluates
     * (in the header and inside a table) and the references of the rendered content, for the names the document does
     * not set itself, since an attribute entry of the document wins over them.
     */
    @Parameter(property = "yupiik.asciidoc.attributes")
    private Map<String, String> attributes;

    /**
     * If positive the rendering will loop with a delay of this value.
     */
    @Parameter(property = "yupiik.asciidoc.watch", defaultValue = "-1")
    private long watch = -1;

    /**
     * Should the browser be opened after the rendering in watch mode (ignored otherwise).
     * Can be {@code true}/{@code false}.
     */
    @Parameter(property = "yupiik.asciidoc.openBrowser", defaultValue = "true")
    private boolean openBrowser = true;

    /**
     * Port to launch the server when opening the browser in watch mode.
     */
    @Parameter(property = "yupiik.asciidoc.port", defaultValue = "4200")
    private int port = 4200;

    /**
     * Renderer to use: html (or asciidoctor), shower, markdown (or md), pdf, or a fully qualified visitor class name.
     */
    @Parameter(property = "yupiik.asciidoc.renderer", defaultValue = "io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer")
    private String renderer = AsciidoctorLikeHtmlRenderer.class.getName();

    /**
     * Assets folder to copy before the rendering next to the output.
     * Can host js, css etc...
     */
    @Parameter
    private List<File> assets;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var globals = attributes == null ? Map.<String, String>of() : attributes.entrySet().stream()
                .collect(toMap(Map.Entry::getKey, e -> e.getValue() == null ? "" : e.getValue()));
        final var input = Path.of(this.input);
        final var output = Path.of(this.output);
        final var includeBase = base == null ? input.toAbsolutePath().getParent().normalize() : Path.of(base);
        final var resolver = ContentResolver.of(includeBase);
        final var resourceBase = assetsBase == null ? includeBase : assetsBase.toPath();
        try (final var imageResolver = newDataResolver(resourceBase)) {
            final var parser = new Parser(new Parser.Configuration()
                    .setGlobalAttributes(globals)
                    .setWarning(getLog()::warn));
            doRender(input, parser, resolver, output, resourceBase, imageResolver);

            if (watch < 0) {
                return;
            }

            final var server = new AtomicReference<StaticHttpServer>();
            final var watch = new Watch<>(
                    getLog()::info, getLog()::debug, getLog()::debug, getLog()::error,
                    assets == null || assets.isEmpty() ? List.of(input) : Stream.concat(assets.stream().map(File::toPath), Stream.of(input)).collect(toList()),
                    null, null, this.watch,
                    (opts, a) -> {
                        try {
                            doRender(input, parser, resolver, output, resourceBase, imageResolver);
                        } catch (final IOException e) {
                            getLog().error(e);
                        }
                    },
                    () -> {
                        if (port < 0 || !openBrowser) {
                            return;
                        }

                        try {
                            server.get().open(true);
                        } catch (final RuntimeException re) {
                            getLog().error("Can't open browser, ignoring", re);
                        }
                    });
            if (port > 0) {
                final var staticHttpServer = new StaticHttpServer(
                        getLog()::info, getLog()::error, port,
                        output.toAbsolutePath().getParent().normalize(),
                        output.getFileName().toString(), watch);
                server.set(staticHttpServer);
                staticHttpServer.run();
            } else {
                watch.run();
            }
        } catch (final IOException | RuntimeException ioe) {
            throw new MojoExecutionException(ioe.getMessage(), ioe);
        }

    }

    private void copy(final Path src, final Path target) throws IOException {
        if (Files.isDirectory(src)) {
            Files.walkFileTree(src, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final var out = target.resolve(src.relativize(file));
                    Files.createDirectories(out.getParent());
                    Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
                    return super.visitFile(file, attrs);
                }
            });
        } else {
            Files.copy(src, target.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void doRender(final Path input, final Parser parser, final ContentResolver resolver,
                          final Path output, final Path resourceBase, final DataResolver imageResolver) throws IOException {
        if (assets != null && !assets.isEmpty()) {
            final var target = Files.createDirectories(output.toAbsolutePath().getParent());
            for (final var src : assets) {
                copy(src.toPath(), target);
            }
        }

        final Document document;
        try (final var reader = Files.newBufferedReader(input)) {
            document = parser.parse(reader, new Parser.ParserContext(resolver));
        }

        final var visitor = newRenderer(document, output, resourceBase, imageResolver);
        visitor.visit(document);
        final var result = visitor.result();
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        if (result instanceof String text) {
            Files.writeString(output, text);
        } else if (!(result instanceof Path path) || !output.equals(path)) {
            throw new IllegalStateException("Renderer must return text or the configured output path: " + result);
        }
        getLog().info("Rendered '" + input + "'");
    }

    private Visitor<?> newRenderer(final Document document, final Path output,
                                   final Path resourceBase, final DataResolver imageResolver) {
        final var selected = renderer.strip();
        final var alias = selected.toLowerCase(Locale.ROOT);
        if (PdfRenderer.class.getName().equals(selected) || "pdf".equals(alias)) {
            final var options = pdf == null ? new Pdf() : pdf;
            final var pdfConfiguration = new PdfConfiguration()
                    .setOutput(output)
                    .setAssetsBase(resourceBase)
                    .setAttributes(rendererAttributes(document))
                    .setUnknownMacro(unknownMacro)
                    .setResolver(imageResolver)
                    .setTitlePage(options.titlePage)
                    .setSyntaxHighlighting(options.syntaxHighlighting)
                    .setSectionFooter(options.sectionFooter)
                    .setClassLoader(getClass().getClassLoader())
                    .setTheme(options.theme == null ? PdfTheme.defaults() : options.theme.toTheme());
            if (options.fopConfiguration != null && !options.fopConfiguration.isBlank()) {
                pdfConfiguration.setFopConfiguration(URI_SCHEME.matcher(options.fopConfiguration).matches() ?
                        URI.create(options.fopConfiguration) : Path.of(options.fopConfiguration).toUri());
            }
            return new PdfRenderer(pdfConfiguration);
        }
        if (GithubFlavoredMarkdownRenderer.class.getName().equals(selected) || "markdown".equals(alias) || "md".equals(alias)) {
            return new GithubFlavoredMarkdownRenderer(new GithubFlavoredMarkdownRenderer.Configuration()
                    .setAttributes(rendererAttributes(document))
                    .setUnknownMacro(unknownMacro));
        }
        if (AsciidoctorLikeHtmlRenderer.class.getName().equals(selected) || "asciidoctor".equals(alias) || "html".equals(alias)) {
            return new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                    .setAssetsBase(resourceBase)
                    .setSupportDataAttributes(supportsDataAttributes)
                    .setResolver(imageResolver)
                    .setSkipSectionBody(skipSectionBody)
                    .setSkipGlobalContentWrapper(skipGlobalContentWrapper)
                    .setDataUriForAscii2Svg(dataUriForAscii2Svg)
                    .setSectionTag(sectionTag)
                    .setSectionIdOnTitle(sectionIdOnTitle)
                    .setUnknownMacro(unknownMacro)
                    .setAttributes(rendererAttributes(document)));
        }
        if (ShowerRenderer.class.getName().equals(selected) || "shower".equals(alias)) {
            return new ShowerRenderer(new ShowerRenderer.Configuration()
                    .setAssetsBase(resourceBase)
                    .setSupportDataAttributes(supportsDataAttributes)
                    .setDataUriForAscii2Svg(dataUriForAscii2Svg)
                    .setResolver(imageResolver)
                    .setAttributes(rendererAttributes(document)));
        }
        try {
            return getClass().getClassLoader()
                    .loadClass(selected)
                    .asSubclass(Visitor.class)
                    .getConstructor()
                    .newInstance();
        } catch (final InstantiationException | ClassNotFoundException | NoSuchMethodException |
                       IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (final InvocationTargetException e) {
            throw new IllegalStateException(e.getTargetException());
        }
    }

    private Map<String, String> rendererAttributes(final Document document) {
        return Stream.of(attributes, document.header().attributes())
                .filter(Objects::nonNull)
                .flatMap(e -> e.entrySet().stream())
                .collect(toMap(Map.Entry::getKey, e -> e.getValue() == null ? "" : e.getValue(), (a, b) -> b));
    }

    private DataResolver newDataResolver(final Path base) {
        if (dataResolver == null || dataResolver.isBlank()) {
            return null;
        }
        if (dataResolver.equals(DataResolver.class.getName())) {
            return new DataResolver(base);
        }
        try {
            return getClass().getClassLoader().loadClass(dataResolver.strip())
                    .asSubclass(DataResolver.class).getConstructor(Path.class).newInstance(base);
        } catch (final ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("Can't create DataResolver '" + dataResolver +
                    "'; add it to the plugin dependencies and provide a public constructor(Path)", e);
        }
    }

    /** Maven PDF configuration. */
    public static class Pdf {
        /** Render a separate title page. */
        @Parameter(defaultValue = "true")
        private boolean titlePage = true;

        /** Highlight source code. */
        @Parameter(defaultValue = "true")
        private boolean syntaxHighlighting = true;

        /** Show the current section in page footers. */
        @Parameter(defaultValue = "true")
        private boolean sectionFooter = true;

        /** Optional FOP XML local path or URI, including classpath:/ resources. */
        @Parameter
        private String fopConfiguration;

        /** Print palette, font families and page dimensions; omitted fields retain their defaults. */
        @Parameter
        private Theme theme = new Theme();

        /** Mutable Maven configuration counterpart of the immutable PDF theme. */
        public static class Theme {
            private static final PdfTheme DEFAULT = PdfTheme.defaults();

            /** Primary accent color. */
            @Parameter(defaultValue = "#D858FF")
            private String primary = DEFAULT.primary();
            /** Secondary accent and link color. */
            @Parameter(defaultValue = "#056EBB")
            private String secondary = DEFAULT.secondary();
            /** Main text color. */
            @Parameter(defaultValue = "#212121")
            private String text = DEFAULT.text();
            /** Muted text color. */
            @Parameter(defaultValue = "#656565")
            private String muted = DEFAULT.muted();
            /** Panel background color. */
            @Parameter(defaultValue = "#F4F4F4")
            private String background = DEFAULT.background();
            /** Border color. */
            @Parameter(defaultValue = "#E3E3E3")
            private String border = DEFAULT.border();
            /** Document font family, registered in the FOP configuration. */
            @Parameter(defaultValue = "Inter")
            private String sans = DEFAULT.sans();
            /** Code font family, registered in the FOP configuration. */
            @Parameter(defaultValue = "JetBrains Mono")
            private String mono = DEFAULT.mono();
            /** Page width in XSL-FO units. */
            @Parameter(defaultValue = "210mm")
            private String pageWidth = DEFAULT.pageWidth();
            /** Page height in XSL-FO units. */
            @Parameter(defaultValue = "297mm")
            private String pageHeight = DEFAULT.pageHeight();
            /** Page margin in XSL-FO units. */
            @Parameter(defaultValue = "20mm")
            private String margin = DEFAULT.margin();

            private PdfTheme toTheme() {
                return new PdfTheme(primary, secondary, text, muted, background, border,
                        sans, mono, pageWidth, pageHeight, margin);
            }
        }
    }
}
