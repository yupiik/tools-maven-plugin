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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
     * attributes.
     */
    @Parameter(property = "yupiik.asciidoc.attributes")
    private Map<String, String> attributes;

    /**
     * If positive the rendering will loop with a delay of this value.
     */
    @Parameter(property = "yupiik.asciidoc.watch", defaultValue = "-1")
    private long watch;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var attributes = new HashMap<String, String>();
        final var configuration = new AsciidoctorLikeHtmlRenderer.Configuration()
                .setSupportDataAttributes(true)
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
            if (watch < 0) {
                doRender(input, parser, resolver, output);
                return;
            }

            getLog().info("Entering into watch mode, Ctrl+C to exit");
            FileTime lastModified = null;
            while (true) {
                final var newLastModified = Files.getLastModifiedTime(input);
                if (lastModified == null || !Objects.equals(lastModified, newLastModified)) {
                    doRender(input, parser, resolver, output);
                    lastModified = newLastModified;
                } else {
                    getLog().debug("No change detected");
                }
                try {
                    Thread.sleep(watch);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (final IOException ioe) {
            throw new MojoExecutionException(ioe.getMessage(), ioe);
        }

    }

    private void doRender(final Path input, final Parser parser, final ContentResolver resolver, final Path output) throws IOException {
        final Document document;
        try (final var reader = Files.newBufferedReader(input)) {
            document = parser.parse(reader, new Parser.ParserContext(resolver));
        }

        final var html = new AsciidoctorLikeHtmlRenderer();
        html.visit(document);
        Files.writeString(output, html.result());
        getLog().info("Rendered '" + input + "'");
    }
}
