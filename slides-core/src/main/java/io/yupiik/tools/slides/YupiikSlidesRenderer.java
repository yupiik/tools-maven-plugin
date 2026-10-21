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

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure java slides renderer based on the yupiik {@code asciidoc-java} parser/renderer.
 * It does not require asciidoctorj/JRuby and emits a standalone bespoke/revealjs document
 * skeleton matching the placeholders the {@code Slider.postProcess} expects
 * ({@code build/build.js} and {@code build/build.css}).
 * <p>
 * It renders the body with {@link BespokeSlideRenderer}, a slides-flavoured {@link AsciidoctorLikeHtmlRenderer}
 * (light markup: no section prefix, no ids, inline paragraphs, title-cased admonitions, {@code valign-top} cells),
 * and then applies the remaining bespoke-specific structure: a real document title, the dedicated title slide,
 * the {@code span.primary} list markers and the {@code <figure>} image wrapper.
 */
public class YupiikSlidesRenderer implements SlidesRenderer {
    /** block-level element a list item can contain - not wrapped in {@code span.primary} */
    private static final String[] BLOCK_TAGS = {"<ul", "<ol", "<div", "<pre", "<table", "</ul", "</ol"};
    private static final Pattern IMAGE_BLOCK = Pattern.compile("<div class=\"imageblock( [^\"]*)?\">\\s*<div class=\"content\">(.*?)</div>\\s*</div>", Pattern.DOTALL);
    private static final Pattern PREAMBLE = Pattern.compile("(?s)<div id=\"preamble\">.*?<div class=\"sectionbody\">(.*?)</div>\\s*</div>");

