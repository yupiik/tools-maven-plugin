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
package io.yupiik.asciidoc.renderer.markdown;

import io.yupiik.asciidoc.model.Admonition;
import io.yupiik.asciidoc.model.Anchor;
import io.yupiik.asciidoc.model.Attribute;
import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.DescriptionList;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.FloatingTitle;
import io.yupiik.asciidoc.model.HorizontalRule;
import io.yupiik.asciidoc.model.LineBreak;
import io.yupiik.asciidoc.model.Link;
import io.yupiik.asciidoc.model.Listing;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.OpenBlock;
import io.yupiik.asciidoc.model.OrderedList;
import io.yupiik.asciidoc.model.PageBreak;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.PassthroughBlock;
import io.yupiik.asciidoc.model.Quote;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Table;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.model.UnOrderedList;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.VisitorSibling;
import io.yupiik.asciidoc.renderer.VisitorState;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import static java.util.stream.Collectors.joining;

/**
 * Renders a document as GitHub-Flavored Markdown: ATX headings, fenced code with the language, pipe tables,
 * admonitions as GitHub alerts, callouts as a numbered list after the code block, footnotes.
 * <p>
 * Every block appends its Markdown followed by a blank line. Nested content (admonitions, quotes, list items,
 * table cells) is rendered by the same renderer into a temporary buffer, so a subclass's overrides apply at any
 * depth. When neither the document nor the configuration defines an attribute, its reference stays literal, as
 * asciidoctor does with {@code attribute-missing=skip}. An unknown construct never throws, the renderer falls back to
 * the element text; only an {@code include} macro fails, since the parser resolves includes before any rendering.
 * <p>
 * The renderer writes the Markdown. It reads the values of the model that need parsing (ids, styles, options,
 * targets, macro labels) with a {@link VisitorSibling}, and keeps the document, the section index and the footnotes in a
 * {@link VisitorState}, which it delegates to when a document or a body starts.
 * <p>
 * A renderer can render several documents one after the other, {@link #visit(Document)} starts from a clean state.
 * Like {@link io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer} it is not thread safe.
 */
public class GithubFlavoredMarkdownRenderer implements Visitor<String> {
    protected final Configuration configuration;
    protected final VisitorSibling sibling;
    protected final VisitorState state; // this is why we are not thread safe
    protected StringBuilder builder = new StringBuilder(); // replaced while a nested block renders, see block()

    public GithubFlavoredMarkdownRenderer() {
        this(new Configuration());
    }

    public GithubFlavoredMarkdownRenderer(final Configuration configuration) {
        this(configuration, new VisitorSibling());
    }

    /**
     * @param sibling how the renderer and its state read the model, a subclass of {@link VisitorSibling} changes a
     *                reading in both.
     */
    public GithubFlavoredMarkdownRenderer(final Configuration configuration, final VisitorSibling sibling) {
        this.configuration = configuration;
        this.sibling = sibling;
        this.state = new VisitorState(sibling, key -> configuration.getAttributes().get(key)) {
            @Override
            public ConditionalBlock.Context context() { // a subclass overriding context() changes the index too
                return GithubFlavoredMarkdownRenderer.this.context();
            }
        };
    }

    // ------------------------------------------------------------------------------------------------------ document

    @Override
    public void visit(final Document document) {
        builder = new StringBuilder();
        state.visit(document);
        final var header = document.header();
        final var noheader = state.attribute("noheader", null); // asciidoctor: set means true, whatever the value but "false"
        if (header != null && header.title() != null && !header.title().isBlank()
                && (noheader == null || "false".equalsIgnoreCase(noheader.strip()))) {
            visitTitle(header.title());
        }
        visitBody(document.body());
    }

    @Override
    public void visitTitle(final String title) {
        final var value = sibling.substitute(title, context()).strip();
        if (!value.isEmpty()) {
            builder.append("# ").append(value).append("\n\n");
        }
    }

    @Override
    public void visitBody(final Body body) {
        // indexed here rather than in visit(Document), so rendering only a body keeps section links working
        state.visitBody(body);
        final var placement = sibling.tocPlacement(context());
        final var children = body.children();
        if (placement == null) {
            renderChildren(children);
            return;
        }
        switch (placement) {
            case "macro" -> renderChildren(children); // written by visitMacro
            case "preamble" -> { // after the content before the first section
                int firstSection = 0;
                while (firstSection < children.size() && children.get(firstSection).type() != Element.ElementType.SECTION) {
                    firstSection++;
                }
                renderChildren(children.subList(0, firstSection));
                toc(null, null);
                renderChildren(children.subList(firstSection, children.size()));
            }
            default -> {
                toc(null, null);
                renderChildren(children);
            }
        }
    }

