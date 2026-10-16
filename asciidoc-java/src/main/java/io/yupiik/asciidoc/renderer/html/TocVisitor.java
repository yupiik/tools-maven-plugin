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

import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.VisitorSibling;
import io.yupiik.asciidoc.renderer.VisitorState;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class TocVisitor implements Visitor<StringBuilder> {
    private static final Pattern DROP_ANCHOR_RX = Pattern.compile("<(?:a\\b[^>]*|/a)>");
    private final Configuration configuration;
    private final Collection<Section> sections = new ArrayList<>();

    public TocVisitor(final int toclevels, final int currentLevel) {
        this(toclevels, currentLevel, null, null);
    }

    public TocVisitor(final int toclevels, final int currentLevel, final String idprefix, final String idseparator) {
        this(new VisitorSibling(), key -> switch (key) {
            case "idprefix" -> idprefix;
            case "idseparator" -> idseparator;
            default -> null;
        }, toclevels, currentLevel);
    }

    /**
     * @param sibling the reading of the section ids, the one of the renderer that writes the sections, so each link
     *                points at its section.
     * @param context the attributes the ids are built with, such as {@code idprefix} and {@code idseparator}.
     */
    public TocVisitor(final VisitorSibling sibling, final ConditionalBlock.Context context, final int toclevels, final int currentLevel) {
        this(new Configuration()
                .setState(new VisitorState(sibling, context))
                .setIndexes(true)
                .setToclevels(toclevels)
                .setCurrentLevel(currentLevel));
    }

    /**
     * @param state the state of the renderer that writes the sections: each link reads the id of its section there,
     *              numbered as on the page when two sections would get the same id.
     */
    public TocVisitor(final VisitorState state, final int toclevels, final int currentLevel) {
        this(new Configuration()
                .setState(state)
                .setToclevels(toclevels)
                .setCurrentLevel(currentLevel));
    }

    /**
     * @param configuration what the table of contents lists and how it renders the titles, see {@link Configuration}.
     */
    public TocVisitor(final Configuration configuration) {
        this.configuration = requireNonNull(configuration, "configuration");
        requireNonNull(configuration.getState(), "state");
    }

    @Override
    public void visitBody(final Body body) {
        if (configuration.isIndexes()) {
            configuration.getState().visitBody(body);
        }
        Visitor.super.visitBody(body);
    }

    @Override
    public ConditionalBlock.Context context() { // a section inside ifdef is listed when the page renders it
        return configuration.getState().context();
    }

    @Override
    public void visitSection(final Section element) {
        if (element.level() == configuration.getCurrentLevel() + 1) {
            sections.add(element);
        }
    }

    @Override
    public StringBuilder result() {
        final var builder = new StringBuilder();
        if (sections.isEmpty()) {
            return builder;
        }

        final var state = configuration.getState();
        final var currentLevel = configuration.getCurrentLevel();
        builder.append(" <ul class=\"sectlevel").append(currentLevel).append("\">\n");
        if (currentLevel == configuration.getToclevels()) {
            builder.append(sections.stream()
                    .map(it -> {
                        final var title = title(it.title());
                        return " <li><a href=\"#" + state.sectionId(it.options(), it.title()) + "\">" + title + "</a></li>";
                    })
                    .collect(joining("\n", "", "\n")));
        } else {
            builder.append(sections.stream()
                    .map(it -> {
                        final var tocVisitor = new TocVisitor(new Configuration()
                                .setState(state)
                                .setToclevels(configuration.getToclevels())
                                .setCurrentLevel(currentLevel + 1)
                                .setFontHandler(configuration.getFontHandler()));
                        tocVisitor.visitBody(new Body(it.children()));
                        final var children = tocVisitor.result().toString();
                        final var title = title(it.title());
                        return " <li><a href=\"#" + state.sectionId(it.options(), it.title()) + "\">" + title + "</a>\n" + children + " </li>";
                    })
                    .collect(joining("\n", "", "\n")));
        }
        builder.append(" </ul>\n");
        return builder;
    }

    private String title(final Element title) {
        final var titleRenderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setFontHandler(configuration.getFontHandler()));
        titleRenderer.visitElement(title instanceof Text t && t.options().isEmpty() && t.style().isEmpty() ?
                new Text(t.style(), t.value(), Map.of("nowrap", "")) :
                title);
        var result = titleRenderer.result();
        if (result.contains("<a")) {
            result = DROP_ANCHOR_RX.matcher(result).replaceAll("");
        }
        return result;
    }

    /**
     * The dependencies of a table of contents, so a new one breaks no caller of the constructors.
     */
    @Getter
    public static class Configuration {
        private VisitorState state;
        private int toclevels = 2;
        private int currentLevel = 1;
        private boolean indexes;
        private FontHandler fontHandler;

        /**
         * @param state the state of the renderer that writes the sections, so each link reads the id of its section
         *              there, numbered as on the page; or a state of the table of contents' own, with
         *              {@link #setIndexes(boolean)}.
         * @return this.
         */
        public Configuration setState(final VisitorState state) {
            this.state = state;
            return this;
        }

        /**
         * @param toclevels the deepest section level listed, the {@code toclevels} attribute of the document, 2 by default.
         * @return this.
         */
        public Configuration setToclevels(final int toclevels) {
            this.toclevels = toclevels;
            return this;
        }

        /**
         * @param currentLevel the level of the sections this visitor lists, 1 at the top of the table of contents.
         * @return this.
         */
        public Configuration setCurrentLevel(final int currentLevel) {
            this.currentLevel = currentLevel;
            return this;
        }

        /**
         * @param indexes {@code true} when the state is the table of contents' own, so it indexes the body it lists
         *                before listing it; {@code false} when the renderer that wrote the sections indexed it.
         * @return this.
         */
        public Configuration setIndexes(final boolean indexes) {
            this.indexes = indexes;
            return this;
        }

        /**
         * @param fontHandler the handler of the icon macros in the titles, the one the page renders with, resolved by
         *                    the enclosing renderer; the table of contents does not resolve it itself, so unset it
         *                    falls back to the default of the title renderer.
         * @return this.
         */
        public Configuration setFontHandler(final FontHandler fontHandler) {
            this.fontHandler = fontHandler;
            return this;
        }
    }
}