    @Override
    public void render(final Rendering rendering) {
        final Path source = rendering.getSource();
        final String content;
        try {
            content = Files.readString(source);
        } catch (final IOException e) {
            throw new IllegalStateException("Can't read slide deck " + source, e);
        }
        final var attributes = new LinkedHashMap<String, String>();
        rendering.getAttributes().forEach((k, v) -> attributes.put(k, v == null ? "" : String.valueOf(v)));
        attributes.putIfAbsent("noheader", "true"); // content-only: we assemble the deck skeleton ourselves

        final var parser = new Parser(attributes);
        final var base = rendering.getBaseDir() == null ? source.getParent() : rendering.getBaseDir();
        final var document = parser.parse(content, new Parser.ParserContext(ContentResolver.of(base == null ? Path.of(".") : base)));

        final var bodyRenderer = new BespokeSlideRenderer(new AsciidoctorLikeHtmlRenderer.Configuration()
                .setSkipGlobalContentWrapper(true)
                .setSkipSectionBody(true)
                .setSectionTag("section")
                .setAttributes(attributes)
                .setAssetsBase(base));
        bodyRenderer.visit(document);

        final var header = document.header();
        final String deckTitle = header.title();
        final String author = header.author() == null || header.author().isEmpty() ? null : header.author().get(0).name();
        final String preamble = extractPreambleText(document);
        final var rawBody = bodyRenderer.result();
        final var body = postProcess(rawBody);
        final var titleSlide = buildTitleSlide(header, deckTitle, preamble);
        final var deck = renderSkeleton("bespoke".equals(rendering.getBackend()), deckTitle, author, titleSlide + body);
        try {
            Files.createDirectories(rendering.getOutput().getParent());
            Files.writeString(rendering.getOutput(), deck, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Emits the bespoke title slide from the document header: an {@code <h1>} with the deck title, the
     * preamble (kicker) content and an author footer block (with contact/twitter/url when present).
     */
    private String buildTitleSlide(final io.yupiik.asciidoc.model.Header header, final String deckTitle, final String preamble) {
        final var sb = new StringBuilder();
        sb.append("<section class=\"title\" data-title=\"\"><h1>")
                .append(deckscape(deckTitle))
                .append("</h1>");
        if (preamble != null && !preamble.isBlank()) {
            sb.append("<p>").append(preamble).append("</p>");
        }
        final var authors = header.author();
        final var authorAttrs = header.attributes();
        final var org = authorAttrs.get("organization");
        final var position = authorAttrs.get("position");
        final var twitter = authorAttrs.get("twitter");
        if (authors != null && !authors.isEmpty()) {
            final var builder = new StringBuilder("<footer><p class=\"author\">");
            for (final var author : authors) {
                builder.append("<span class=\"personname\"><span class=\"firstname\">")
                        .append(deckscape(author.firstname()))
                        .append("</span> <span class=\"surname\">")
                        .append(author.lastname() == null ? "" : deckscape(author.lastname()))
                        .append("</span></span>");
                if (position != null || org != null) {
                    builder.append("<span class=\"affiliation\">");
                    if (position != null) {
                        builder.append("<span class=\"position\">").append(deckscape(position)).append("</span> ");
                    }
                    if (org != null) {
                        builder.append("<span class=\"organization\">").append(deckscape(org)).append("</span>");
                    }
                    builder.append("</span>");
                }
                final var url = authorUrl(author);
                if (twitter != null || url != null) {
                    builder.append("<span class=\"contact\">");
                    if (twitter != null) {
                        builder.append("<span class=\"twitter\">").append(deckscape(twitter)).append("</span> ");
                    }
                    if (url != null) {
                        builder.append("<span class=\"url\">").append(deckscape(url)).append("</span>");
                    }
                    builder.append("</span>");
                }
            }
            builder.append("</p></footer>");
            sb.append(builder);
        }
        sb.append("</section>\n");
        return sb.toString();
    }

    /**
     * The author url, derived from its mail (as asciidoctor does): the protocol and any {@code mailto:} are stripped.
     */
    private String authorUrl(final io.yupiik.asciidoc.model.Author author) {
        if (author.mail() == null || author.mail().isBlank()) {
            return null;
        }
        return author.mail()
                .replaceFirst("^mailto:", "")
                .replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
    }

    /**
     * Extracts the document preamble (content before the first section) from the parsed model, rendered as inline
     * text paragraphs for the title slide. The {@link BespokeSlideRenderer} is asked (via a shared flag) not to
     * emit it in the body so it does not leak before the first slide.
     */
    private String extractPreambleText(final io.yupiik.asciidoc.model.Document document) {
        final var body = document.body();
        if (body == null) {
            return "";
        }
        final var sb = new StringBuilder();
        for (final var child : body.children()) {
            if (child instanceof io.yupiik.asciidoc.model.Section) {
                break;
            }
            sb.append(deckscape(textOf(child)));
        }
        return sb.toString();
    }

    private String textOf(final Object child) {
        if (child instanceof io.yupiik.asciidoc.model.Text t) {
            return t.value();
        }
        if (child instanceof io.yupiik.asciidoc.model.Paragraph p) {
            final var sb = new StringBuilder();
            for (final var c : p.children()) {
                sb.append(textOf(c));
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * Applies the bespoke-specific structural transforms on the generic rendered body: drop the header details
     * and preamble (the kicker becomes part of the title slide), split {@code : } titles into a header, wrap
     * list items in {@code span.primary} and turn {@code div.imageblock} into {@code figure}.
     */
    private String postProcess(final String raw) {
        // remove the generic document details (author block) and the preamble block - both are re-built in the
        // title slide by this renderer
        var body = raw.replaceFirst("(?s)\\s*<div class=\"details\">.*?</div>\\s*", "")
                .replaceFirst("(?s)\\s*<div id=\"preamble\">.*?</div>\\s*", "");
        // wrap the inline content of a list item in span.primary (bespoke styling); nested/block items are left alone
        body = wrapListItems(body);
        // turn a div.imageblock (with optional roles) into a figure
        body = IMAGE_BLOCK.matcher(body).replaceAll(m -> {
            final var role = m.group(1) == null ? "" : m.group(1).trim();
            final var content = m.group(2);
            return "<figure class=\"image" + (role.isBlank() ? "" : " " + role) + "\">" + content + "</figure>";
        });
        return body;
    }

    /**
     * Wraps the (inline) content of a simple list item in a {@code span.primary}, as asciidoctor-bespoke does.
     * Items whose content includes a block-level element (nested list, div, pre, table) are left untouched.
     */
    private static String wrapListItems(final String body) {
        final var out = new StringBuilder(body.length());
        int idx = 0;
        while (idx < body.length()) {
            final var open = body.indexOf("<li>", idx);
            if (open < 0) {
                out.append(body, idx, body.length());
                break;
            }
            final var close = body.indexOf("</li>", open);
            if (close < 0) {
                out.append(body, idx, body.length());
                break;
            }
            out.append(body, idx, open);
            final String inner = body.substring(open + 4, close);
            boolean block = false;
            for (final String tag : BLOCK_TAGS) {
                if (inner.contains(tag)) {
                    block = true;
                    break;
                }
            }
            out.append("<li>");
            if (block) {
                out.append(inner);
            } else {
                out.append("<span class=\"primary\">").append(inner).append("</span>");
            }
            out.append("</li>");
            idx = close + 5;
        }
        return out.toString();
    }

    private String renderSkeleton(final boolean bespoke, final String title, final String author, final String body) {
        final var titleEscaped = deckscape(title);
        final var head = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>" + titleEscaped + "</title>" +
                (author == null || author.isBlank() ? "" : "<meta name=\"author\" content=\"" + deckscape(author) + "\">") +
                "<meta name=\"mobile-web-app-capable\" content=\"yes\">";
        if (bespoke) {
            return head + "<link rel=\"stylesheet\" href=\"build/build.css\">" +
                    "</head><body><article class=\"deck\">" + body + "</article>" +
                    "<script src=\"build/build.js\"></script></body></html>";
        }
        return head +
                "</head><body><div class=\"reveal\"><div class=\"slides\">" + body + "</div></div>" +
                "<script src=\"build/build.js\"></script></body></html>";
    }

    private static String deckscape(final String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}