    /**
     * Writes the table of contents as a nested list of links to the sections, under its title in bold.
     *
     * @param title       the title of the {@code toc::[]} macro block, {@code null} to use {@code toc-title}.
     * @param macroLevels the {@code levels} attribute of the macro, {@code null} to use {@code toclevels}.
     */
    protected void toc(final String title, final String macroLevels) {
        final int levels = sibling.tocLevels(macroLevels, context());
        int top = Integer.MAX_VALUE; // a book starts at level 0, an article at level 1
        for (final var section : state.tocSections()) {
            if (section.level() <= levels) {
                top = Math.min(top, section.level());
            }
        }
        if (top == Integer.MAX_VALUE) { // asciidoctor writes no table of contents for a document without section
            return;
        }
        final var tocTitle = title != null && !title.isBlank() ? title : state.attribute("toc-title", "Table of Contents");
        if (!tocTitle.isBlank()) {
            builder.append("**").append(sibling.substitute(tocTitle, context()).strip()).append("**\n\n");
        }
        for (final var section : state.tocSections()) {
            if (section.level() <= levels) {
                builder.append("  ".repeat(section.level() - top))
                        .append("- [").append(escape(section.title(), "[]")).append("](#").append(section.id()).append(")\n");
            }
        }
        builder.append('\n');
    }

    /**
     * Renders the children of a block container (body, section, open block, ...), where each inline element is a
     * paragraph of its own unless a line break ties it to its neighbours, see {@link #segments(List, boolean)}.
     */
    protected void renderChildren(final List<Element> children) {
        renderSegments(segments(children, false));
    }

    protected void renderSegments(final List<List<Element>> segments) {
        for (final var segment : segments) {
            if (segment.size() == 1 && !sibling.isInline(segment.get(0))) {
                visitElement(segment.get(0));
            } else {
                paragraph(inlineChildren(segment));
            }
        }
    }

    /**
     * Splits sibling elements into segments: each block element alone, and each run of inline elements forming one
     * paragraph.
     * <p>
     * The parser does not wrap every paragraph, so where a paragraph ends is guessed from the whitespace the parser
     * keeps around the styled texts, attributes and links it splits a paragraph into:
     * <ul>
     *     <li>in a {@code Paragraph} the children are one paragraph, except that a delimited block (admonition, list
     *     item) gives its source paragraphs as sibling texts: two unstyled texts meeting with no whitespace are two
     *     paragraphs,</li>
     *     <li>in a block container each inline element is a paragraph, except that a paragraph holding a line break is
     *     given as its inline elements: around a line break, elements meeting on whitespace or punctuation are one
     *     paragraph.</li>
     * </ul>
     *
     * @param children       the sibling elements.
     * @param paragraphLevel true for the children of a {@code Paragraph}, false for the children of a block container.
     * @return the segments, in order.
     */
    protected List<List<Element>> segments(final List<Element> children, final boolean paragraphLevel) {
        final var segments = new ArrayList<List<Element>>();
        List<Element> run = null;
        for (final var child : children) {
            if (run != null && run.get(run.size() - 1) instanceof LineBreak && child instanceof Paragraph paragraph
                    && paragraph.children().stream().allMatch(sibling::isInline)) {
                run.addAll(paragraph.children()); // the parser wraps the line after a line break when it has markup
            } else if (!sibling.isInline(child)) {
                if (run != null) {
                    segments.addAll(paragraphLevel ? splitParagraphRun(run) : splitBlockRun(run));
                    run = null;
                }
                segments.add(List.of(child));
            } else {
                if (run == null) {
                    run = new ArrayList<>();
                }
                run.add(child);
            }
        }
        if (run != null) {
            segments.addAll(paragraphLevel ? splitParagraphRun(run) : splitBlockRun(run));
        }
        return segments;
    }

    // the parser gives the paragraphs of a delimited admonition or of a list item as sibling texts, see segments()
    protected List<List<Element>> splitParagraphRun(final List<Element> run) {
        return split(run, (before, after) -> before instanceof Text b && after instanceof Text a
                && b.style().isEmpty() && a.style().isEmpty()
                && !endsWithWhitespace(b) && !startsWithWhitespaceOrPunctuation(a));
    }

    protected List<List<Element>> splitBlockRun(final List<Element> run) {
        if (run.stream().noneMatch(LineBreak.class::isInstance)) {
            return run.stream().map(List::of).toList();
        }
        return split(run, (before, after) -> !(before instanceof LineBreak) && !(after instanceof LineBreak)
                && !(before instanceof Text b && endsWithWhitespace(b))
                && !(after instanceof Text a && startsWithWhitespaceOrPunctuation(a)));
    }

    private List<List<Element>> split(final List<Element> run, final BiPredicate<Element, Element> isBoundary) {
        final var segments = new ArrayList<List<Element>>();
        var current = new ArrayList<Element>();
        for (final var element : run) {
            if (!current.isEmpty() && isBoundary.test(current.get(current.size() - 1), element)) {
                segments.add(current);
                current = new ArrayList<>();
            }
            current.add(element);
        }
        segments.add(current);
        return segments;
    }

