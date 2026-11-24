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

import io.yupiik.asciidoc.renderer.UnknownMacro;
import io.yupiik.asciidoc.renderer.uri.DataResolver;
import lombok.Getter;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Common model-reading options and PDF output settings, using the HTML renderer's configuration conventions. */
@Getter
public class PdfConfiguration {
    private Path output = Path.of("target/document.pdf");
    private Path assetsBase = Path.of(".");
    private Map<String, String> attributes = Map.of();
    private UnknownMacro unknownMacro = UnknownMacro.TEXT;
    private DataResolver resolver;
    private PdfTheme theme = PdfTheme.defaults();
    private boolean titlePage = true;
    private boolean syntaxHighlighting = true;
    private boolean sectionFooter = true;
    private URI fopConfiguration;
    private ClassLoader classLoader = Thread.currentThread().getContextClassLoader() == null ?
            PdfConfiguration.class.getClassLoader() : Thread.currentThread().getContextClassLoader();

    /** @param output destination file, {@code target/document.pdf} by default. @return this. */
    public PdfConfiguration setOutput(final Path output) {
        this.output = Objects.requireNonNull(output, "output");
        return this;
    }

    /** @param assetsBase base for relative images and FOP resources, the working directory by default. @return this. */
    public PdfConfiguration setAssetsBase(final Path assetsBase) {
        this.assetsBase = Objects.requireNonNull(assetsBase, "assetsBase");
        return this;
    }

    /** @param attributes fallback attributes; document attributes take precedence. @return this. */
    public PdfConfiguration setAttributes(final Map<String, String> attributes) {
        this.attributes = Objects.requireNonNull(attributes, "attributes");
        return this;
    }

    /**
     * @param unknownMacro fallback policy, TEXT by default; {@link FoRenderer#UNKNOWN_MACRO_ATTRIBUTE} wins when set.
     * @return this.
     */
    public PdfConfiguration setUnknownMacro(final UnknownMacro unknownMacro) {
        this.unknownMacro = Objects.requireNonNull(unknownMacro, "unknownMacro");
        return this;
    }

    /**
     * @param resolver optional caller-owned image resolver; receives targets including {@code imagesdir}.
     *                 Its data is embedded in the FO. Without one, local files and classpath resources are accepted.
     * @return this.
     */
    public PdfConfiguration setResolver(final DataResolver resolver) {
        this.resolver = resolver;
        return this;
    }

    public PdfConfiguration setTheme(final PdfTheme theme) {
        this.theme = Objects.requireNonNull(theme, "theme");
        return this;
    }

    /** @param titlePage whether to put the title and header metadata on a separate, unnumbered cover (default true). @return this. */
    public PdfConfiguration setTitlePage(final boolean titlePage) {
        this.titlePage = titlePage;
        return this;
    }

    /** @param syntaxHighlighting whether to highlight source blocks with a known language (default true). @return this. */
    public PdfConfiguration setSyntaxHighlighting(final boolean syntaxHighlighting) {
        this.syntaxHighlighting = syntaxHighlighting;
        return this;
    }

    /** @param sectionFooter whether the left footer follows the current section, rather than the document title (default true). @return this. */
    public PdfConfiguration setSectionFooter(final boolean sectionFooter) {
        this.sectionFooter = sectionFooter;
        return this;
    }

    /** @param fopConfiguration optional FOP XML file replacing the default font configuration. @return this. */
    public PdfConfiguration setFopConfiguration(final Path fopConfiguration) {
        this.fopConfiguration = fopConfiguration == null ? null : fopConfiguration.toUri();
        return this;
    }

    /** @param fopConfiguration optional FOP XML URI, including {@code classpath:/} and {@code jar:} resources. @return this. */
    public PdfConfiguration setFopConfiguration(final URI fopConfiguration) {
        this.fopConfiguration = fopConfiguration;
        return this;
    }

    /** @param classLoader loader for fonts, images and {@code classpath:/} FOP resources; caller-owned. @return this. */
    public PdfConfiguration setClassLoader(final ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        return this;
    }
}
