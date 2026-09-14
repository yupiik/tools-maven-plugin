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
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.joining;

/**
 * Renders a document as GitHub-Flavored Markdown: ATX headings, fenced code with the language, pipe tables,
 * admonitions as GitHub alerts, callouts as a numbered list after the code block, footnotes.
 * <p>
 * Every block appends its Markdown followed by a blank line. Nested content (admonitions, quotes, list items,
 * table cells) is rendered by the same renderer into a temporary buffer, so a subclass's overrides apply at any
 * depth. When neither the document nor the configuration defines an attribute, its reference stays literal, as
 * asciidoctor does with {@code attribute-missing=skip}. An unknown construct never throws, the renderer falls back to
 * the element text.
 * <p>
 * A renderer can render several documents one after the other, {@link #visit(Document)} starts from a clean state.
 * Like {@link io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer} it is not thread safe.
 */
public class GithubFlavoredMarkdownRenderer implements Visitor<String> {
    private static final Set<String> ADMONITION_STYLES = Set.of("NOTE", "TIP", "IMPORTANT", "CAUTION", "WARNING");
    private static final Set<String> ASCIIDOC_EXTENSIONS = Set.of(".adoc", ".asciidoc", ".ad", ".asc");
    private static final Pattern ATTRIBUTE_REFERENCE = Pattern.compile("\\{([a-zA-Z0-9_][a-zA-Z0-9_-]*)}");
    private static final Pattern URL_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern BLOCK_STYLE = Pattern.compile("[a-zA-Z][a-zA-Z0-9_+-]*");
    private static final Pattern BACKTICK_RUN = Pattern.compile("`+");

    protected final Configuration configuration;
    protected final State state; // this is why we are not thread safe
    protected StringBuilder builder = new StringBuilder(); // replaced while a nested block renders, see block()

    public GithubFlavoredMarkdownRenderer() {
        this(new Configuration());
    }

