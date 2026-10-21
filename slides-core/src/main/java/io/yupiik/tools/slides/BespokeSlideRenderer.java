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
package io.yupiik.tools.slides;

import io.yupiik.asciidoc.model.Admonition;
import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.OrderedList;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.model.UnOrderedList;
import io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Locale.ROOT;

/**
 * A slides-flavoured {@link AsciidoctorLikeHtmlRenderer} emitting the lighter markup the asciidoctor-bespoke
 * backend expects, without touching the generic HTML5 renderer (used by minisite/docs). Divergences:
 * <ul>
 *     <li>sections are {@code <section class=roles>} without the {@code sectN} prefix nor an id
 *         (honouring {@code :!sectids:}); a {@code Title: subtitle} is split into a
 *         {@code <header><h2>+<h3></header>};</li>
 *     <li>paragraphs render their (styled) text inline - no {@code div.paragraph}, no {@code <p>};</li>
 *     <li>admonitions drop the {@code tbody} and title-case their label;</li>
 *     <li>table cells get a {@code valign-top} class.</li>
 * </ul>
 */
public class BespokeSlideRenderer extends AsciidoctorLikeHtmlRenderer {
    public BespokeSlideRenderer() {
        this(new Configuration());
    }

    public BespokeSlideRenderer(final Configuration configuration) {
        super(new Configuration()
                .setSkipGlobalContentWrapper(configuration.isSkipGlobalContentWrapper())
                .setSkipSectionBody(configuration.isSkipSectionBody())
                .setSectionTag(configuration.getSectionTag())
                .setSectionIdOnTitle(configuration.isSectionIdOnTitle())
                .setSupportDataAttributes(configuration.isSupportDataAttributes())
                .setAttributes(configuration.getAttributes())
                .setAssetsBase(configuration.getAssetsBase())
                .setDataUriForAscii2Svg(configuration.isDataUriForAscii2Svg())
                .setFontHandler(configuration.getFontHandler())
                .setResolver(configuration.getResolver()));
    }

    @Override
    public void visitBody(final Body body) {
        // the preamble (content before the first section) is re-built in the title slide by YupiikSlidesRenderer,
        // so only the sections are rendered into the deck body here
        final List<Element> sections = new ArrayList<>();
        for (final Element child : body.children()) {
            if (child instanceof Section) {
                sections.add(child);
            }
        }
        if (sections.isEmpty()) {
            body.children().forEach(this::visitElement);
            return;
        }
        stackChain(sections, () -> sections.forEach(this::visitElement));
    }

    @Override
    public void visitSection(final Section element) {
        stackChain(element.children(), () -> {
            final var title = proseSubstitute(sectionTitleText(element));
            final var roles = element.options().get("role");
            builder.append(" <section");
            if (roles != null && !roles.isBlank()) {
                builder.append(" class=\"").append(roles).append('"');
            }
            builder.append(">\n");
            final var subtitle = splitSubtitle(title);
            if (subtitle == null) {
                builder.append("  <h2>").append(escape(title)).append("</h2>\n");
            } else {
                builder.append("  <header><h2>").append(escape(subtitle[0])).append("</h2><h3>").append(escape(subtitle[1])).append("</h3></header>\n");
            }
            if (!configuration.isSkipSectionBody()) {
                builder.append(" <div class=\"sectionbody\">\n");
            }
            for (final Element child : element.children()) {
                visitElement(child);
            }
            if (!configuration.isSkipSectionBody()) {
                builder.append(" </div>\n");
            }
            builder.append(" </section>\n");
        });
    }

    private String sectionTitleText(final Section element) {
        return this.sibling.titleText(element.title(), this.state.context());
    }

    /**
     * Splits a {@code Title: subtitle} into two parts (bespoke title partition), or returns {@code null} when
     * the title has no such separator.
     */
    private String[] splitSubtitle(final String title) {
        final var idx = title.indexOf(": ");
        if (idx <= 0 || idx == title.length() - 2) {
            return null;
        }
        return new String[]{title.substring(0, idx), title.substring(idx + 2)};
    }

    @Override
    public void visitParagraph(final Paragraph element) {
        stackChain(element.children(), () -> {
            // bespoke slides render the (styled) paragraph text inline, without the div.paragraph nor the <p>
            for (final Element child : element.children()) {
                visitElement(child);
            }
        });
    }

    @Override
    public void visitUnOrderedList(final UnOrderedList element) {
        if (element.children().isEmpty()) {
            return;
        }
        stackChain(element.children(), () -> {
            final var isChecklist = element.options().containsKey("checklist");
            builder.append(isChecklist ? " <ul class=\"checklist\">\n" : " <ul>\n");
            visitListElements(element.children(), element.options());
            builder.append(" </ul>\n");
        });
    }

    @Override
    public void visitOrderedList(final OrderedList element) {
        if (element.children().isEmpty()) {
            return;
        }
        final var style = element.options().get("style");
        stackChain(element.children(), () -> {
            builder.append(" <ol");
            if (style != null) {
                builder.append(" class=\"").append(style).append('"');
            }
            builder.append(">\n");
            visitListElements(element.children(), element.options());
            builder.append(" </ol>\n");
        });
    }

    @Override
    public void visitText(final Text element) {
        // bespoke slides render styled text inline - no block <p> / div.paragraph wrappers
        final var value = proseSubstitute(element.value());
        writeStyledText(new Text(element.style(), value, element.options()), false);
    }

