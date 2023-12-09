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
package io.yupiik.asciidoc.renderer.html;

import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Header;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.renderer.Visitor;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;

import static java.util.stream.Collectors.joining;

/**
 * Important: as of today it is a highly incomplete implementation but it gives a starting point.
 * <p>
 * Trivial document renderer as HTML.
 */
public class SimpleHtmlRenderer implements Visitor<String> {
    private final StringBuilder builder = new StringBuilder();
    private final Map<String, String> attributes;

    public SimpleHtmlRenderer() {
        this(Map.of());
    }

    public SimpleHtmlRenderer(final Map<String, String> attributes) {
        this.attributes = attributes;
    }

    private String attr(final String key, final Map<String, String> defaultMap) {
        return attributes.getOrDefault(key, defaultMap.get(key));
    }

    @Override
    public void visit(final Document document) {
        final boolean contentOnly = Boolean.parseBoolean(attributes.getOrDefault("noheader", "false"));
        if (!contentOnly) {
            builder.append("<!DOCTYPE html>\n");
            builder.append("<html");
            if (attr("nolang", document.header().attributes()) == null) {
                final var lang = attr("lang", document.header().attributes());
                builder.append(" lang=\"").append(lang == null ? "en" : lang).append('"');
            }
            builder.append(">\n");
            builder.append("<head>\n");

            final var encoding = attr("encoding", document.header().attributes());
            builder.append(" <meta charset=\"").append(encoding == null ? "UTF-8" : encoding).append("\">\n");

            builder.append(" <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n");

            final var appName = attr("app-name", document.header().attributes());
            if (appName != null) {
                builder.append(" <meta name=\"application-name\" content=\"").append(appName).append("\">\n");
            }
            final var description = attr("description", document.header().attributes());
            if (description != null) {
                builder.append(" <meta name=\"description\" content=\"").append(description).append("\">\n");
            }
            final var keywords = attr("keywords", document.header().attributes());
            if (keywords != null) {
                builder.append(" <meta name=\"keywords\" content=\"").append(keywords).append("\">\n");
            }
            final var author = attr("author", document.header().attributes());
            if (author != null) {
                builder.append(" <meta name=\"author\" content=\"").append(author).append("\">\n");
            }
            final var copyright = attr("copyright", document.header().attributes());
            if (copyright != null) {
                builder.append(" <meta name=\"copyright\" content=\"").append(copyright).append("\">\n");
            }

            // todo: favicon, highlighter, toc support etc...
            builder.append("</head>\n");

            builder.append("<body>\n");

            builder.append(" <div id=\"content\">\n");
        }
        Visitor.super.visit(document);
        if (!contentOnly) {
            builder.append(" </div>\n");

            builder.append("</body>\n");
            builder.append("</html>\n");
        }
    }

    @Override
    public void visitHeader(final Header header) {
        if (header.attributes().get("notitle") == null && !header.title().isBlank()) {
            builder.append(" <h1>").append(escape(header.title())).append("</h1>\n");
        }

        final var details = new StringBuilder();
        {
            int authorIdx = 1;
            final var mails = header.author().name().split(",");
            for (final var name : header.author().name().split(",")) {
                if (name.isBlank()) {
                    continue;
                }
                details.append("<span class=\"author author-").append(authorIdx).append("\">").append(escape(name)).append("</span>\n");

                final var mail = mails.length > (authorIdx - 1) ? mails[authorIdx - 1] : null;
                if (mail != null) {
                    details.append("<span class=\"email email-").append(authorIdx++).append("\">").append(escape(mail)).append("</span>\n");
                }
                authorIdx++;
            }
        }
        if (!header.revision().number().isBlank()) {
            details.append("<span id=\"revnumber\">").append(escape(header.revision().number())).append("</span>\n");
        }
        if (!header.revision().date().isBlank()) {
            details.append("<span id=\"revdate\">").append(escape(header.revision().date())).append("</span>\n");
        }
        if (!header.revision().revmark().isBlank()) {
            details.append("<span id=\"revremark\">").append(escape(header.revision().revmark())).append("</span>\n");
        }
        if (!details.isEmpty()) {
            builder.append("  <div class=\"details\">\n").append(details.toString().indent(3)).append("  </div>\n");
        }
    }

    @Override
    public void visitSection(final Section element) {
        builder.append(" <div>\n");
        builder.append("  <h").append(element.level());
        writeCommonAttributes(element.options(), null);
        builder.append(">");
        final var titleRenderer = new SimpleHtmlRenderer(attributes);
        titleRenderer.visitElement(element.title() instanceof Text t && t.options().isEmpty() && t.style().isEmpty() ?
                new Text(t.style(), t.value(), Map.of("nowrap", "")) :
                element);
        builder.append(titleRenderer.result());
        builder.append("</h").append(element.level()).append(">\n");
        Visitor.super.visitSection(element);
        builder.append(" </div>\n");
    }

    @Override
    public void visitText(final Text element) {
        final var wrap = element.options().get("nowrap") == null && element.style().size() != 1;
        if (wrap) {
            builder.append(" <span");
            writeCommonAttributes(element.options(), null);
            builder.append(">\n");
        }
        final var styleTags = element.style().stream()
                .map(s -> switch (s) {
                    case BOLD -> "b";
                    case ITALIC -> "i";
                    case EMPHASIS -> "em";
                    case SUB -> "sub";
                    case SUP -> "sup";
                    case MARK -> "mark";
                })
                .toList();
        builder.append(styleTags.stream().map(s -> '<' + s + '>').collect(joining()));
        builder.append(escape(element.value().strip()));
        builder.append(styleTags.stream().sorted(Comparator.reverseOrder()).map(s -> "</" + s + '>').collect(joining()));
        if (wrap) {
            builder.append("\n </span>\n");
        }
    }

    @Override
    public void visitCode(final Code element) {
        builder.append("<pre");
        final var lang = element.options().getOrDefault("lang", element.options().get("language"));
        if (lang != null) {
            builder.append(" data-lang=\"").append(lang).append("\"");
        }
        writeCommonAttributes(element.options(), c -> lang != null ? "language-" + lang + (c != null ? ' ' + c : "") : c);
        builder.append(">\n  <code>\n");
        builder.append(element.value()); // todo: handle callouts - but by default it should render not that bad
        builder.append("  </code>\n </pre>\n");
    }

    @Override
    public ConditionalBlock.Context context() {
        return attributes::get;
    }

    @Override
    public String result() {
        return builder.toString();
    }

    protected void writeCommonAttributes(final Map<String, String> options, final Function<String, String> classProcessor) {
        var classes = options.get("role");
        if (classProcessor != null) {
            classes = classProcessor.apply(classes);
        }
        if (classes != null && !classes.isBlank()) {
            builder.append(" class=\"").append(classes).append("\"");
        }

        final var id = options.get("id");
        if (id != null && !id.isBlank()) {
            builder.append(" id=\"").append(id).append("\"");
        }
    }

    protected String escape(final String name) {
        return HtmlEscaping.INSTANCE.apply(name);
    }
}
