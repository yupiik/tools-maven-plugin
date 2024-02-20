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
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.renderer.Visitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

public class TocVisitor implements Visitor<StringBuilder> {
    private final int maxLevel;
    private final int currentLevel;
    private final Collection<Section> sections = new ArrayList<>();

    public TocVisitor(final int toclevels, final int currentLevel) {
        this.maxLevel = toclevels;
        this.currentLevel = currentLevel;
    }

    @Override
    public void visitSection(final Section element) {
        if (element.level() == currentLevel) {
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
                        return " <li><a href=\"#" + id(it, title) + "\">" + title + "</a></li>";
                    })
                    .collect(joining("\n", "", "\n")));
        } else {
            builder.append(sections.stream()
                    .map(it -> {
                        final var tocVisitor = new TocVisitor(maxLevel, currentLevel + 1);
                        tocVisitor.visitBody(new Body(it.children()));
                        final var children = tocVisitor.result().toString();
                        final var title = title(it.title());
                        return " <li><a href=\"#" + id(it, title) + "\">" + title + "</a>\n" + children + " </li>";
                    })
                    .collect(joining("\n", "", "\n")));
        }
        builder.append(" </ul>\n");
        return builder;
    }

    private String id(final Section section, final String title) {
        return ofNullable(section.options().get("id"))
                // todo: better sanitization
                .orElseGet(() -> IdGenerator.forTitle(title));
    }

    private String title(final Element title) {
        final var titleRenderer = new AsciidoctorLikeHtmlRenderer(new AsciidoctorLikeHtmlRenderer.Configuration());
        titleRenderer.visitElement(title instanceof Text t && t.options().isEmpty() && t.style().isEmpty() ?
                new Text(t.style(), t.value(), Map.of("nowrap", "")) :
                title);
        return titleRenderer.result();
    }
}