    /**
     * Applies the asciidoctor "replacements" text substitutions used by the bespoke backend on prose: a {@code ...}
     * becomes an ellipsis plus a zero-width space, a straight double-quoted word pair becomes curly quotes and an
     * apostrophe between letters becomes a right single quote. Code blocks are not affected (they do not go through
     * {@link #visitText}).
     */
    private static String proseSubstitute(final String value) {
        return curlyQuotes(smartApostrophe(value.replace("...", "\u2026\u200b")));
    }

    private static String smartApostrophe(final String value) {
        // replace an apostrophe between two word characters with a right single quote
        final var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\'' && i > 0 && i < value.length() - 1 &&
                    Character.isLetterOrDigit(value.charAt(i - 1)) && Character.isLetterOrDigit(value.charAt(i + 1))) {
                out.append('\u2019');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String curlyQuotes(final String value) {
        // alternate straight double quotes into open/close curly quotes starting with open
        final var out = new StringBuilder(value.length());
        boolean open = true;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '"') {
                // a quote that is immediately after a word char is a closing quote
                boolean close = i > 0 && Character.isLetterOrDigit(value.charAt(i - 1));
                out.append(close ? '\u201d' : '\u201c');
                open = !open;
                if (close) {
                    open = true;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public void visitCode(final Code element) {
        if (element.inline()) { // inline `code` - resolve `link:URL[text]` macros as asciidoctor does inside a code span
            builder.append("<code>").append(inlineCodeWithLinks(element.value())).append("</code>");
            return;
        }
        final var language = sibling.language(element.options());
        final var lang = language.isEmpty() ? null : language;
        final var style = element.options().get("");
        final var isListing = lang != null || "source".equals(style) || "listing".equals(style);
        if (!isListing) {
            // literal block: <pre>content</pre> as the bespoke template does
            builder.append(" <pre>").append(escape(element.value())).append("</pre>\n");
            return;
        }
        // source/listing block, matching slides-core bespoke template.bespoke/listing.html.slim
        builder.append(" <pre class=\"source");
        final var role = element.options().get("role");
        if (role != null && !role.isBlank()) {
            builder.append(' ').append(role);
        }
        builder.append("\"><code");
        if (lang != null) {
            builder.append(" data-lang=\"").append(lang).append("\"");
        }
        builder.append(" class=\"");
        if (lang != null) {
            builder.append("language-").append(lang).append(' ');
        }
        builder.append("prettyprint\"");
        builder.append(">");
        builder.append(codeEscape(element.value()));
        builder.append("</code></pre>\n");
    }

    /** escapes code content for a {@code <code>} element, keeping double quotes (as the bespoke template does) */
    private static String codeEscape(final String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final java.util.regex.Pattern INLINE_LINK =
            java.util.regex.Pattern.compile("link:([^\\s\\[\\]]+)\\[([^\\]]*)]");

    /**
     * Renders an inline ({@code `code`}) element, escaping it and resolving any embedded {@code link:URL[text]}
     * macro into an {@code <a>} tag (asciidoctor runs inline-macro substitution inside a code span).
     */
    private static String inlineCodeWithLinks(final String value) {
        final var out = new StringBuilder();
        int idx = 0;
        final var matcher = INLINE_LINK.matcher(value);
        while (matcher.find()) {
            out.append(codeEscape(value.substring(idx, matcher.start())));
            final var url = matcher.group(1);
            final var text = matcher.group(2);
            out.append("<a href=\"").append(codeEscape(url)).append("\">").append(codeEscape(text)).append("</a>");
            idx = matcher.end();
        }
        out.append(codeEscape(value.substring(idx)));
        return out.toString();
    }

    @Override
    public void visitAdmonition(final Admonition element) {
        final var opts = element.options();
        final var classes = "admonitionblock " + element.level().name().toLowerCase(ROOT) +
                (opts.containsKey("role") ? ' ' + opts.get("role") : "");
        builder.append(" <div");
        if (opts.containsKey("id")) {
            builder.append(" id=\"").append(opts.get("id")).append('"');
        }
        builder.append(" class=\"").append(classes).append("\">\n");
        builder.append("  <table>\n   <tr>\n    <td class=\"icon\">\n");
        final var docAttrs = state.document() == null ? Map.<String, String>of() : state.document().header().attributes();
        final var icons = attr("icons", docAttrs);
        final var label = element.level().name();
        if (icons != null) {
            if ("font".equals(icons)) {
                builder.append("     <i class=\"fa icon-").append(label.toLowerCase(ROOT)).append("\" title=\"").append(label).append("\"></i>\n");
            } else {
                builder.append("     <img src=\"").append(icons).append("/").append(label.toLowerCase(ROOT)).append(".png\" alt=\"").append(label).append("\">\n");
            }
        } else {
            builder.append("     <div class=\"title\">").append(titleCase(label)).append("</div>\n");
        }
        builder.append("       </td>\n")
                .append("      <td class=\"content\">\n");
        writeBlockTitle(opts);
        visitElement(element.content());
        builder.append("    </td>\n")
                .append("   </tr>\n")
                .append("  </table>\n")
                .append(" </div>\n");
    }

    @Override
    protected void writeTableCell(final String tagName, final Element cell,
                                  final String halign, final String baseClass) {
        // bespoke cells are top aligned by default, as asciidoctor-bespoke does
        super.writeTableCell(tagName, cell, halign,
                baseClass == null || baseClass.contains("valign-") ? baseClass : baseClass + " valign-top");
    }

    private static String titleCase(final String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(ROOT);
    }
}