    protected boolean endsWithWhitespace(final Text text) {
        final var value = text.value();
        return value == null || value.isEmpty() || Character.isWhitespace(value.charAt(value.length() - 1));
    }

    protected boolean startsWithWhitespaceOrPunctuation(final Text text) {
        final var value = text.value();
        return value == null || value.isEmpty() || Character.isWhitespace(value.charAt(0)) || ".,;:!?)]}".indexOf(value.charAt(0)) >= 0;
    }

    private void paragraph(final String text) {
        var value = text.strip();
        if (value.endsWith("\\")) { // a line break ending the paragraph
            value = value.substring(0, value.length() - 1).strip();
        }
        if (!value.isEmpty()) {
            builder.append(value).append("\n\n");
        }
    }

    @Override
    public ConditionalBlock.Context context() {
        return state.attributes();
    }

    /**
     * @return the Markdown, ending with one line feed as a text file does.
     */
    @Override
    public String result() {
        final var body = builder.toString().strip();
        if (state.footnotes().isEmpty()) {
            return body + '\n';
        }
        final var result = new StringBuilder(body);
        if (!body.isEmpty()) {
            result.append("\n\n");
        }
        for (final var footnote : state.footnotes()) {
            final var text = footnote.text().strip();
            result.append("[^").append(footnote.label()).append("]:").append(text.isEmpty() ? "" : " ").append(text).append('\n');
        }
        return result.toString();
    }

    // -------------------------------------------------------------------------------------------------------- blocks

    @Override
    public void visitSection(final Section section) {
        heading(section.level(), section.title(), section.options());
        renderChildren(section.children());
    }

    @Override
    public void visitFloatingTitle(final FloatingTitle title) {
        heading(title.level(), title.title(), title.options());
    }

    protected void heading(final int level, final Element title, final Map<String, String> options) {
        final var id = sibling.sectionId(options, title, context());
        if (sibling.id(options) != null || state.isReferenced(id)) { // asciidoctor gives every section an id, written when the document links to it
            anchor(id);
        }
        builder.append("#".repeat(Math.max(1, Math.min(level, 6)))).append(' ').append(inline(title).strip()).append("\n\n");
    }

    /**
     * Markdown has no syntax for an id, an empty HTML anchor keeps the {@code #id} links working. The line is written
     * right before the heading or the paragraph it belongs to.
     */
    protected void anchor(final String id) {
        builder.append("<a id=\"").append(id).append("\"></a>\n");
    }

    /**
     * Writes the anchor of a block on a line of its own, so it can precede any block (a table cannot follow a
     * paragraph line directly).
     */
    protected void blockAnchor(final Map<String, String> options) {
        final var id = sibling.id(options);
        if (id != null) {
            anchor(id);
            builder.append('\n');
        }
    }

    @Override
    public void visitParagraph(final Paragraph paragraph) {
        final var id = sibling.id(paragraph.options());
        if (id != null) {
            anchor(id);
        }
        renderSegments(segments(paragraph.children(), true));
    }

    @Override
    public void visitText(final Text text) {
        paragraph(inline(text));
    }

    @Override
    public void visitLink(final Link link) {
        paragraph(inline(link));
    }

    @Override
    public void visitAnchor(final Anchor anchor) {
        paragraph(inline(anchor));
    }

    @Override
    public void visitLineBreak(final LineBreak lineBreak) {
        // only reached outside a paragraph, renderChildren keeps a line break inside its paragraph
        builder.append("\\\n");
    }

    @Override
    public void visitAttribute(final Attribute attribute) {
        final var value = context().attribute(attribute.attribute());
        if (value == null) {
            builder.append('{').append(attribute.attribute()).append("}\n\n");
            return;
        }
        renderChildren(attribute.evaluator().apply(value));
    }

    @Override
    public void visitCode(final Code code) {
        if (code.inline()) {
            builder.append(inline(code));
            return;
        }
        blockAnchor(code.options());
        blockTitle(code.options());
        fence(fenceInfo(options(code.options())), withCallOutMarkers(code));
        final var callOuts = code.callOuts(); // derived from the lines, so materialized once
        if (!callOuts.isEmpty()) {
            for (final var callOut : callOuts) { // a callout can carry blocks, attached with +
                listItem(callOut.number() + ". ", callOut.text());
            }
            builder.append('\n');
        }
    }

