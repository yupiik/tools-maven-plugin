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
import io.yupiik.asciidoc.renderer.html.IdGenerator;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * A renderer can render several documents one after the other, {@link #visit(Document)} starts from a clean state.
 * Like {@link io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer} it is not thread safe.
 */
public class GithubFlavoredMarkdownRenderer implements Visitor<String> {
    private static final Pattern ATTRIBUTE_REFERENCE = Pattern.compile("\\{([a-zA-Z0-9_][a-zA-Z0-9_-]*)}");

    protected final Configuration configuration;
    protected final State state; // this is why we are not thread safe
    protected final ConditionalBlock.Context context; // document attributes first, then the configuration ones
    protected StringBuilder builder = new StringBuilder(); // replaced while a nested block renders, see block()

    public GithubFlavoredMarkdownRenderer() {
        this(new Configuration());
    }

    public GithubFlavoredMarkdownRenderer(final Configuration configuration) {
        this.configuration = configuration;
        this.state = new State();
        this.context = key -> {
            final var value = state.documentAttributes.get(key);
            return value != null ? value : configuration.getAttributes().get(key);
        };
    }

    // ------------------------------------------------------------------------------------------------------ document

    @Override
    public void visit(final Document document) {
        reset();
        final var header = document.header();
        state.documentAttributes = header == null || header.attributes() == null ? Map.of() : header.attributes();
        final var noheader = attr("noheader", null); // asciidoctor: set means true, whatever the value but "false"
        if (header != null && header.title() != null && !header.title().isBlank()
                && (noheader == null || "false".equalsIgnoreCase(noheader.strip()))) {
            visitTitle(header.title());
        }
        visitBody(document.body());
    }

    @Override
    public void visitTitle(final String title) {
        final var value = substitute(title).strip();
        if (!value.isEmpty()) {
            builder.append("# ").append(value).append("\n\n");
        }
    }

    /**
     * Clears the output and the document state, so the renderer can render another document.
     */
    protected void reset() {
        builder = new StringBuilder();
        state.reset();
    }

    @Override
    public void visitBody(final Body body) {
        // indexed here rather than in visit(Document), so rendering only a body keeps section links working
        final var index = new Index();
        index.visitBody(body);
        state.referencedIds = index.ids;
        state.sectionTitlesById = index.titles;
        state.tocSections = index.sections;

        final var placement = tocPlacement();
        if (placement == null) {
            renderChildren(body.children());
            return;
        }
        // the table of contents links every section it lists, so each one gets its anchor
        final int levels = "macro".equals(placement) ? index.tocMacroLevels : tocLevels(null);
        for (final var section : index.sections) {
            if (section.level() <= levels) {
                state.referencedIds.add(section.id());
            }
        }
        final var children = body.children();
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
     * @return where asciidoctor places the table of contents: {@code auto} (at the top), {@code preamble} or
     * {@code macro} (at the {@code toc::[]} macro); {@code null} when the document has no {@code toc} attribute.
     */
    protected String tocPlacement() {
        final var toc = attr("toc", null);
        if (toc == null) {
            return null;
        }
        final var placement = attr("toc-placement", null);
        if (placement != null && !placement.isBlank()) {
            return placement.strip();
        }
        return switch (toc.strip()) {
            case "macro", "preamble" -> toc.strip();
            default -> "auto"; // empty, auto, left and right: embedded, the table of contents comes first
        };
    }

    /**
     * @param macroLevels the {@code levels} attribute of a {@code toc::[]} macro, {@code null} when there is none.
     * @return how many section levels the table of contents lists, {@code toclevels} defaulting to 2 as in asciidoctor.
     */
    protected int tocLevels(final String macroLevels) {
        final var levels = macroLevels != null && !macroLevels.isBlank() ? macroLevels : attr("toclevels", "2");
        try {
            return Integer.parseInt(levels.strip());
        } catch (final NumberFormatException nfe) {
            return 2;
        }
    }

    /**
     * Writes the table of contents as a nested list of links to the sections, under its title in bold.
     *
     * @param title       the title of the {@code toc::[]} macro block, {@code null} to use {@code toc-title}.
     * @param macroLevels the {@code levels} attribute of the macro, {@code null} to use {@code toclevels}.
     */
    protected void toc(final String title, final String macroLevels) {
        final int levels = tocLevels(macroLevels);
        int top = Integer.MAX_VALUE; // a book starts at level 0, an article at level 1
        for (final var section : state.tocSections) {
            if (section.level() <= levels) {
                top = Math.min(top, section.level());
            }
        }
        if (top == Integer.MAX_VALUE) { // asciidoctor writes no table of contents for a document without section
            return;
        }
        final var tocTitle = title != null && !title.isBlank() ? title : attr("toc-title", "Table of Contents");
        if (!tocTitle.isBlank()) {
            builder.append("**").append(substitute(tocTitle).strip()).append("**\n\n");
        }
        for (final var section : state.tocSections) {
            if (section.level() <= levels) {
                builder.append("  ".repeat(section.level() - top))
                        .append("- [").append(section.title()).append("](#").append(section.id()).append(")\n");
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
            if (segment.size() == 1 && !isInline(segment.get(0))) {
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
     * @param children        the sibling elements.
     * @param paragraphLevel  true for the children of a {@code Paragraph}, false for the children of a block container.
     * @return the segments, in order.
     */
    protected List<List<Element>> segments(final List<Element> children, final boolean paragraphLevel) {
        final var segments = new ArrayList<List<Element>>();
        List<Element> run = null;
        for (final var child : children) {
            if (run != null && run.get(run.size() - 1) instanceof LineBreak && child instanceof Paragraph paragraph
                    && paragraph.children().stream().allMatch(this::isInline)) {
                run.addAll(paragraph.children()); // the parser wraps the line after a line break when it has markup
            } else if (!isInline(child)) {
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

    private List<List<Element>> split(final List<Element> run, final java.util.function.BiPredicate<Element, Element> isBoundary) {
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
        return context;
    }

    /**
     * @return the Markdown, ending with one line feed as a text file does.
     */
    @Override
    public String result() {
        final var body = builder.toString().strip();
        if (state.footnotes.isEmpty()) {
            return body + '\n';
        }
        final var result = new StringBuilder(body);
        if (!body.isEmpty()) {
            result.append("\n\n");
        }
        for (final var footNote : state.footnotes) {
            final var text = footNote.text.strip();
            result.append("[^").append(footNote.label).append("]:").append(text.isEmpty() ? "" : " ").append(text).append('\n');
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
        final var id = id(options);
        if (id != null) {
            anchor(id);
        } else { // asciidoctor gives the section an id anyway, write it when the document links to it
            final var generated = generatedId(title);
            if (state.referencedIds.contains(generated)) {
                anchor(generated);
            }
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
        final var id = id(options);
        if (id != null) {
            anchor(id);
            builder.append('\n');
        }
    }

    /**
     * @return the explicit id of a block, given as {@code [#id]}, as a {@code [[id]]} line above the block, or in a
     * style shorthand such as {@code [NOTE#id]}; {@code null} when the block has none.
     */
    protected String id(final Map<String, String> options) {
        if (options == null) {
            return null;
        }
        final var id = options.get("id");
        if (id != null && !id.isBlank()) {
            return id.strip();
        }
        final var style = options.get("");
        if (style == null || style.isBlank()) {
            return null;
        }
        final var value = style.strip();
        if (value.length() > 2 && value.startsWith("[") && value.endsWith("]")) { // the parser stores [[id]] as [id]
            return value.substring(1, value.length() - 1);
        }
        final int hash = value.indexOf('#');
        if (hash >= 0 && hash < value.length() - 1) {
            final var shorthand = value.substring(hash + 1);
            final int end = firstIndexOf(shorthand, ".%");
            return end < 0 ? shorthand : end == 0 ? null : shorthand.substring(0, end);
        }
        return null;
    }

    /**
     * @return the style name of a block, without the {@code #id}, {@code .role} or {@code %option} shorthands; empty
     * when the block has no style, the parser also storing a {@code [[id]]} block anchor there, as {@code [id]}.
     */
    protected String styleName(final Map<String, String> options) {
        final var style = options == null ? null : options.get("");
        if (style == null || style.isBlank() || style.strip().startsWith("[")) {
            return "";
        }
        final var value = style.strip();
        final int end = firstIndexOf(value, "#.%");
        return end < 0 ? value : value.substring(0, end);
    }

    private int firstIndexOf(final String value, final String characters) {
        for (int i = 0; i < value.length(); i++) {
            if (characters.indexOf(value.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return the id asciidoctor gives a section without an explicit one, built as the HTML renderer builds it.
     */
    protected String generatedId(final Element title) {
        return IdGenerator.forTitle(plainText(title).strip(), attr("idprefix", null), attr("idseparator", null));
    }

    @Override
    public void visitParagraph(final Paragraph paragraph) {
        final var id = id(paragraph.options());
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
        final var language = language(options);
        if (!language.isEmpty()) {
            return language;
        }
        final var style = styleName(options);
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

    protected String language(final Map<String, String> options) {
        if (options == null) {
            return "";
        }
        final var language = options.getOrDefault("language", options.get("lang"));
        return language == null ? "" : language.strip();
    }

    protected void blockTitle(final Map<String, String> options) {
        final var title = options == null ? null : options.get("title");
        if (title != null && !title.isBlank()) {
            builder.append("**").append(substitute(title).strip()).append("**\n\n");
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
        if (ordered && options.get("start") != null) {
            try { // GFM list numbers are 0 to 999999999, a negative start cannot be written
                number = Math.max(0, Math.min(999_999_999, Integer.parseInt(options.get("start").strip())));
            } catch (final NumberFormatException nfe) {
                // keep the default
            }
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
        final var segments = segments(item instanceof Paragraph paragraph ? paragraph.children() : List.of(item), true);
        if (!segments.isEmpty() && segments.get(0).size() == 1 && segments.get(0).get(0) instanceof Paragraph wrapped
                && !wrapped.children().isEmpty() && wrapped.children().stream().allMatch(this::isInline)
                && segments(wrapped.children(), true).size() == 1) { // first paragraph of an item carrying blocks
            segments.set(0, wrapped.children());
        }
        final var first = segments.isEmpty() || !isInline(segments.get(0).get(0)) ? List.<Element>of() : segments.remove(0);
        builder.append(marker).append(checkbox(item)).append(inlineChildren(first).strip()).append('\n');
        final var indent = " ".repeat(marker.length());
        // the rest of the item, in source order: continuation paragraphs, code blocks, nested lists
        for (final var segment : segments) {
            final var rendered = segment.size() == 1 && !isInline(segment.get(0)) ? block(segment) : inlineChildren(segment).strip();
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
        if (item instanceof Paragraph paragraph && paragraph.options() != null
                && paragraph.options().containsKey("checkbox")) {
            final var checked = paragraph.options().containsKey("checked")
                    && !"false".equalsIgnoreCase(paragraph.options().get("checked"));
            return checked ? "[x] " : "[ ] ";
        }
        return "";
    }

    @Override
    public void visitDescriptionList(final DescriptionList list) {
        blockAnchor(list.options());
        // the parser also copies the document attributes into these options, a :title: attribute is no block title
        final var title = list.options() == null ? null : list.options().get("title");
        if (title != null && !title.equals(context().attribute("title"))) {
            blockTitle(list.options());
        }
        // GFM has no description list, a bold term followed by a hard line break and the description.
        for (final var entry : list.children().entrySet()) {
            builder.append("**").append(inline(entry.getKey()).strip()).append("**");
            final var description = entry.getValue();
            if (description == null) {
                builder.append("\n\n");
            } else if (isInline(description) || description instanceof Paragraph paragraph
                    && paragraph.children().stream().allMatch(this::isInline)
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
            builder.append("> **").append(substitute(title).strip()).append("**\n>\n");
        }
        builder.append(quote(block(content))).append("\n\n");
    }

    @Override
    public void visitOpenBlock(final OpenBlock block) {
        final var options = options(block.options());
        final var level = admonitionLevel(styleName(options));
        if (level != null) {
            alert(level, options, block.children());
            return;
        }
        if (hasOption(options, "collapsible")) {
            blockAnchor(options);
            final var title = options.getOrDefault("title", "Details");
            builder.append(hasOption(options, "open") ? "<details open>" : "<details>")
                    .append("\n<summary>").append(substitute(title).strip()).append("</summary>\n\n")
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

    /**
     * @return the admonition level a block style names, {@code null} when it names none; as in asciidoctor and in the
     * parser, the style must be written in upper case, {@code [note]} is no admonition.
     */
    protected Admonition.Level admonitionLevel(final String style) {
        for (final var level : Admonition.Level.values()) {
            if (level.name().equals(style)) {
                return level;
            }
        }
        return null;
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
        final var header = !options.containsKey("noheader-option");
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
        final var text = isInline(element) ? inline(element) : block(List.of(element));
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
        final var context = context();
        if (block.evaluator().test(context)) {
            renderChildren(block.children());
            return;
        }
        if (block.elseBranches() == null) {
            return;
        }
        for (final var branch : block.elseBranches()) {
            if (branch.evaluator().test(context)) {
                renderChildren(branch.children());
                return;
            }
        }
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
                    builder.append("\n*").append(substitute(title).strip()).append('*');
                }
                builder.append("\n\n");
            }
            case "toc" -> {
                if ("macro".equals(tocPlacement())) { // asciidoctor ignores the macro unless the toc attribute is macro
                    toc(options.get("title"), options.get("levels"));
                }
            }
            case "include" -> throw unresolvedInclude(macro);
            case "video", "audio" -> {
                final var target = substitute(macro.label() == null ? "" : macro.label()).strip();
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

    protected boolean isInline(final Element element) {
        return switch (element.type()) {
            case TEXT, LINK, ANCHOR, ATTRIBUTE, LINE_BREAK -> true;
            case CODE -> ((Code) element).inline();
            case MACRO -> ((Macro) element).inline();
            default -> false;
        };
    }

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
        final var url = substitute(link.url() == null ? "" : link.url()).strip();
        if (link.options() != null && "inline-code".equals(link.options().get("role"))) {
            // written in backticks: a bare URL stays code (Markdown cannot link inside a code span),
            // a labelled link keeps its target and shows the label as code
            final var text = link.label() == null ? "" : plainText(link.label()).strip();
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
            return (isUri(target) && target.equals(destination(target)) ?
                    "<" + target + ">" :
                    "[" + escapeLinkText(target) + "](" + destination(target) + ")") + closing;
        }
        return "[" + label + "](" + destination(url) + ")";
    }

    /**
     * @return true when the value starts with a URI scheme as asciidoctor's {@code Helpers.uriish?} reads it: a letter,
     * at least one more letter, digit, {@code +}, {@code .} or {@code -}, then a colon, so {@code C:/images} is a path.
     * The scheme is at most 32 characters long, as a CommonMark autolink requires.
     */
    protected boolean isUri(final String value) {
        final int colon = value.indexOf(':');
        if (colon < 2 || colon > 32 || !isAsciiLetter(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < colon; i++) {
            final char c = value.charAt(i);
            if (!isAsciiLetter(c) && (c < '0' || c > '9') && c != '+' && c != '.' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private boolean isAsciiLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * @return the text of a link with its brackets escaped, so they do not end the text, and its angle brackets
     * escaped, so a {@code <name>} placeholder is not read as an HTML tag.
     */
    protected String escapeLinkText(final String text) {
        final var escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '[' || c == ']' || c == '<' || c == '>') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
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

    protected String anchorLink(final Anchor anchor) {
        final var id = anchor.value() == null ? "" : anchor.value().strip();
        var label = anchor.label();
        if (label == null || label.isBlank()) {
            label = state.sectionTitlesById.getOrDefault(id, id);
        }
        return "[" + label.strip() + "](#" + id + ")";
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
        // bracket-only macros such as kbd:[Ctrl+C] carry their content in the positional option, not in the label
        final var content = label.isBlank() ? options.getOrDefault("", "") : label;
        return switch (macro.name()) {
            case "image" -> image(macro);
            case "link" -> "[" + substitute(options.getOrDefault("", label)).strip() + "](" + destination(substitute(label).strip()) + ")";
            case "xref" -> xref(macro, options);
            case "mailto" -> "[" + options.getOrDefault("", label).strip() + "](" + destination("mailto:" + label.strip()) + ")";
            case "kbd" -> keys(options.containsKey("opts") ? content + "," + options.get("opts") : content);
            case "btn" -> "**" + content.strip() + "**";
            case "menu" -> "**" + (label.isBlank() ?
                    options.getOrDefault("", "").strip() :
                    label.strip() + (options.get("") != null && !options.get("").isBlank() ? " > " + options.get("").strip() : "")) + "**";
            case "pass" -> options.getOrDefault("", ""); // the label holds the substitutions, pass:q[...]
            case "icon" -> icon(macro, options);
            case "include" -> throw unresolvedInclude(macro);
            case "indexterm" -> ""; // indexterm:[...] only feeds the index, asciidoctor shows nothing either
            case "indexterm2" -> options.getOrDefault("", label); // indexterm2:[term] shows the term
            case "footnote", "footnoteref", "doublefootnote" -> footnote(macro, options);
            case "stem", "latexmath", "asciimath" -> "$" + content.strip() + "$";
            default -> options.getOrDefault("", label);
        };
    }

    /**
     * Markdown has no icon font, so an icon is written as asciidoctor writes it without one: {@code [name]}, the name
     * being the {@code alt} attribute or the icon name with {@code _} and {@code -} read as spaces.
     */
    protected String icon(final Macro macro, final Map<String, String> options) {
        final var alt = options.get("alt");
        final var name = alt != null && !alt.isBlank() ? alt.strip() : defaultAlt(macro.label() == null ? "" : macro.label().strip());
        final var text = "[" + name + "]";
        final var link = options.get("link");
        return link != null && !link.isBlank() ? "[" + text + "](" + destination(substitute(link).strip()) + ")" : text;
    }

    /**
     * @return the text asciidoctor gives an image or an icon without an alt text: the file name without its directory
     * and extension, {@code _} and {@code -} read as spaces.
     */
    protected String defaultAlt(final String target) {
        final var name = target.substring(target.lastIndexOf('/') + 1);
        final int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').replace('-', ' ');
    }

    /**
     * The parser resolves includes, a missing one failing the parsing unless it is optional, so an include reaching
     * the renderer means the model was not built by the parser.
     */
    protected IllegalArgumentException unresolvedInclude(final Macro macro) {
        return new IllegalArgumentException("Unresolved include: '" + macro.label() + "', the parser resolves includes before rendering");
    }

    /**
     * Splits keys as asciidoctor does: on the first of {@code ,} or {@code +} found after the first character, a
     * trailing delimiter being a key itself ({@code Ctrl++}).
     */
    protected String keys(final String label) {
        final var value = label.strip();
        final int comma = value.indexOf(',', 1);
        final int plus = value.indexOf('+', 1);
        final int delimiter = comma < 0 ? plus : plus < 0 ? comma : Math.min(comma, plus);
        if (value.length() <= 1 || delimiter <= 0) {
            return value.isEmpty() ? "" : "<kbd>" + value + "</kbd>";
        }
        final char separator = value.charAt(delimiter);
        final boolean trailingKey = value.charAt(value.length() - 1) == separator;
        final var keys = new ArrayList<String>();
        final var list = trailingKey ? value.substring(0, value.length() - 1) : value;
        int from = 0;
        for (int i = 0; i <= list.length(); i++) {
            if (i == list.length() || list.charAt(i) == separator) {
                keys.add(list.substring(from, i).strip());
                from = i + 1;
            }
        }
        if (trailingKey) {
            keys.set(keys.size() - 1, keys.get(keys.size() - 1) + separator);
        }
        final var out = new StringBuilder();
        for (final var key : keys) {
            if (!key.isEmpty()) {
                out.append(out.isEmpty() ? "" : "+").append("<kbd>").append(key).append("</kbd>");
            }
        }
        return out.toString();
    }

    /**
     * {@code footnote:[text]} gets a numbered label, {@code footnote:id[text]} a named one and {@code footnote:id[]}
     * (or the legacy {@code footnoteref:[id,text]}) refers to the footnote already defined with that id.
     */
    protected String footnote(final Macro macro, final Map<String, String> options) {
        final String id;
        final String text;
        if ("footnoteref".equals(macro.name()) && (macro.label() == null || macro.label().isEmpty())) {
            id = options.getOrDefault("", "");
            text = options.getOrDefault("opts", "");
        } else {
            id = macro.label() == null ? "" : macro.label();
            text = options.getOrDefault("", "");
        }
        if (id.isEmpty()) {
            String label;
            do {
                label = Integer.toString(++state.anonymousFootnotes);
            } while (!state.footnoteLabels.add(label));
            final var footNote = new FootNote(label, text);
            state.footnotes.add(footNote);
            return "[^" + label + "]";
        }
        var footNote = state.footnotesById.get(id);
        if (footNote == null) { // first use of the id, a definition or a reference to a later definition
            var label = id;
            for (int i = 1; !state.footnoteLabels.add(label); i++) {
                label = id + "-" + i;
            }
            footNote = new FootNote(label, null);
            state.footnotesById.put(id, footNote);
        }
        if (footNote.text == null && !text.isEmpty()) { // later uses of the id only refer to it
            footNote.text = text;
            state.footnotes.add(footNote);
        }
        return "[^" + footNote.label + "]";
    }

    /**
     * Reads the target as asciidoctor reads the {@code xref} macro, split at its first {@code #}:
     * <ul>
     *     <li>{@code #id}, or an id without {@code #} and without extension, links the same page, with the section
     *     title as text when the macro has none,</li>
     *     <li>a document, with one of the {@code asciidoc-extensions} or without extension before the {@code #}, links
     *     the rendered page, see {@link #xrefTarget(String)},</li>
     *     <li>any other file keeps its name.</li>
     * </ul>
     */
    protected String xref(final Macro macro, final Map<String, String> options) {
        final var target = substitute(macro.label() == null ? "" : macro.label()).strip();
        var text = options.get("");
        final var internal = xrefInternalId(target);
        if (internal != null) {
            if (text == null || text.isBlank()) {
                text = state.sectionTitlesById.getOrDefault(internal, internal);
            }
            return "[" + text.strip() + "](" + destination("#" + internal) + ")";
        }
        final int hash = target.indexOf('#');
        final var file = hash >= 0 ? target.substring(0, hash) : target;
        final var fragment = hash >= 0 ? target.substring(hash + 1) : "";
        final var page = documentName(file);
        final var path = page != null ? xrefTarget(page) : relativeFile(file);
        if (text == null || text.isBlank()) {
            text = fragment.isEmpty() ? path : fragment;
        }
        return "[" + text.strip() + "](" + destination(fragment.isEmpty() ? path : path + "#" + fragment) + ")";
    }

    /**
     * @return the id a cross reference points at in the same document, {@code null} when it points at a file: as in
     * asciidoctor, a target starting with {@code #}, or without {@code #} and without extension, is an id.
     */
    protected String xrefInternalId(final String target) {
        if (target.startsWith("#")) {
            return target.substring(1);
        }
        if (target.isEmpty() || target.indexOf('#') >= 0 || hasExtension(target)) {
            return null;
        }
        return target;
    }

    /**
     * @param file the file part of a cross reference to another file, before its {@code #}.
     * @return the referenced document without its extension when the file is an AsciiDoc document (one of the
     * {@code asciidoc-extensions}, or no extension at all), {@code null} for any other file.
     */
    protected String documentName(final String file) {
        if (!hasExtension(file)) {
            return file;
        }
        for (final var extension : asciidocExtensions()) {
            if (file.endsWith(extension) && file.length() > extension.length()) {
                return file.substring(0, file.length() - extension.length());
            }
        }
        return null;
    }

    /**
     * @return the extensions of the AsciiDoc documents a cross reference can point at, from the
     * {@code asciidoc-extensions} attribute, {@code adoc,asciidoc} by default.
     */
    protected List<String> asciidocExtensions() {
        if (state.asciidocExtensions == null) {
            final var extensions = new ArrayList<String>();
            for (final var extension : attr("asciidoc-extensions", "adoc,asciidoc").split(",")) {
                final var value = extension.strip();
                if (!value.isEmpty()) {
                    extensions.add(value.startsWith(".") ? value : "." + value);
                }
            }
            state.asciidocExtensions = extensions;
        }
        return state.asciidocExtensions;
    }

    // as asciidoctor's Helpers.extname?, a dot in the last segment of the path
    private boolean hasExtension(final String path) {
        final int dot = path.lastIndexOf('.');
        return dot >= 0 && path.indexOf('/', dot) < 0;
    }

    /**
     * Where a cross reference to another document points, as the HTML renderer does:
     * {@code relfileprefix} + page + {@code relfilesuffix}, the suffix defaulting to {@code outfilesuffix} then {@code .md}.
     *
     * @param page the referenced document without its extension.
     * @return the link target.
     */
    protected String xrefTarget(final String page) {
        return relativeFile(page) + attr("relfilesuffix", attr("outfilesuffix", ".md"));
    }

    // relfileprefix applies to every file a cross reference names, as in asciidoctor
    private String relativeFile(final String file) {
        final var prefix = attr("relfileprefix", "");
        return prefix + (!prefix.isEmpty() && file.startsWith("./") ? file.substring(2) : file);
    }

    protected String image(final Macro macro) {
        final var options = options(macro.options());
        var target = substitute(macro.label() == null ? "" : macro.label()).strip();
        if (!target.isEmpty() && !target.startsWith("/") && !isUri(target)) {
            final var imagesDir = context().attribute("imagesdir");
            if (imagesDir != null && !imagesDir.isBlank()) {
                final var dir = substitute(imagesDir).strip();
                target = dir.endsWith("/") ? dir + target : dir + "/" + target;
            }
        }
        var alt = options.get("alt");
        if (alt == null || alt.isBlank()) {
            alt = options.get("");
        }
        if (alt == null || alt.isBlank()) {
            alt = defaultAlt(target);
        }
        final var image = "![" + alt.strip() + "](" + destination(target) + ")";
        final var link = options.get("link");
        return link != null && !link.isBlank() ? "[" + image + "](" + destination(substitute(link).strip()) + ")" : image;
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

    /**
     * @return true when the block sets the option, as {@code %name}, {@code options="name"} or {@code opts=name}.
     */
    protected boolean hasOption(final Map<String, String> options, final String name) {
        if (options.containsKey(name + "-option")) {
            return true;
        }
        final var opts = options.get("opts");
        if (opts != null) {
            for (final var option : opts.split(",")) {
                if (name.equals(option.strip())) {
                    return true;
                }
            }
        }
        for (final var key : options.keySet()) { // the parser reads [%collapsible%open] as one "collapsible%open-option" key
            if (key.indexOf('%') > 0 && key.endsWith("-option")) {
                for (final var option : key.substring(0, key.length() - "-option".length()).split("%")) {
                    if (name.equals(option)) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    protected String attr(final String key, final String defaultValue) {
        final var value = context().attribute(key);
        return value == null ? defaultValue : value;
    }

    /**
     * Replaces {@code {name}} references in a string the parser did not evaluate (block titles, image targets, ...);
     * elements the parser did evaluate come as {@link Attribute} and go through {@link #visitAttribute(Attribute)}.
     */
    protected String substitute(final String text) {
        if (text == null) {
            return "";
        }
        if (text.indexOf('{') < 0) {
            return text;
        }
        final var matcher = ATTRIBUTE_REFERENCE.matcher(text);
        final var result = new StringBuilder();
        while (matcher.find()) {
            final var value = context().attribute(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * @return the text of a link to a section: the plain text of its title on one line, its brackets escaped.
     */
    protected String sectionLinkText(final Element title) {
        final var text = plainText(title);
        final var out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                space = !out.isEmpty();
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            if (c == '[' || c == ']') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Text of an element without markup and without side effect (a footnote in a title is not registered),
     * used as the text of a link to a section.
     */
    protected String plainText(final Element element) {
        if (element == null) {
            return "";
        }
        return switch (element.type()) {
            case TEXT -> ((Text) element).value();
            case CODE -> ((Code) element).value();
            case LINK -> {
                final var link = (Link) element;
                yield link.label() == null ? link.url() : plainText(link.label());
            }
            case ANCHOR -> {
                final var anchor = (Anchor) element;
                yield anchor.label() == null || anchor.label().isBlank() ? anchor.value() : anchor.label();
            }
            case ATTRIBUTE -> {
                final var attribute = (Attribute) element;
                final var value = context().attribute(attribute.attribute());
                yield value == null ? "{" + attribute.attribute() + "}" :
                        attribute.evaluator().apply(value).stream().map(this::plainText).collect(joining());
            }
            case MACRO -> {
                final var macro = (Macro) element;
                yield switch (macro.name()) {
                    case "footnote", "footnoteref", "doublefootnote", "indexterm", "icon", "image" -> "";
                    default -> options(macro.options()).getOrDefault("", macro.label());
                };
            }
            case LINE_BREAK -> " ";
            case PARAGRAPH -> ((Paragraph) element).children().stream().map(this::plainText).collect(joining());
            default -> "";
        };
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

    protected static class State {
        protected Map<String, String> documentAttributes = Map.of();
        protected Map<String, String> sectionTitlesById = Map.of();
        protected Set<String> referencedIds = Set.of(); // ids the document links to, a generated section id is written only then
        protected List<TocSection> tocSections = List.of(); // in document order, following the rendered conditional branches
        protected List<String> asciidocExtensions; // read from the attributes on first use, see asciidocExtensions()
        protected final List<FootNote> footnotes = new ArrayList<>(); // definitions, in order
        protected final Map<String, FootNote> footnotesById = new HashMap<>();
        protected final Set<String> footnoteLabels = new HashSet<>();
        protected int anonymousFootnotes;

        protected void reset() {
            documentAttributes = Map.of();
            sectionTitlesById = Map.of();
            referencedIds = Set.of();
            tocSections = List.of();
            asciidocExtensions = null;
            footnotes.clear();
            footnotesById.clear();
            footnoteLabels.clear();
            anonymousFootnotes = 0;
        }
    }

    /**
     * Indexes the body in one walk, following only the conditional branches that are rendered: the ids the document
     * links to, with {@code <<id>>} or {@code xref:id[]}, the link text of each section and floating title by id, the
     * sections a table of contents lists, and the deepest {@code levels} a {@code toc::[]} macro asks for.
     */
    private final class Index implements Visitor<Void> {
        private final Set<String> ids = new HashSet<>();
        private final Map<String, String> titles = new HashMap<>();
        private final List<TocSection> sections = new ArrayList<>();
        private int tocMacroLevels;

        @Override
        public ConditionalBlock.Context context() {
            return GithubFlavoredMarkdownRenderer.this.context();
        }

        @Override
        public void visitAnchor(final Anchor element) {
            if (element.value() != null && !element.value().isBlank()) {
                ids.add(element.value().strip());
            }
        }

        @Override
        public void visitMacro(final Macro element) {
            if ("xref".equals(element.name()) && element.label() != null) {
                final var id = xrefInternalId(substitute(element.label()).strip());
                if (id != null) {
                    ids.add(id);
                }
            } else if ("toc".equals(element.name())) {
                tocMacroLevels = Math.max(tocMacroLevels, tocLevels(options(element.options()).get("levels")));
            }
        }

        @Override
        public void visitSection(final Section element) {
            final var id = sectionId(element.options(), element.title());
            final var text = sectionLinkText(element.title());
            titles.putIfAbsent(id, text);
            sections.add(new TocSection(element.level() - 1, id, text));
            visitElement(element.title());
            Visitor.super.visitSection(element);
        }

        @Override
        public void visitFloatingTitle(final FloatingTitle element) {
            titles.putIfAbsent(sectionId(element.options(), element.title()), sectionLinkText(element.title()));
            visitElement(element.title());
        }

        private String sectionId(final Map<String, String> options, final Element title) {
            final var explicit = id(options);
            return explicit != null ? explicit : generatedId(title);
        }

        @Override
        public void visitLink(final Link element) {
            if (element.label() != null) {
                visitElement(element.label());
            }
        }

        @Override
        public void visitCode(final Code element) {
            element.callOuts().forEach(callOut -> visitElement(callOut.text()));
        }

        @Override
        public void visitDescriptionList(final DescriptionList element) {
            element.children().forEach((term, description) -> { // a term can come without a description
                visitElement(term);
                if (description != null) {
                    visitElement(description);
                }
            });
        }

        @Override
        public void visitConditionalBlock(final ConditionalBlock element) {
            if (element.evaluator().test(context())) {
                element.children().forEach(this::visitElement);
            } else if (element.elseBranches() != null) {
                element.elseBranches().stream()
                        .filter(branch -> branch.evaluator().test(context()))
                        .findFirst()
                        .ifPresent(branch -> branch.children().forEach(this::visitElement));
            }
        }
    }

    /**
     * A section a table of contents can list.
     *
     * @param level its asciidoctor level, 1 for {@code ==}.
     * @param id    its explicit or generated id.
     * @param title the text of a link to it.
     */
    protected record TocSection(int level, String id, String title) {
    }

    /**
     * A footnote: its Markdown label ({@code [^label]}) and its text, {@code null} while only referenced.
     */
    protected static class FootNote {
        protected final String label;
        protected String text;

        protected FootNote(final String label, final String text) {
            this.label = label;
            this.text = text;
        }
    }
}
