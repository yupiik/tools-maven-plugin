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
package io.yupiik.asciidoc.renderer.html;

import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.renderer.uri.DataResolver;
import lombok.Getter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Enable to render HTML5 slides.
 * it is fully doable using placeholders but it is easier to get it preconfigured.
 * <p>
 * Here is a sample <pre>slides.adoc</pre>:
 * <p>
 * <code>
 * <pre>
 * = My title
 *
 * [.slide]
 * == Slide 1
 *
 * bla
 *
 * [.notes]
 * . Some
 * . notes
 *
 * [.slide]
 * == Slide 2
 *
 * [source,java]
 * ----
 * public record Test() {}
 * ----
 *     </pre>
 * </code>
 * <p>
 * Important: depending if you include or not asciidoctor css (attribute {@code asciidoctor-css}) the rendering can differ.
 */
public class ShowerRenderer extends AsciidoctorLikeHtmlRenderer {
    public ShowerRenderer() {
        this(new Configuration());
    }

    public ShowerRenderer(final Configuration configuration) {
        super(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setSkipGlobalContentWrapper(true)
                .setSkipSectionBody(true)
                .setSectionTag("section")
                .setDataUriForAscii2Svg(configuration.isDataUriForAscii2Svg())
                .setAttributes(configuration.getAttributes())
                .setSupportDataAttributes(configuration.isSupportDataAttributes())
                .setAssetsBase(configuration.getAssetsBase())
                .setResolver(configuration.getResolver()));
    }

    @Override
    public void visit(final Document document) {
        state.document = document;
        configuration.setAttributes(customizeAttributes(configuration.getAttributes()));
        super.visit(document);
    }

    private Map<String, String> customizeAttributes(final Map<String, String> original) {
        final var copy = new HashMap<>(original);
        append(copy, "custom-css", "" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                original.getOrDefault("shower-css", "  <link rel=\"stylesheet\" href=\"https://www.unpkg.com/@shower/ribbon@3.5.1/styles/styles.css\">\n") +
                "  <style>.shower { --slide-ratio: calc(" +
                copy.getOrDefault("shower-ratio", "16/9") +
                ");}</style>\n");
        append(copy, "header-html", " <header class=\"caption\">\n    <h1>" +
                escape(state.document.header().title()) +
                "</h1>\n </header>");
        append(copy, "custom-js", "" +
                " <footer class=\"badge\">\n" +
                "  <a href=\"https://yupiik.io\">Yupiik OSS</a>\n" +
                " </footer>\n" +
                " <div class=\"progress\"></div>\n" +
                original.getOrDefault("shower-js", " <script src=\"https://www.unpkg.com/@shower/core@3.3.0/dist/shower.js\"></script>\n"));
        append(copy, "body-classes", " " + original.getOrDefault("shower-body-classes", "shower list"));
        return copy;
    }

    private void append(final Map<String, String> copy, final String key, final String suffix) {
        copy.merge(key, suffix, (a, b) -> b + "\n" + a);
    }

    @Getter
    public static class Configuration {
        private boolean dataUriForAscii2Svg = true;
        private boolean supportDataAttributes = true;
        private DataResolver resolver;
        private Path assetsBase;
        private Map<String, String> attributes = Map.of();

        public Configuration setDataUriForAscii2Svg(final boolean dataUriForAscii2Svg) {
            this.dataUriForAscii2Svg = dataUriForAscii2Svg;
            return this;
        }

        public Configuration setSupportDataAttributes(final boolean supportDataAttributes) {
            this.supportDataAttributes = supportDataAttributes;
            return this;
        }

        public Configuration setResolver(final DataResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        public Configuration setAssetsBase(final Path assetsBase) {
            this.assetsBase = assetsBase;
            return this;
        }

        public Configuration setAttributes(final Map<String, String> attributes) {
            this.attributes = attributes;
            return this;
        }
    }
}
