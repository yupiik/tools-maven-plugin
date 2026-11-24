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

import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.renderer.Visitor;
import org.apache.fop.apps.FopConfParser;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.apps.io.ResourceResolverFactory;
import org.apache.xmlgraphics.io.Resource;
import org.apache.xmlgraphics.io.ResourceResolver;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Native PDF output through the optional Apache FOP dependency, without an HTML intermediary.
 * Instances reuse their FOP factory and are not thread safe.
 * No FOP type is exposed in the public API; creating a renderer does not load FOP.
 */
public final class PdfRenderer implements Visitor<Path> {
    public static final String UNKNOWN_MACRO_ATTRIBUTE = FoRenderer.UNKNOWN_MACRO_ATTRIBUTE;

    private final Path output;
    private final Path baseDirectory;
    private final URI fopConfiguration;
    private final ClassLoader classLoader;
    private final FoRenderer foRenderer;
    private Document document;
    private Path result;
    private Backend backend;

    public PdfRenderer(final PdfConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        this.output = Objects.requireNonNull(configuration.getOutput(), "output");
        this.baseDirectory = configuration.getAssetsBase().toAbsolutePath().normalize();
        this.fopConfiguration = configuration.getFopConfiguration();
        this.classLoader = configuration.getClassLoader();
        this.foRenderer = new FoRenderer(configuration);
    }

    /** Generates and validates the intermediate FO without loading FOP or opening the destination. */
    @Override
    public void visit(final Document document) {
        this.document = null;
        result = null;
        foRenderer.visit(Objects.requireNonNull(document, "document"));
        this.document = document;
    }

    @Override
    public ConditionalBlock.Context context() {
        return foRenderer.context();
    }

    /**
     * Writes the PDF on the first call after a successful {@link #visit(Document)} and returns its configured path.
     * Repeated calls return the same path without rewriting the file.
     */
    @Override
    public Path result() {
        if (document == null) {
            throw new IllegalStateException("No successfully rendered document; call visit(Document) first");
        }
        if (result != null) {
            return result;
        }
        final var fo = foRenderer.toString();
        final var engine = backend();
        final var destination = output.toAbsolutePath();
        try {
            Files.createDirectories(destination.getParent());
            try (final var stream = Files.newOutputStream(destination)) {
                engine.render(document, fo, stream);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to write PDF to " + output, e);
        }
        result = output;
        return result;
    }

    private Backend backend() {
        try {
            if (backend == null) {
                backend = new Backend(baseDirectory, fopConfiguration, classLoader);
            }
            return backend;
        } catch (final NoClassDefFoundError missing) {
            throw new IllegalStateException("PDF rendering requires the optional dependency " +
                    "org.apache.xmlgraphics:fop:2.12. Add it to the application's dependencies " +
                    "(or plugin dependencies when rendering inside a Maven plugin).", missing);
        }
    }

    // Keep optional dependency linkage behind an explicit construction, not reflective detection.
    private static final class Backend {
        private final FopFactory factory;

        private Backend(final Path baseDirectory, final URI configuration, final ClassLoader classLoader) {
            final var resolver = resourceResolver(classLoader);
            try (final var input = configuration == null ?
                    new ByteArrayInputStream(fontConfiguration(classLoader).getBytes(StandardCharsets.UTF_8)) :
                    resolver.getResource(baseDirectory.toUri().resolve(configuration))) {
                factory = new FopConfParser(input, baseDirectory.toUri(), resolver).getFopFactoryBuilder().build();
            } catch (final Exception e) {
                throw new IllegalStateException("Unable to initialize PDF renderer: " + e.getMessage(), e);
            }
        }

        private static ResourceResolver resourceResolver(final ClassLoader classLoader) {
            final var fallback = ResourceResolverFactory.createDefaultResourceResolver();
            return new ResourceResolver() {
                @Override
                public Resource getResource(final URI uri) throws IOException {
                    if ("classpath".equals(uri.getScheme())) {
                        final var name = uri.getSchemeSpecificPart();
                        final var resource = classLoader.getResource(name.startsWith("/") ? name.substring(1) : name);
                        if (resource == null) {
                            throw new FileNotFoundException("Missing PDF classpath resource: " + uri);
                        }
                        return new Resource(resource.openStream());
                    }
                    return fallback.getResource(uri);
                }

                @Override
                public OutputStream getOutputStream(final URI uri) throws IOException {
                    return fallback.getOutputStream(uri);
                }
            };
        }

        private static String fontConfiguration(final ClassLoader classLoader) {
            final var config = new StringBuilder("<fop version=\"1.0\"><strict-configuration>true</strict-configuration>" +
                    "<renderers><renderer mime=\"application/pdf\"><fonts>");
            for (final var family : new String[]{"Inter", "JetBrainsMono"}) {
                final boolean sans = "Inter".equals(family);
                for (final var style : new String[]{"Regular", "Bold", "Italic", "BoldItalic"}) {
                    final var name = "com/formdev/flatlaf/fonts/" + (sans ? "inter/" : "jetbrains_mono/") +
                            family + "-" + style + (sans ? ".otf" : ".ttf");
                    final var resource = classLoader.getResource(name);
                    if (resource == null) {
                        throw new IllegalStateException("Default PDF fonts require " + (sans ?
                                "com.formdev:flatlaf-fonts-inter:4.1" : "com.formdev:flatlaf-fonts-jetbrains-mono:2.304") +
                                " on the classpath, or provide a custom PdfConfiguration.setFopConfiguration().");
                    }
                    config.append("<font embed-url=\"")
                            .append(resource.toExternalForm().replace("&", "&amp;").replace("\"", "&quot;"))
                            .append("\" embedding-mode=\"subset\"><font-triplet name=\"")
                            .append(sans ? "Inter" : "JetBrains Mono")
                            .append("\" style=\"").append(style.contains("Italic") ? "italic" : "normal")
                            .append("\" weight=\"").append(style.contains("Bold") ? "bold" : "normal")
                            .append("\"/></font>");
                }
            }
            return config.append("</fonts></renderer></renderers></fop>").toString();
        }

        private void render(final Document document, final String fo, final OutputStream output) {
            try {
                final var agent = factory.newFOUserAgent();
                agent.setTitle(document.header().title());
                agent.setCreator("Yupiik AsciiDoc Java");
                final var fop = factory.newFop(MimeConstants.MIME_PDF, agent, output);
                final var parsers = SAXParserFactory.newDefaultInstance();
                parsers.setNamespaceAware(true);
                parsers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                parsers.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                final var reader = parsers.newSAXParser().getXMLReader();
                reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                reader.setContentHandler(fop.getDefaultHandler());
                reader.parse(new InputSource(new StringReader(fo)));
            } catch (final NoClassDefFoundError missing) {
                throw new IllegalStateException("PDF rendering requires org.apache.xmlgraphics:fop:2.12 " +
                        "and its transitive dependencies.", missing);
            } catch (final Exception e) {
                throw new IllegalStateException("Unable to render PDF: " + e.getMessage(), e);
            }
        }
    }
}