    /**
     * The parser removed the callout markers from the code and records which callouts ended each line
     * ({@link Code#lineCallOuts()}), this puts the markers back in their source form, {@code a=b <1><2>}.
     */
    protected String withCallOutMarkers(final Code code) {
        final var value = code.value() == null ? "" : code.value();
        if (code.lineCallOuts().isEmpty()) {
            return value;
        }
        final var lines = value.split("\n", -1);
        final var out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < code.lineCallOuts().size() && !code.lineCallOuts().get(i).isEmpty()) {
                out.append(' ');
                for (final var callOut : code.lineCallOuts().get(i)) {
                    out.append('<').append(callOut.number()).append('>');
                }
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    @Override
    public void visitListing(final Listing listing) {
        final var options = options(listing.options());
        blockAnchor(options);
        blockTitle(options);
        fence(fenceInfo(options), listing.value() == null ? "" : listing.value());
    }

    /**
     * @return the info string of a code fence: the language, else the style of a diagram block such as
     * {@code [mermaid]}; empty for the {@code source}, {@code listing} and {@code literal} styles, which name no language.
     */
    protected String fenceInfo(final Map<String, String> options) {
        final var language = sibling.language(options);
        if (!language.isEmpty()) {
            return language;
        }
        final var style = sibling.styleName(options);
        if ("source".equals(style) || "listing".equals(style) || "literal".equals(style)) {
            return "";
        }
        for (int i = 0; i < style.length(); i++) { // a backtick or a space would end the info string of the fence
            if (style.charAt(i) == '`' || Character.isWhitespace(style.charAt(i))) {
                return "";
            }
        }
        return style;
    }

    /**
     * The fence is one backtick longer than the longest run of backticks in the code, and at least three long, so no
     * line of the code can close it.
     */
    protected void fence(final String language, final String value) {
        final var fence = "`".repeat(Math.max(3, longestBacktickRun(value) + 1));
        builder.append(fence).append(language == null ? "" : language).append('\n').append(value);
        if (!value.endsWith("\n")) {
            builder.append('\n');
        }
        builder.append(fence).append("\n\n");
    }

    /**
     * @return the length of the longest run of backticks in the value, which a Markdown code delimiter must exceed.
     */
    protected int longestBacktickRun(final String value) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '`') {
                longest = Math.max(longest, ++current);
            } else {
                current = 0;
            }
        }
        return longest;
    }

    protected void blockTitle(final Map<String, String> options) {
        final var title = options == null ? null : options.get("title");
        if (title != null && !title.isBlank()) {
            builder.append("**").append(title.strip()).append("**\n\n");
        }
    }

    @Override
    public void visitUnOrderedList(final UnOrderedList list) {
        renderList(list.children(), false, options(list.options()));
    }

    @Override
    public void visitOrderedList(final OrderedList list) {
        renderList(list.children(), true, options(list.options()));
    }

    protected void renderList(final List<Element> items, final boolean ordered, final Map<String, String> options) {
        blockAnchor(options);
        blockTitle(options);
        int number = 1;
        final var start = ordered ? sibling.listStart(options) : null;
        if (start != null) { // GFM list numbers are 0 to 999999999, a negative start cannot be written
            number = Math.max(0, Math.min(999_999_999, start));
        }
        for (final var item : items) {
            listItem(ordered ? number++ + ". " : "- ", item);
        }
        builder.append('\n');
    }

    /**
     * Writes a list item: its first paragraph on the marker line, then the rest of the item (continuation paragraphs,
     * code blocks, nested lists) in source order, indented under the marker.
     */
    protected void listItem(final String marker, final Element item) {
        final var segments = new ArrayList<>(segments(item instanceof Paragraph paragraph ? paragraph.children() : List.of(item), true));
        if (!segments.isEmpty() && segments.get(0).size() == 1 && segments.get(0).get(0) instanceof Paragraph wrapped
                && !wrapped.children().isEmpty() && wrapped.children().stream().allMatch(sibling::isInline)
                && segments(wrapped.children(), true).size() == 1) { // first paragraph of an item carrying blocks
            segments.set(0, wrapped.children());
        }
        final var first = segments.isEmpty() || !sibling.isInline(segments.get(0).get(0)) ? List.<Element>of() : segments.remove(0);
        builder.append(marker).append(checkbox(item)).append(inlineChildren(first).strip()).append('\n');
        final var indent = " ".repeat(marker.length());
        // the rest of the item, in source order: continuation paragraphs, code blocks, nested lists
        for (final var segment : segments) {
            final var rendered = segment.size() == 1 && !sibling.isInline(segment.get(0)) ? block(segment) : inlineChildren(segment).strip();
            if (rendered.isEmpty()) {
                continue;
            }
            final var nestedList = segment.size() == 1 && (segment.get(0).type() == Element.ElementType.UNORDERED_LIST
                    || segment.get(0).type() == Element.ElementType.ORDERED_LIST);
            if (!nestedList && !(builder.length() >= 2 && builder.charAt(builder.length() - 2) == '\n'
                    && builder.charAt(builder.length() - 1) == '\n')) {
                builder.append('\n');
            }
            builder.append(indent(rendered, indent)).append('\n');
            if (!nestedList) {
                builder.append('\n');
            }
        }
    }

    protected String checkbox(final Element item) {
        if (!sibling.isChecklistItem(item)) {
            return "";
        }
        return sibling.isChecked(item) ? "[x] " : "[ ] ";
    }

    @Override
    public void visitDescriptionList(final DescriptionList list) {
        blockAnchor(list.options());
        blockTitle(list.options());
        // GFM has no description list, a bold term followed by a hard line break and the description.
        for (final var entry : list.children().entrySet()) {
            builder.append("**").append(inline(entry.getKey()).strip()).append("**");
            final var description = entry.getValue();
            if (description == null) {
                builder.append("\n\n");
            } else if (sibling.isInline(description) || description instanceof Paragraph paragraph
                    && paragraph.children().stream().allMatch(sibling::isInline)
                    && segments(paragraph.children(), true).size() == 1) {
                builder.append("\\\n").append(inline(description).strip()).append("\n\n");
            } else {
                builder.append("\n\n").append(indent(block(List.of(description)), "  ")).append("\n\n");
            }
        }
    }

    @Override
    public void visitAdmonition(final Admonition admonition) {
        alert(admonition.level(), options(admonition.options()), List.of(admonition.content()));
    }

    /**
     * GitHub alert, {@code > [!NOTE]} followed by the content as a quote.
     */
    protected void alert(final Admonition.Level level, final Map<String, String> options, final List<Element> content) {
        blockAnchor(options);
        builder.append("> [!").append(level.name()).append("]\n");
        final var title = options.get("title");
        if (title != null && !title.isBlank()) {
            builder.append("> **").append(title.strip()).append("**\n>\n");
        }
        builder.append(quote(block(content))).append("\n\n");
    }

    @Override
    public void visitOpenBlock(final OpenBlock block) {
        final var options = options(block.options());
        final var level = sibling.admonitionLevel(sibling.styleName(options));
        if (level != null) {
            alert(level, options, block.children());
            return;
        }
        if ("example".equals(sibling.styleName(options)) && sibling.hasOption(options, "collapsible")) {
            blockAnchor(options);
            final var title = options.getOrDefault("title", "Details");
            builder.append(sibling.hasOption(options, "open") ? "<details open>" : "<details>")
                    .append("\n<summary>").append(title.strip()).append("</summary>\n\n")
                    .append(block(block.children())).append("\n\n</details>\n\n");
            return;
        }
        blockAnchor(options);
        blockTitle(options);
        final var body = block(block.children());
        if (!body.isEmpty()) {
            builder.append(body).append("\n\n");
        }
    }

    @Override
    public void visitQuote(final Quote quoteBlock) {
        final var options = options(quoteBlock.options());
        blockAnchor(options);
        blockTitle(options);
        final var body = new StringBuilder(block(quoteBlock.children()));
        final var attribution = options.get("attribution");
        final var cite = options.get("citetitle");
        if (attribution != null && !attribution.isBlank()) {
            body.append("\n\n\u2014 ").append(attribution.strip());
            if (cite != null && !cite.isBlank()) {
                body.append(", ").append(cite.strip());
            }
        } else if (cite != null && !cite.isBlank()) {
            body.append("\n\n\u2014 ").append(cite.strip());
        }
        builder.append(quote(body.toString())).append("\n\n");
    }

    @Override
    public void visitTable(final Table table) {
        final var rows = table.elements();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        final var options = options(table.options());
        blockAnchor(options);
        blockTitle(options);
        final int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return;
        }
        final var header = !sibling.hasOption(options, "noheader");
        final var headerCells = new ArrayList<String>();
        int first = 0;
        if (header) {
            for (final var cell : rows.get(0)) {
                headerCells.add(cell(cell));
            }
            first = 1;
        }
        while (headerCells.size() < columns) {
            headerCells.add("");
        }
        builder.append("| ").append(String.join(" | ", headerCells)).append(" |\n");
        builder.append("|").append(" --- |".repeat(columns)).append('\n');
        for (int i = first; i < rows.size(); i++) {
            final var cells = new ArrayList<String>();
            for (final var cell : rows.get(i)) {
                cells.add(cell(cell));
            }
            while (cells.size() < columns) {
                cells.add("");
            }
            builder.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        builder.append('\n');
    }

    /**
     * Cells are single-line in GFM: block content is flattened and pipes are escaped.
     */
    protected String cell(final Element element) {
        final var text = sibling.isInline(element) ? inline(element) : block(List.of(element));
        return text.strip().replace("|", "\\|").replace("\n\n", "<br><br>").replace("\n", "<br>");
    }

    @Override
    public void visitPassthroughBlock(final PassthroughBlock block) {
        if (block.value() != null && !block.value().isBlank()) {
            builder.append(block.value().strip()).append("\n\n");
        }
    }

    @Override
    public void visitHorizontalRule(final HorizontalRule rule) {
        builder.append("---\n\n");
    }

    @Override
    public void visitPageBreak(final PageBreak pageBreak) {
        // Markdown has no page break
    }

    @Override
    public void visitConditionalBlock(final ConditionalBlock block) {
        renderChildren(sibling.renderedChildren(block, context()));
    }

    @Override
    public void visitMacro(final Macro macro) {
        if (macro.inline()) {
            builder.append(inline(macro));
            return;
        }
        final var options = options(macro.options());
        switch (macro.name()) {
            case "image" -> {
                blockAnchor(options);
                builder.append(image(macro));
                final var title = options.get("title");
                if (title != null && !title.isBlank()) {
                    builder.append("\n*").append(title.strip()).append('*');
                }
                builder.append("\n\n");
            }
            case "toc" -> {
                if ("macro".equals(sibling.tocPlacement(context()))) { // asciidoctor ignores the macro unless the toc attribute is macro
                    toc(options.get("title"), options.get("levels"));
                }
            }
            case "include" -> throw unresolvedInclude(macro);
            case "video", "audio" -> {
                final var target = (macro.label() == null ? "" : macro.label()).strip();
                builder.append('[').append(target).append("](").append(destination(target)).append(")\n\n");
            }
            default -> {
                final var text = inlineMacro(macro).strip();
                if (!text.isEmpty()) {
                    builder.append(text).append("\n\n");
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------- inline

    /**
     * Renders one element as inline Markdown; block elements are rendered into a temporary buffer and returned as text.
     */
    protected String inline(final Element element) {
        if (element == null) {
            return "";
        }
        return switch (element.type()) {
            case TEXT -> text((Text) element);
            case CODE -> {
                final var code = (Code) element;
                yield code.inline() ? inlineCode(code.value()) : block(List.of(element));
            }
            case LINK -> link((Link) element);
            case MACRO -> {
                final var macro = (Macro) element;
                yield macro.inline() ? inlineMacro(macro) : block(List.of(element));
            }
            case ANCHOR -> anchorLink((Anchor) element);
            case ATTRIBUTE -> attributeText((Attribute) element);
            case LINE_BREAK -> "\\\n";
            case PARAGRAPH -> inlineChildren(((Paragraph) element).children());
            default -> block(List.of(element));
        };
    }

    private String inlineChildren(final List<Element> children) {
        final var text = new StringBuilder();
        for (final var child : children) {
            text.append(inline(child));
        }
        return text.toString();
    }

    protected String text(final Text text) {
        final var value = text.value() == null ? "" : text.value();
        final var options = options(text.options());
        final var id = options.get("id");
        final var prefix = id != null && !id.isBlank() ? "<a id=\"" + id.strip() + "\"></a>" : "";
        if (text.style() == null || text.style().isEmpty() || value.isBlank()) {
            return prefix + value;
        }
        // GFM emphasis cannot start or end with a space: keep surrounding whitespace outside the markup
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        int end = value.length();
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        var styled = value.substring(start, end);
        for (final var style : text.style()) {
            styled = switch (style) {
                case BOLD -> "**" + styled + "**";
                case ITALIC, EMPHASIS -> "*" + styled + "*";
                case STRIKETHROUGH -> "~~" + styled + "~~";
                case MARK -> "<mark>" + styled + "</mark>";
                case SUB -> "<sub>" + styled + "</sub>";
                case SUP -> "<sup>" + styled + "</sup>";
                default -> styled;
            };
        }
        return prefix + value.substring(0, start) + styled + value.substring(end);
    }

    /**
     * A code span is delimited by more backticks than the longest run inside it, padded with a space when it holds
     * backticks so one at its start or end does not join the delimiter.
     */
    protected String inlineCode(final String value) {
        final var code = value == null ? "" : value;
        final int longest = longestBacktickRun(code);
        if (longest == 0) {
            return "`" + code + "`";
        }
        final var delimiter = "`".repeat(longest + 1);
        return delimiter + ' ' + code + ' ' + delimiter;
    }

    protected String link(final Link link) {
        // the parser already substituted the url of a link and dropped the backslash of an escaped reference,
        // so substituting again would replace a \{name} the author asked to keep, as the HTML renderer does not
        final var url = link.url() == null ? "" : link.url().strip();
        if (link.options() != null && "inline-code".equals(link.options().get("role"))) {
            // written in backticks: a bare URL stays code (Markdown cannot link inside a code span),
            // a labelled link keeps its target and shows the label as code
            final var text = link.label() == null ? "" : sibling.plainText(link.label(), context()).strip();
            return text.isEmpty() || text.equals(url) ? inlineCode(url) : "[" + inlineCode(text) + "](" + destination(url) + ")";
        }
        final var label = link.label() == null ? "" : inline(link.label()).strip();
        if (label.isEmpty() || label.equals(url)) {
            // the parser keeps the closing bracket of <https://...> in the url, keep it out of the link
            int end = url.length();
            while (end > 0 && url.charAt(end - 1) == '>') {
                end--;
            }
            final var target = url.substring(0, end);
            final var closing = url.substring(end);
            // an autolink needs a scheme and no space, other targets become a link showing the target,
            // escaped so a <name> placeholder in it is not read as an HTML tag
            return (sibling.isUri(target) && target.equals(destination(target)) ?
                    "<" + target + ">" :
                    "[" + escapeLinkText(target) + "](" + destination(target) + ")") + closing;
        }
        return "[" + label + "](" + destination(url) + ")";
    }

    /**
     * @return the text of a link with its brackets escaped, so they do not end the text, and its angle brackets
     * escaped, so a {@code <name>} placeholder is not read as an HTML tag.
     */
    protected String escapeLinkText(final String text) {
        return escape(text, "[]<>");
    }

    /**
     * @return the text with a backslash before each of the characters.
     */
    protected String escape(final String text, final String characters) {
        StringBuilder escaped = null; // most texts have nothing to escape, then the text is returned as is
        int copied = 0;
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                if (escaped == null) {
                    escaped = new StringBuilder(text.length() + 8);
                }
                escaped.append(text, copied, i).append('\\');
                copied = i;
            }
        }
        return escaped == null ? text : escaped.append(text, copied, text.length()).toString();
    }

    /**
     * Escapes a link destination for the Markdown syntax, without interpreting the URL.
     *
     * @return the destination, in angle brackets when CommonMark would otherwise end it too early: on whitespace, on an
     * angle bracket, or on a parenthesis without its pair.
     */
    protected String destination(final String url) {
        int depth = 0;
        boolean wrap = false;
        for (int i = 0; i < url.length() && !wrap; i++) {
            final char c = url.charAt(i);
            if (Character.isWhitespace(c) || c == '<' || c == '>') {
                wrap = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                wrap = true;
            }
        }
        if (!wrap && depth == 0) {
            return url;
        }
        return "<" + url.replace("<", "%3C").replace(">", "%3E") + ">";
    }

    /**
     * Writes a {@code <<target>>} cross reference, its target read by
     * {@link VisitorSibling#shorthandCrossReference(String, List)}.
     */
    protected String anchorLink(final Anchor anchor) {
        return crossReferenceLink(sibling.shorthandCrossReference((anchor.value() == null ? "" : anchor.value()).strip(), state.asciidocExtensions()), anchor.label());
    }

    /**
     * @return the text of a link to the element with this id: the text {@link VisitorState#referenceText(String)}
     * gives with its brackets escaped, else the id between escaped square brackets as asciidoctor writes it; an id
     * holds nothing to escape.
     */
    protected String referenceLinkText(final String id) {
        final var referenceText = state.referenceText(id);
        return referenceText != null ? escape(referenceText, "[]") : "\\[" + id + "\\]";
    }

    private String attributeText(final Attribute attribute) {
        final var value = context().attribute(attribute.attribute());
        if (value == null) {
            return "{" + attribute.attribute() + "}";
        }
        return inlineChildren(attribute.evaluator().apply(value));
    }

    protected String inlineMacro(final Macro macro) {
        final var options = options(macro.options());
        final var label = macro.label() == null ? "" : macro.label();
        return switch (macro.name()) {
            case "image" -> image(macro);
            case "link" -> "[" + sibling.substitute(options.getOrDefault("", label), context()).strip() + "](" + destination(sibling.substitute(label, context()).strip()) + ")";
            case "xref" -> xref(macro, options);
            case "mailto" -> "[" + options.getOrDefault("", label).strip() + "](" + destination("mailto:" + label.strip()) + ")";
            case "kbd" -> kbd(sibling.kbdKeys(macro));
            case "btn" -> "**" + sibling.content(macro).strip() + "**";
            case "menu" -> "**" + (label.isBlank() ?
                    options.getOrDefault("", "").strip() :
                    label.strip() + (options.get("") != null && !options.get("").isBlank() ? " > " + options.get("").strip() : "")) + "**";
            case "pass" -> options.getOrDefault("", ""); // the label holds the substitutions, pass:q[...]
            case "icon" -> icon(macro, options);
            case "include" -> throw unresolvedInclude(macro);
            case "indexterm" -> ""; // indexterm:[...] only feeds the index, asciidoctor shows nothing either
            case "indexterm2" -> options.getOrDefault("", label); // indexterm2:[term] shows the term
            case "footnote", "footnoteref", "doublefootnote" -> footnote(macro);
            case "stem", "latexmath", "asciimath" -> "$" + sibling.content(macro).strip() + "$";
            default -> options.getOrDefault("", label);
        };
    }

    /**
     * Markdown has no icon font, so an icon is written as asciidoctor writes it without one: {@code [name]}, see
     * {@link VisitorSibling#iconText(Macro)}.
     */
    protected String icon(final Macro macro, final Map<String, String> options) {
        final var text = "[" + sibling.iconText(macro) + "]";
        final var link = options.get("link");
        return link != null && !link.isBlank() ? "[" + text + "](" + destination(sibling.substitute(link, context()).strip()) + ")" : text;
    }

    /**
     * The parser resolves includes, a missing one failing the parsing unless it is optional, so an include reaching
     * the renderer means the model was not built by the parser.
     */
    protected IllegalArgumentException unresolvedInclude(final Macro macro) {
        return new IllegalArgumentException("Unresolved include: '" + macro.label() + "', the parser resolves includes before rendering");
    }

    /**
     * @return the keys of a {@code kbd} macro, each in a {@code <kbd>} element, joined with {@code +}, see
     * {@link VisitorSibling#kbdKeys(Macro)}.
     */
    protected String kbd(final List<String> keys) {
        final var out = new StringBuilder();
        for (final var key : keys) {
            out.append(out.isEmpty() ? "" : "+").append("<kbd>").append(key).append("</kbd>");
        }
        return out.toString();
    }

    /**
     * {@code footnote:[text]} gets a numbered label, {@code footnote:id[text]} a named one and {@code footnote:id[]}
     * (or the legacy {@code footnoteref:[id,text]}) refers to the footnote already defined with that id, see
     * {@link VisitorState#footnote(String, String)}; the definitions are written after the document.
     */
    protected String footnote(final Macro macro) {
        return "[^" + state.footnote(sibling.footnoteId(macro), sibling.footnoteText(macro)).label() + "]";
    }

    /**
     * Writes an {@code xref} macro, its target read by {@link VisitorSibling#crossReference(String, List)}.
     */
    protected String xref(final Macro macro, final Map<String, String> options) {
        return crossReferenceLink(sibling.crossReference((macro.label() == null ? "" : macro.label()).strip(), state.asciidocExtensions()), options.get(""));
    }

    /**
     * Writes a cross reference: an id of the same page links it, with the text of the target when the reference has
     * none; a document links the rendered page, {@code relfileprefix} + document + {@code relfilesuffix}, the suffix
     * defaulting to {@code outfilesuffix} then {@code .md}, with the fragment as text when the reference has none;
     * any other file keeps its name.
     */
    protected String crossReferenceLink(final VisitorSibling.CrossReference reference, final String label) {
        var text = label;
        if (reference.id() != null) {
            if (text == null || text.isBlank()) {
                text = referenceLinkText(reference.id());
            }
            return "[" + text.strip() + "](" + destination("#" + reference.id()) + ")";
        }
        final var path = reference.document() != null ?
                sibling.documentPath(reference.document(), context(), ".md") :
                sibling.relativeFile(reference.file(), context());
        final var fragment = reference.fragment();
        if (text == null || text.isBlank()) {
            text = fragment.isEmpty() ? path : fragment;
        }
        return "[" + text.strip() + "](" + destination(fragment.isEmpty() ? path : path + "#" + fragment) + ")";
    }

    protected String image(final Macro macro) {
        final var target = sibling.imageTarget(macro, context());
        final var image = "![" + sibling.imageAlt(macro, target) + "](" + destination(target) + ")";
        final var link = options(macro.options()).get("link");
        return link != null && !link.isBlank() ? "[" + image + "](" + destination(sibling.substitute(link, context()).strip()) + ")" : image;
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    /**
     * Renders elements into a temporary buffer and returns the Markdown, with no trailing blank line.
     * Nested blocks go through this renderer, so an overridden method applies inside them too.
     */
    protected String block(final List<Element> elements) {
        final var enclosing = builder;
        builder = new StringBuilder();
        try {
            renderChildren(elements);
            return builder.toString().strip();
        } finally {
            builder = enclosing;
        }
    }

    protected String quote(final String text) {
        return text.lines().map(line -> line.isEmpty() ? ">" : "> " + line).collect(joining("\n"));
    }

    protected String indent(final String text, final String indent) {
        return text.lines().map(line -> line.isEmpty() ? line : indent + line).collect(joining("\n"));
    }

    private Map<String, String> options(final Map<String, String> options) {
        return options == null ? Map.of() : options;
    }

    @Getter
    public static class Configuration {
        private Map<String, String> attributes = Map.of();

        /**
         * @param attributes attributes resolved when the document does not define them
         *                   ({@code imagesdir}, {@code outfilesuffix}, {@code noheader}, ...).
         * @return this.
         */
        public Configuration setAttributes(final Map<String, String> attributes) {
            this.attributes = attributes == null ? Map.of() : attributes;
            return this;
        }
    }
}
