/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer;
import io.yupiik.tools.common.http.StaticHttpServer;
import io.yupiik.tools.common.watch.Watch;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

/**
 * Uses the Yupiik asciidoc implementation to render an asciidoc file in HTML.
 */
@Mojo(name = "asciidoc", threadSafe = true)
public class AsciidocMojo extends AbstractMojo {
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

    /**
     * should html data attributes be supported.
     */
    @Parameter(property = "yupiik.asciidoc.supportsDataAttributes", defaultValue = "true")
    private boolean supportsDataAttributes;

    /**
     * should section body div wrappers be skipped (can be useful for some theming).
     */
    @Parameter(property = "yupiik.asciidoc.skipSectionBody", defaultValue = "false")
    private boolean skipSectionBody;

    /**
     * attributes.
     */
    @Parameter(property = "yupiik.asciidoc.attributes")
    private Map<String, String> attributes;

    /**
     * If positive the rendering will loop with a delay of this value.
     */
    @Parameter(property = "yupiik.asciidoc.watch", defaultValue = "-1")
    private long watch;

    /**
     * Should the browser be opened after the rendering in watch mode (ignored otherwise).
     * Can be {@code true}/{@code false}.
     */
    @Parameter(property = "yupiik.asciidoc.openBrowser", defaultValue = "true")
    private boolean openBrowser;

    /**
     * Port to launch the server when opening the browser in watch mode.
     */
    @Parameter(property = "yupiik.asciidoc.port", defaultValue = "4200")
    private int port;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var configuration = new AsciidoctorLikeHtmlRenderer.Configuration()
                .setSupportDataAttributes(supportsDataAttributes)
                .setSkipSectionBody(skipSectionBody)
                .setAttributes(attributes == null ? Map.of() : attributes);
        final var input = Path.of(this.input);
        final var output = Path.of(this.output);
        final ContentResolver resolver;
        if (base != null) {
            final var base = Path.of(this.base);
            configuration.setAssetsBase(base);
            resolver = ContentResolver.of(base);
        } else {
            final var parent = input.toAbsolutePath().getParent().normalize();
            resolver = ContentResolver.of(parent);
            configuration.setAssetsBase(parent);
        }

        try {
            final var parser = new Parser();
            doRender(input, parser, resolver, output, configuration);

            if (watch < 0) {
                return;
            }

            final var server = new AtomicReference<StaticHttpServer>();
            final var watch = new Watch<>(
                    getLog()::info, getLog()::debug, getLog()::debug, getLog()::error,
                    List.of(input), null, null, this.watch,
                    (opts, a) -> {
                        try {
                            doRender(input, parser, resolver, output, configuration);
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
                        getLog()::info, getLog()::error, port, output.toAbsolutePath().getParent().normalize(), output.getFileName().toString(), watch);
                server.set(staticHttpServer);
                staticHttpServer.run();
            } else {
                watch.run();
            }
        } catch (final IOException ioe) {
            throw new MojoExecutionException(ioe.getMessage(), ioe);
        }

    }

    private void doRender(final Path input, final Parser parser, final ContentResolver resolver,
                          final Path output, final AsciidoctorLikeHtmlRenderer.Configuration configuration) throws IOException {
        final Document document;
        try (final var reader = Files.newBufferedReader(input)) {
            document = parser.parse(reader, new Parser.ParserContext(resolver));
        }

        final var html = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setAssetsBase(configuration.getAssetsBase())
                .setSupportDataAttributes(configuration.isSupportDataAttributes())
                .setResolver(configuration.getResolver())
                .setSkipSectionBody(configuration.isSkipSectionBody())
                .setAttributes(Stream.of(attributes == null ? Map.<String, String>of() : attributes, document.header().attributes())
                        .flatMap(e -> e.entrySet().stream())
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b))));
        html.visit(document);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, html.result());
        getLog().info("Rendered '" + input + "'");
    }
}