    public GithubFlavoredMarkdownRenderer(final Configuration configuration) {
        this.configuration = configuration;
        this.state = new State();
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
        final var references = new References();
        references.visitBody(body);
        state.referencedIds = references.ids;
        state.sectionTitlesById = indexSectionTitles(body.children());
        renderChildren(body.children());
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
                    && paragraph.children().stream().allMatch(GithubFlavoredMarkdownRenderer::isInline)) {
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

    private static List<List<Element>> splitParagraphRun(final List<Element> run) {
        return split(run, (before, after) -> before instanceof Text b && after instanceof Text a
                && b.style().isEmpty() && a.style().isEmpty()
                && !endsWithWhitespace(b) && !startsWithWhitespaceOrPunctuation(a));
    }

    private static List<List<Element>> splitBlockRun(final List<Element> run) {
        if (run.stream().noneMatch(LineBreak.class::isInstance)) {
            return run.stream().map(List::of).toList();
        }
        return split(run, (before, after) -> !(before instanceof LineBreak) && !(after instanceof LineBreak)
                && !(before instanceof Text b && endsWithWhitespace(b))
                && !(after instanceof Text a && startsWithWhitespaceOrPunctuation(a)));
    }

    private static List<List<Element>> split(final List<Element> run, final java.util.function.BiPredicate<Element, Element> isBoundary) {
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

    private static boolean endsWithWhitespace(final Text text) {
        final var value = text.value();
        return value == null || value.isEmpty() || Character.isWhitespace(value.charAt(value.length() - 1));
    }

    private static boolean startsWithWhitespaceOrPunctuation(final Text text) {
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
        return key -> {
            final var value = state.documentAttributes.get(key);
            return value != null ? value : configuration.getAttributes().get(key);
        };
    }

    @Override
    public String result() {
        final var result = new StringBuilder(builder.toString().strip());
        if (!state.footnotes.isEmpty()) {
            result.append("\n\n");
            for (final var footNote : state.footnotes) {
                result.append("[^").append(footNote.label).append("]: ").append(footNote.text.strip()).append('\n');
            }
        }
        return result.toString().strip() + "\n";
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
    protected static String id(final Map<String, String> options) {
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
     * @return the style name of a block, without the {@code #id}, {@code .role} or {@code %option} shorthands.
     */
    private static String styleName(final Map<String, String> options) {
        final var style = options == null ? null : options.get("");
        if (style == null || style.isBlank() || style.strip().startsWith("[")) {
            return "";
        }
        final var value = style.strip();
        final int end = firstIndexOf(value, "#.%");
        return end < 0 ? value : value.substring(0, end);
    }

    private static int firstIndexOf(final String value, final String characters) {
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
        var language = language(code.options());
        if (language.isEmpty()) { // [mermaid] and other diagram blocks keep their style as the fence info string
            language = style(code.options());
        }
        fence(language, withCallOutMarkers(code));
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
        final var style = style(options);
        final var diagramOrLanguage = !style.isEmpty() && !"listing".equals(style) && !"literal".equals(style);
        fence(diagramOrLanguage ? style : language(options), listing.value() == null ? "" : listing.value());
    }

    /**
     * @return the block style (first positional attribute) when it is a name, empty otherwise;
     * the parser also stores a {@code [[id]]} block anchor there, as {@code [id]}.
     */
    private static String style(final Map<String, String> options) {
        final var style = options == null ? null : options.get("");
        return style != null && BLOCK_STYLE.matcher(style.strip()).matches() ? style.strip() : "";
    }

    /**
     * The fence is one backtick longer than the longest run of backticks in the code, and at least three long.
     */
    protected void fence(final String language, final String value) {
        int longest = 0;
        final var runs = BACKTICK_RUN.matcher(value);
        while (runs.find()) {
            longest = Math.max(longest, runs.end() - runs.start());
        }
        final var fence = "`".repeat(Math.max(3, longest + 1));
        builder.append(fence).append(language == null ? "" : language).append('\n').append(value);
        if (!value.endsWith("\n")) {
            builder.append('\n');
        }
        builder.append(fence).append("\n\n");
    }

    private static String language(final Map<String, String> options) {
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
                && !wrapped.children().isEmpty() && wrapped.children().stream().allMatch(GithubFlavoredMarkdownRenderer::isInline)
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

    private static String checkbox(final Element item) {
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
                    && paragraph.children().stream().allMatch(GithubFlavoredMarkdownRenderer::isInline)
                    && segments(paragraph.children(), true).size() == 1) {
                builder.append("\\\n").append(inline(description).strip()).append("\n\n");
            } else {
                builder.append("\n\n").append(indent(block(List.of(description)), "  ")).append("\n\n");
            }
        }
    }

    @Override
    public void visitAdmonition(final Admonition admonition) {
        alert(admonition.level().name(), options(admonition.options()), List.of(admonition.content()));
    }

    /**
     * GitHub alert, {@code > [!NOTE]} followed by the content as a quote.
     */
    protected void alert(final String level, final Map<String, String> options, final List<Element> content) {
        blockAnchor(options);
        builder.append("> [!").append(level).append("]\n");
        final var title = options.get("title");
        if (title != null && !title.isBlank()) {
            builder.append("> **").append(substitute(title).strip()).append("**\n>\n");
        }
        builder.append(quote(block(content))).append("\n\n");
    }

    @Override
    public void visitOpenBlock(final OpenBlock block) {
        final var options = options(block.options());
        final var style = styleName(options).toUpperCase(ROOT);
        if (ADMONITION_STYLES.contains(style)) {
            alert(style, options, block.children());
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
            case "toc", "include" -> {
                // nothing to show
            }
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

    protected static boolean isInline(final Element element) {
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

    private static String inlineCode(final String value) {
        final var code = value == null ? "" : value;
        return code.contains("`") ? "`` " + code + " ``" : "`" + code + "`";
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
            return (URL_SCHEME.matcher(target).find() && target.equals(destination(target)) ?
                    "<" + target + ">" :
                    "[" + target.replaceAll("([\\[\\]<>])", "\\\\$1") + "](" + destination(target) + ")") + closing;
        }
        return "[" + label + "](" + destination(url) + ")";
    }

    /**
     * @return a link destination, in angle brackets when CommonMark would otherwise end it too early: on whitespace,
     * on an angle bracket, or on a parenthesis without its pair.
     */
    protected static String destination(final String url) {
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
            case "icon", "toc", "include", "indexterm" -> ""; // indexterm:[...] only feeds the index
            case "indexterm2" -> options.getOrDefault("", label); // indexterm2:[term] shows the term
            case "footnote", "footnoteref", "doublefootnote" -> footnote(macro, options);
            case "stem", "latexmath", "asciimath" -> "$" + content.strip() + "$";
            default -> options.getOrDefault("", label);
        };
    }

    /**
     * Splits keys as asciidoctor does: on the first of {@code ,} or {@code +} found after the first character, a
     * trailing delimiter being a key itself ({@code Ctrl++}).
     */
    static String keys(final String label) {
        final var value = label.strip();
        List<String> keys;
        final int comma = value.indexOf(',', 1);
        final int plus = value.indexOf('+', 1);
        final int delimiter = comma < 0 ? plus : plus < 0 ? comma : Math.min(comma, plus);
        if (value.length() > 1 && delimiter > 0) {
            final var separator = value.substring(delimiter, delimiter + 1);
            if (value.endsWith(separator)) {
                keys = new ArrayList<>(List.of(value.substring(0, value.length() - 1).split(Pattern.quote(separator), -1)));
                keys.set(keys.size() - 1, keys.get(keys.size() - 1) + separator);
            } else {
                keys = List.of(value.split(Pattern.quote(separator)));
            }
        } else {
            keys = List.of(value);
        }
        return keys.stream()
                .map(String::strip)
                .filter(key -> !key.isEmpty())
                .map(key -> "<kbd>" + key + "</kbd>")
                .collect(joining("+"));
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
     * {@code xref:page.adoc#id[text]} links the rendered page, see {@link #xrefTarget(String)}; same-page references,
     * {@code xref:#id[]} or {@code xref:id[]}, keep {@code #id} and take the section title as text when the macro has none.
     */
    protected String xref(final Macro macro, final Map<String, String> options) {
        final var target = substitute(macro.label() == null ? "" : macro.label()).strip();
        final int hash = target.indexOf('#');
        final var internal = xrefInternalId(target);
        final var file = internal != null ? "" : hash >= 0 ? target.substring(0, hash) : target;
        final var fragment = internal != null ? internal : hash >= 0 ? target.substring(hash + 1) : "";
        final int extension = file.lastIndexOf('.');
        final var path = extension > 0 && ASCIIDOC_EXTENSIONS.contains(file.substring(extension)) ? xrefTarget(file.substring(0, extension)) : file;
        final var href = destination(file.isEmpty() ? "#" + fragment : fragment.isEmpty() ? path : path + "#" + fragment);
        var text = options.get("");
        if (text == null || text.isBlank()) {
            if (file.isEmpty()) {
                text = state.sectionTitlesById.getOrDefault(fragment, fragment);
            } else {
                text = fragment.isEmpty() ? path : fragment;
            }
        }
        return "[" + text.strip() + "](" + href + ")";
    }

    /**
     * Where a cross reference to another document points, as the HTML renderer does:
     * {@code relfileprefix} + page + {@code relfilesuffix}, the suffix defaulting to {@code outfilesuffix} then {@code .md}.
     *
     * @param page the referenced document without its {@code .adoc} extension.
     * @return the link target.
     */
    protected String xrefTarget(final String page) {
        final var prefix = attr("relfileprefix", "");
        return prefix + (!prefix.isEmpty() && page.startsWith("./") ? page.substring(2) : page)
                + attr("relfilesuffix", attr("outfilesuffix", ".md"));
    }

    protected String image(final Macro macro) {
        final var options = options(macro.options());
        var target = substitute(macro.label() == null ? "" : macro.label()).strip();
        if (!target.isEmpty() && !target.startsWith("/") && !URL_SCHEME.matcher(target).find()) {
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
            final var name = target.substring(target.lastIndexOf('/') + 1);
            final int dot = name.lastIndexOf('.');
            alt = dot > 0 ? name.substring(0, dot) : name;
        }
        final var image = "![" + alt.strip() + "](" + destination(target) + ")";
        final var link = options.get("link");
        return link != null && !link.isBlank() ? "[" + image + "](" + destination(substitute(link).strip()) + ")" : image;
    }

    /**
     * @return the id a cross reference points at in the same document, {@code null} when it points at another
     * document: as in asciidoctor, a target without {@code #} and without an AsciiDoc extension is an id.
     */
    protected static String xrefInternalId(final String target) {
        if (target.startsWith("#")) {
            return target.substring(1);
        }
        if (target.isEmpty() || target.indexOf('#') >= 0) {
            return null;
        }
        final int dot = target.lastIndexOf('.');
        return dot >= 0 && ASCIIDOC_EXTENSIONS.contains(target.substring(dot)) ? null : target;
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
    protected static boolean hasOption(final Map<String, String> options, final String name) {
        if (options.containsKey(name + "-option")) {
            return true;
        }
        final var opts = options.get("opts");
        if (opts != null && Stream.of(opts.split(",")).map(String::strip).anyMatch(name::equals)) {
            return true;
        }
        // the parser reads [%collapsible%open] as one "collapsible%open-option" key
        return options.keySet().stream()
                .filter(key -> key.endsWith("-option"))
                .anyMatch(key -> Stream.of(key.substring(0, key.length() - "-option".length()).split("%")).anyMatch(name::equals));
    }

    protected static String quote(final String text) {
        return text.lines().map(line -> line.isEmpty() ? ">" : "> " + line).collect(joining("\n"));
    }

    protected static String indent(final String text, final String indent) {
        return text.lines().map(line -> line.isEmpty() ? line : indent + line).collect(joining("\n"));
    }

    private static Map<String, String> options(final Map<String, String> options) {
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

    private Map<String, String> indexSectionTitles(final List<Element> elements) {
        final var titles = new HashMap<String, String>();
        collectTitles(elements, titles);
        return titles;
    }

    private void collectTitles(final List<Element> elements, final Map<String, String> titles) {
        for (final var element : elements) {
            if (element instanceof Section section) {
                registerTitle(section.options(), section.title(), titles);
                collectTitles(section.children(), titles);
            } else if (element instanceof FloatingTitle floatingTitle) {
                registerTitle(floatingTitle.options(), floatingTitle.title(), titles);
            } else if (element instanceof ConditionalBlock conditional) {
                if (conditional.evaluator().test(context())) {
                    collectTitles(conditional.children(), titles);
                } else if (conditional.elseBranches() != null) {
                    conditional.elseBranches().stream()
                            .filter(branch -> branch.evaluator().test(context()))
                            .findFirst()
                            .ifPresent(branch -> collectTitles(branch.children(), titles));
                }
            } else if (element instanceof OpenBlock openBlock) {
                collectTitles(openBlock.children(), titles);
            }
        }
    }

    private void registerTitle(final Map<String, String> options, final Element title, final Map<String, String> titles) {
        final var explicit = id(options);
        titles.putIfAbsent(explicit != null ? explicit : generatedId(title),
                plainText(title).replaceAll("\\s+", " ").strip().replace("[", "\\[").replace("]", "\\]"));
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
                    default -> macro.options().getOrDefault("", macro.label());
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
        protected final List<FootNote> footnotes = new ArrayList<>(); // definitions, in order
        protected final Map<String, FootNote> footnotesById = new HashMap<>();
        protected final Set<String> footnoteLabels = new HashSet<>();
        protected int anonymousFootnotes;

        protected void reset() {
            documentAttributes = Map.of();
            sectionTitlesById = Map.of();
            referencedIds = Set.of();
            footnotes.clear();
            footnotesById.clear();
            footnoteLabels.clear();
            anonymousFootnotes = 0;
        }
    }

    /**
     * Collects the ids the document links to, with {@code <<id>>} or {@code xref:id[]}, following only the
     * conditional branches that are rendered.
     */
    private final class References implements Visitor<Void> {
        private final Set<String> ids = new HashSet<>();

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
            }
        }

        @Override
        public void visitSection(final Section element) {
            visitElement(element.title());
            Visitor.super.visitSection(element);
        }

        @Override
        public void visitFloatingTitle(final FloatingTitle element) {
            visitElement(element.title());
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
