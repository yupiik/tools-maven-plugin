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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.joining;

public class TocVisitor implements Visitor<StringBuilder> {
    private static final Pattern DROP_ANCHOR_RX = Pattern.compile("<(?:a\\b[^>]*|/a)>");
    private final int maxLevel;
    private final int currentLevel;
    private final VisitorState state;
    private final boolean indexes; // the state is its own, so it indexes the body it lists
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
        this(new VisitorState(sibling, context), toclevels, currentLevel, true);
    }

    /**
     * @param state the state of the renderer that writes the sections: each link reads the id of its section there,
     *              numbered as on the page when two sections would get the same id.
     */
    public TocVisitor(final VisitorState state, final int toclevels, final int currentLevel) {
        this(state, toclevels, currentLevel, false);
    }

    private TocVisitor(final VisitorState state, final int toclevels, final int currentLevel, final boolean indexes) {
        this.state = state;
        this.maxLevel = toclevels;
        this.currentLevel = currentLevel;
        this.indexes = indexes;
    }

    @Override
    public void visitBody(final Body body) {
        if (indexes) {
            state.visitBody(body);
        }
        Visitor.super.visitBody(body);
    }

    @Override
    public ConditionalBlock.Context context() { // a section inside ifdef is listed when the page renders it
        return state.context();
    }

    @Override
    public void visitSection(final Section element) {
        if (element.level() == currentLevel + 1) {
            sections.add(element);
        }
    }

    @Override
    public StringBuilder result() {
        final var builder = new StringBuilder();
        if (sections.isEmpty()) {
            return builder;
        }

        builder.append(" <ul class=\"sectlevel").append(currentLevel).append("\">\n");
        if (currentLevel == maxLevel) {
            builder.append(sections.stream()
                    .map(it -> {
                        final var title = title(it.title());
                        return " <li><a href=\"#" + state.sectionId(it.options(), it.title()) + "\">" + title + "</a></li>";
                    })
                    .collect(joining("\n", "", "\n")));
        } else {
            builder.append(sections.stream()
                    .map(it -> {
                        final var tocVisitor = new TocVisitor(state, maxLevel, currentLevel + 1);
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
        final var titleRenderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration());
        titleRenderer.visitElement(title instanceof Text t && t.options().isEmpty() && t.style().isEmpty() ?
                new Text(t.style(), t.value(), Map.of("nowrap", "")) :
                title);
        var result = titleRenderer.result();
        if (result.contains("<a")) {
            result = DROP_ANCHOR_RX.matcher(result).replaceAll("");
        }
        return result;
    }
}
