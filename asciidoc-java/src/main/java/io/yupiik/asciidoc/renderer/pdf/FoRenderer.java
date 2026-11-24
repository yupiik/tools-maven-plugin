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
package io.yupiik.asciidoc.renderer.pdf;

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
import io.yupiik.asciidoc.renderer.UnknownMacro;
import io.yupiik.asciidoc.renderer.Visitor;
import io.yupiik.asciidoc.renderer.VisitorSibling;
import io.yupiik.asciidoc.renderer.VisitorState;
import io.yupiik.language.tokenizer.Kind;
import io.yupiik.language.tokenizer.SyntaxHighlighter;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;

/**
 * Serializes AsciiDoc to XSL-FO, independently of a PDF processor. Instances are reusable, but not thread safe.
 * Unknown macros are rendered as text by default; raw passthroughs fail rather than silently losing content.
 * A section with a {@code pdf-source} option registers an included document path for internal cross-document links.
 */
public final class FoRenderer implements Visitor<String> {
    public static final String UNKNOWN_MACRO_ATTRIBUTE = "yupiik-renderer-pdf-unknownMacro";
    public static final String TITLE_PAGE_ATTRIBUTE = "pdf-title-page";
    public static final String SYNTAX_HIGHLIGHTING_ATTRIBUTE = "pdf-syntax-highlighting";
    public static final String SECTION_FOOTER_ATTRIBUTE = "pdf-section-footer";

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*", Pattern.DOTALL);
    private static final Pattern LENGTH = Pattern.compile("[0-9]+(?:\\.[0-9]+)?(?:%|pt|pc|px|mm|cm|in|em)?");
    private static final Pattern ENTITIES = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp|vert|lbrack|rbrack|num);");
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private final PdfConfiguration configuration;
    private final Path baseDirectory;
    private final PdfTheme theme;
    private final VisitorSibling sibling = new VisitorSibling();
    private SyntaxHighlighter highlighter;
    private final VisitorState state;
    private final StringBuilder fo = new StringBuilder();
    private final Set<String> ids = new HashSet<>();
    private final Set<String> references = new HashSet<>();
    private final Map<String, IncludedDocument> includedDocuments = new HashMap<>();
    private final Map<Element, String> destinations = new IdentityHashMap<>();
    private final Map<String, String> referenceTexts = new HashMap<>();
    private final List<VisitorState.TocSection> sections = new ArrayList<>();
    private final List<String> currentSections = new ArrayList<>();
    private String sourceDocument;
    private int inlineDepth;
    private int tableCellDepth;
    private boolean tableHeader;
    private String endId;
    private boolean complete;
    private boolean syntaxHighlighting;
    private boolean sectionFooter;

    public FoRenderer() {
        this(new PdfConfiguration());
    }

    public FoRenderer(final PdfConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.baseDirectory = Objects.requireNonNull(configuration.getAssetsBase(), "assetsBase").toAbsolutePath().normalize();
        this.theme = Objects.requireNonNull(configuration.getTheme(), "theme");
        this.state = new VisitorState(sibling, key -> configuration.getAttributes().get(key));
    }

    @Override
    public ConditionalBlock.Context context() {
        return state.context();
    }

    @Override
    public void visit(final Document document) {
        complete = false;
        fo.setLength(0);
        ids.clear();
        references.clear();
        includedDocuments.clear();
        destinations.clear();
        referenceTexts.clear();
        sections.clear();
        currentSections.clear();
        sourceDocument = null;
        inlineDepth = 0;
        tableCellDepth = 0;
        tableHeader = false;
        state.visit(Objects.requireNonNull(document, "document"));
        unknownMacro(); // validate even when the document contains no unknown macro
        syntaxHighlighting = enabled(SYNTAX_HIGHLIGHTING_ATTRIBUTE, configuration.isSyntaxHighlighting());
        sectionFooter = enabled(SECTION_FOOTER_ATTRIBUTE, configuration.isSectionFooter());
        final var coverTitle = metadata("pdf-title-page-title", document.header().title());
        final boolean cover = enabled(TITLE_PAGE_ATTRIBUTE, configuration.isTitlePage()) && !coverTitle.isBlank();
        state.visitBody(document.body());
        // Pick a private destination which cannot collide with any user destination, including block ids.
        final var occupied = new HashSet<String>();
        new Visitor<Void>() {
            @Override
            public ConditionalBlock.Context context() {
                return FoRenderer.this.context();
            }

            @Override
            public void visitElement(final Element element) {
                final var id = sibling.id(sibling.blockOptions(element));
                if (id != null) {
                    occupied.add(substitute(id));
                }
                Visitor.super.visitElement(element);
            }

            @Override
            public void visitSection(final Section section) {
                occupied.add(substitute(state.sectionId(section.options(), section.title())));
                visitElement(section.title());
                Visitor.super.visitSection(section);
            }

            @Override
            public void visitFloatingTitle(final FloatingTitle title) {
                occupied.add(substitute(state.sectionId(title.options(), title.title())));
                visitElement(title.title());
            }

            @Override
            public void visitLink(final Link link) {
                if (link.label() != null) {
                    visitElement(link.label());
                }
            }

            @Override
            public void visitCode(final Code code) {
                code.callOuts().forEach(callout -> visitElement(callout.text()));
            }

            @Override
            public void visitConditionalBlock(final ConditionalBlock block) {
                sibling.renderedChildren(block, context()).forEach(this::visitElement);
            }

            @Override
            public void visitDescriptionList(final DescriptionList list) {
                list.children().forEach((term, description) -> {
                    visitElement(term);
                    if (description != null) {
                        visitElement(description);
                    }
                });
            }
        }.visitBody(document.body());
        endId = "_fo_document_end";
        while (occupied.contains(endId)) {
            endId += "_";
        }
        indexDestinations(document.body(), occupied);
        fo.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><fo:root xmlns:fo=\"http://www.w3.org/1999/XSL/Format\"");
        property("font-family", theme.sans());
        property("color", theme.text());
        fo.append(" font-size=\"10.5pt\" line-height=\"1.2\"><fo:layout-master-set>");
        pageMaster("document", theme.margin());
        fo.append("><fo:region-body margin-top=\"10mm\" margin-bottom=\"12mm\"/>"
                + "<fo:region-before extent=\"8mm\"/><fo:region-after extent=\"10mm\"/>"
                + "</fo:simple-page-master>");
        if (cover) {
            pageMaster("title-page", "0mm");
            fo.append('>');
            fo.append("<fo:region-body");
            property("background-color", metadata("pdf-title-page-background", "#FFFFFF"));
            fo.append("/></fo:simple-page-master>");
        }
        fo.append("</fo:layout-master-set>");
        bookmarks();
        if (cover) {
            titlePage(document, coverTitle);
        }
        fo.append("<fo:page-sequence master-reference=\"document\" initial-page-number=\"1\">"
                + "<fo:static-content flow-name=\"xsl-region-before\"><fo:block font-size=\"8pt\"");
        property("color", theme.muted());
        fo.append('>').append(xml(substitute(document.header().title()))).append("</fo:block></fo:static-content>");
        footer(document.header().title());
        fo.append("<fo:flow flow-name=\"xsl-region-body\"><fo:block>");
        sectionMarker(document.header().title());
        fo.append("</fo:block>");
        if (!cover && !document.header().title().isBlank()) {
            fo.append("<fo:block font-size=\"26pt\" font-weight=\"bold\" space-after=\"14pt\" keep-with-next=\"always\"");
            property("color", theme.text());
            property("border-bottom", "2pt solid " + theme.primary());
            property("padding-bottom", "8pt");
            fo.append('>').append(xml(substitute(document.header().title()))).append("</fo:block>");
        }
        final var placement = sibling.tocPlacement(context());
        if (placement != null && !"macro".equals(placement)) {
            toc(null);
        }
        visitBody(document.body());
        fo.append("<fo:block");
        destination(endId);
        fo.append("/></fo:flow></fo:page-sequence></fo:root>");
        final var missing = new HashSet<>(references);
        missing.removeAll(ids);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Unresolved AsciiDoc PDF references: " + missing);
        }
        complete = true;
    }

    private void pageMaster(final String name, final String margin) {
        fo.append("<fo:simple-page-master");
        property("master-name", name);
        property("page-width", theme.pageWidth());
        property("page-height", theme.pageHeight());
        property("margin", margin);
    }

    private boolean enabled(final String attribute, final boolean fallback) {
        final var value = state.attribute(attribute, null);
        if (value == null) {
            return fallback;
        }
        return switch (substitute(value).strip().toLowerCase(Locale.ROOT)) {
            case "", "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean PDF attribute '" + attribute + "': " + value);
        };
    }

    private String metadata(final String attribute, final String fallback) {
        return substitute(state.attribute(attribute, fallback == null ? "" : fallback));
    }

    private void titlePage(final Document document, final String title) {
        fo.append("<fo:page-sequence master-reference=\"title-page\" force-page-count=\"no-force\">"
                + "<fo:flow flow-name=\"xsl-region-body\"><fo:block");
        property("color", metadata("pdf-title-page-text-color", theme.text()));
        // Nested blocks inherit indents, but do not inherit their parent's padding.
        property("start-indent", theme.margin());
        property("end-indent", theme.margin());
        property("padding-top", theme.margin());
        fo.append('>');
        final var logo = metadata("pdf-title-page-logo", "");
        if (!logo.isBlank()) {
            image(new Macro("image", logo, Map.of("width", "45mm"), false));
        }
        final var label = metadata("pdf-title-page-label", "");
        if (!label.isBlank()) {
            fo.append("<fo:block font-size=\"10pt\" space-before=\"12mm\" space-after=\"5mm\">")
                    .append(xml(label)).append("</fo:block>");
        }
        fo.append("<fo:block font-weight=\"bold\" line-height=\"1.2\" space-after=\"8mm\" space-before.conditionality=\"retain\"");
        property("font-size", imageLength(metadata("pdf-title-page-title-size", "32pt")));
        property("space-before", imageLength(metadata("pdf-title-page-title-top", "40mm")));
        fo.append('>').append(xml(title)).append("</fo:block>");
        final var subtitle = metadata("pdf-title-page-subtitle", metadata("subtitle", ""));
        if (!subtitle.isBlank()) {
            fo.append("<fo:block font-size=\"15pt\" line-height=\"1.3\" space-after=\"10mm\">")
                    .append(xml(subtitle)).append("</fo:block>");
        }
        fo.append("<fo:block border-bottom-width=\"1pt\" border-bottom-style=\"solid\" margin-right=\"75%\"");
        property("border-bottom-color", theme.primary());
        fo.append("/>");
        final var headerAuthors = document.header().author().stream()
                .map(author -> author.name() == null ? "" : author.name()).filter(name -> !name.isBlank()).collect(joining(", "));
        final var authors = metadata("pdf-title-page-authors", metadata("authors", headerAuthors));
        final var revision = document.header().revision();
        final var version = metadata("revnumber", revision == null ? "" : revision.number());
        final var date = metadata("revdate", revision == null ? "" : revision.date());
        final var remark = metadata("revremark", revision == null ? "" : revision.revmark());
        final var revisionText = metadata("pdf-title-page-revision",
                (version.isBlank() ? "" : "Version " + version) + (version.isBlank() || date.isBlank() ? "" : " - ") + date);
        fo.append("<fo:block space-before=\"24mm\" font-size=\"10.5pt\">");
        if (!authors.isBlank()) {
            fo.append("<fo:block space-after=\"3mm\">");
            if (state.attribute("pdf-title-page-authors", null) == null &&
                    !configuration.getAttributes().containsKey("authors") && authors.equals(substitute(headerAuthors))) {
                boolean first = true;
                for (final var author : document.header().author()) {
                    if (author.name() == null || author.name().isBlank()) {
                        continue;
                    }
                    if (!first) {
                        fo.append(", ");
                    }
                    first = false;
                    if (author.mail() == null || author.mail().isBlank()) {
                        fo.append(xml(substitute(author.name())));
                    } else {
                        fo.append("<fo:basic-link text-decoration=\"underline\"");
                        property("external-destination", mailto(substitute(author.mail())));
                        fo.append('>').append(xml(substitute(author.name()))).append("</fo:basic-link>");
                    }
                }
            } else {
                fo.append(xml(authors));
            }
            fo.append("</fo:block>");
        }
        for (final var line : List.of(revisionText, remark)) {
            if (!line.isBlank()) {
                fo.append("<fo:block space-after=\"3mm\">").append(xml(line)).append("</fo:block>");
            }
        }
        fo.append("</fo:block></fo:block></fo:flow></fo:page-sequence>");
    }

    private String mailto(final String address) {
        final var mail = address.strip();
        try {
            return new URI("mailto", mail.regionMatches(true, 0, "mailto:", 0, "mailto:".length()) ?
                    mail.substring("mailto:".length()) : mail, null).toASCIIString();
        } catch (final URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid PDF author email: " + address, invalid);
        }
    }

    private void footer(final String title) {
        fo.append("<fo:static-content flow-name=\"xsl-region-after\"><fo:table table-layout=\"fixed\" width=\"100%\""
                + " font-size=\"8pt\" border-top-width=\"0.5pt\" border-top-style=\"solid\"");
        property("color", theme.muted());
        property("border-top-color", theme.border());
        fo.append("><fo:table-column column-width=\"70%\"/><fo:table-column column-width=\"30%\"/>"
                + "<fo:table-body><fo:table-row><fo:table-cell padding-top=\"3pt\" padding-right=\"8pt\"><fo:block>");
        if (sectionFooter) {
            fo.append("<fo:retrieve-marker retrieve-class-name=\"current-section\" retrieve-position=\"last-starting-within-page\""
                    + " retrieve-boundary=\"page-sequence\"/>");
        } else {
            fo.append(xml(substitute(title)));
        }
        fo.append("</fo:block></fo:table-cell><fo:table-cell padding-top=\"3pt\"><fo:block text-align=\"right\">"
                + "Page <fo:page-number/> / <fo:page-number-citation-last");
        property("ref-id", endId);
        fo.append("/></fo:block></fo:table-cell></fo:table-row></fo:table-body></fo:table></fo:static-content>");
    }

    private void sectionMarker(final String title) {
        if (sectionFooter) {
            fo.append("<fo:marker marker-class-name=\"current-section\">").append(xml(substitute(title))).append("</fo:marker>");
        }
    }

    @Override
    public String result() {
        if (!complete) {
            throw new IllegalStateException("No successfully rendered document; call visit(Document) first");
        }
        return fo.toString();
    }

    @Override
    public String toString() {
        return result();
    }

    private void bookmarks() {
        if (sections.isEmpty()) {
            return;
        }
        fo.append("<fo:bookmark-tree>");
        // Levels can skip numbers, so retain actual open levels rather than assuming a contiguous hierarchy.
        final var levels = new ArrayList<Integer>();
        for (final var section : sections) {
            while (!levels.isEmpty() && levels.get(levels.size() - 1) >= section.level()) {
                fo.append("</fo:bookmark>");
                levels.remove(levels.size() - 1);
            }
            final var id = substitute(section.id());
            references.add(id);
            fo.append("<fo:bookmark");
            property("internal-destination", id);
            fo.append("><fo:bookmark-title>").append(xml(substitute(section.title()))).append("</fo:bookmark-title>");
            levels.add(section.level());
        }
        fo.append("</fo:bookmark>".repeat(levels.size()));
        fo.append("</fo:bookmark-tree>");
    }

    private void toc(final String levels) {
        fo.append("<fo:block font-size=\"18pt\" font-weight=\"bold\" space-before=\"12pt\" space-after=\"8pt\" keep-with-next=\"always\">")
                .append(xml(state.attribute("toc-title", "Table of Contents"))).append("</fo:block>");
        final int depth = sibling.tocLevels(levels, context());
        for (final var section : sections) {
            if (section.level() > depth) {
                continue;
            }
            final var id = substitute(section.id());
            references.add(id);
            fo.append("<fo:block text-align-last=\"justify\" space-after=\"3pt\"");
            property("color", theme.muted());
            property("start-indent", Math.max(0, section.level() - 1) * 10 + "pt");
            fo.append("><fo:basic-link");
            property("internal-destination", id);
            fo.append('>').append(xml(substitute(section.title())))
                    .append("<fo:leader leader-pattern=\"dots\"/><fo:page-number-citation");
            property("ref-id", id);
            fo.append("/></fo:basic-link></fo:block>");
        }
    }

    @Override
    public void visitElement(final Element element) {
        // A conditional's invisible branches must not contribute destinations or block wrappers.
        if (element instanceof ConditionalBlock block) {
            visitConditionalBlock(block);
            return;
        }
        final boolean inline = sibling.isInline(element, context());
        if (inlineDepth > 0 && !inline) {
            throw unsupported(element, "block inside inline content");
        }
        final boolean blockWrapper = inlineDepth == 0 && inline;
        if (blockWrapper) {
            fo.append("<fo:block>");
            inlineDepth++;
        }
        final var options = sibling.blockOptions(element);
        final var id = sibling.id(options);
        final boolean wrapper = id != null && !(element instanceof Section) && !(element instanceof FloatingTitle);
        if (wrapper) {
            fo.append(inline ? "<fo:inline" : "<fo:block");
            destination(destinations.getOrDefault(element, substitute(id)));
            fo.append('>');
        }
        final var title = options.get("title");
        if (!inline && title != null && !(element instanceof Section) && !(element instanceof FloatingTitle)) {
            fo.append("<fo:block font-weight=\"bold\" keep-with-next=\"always\" space-before=\"6pt\">")
                    .append(xml(substitute(title))).append("</fo:block>");
        }
        Visitor.super.visitElement(element);
        if (wrapper) {
            fo.append(inline ? "</fo:inline>" : "</fo:block>");
        }
        if (blockWrapper) {
            inlineDepth--;
            fo.append("</fo:block>");
        }
    }

    @Override
    public void visitConditionalBlock(final ConditionalBlock block) {
        sibling.renderedChildren(block, context()).forEach(this::visitElement);
    }

    @Override
    public void visitAttribute(final Attribute attribute) {
        final var value = context().attribute(attribute.attribute());
        if (value != null) {
            attribute.evaluator().apply(value).forEach(this::visitElement);
        } else {
            final var intrinsic = sibling.intrinsicAttribute(attribute.attribute());
            fo.append(xml(entities(intrinsic == null ? "{" + attribute.attribute() + "}" : intrinsic)));
        }
    }

    @Override
    public void visitSection(final Section section) {
        final var previous = sourceDocument;
        if (section.options().containsKey("pdf-source")) {
            sourceDocument = documentPath(substitute(section.options().get("pdf-source")));
        }
        currentSections.add(sibling.titleText(section.title(), context()));
        try {
            heading(section.level(), section.options(), section.title(), true);
            section.children().forEach(this::visitElement);
        } finally {
            currentSections.remove(currentSections.size() - 1);
            sourceDocument = previous;
        }
    }

    private void indexIncludedDocument(final Section section, final Set<String> occupied) {
        final var source = documentPath(substitute(section.options().get("pdf-source")));
        if (source.isBlank() || SCHEME.matcher(source).matches()) {
            throw new IllegalArgumentException("Invalid AsciiDoc PDF source document: " + source);
        }
        var prefix = "_pdf_source_" + (includedDocuments.size() + 1) + "_";
        while (startsWithAny(occupied, prefix) || endId.startsWith(prefix)) {
            prefix += "_";
        }
        // A nested pdf-source starts another document, not a continuation of this document's section-id sequence.
        final var localIds = new VisitorSibling() {
            @Override
            protected void recordSectionIds(final Element element, final ConditionalBlock.Context context,
                                            final Map<Element, String> sectionIds, final Map<String, Integer> usedIds) {
                if (element instanceof Section nested && nested.options().containsKey("pdf-source")) {
                    return;
                }
                super.recordSectionIds(element, context, sectionIds, usedIds);
            }
        }.sectionIds(new Body(section.children()), context());
        final var document = new IncludedDocument(prefix, prefix + "chapter", localIds, new HashMap<>());
        if (includedDocuments.putIfAbsent(source, document) != null) {
            throw new IllegalArgumentException("Duplicate AsciiDoc PDF source document: " + source);
        }
    }

    private boolean startsWithAny(final Set<String> values, final String prefix) {
        return values.stream().anyMatch(value -> value.startsWith(prefix));
    }

    private void indexDestinations(final Body body, final Set<String> occupied) {
        new Visitor<Void>() {
            @Override
            public ConditionalBlock.Context context() {
                return FoRenderer.this.context();
            }

            @Override
            public void visitElement(final Element element) {
                final var id = sibling.id(sibling.blockOptions(element));
                if (id != null && !(element instanceof Section) && !(element instanceof FloatingTitle)) {
                    record(element, substitute(id), sibling.referenceText(element));
                }
                Visitor.super.visitElement(element);
            }

            private String record(final Element element, final String id, final String text) {
                final var document = includedDocuments.get(sourceDocument);
                final var destination = document == null ? id : document.prefix() + "anchor_" + id;
                destinations.put(element, destination);
                if (document != null) {
                    document.anchors().putIfAbsent(id, destination);
                }
                if (text != null && !text.isBlank()) {
                    referenceTexts.putIfAbsent(destination, text);
                }
                return destination;
            }

            private String title(final Map<String, String> options, final Element title) {
                final var document = includedDocuments.get(sourceDocument);
                final var localId = document == null ? null : document.sectionIds().get(title);
                return record(title, substitute(localId == null ? state.sectionId(options, title) : localId),
                        sibling.titleText(title, context()));
            }

            @Override
            public void visitSection(final Section section) {
                final var previous = sourceDocument;
                if (section.options().containsKey("pdf-source")) {
                    indexIncludedDocument(section, occupied);
                    sourceDocument = documentPath(substitute(section.options().get("pdf-source")));
                }
                try {
                    final var text = sibling.titleText(section.title(), context());
                    final String id;
                    if (section.options().containsKey("pdf-source")) {
                        final var document = includedDocuments.get(sourceDocument);
                        id = document.id();
                        destinations.put(section.title(), id);
                        referenceTexts.put(id, text);
                        final var explicitId = sibling.id(section.options());
                        if (explicitId != null) {
                            document.anchors().put(substitute(explicitId), id);
                        }
                    } else {
                        id = title(section.options(), section.title());
                    }
                    sections.add(new VisitorState.TocSection(section.level() - 1, id, text));
                    visitElement(section.title());
                    Visitor.super.visitSection(section);
                } finally {
                    sourceDocument = previous;
                }
            }

            @Override
            public void visitFloatingTitle(final FloatingTitle floatingTitle) {
                title(floatingTitle.options(), floatingTitle.title());
                visitElement(floatingTitle.title());
            }

            @Override
            public void visitLink(final Link link) {
                if (link.label() != null) {
                    visitElement(link.label());
                }
            }

            @Override
            public void visitCode(final Code code) {
                code.callOuts().forEach(callout -> visitElement(callout.text()));
            }

            @Override
            public void visitDescriptionList(final DescriptionList list) {
                list.children().forEach((term, description) -> {
                    visitElement(term);
                    if (description != null) {
                        visitElement(description);
                    }
                });
            }

            @Override
            public void visitConditionalBlock(final ConditionalBlock block) {
                sibling.renderedChildren(block, context()).forEach(this::visitElement);
            }
        }.visitBody(body);
    }

    private String documentPath(final String source) {
        return Path.of(source.replace('\\', '/')).normalize().toString().replace('\\', '/');
    }

    private IncludedDocument includedDocument(final String file) {
        if (SCHEME.matcher(file).matches()) {
            return null;
        }
        if (sourceDocument != null) {
            final var parent = Path.of(sourceDocument).getParent();
            if (parent != null) {
                final var relative = includedDocumentPath(parent.resolve(file).toString());
                if (relative != null) {
                    return relative;
                }
            }
        }
        return includedDocumentPath(file);
    }

    private IncludedDocument includedDocumentPath(final String file) {
        final var path = documentPath(file);
        final var exact = includedDocuments.get(path);
        if (exact != null) {
            return exact;
        }
        for (final var extension : state.asciidocExtensions()) {
            final var document = includedDocuments.get(path + extension);
            if (document != null) {
                return document;
            }
        }
        return null;
    }

    private String localDestination(final String id) {
        final var document = includedDocuments.get(sourceDocument);
        if (document != null) {
            final var anchor = document.anchors().get(id);
            if (anchor != null) {
                return anchor;
            }
        } else if (referenceTexts.containsKey(id)) {
            return id;
        }
        // AsciiDoc shorthand references can name a section by its title rather than its generated id.
        for (final var section : sections) {
            if (section.title().equals(id) &&
                    (document == null || section.id().startsWith(document.prefix() + "anchor_"))) {
                return section.id();
            }
        }
        return id;
    }

    private record IncludedDocument(String prefix, String id, Map<Element, String> sectionIds, Map<String, String> anchors) {
    }

    @Override
    public void visitFloatingTitle(final FloatingTitle title) {
        heading(title.level(), title.options(), title.title(), false);
    }

    private void heading(final int level, final Map<String, String> options, final Element title, final boolean section) {
        fo.append("<fo:block font-weight=\"bold\" space-before=\"14pt\" space-after=\"7pt\" keep-with-next=\"always\"");
        destination(destinations.getOrDefault(title, substitute(state.sectionId(options, title))));
        property("font-size", Math.max(11, 24 - level * 3) + "pt");
        property("color", theme.text());
        if (level == 1) {
            property("border-bottom", "0.5pt solid " + theme.primary());
            property("padding-bottom", "4pt");
        }
        fo.append('>');
        if (section) {
            sectionMarker(currentSections.stream().skip(Math.max(0, currentSections.size() - 2)).collect(joining(" / ")));
        }
        inline(title);
        fo.append("</fo:block>");
    }

    @Override
    public void visitParagraph(final Paragraph paragraph) {
        final boolean span = sibling.isStyledSpan(paragraph);
        if (inlineDepth > 0 || span) {
            fo.append("<fo:inline");
            styles(sibling.spanStyles(paragraph));
            fo.append('>');
            paragraph.children().forEach(this::visitElement);
            fo.append("</fo:inline>");
        } else {
            fo.append("<fo:block space-after=\"7pt\">");
            // Conditional branches can contain blocks; split inline runs rather than put a block in an inline.
            boolean run = false;
            for (final var child : sibling.expandConditionals(paragraph.children(), context())) {
                final boolean childInline = sibling.isInline(child, context());
                if (childInline && !run) {
                    fo.append("<fo:block>");
                    inlineDepth++;
                    run = true;
                } else if (!childInline && run) {
                    inlineDepth--;
                    fo.append("</fo:block>");
                    run = false;
                }
                visitElement(child);
            }
            if (run) {
                inlineDepth--;
                fo.append("</fo:block>");
            }
            fo.append("</fo:block>");
        }
    }

    @Override
    public void visitText(final Text text) {
        fo.append("<fo:inline");
        styles(text.style());
        fo.append('>').append(bodyText(entities(substitute(text.value())))).append("</fo:inline>");
    }

    private void styles(final List<Text.Style> styles) {
        if (tableHeader) {
            // Column styles apply to the body, not to the repeated header's typography.
            return;
        }
        if (styles.contains(Text.Style.BOLD)) {
            property("font-weight", "bold");
        }
        if (styles.contains(Text.Style.ITALIC) || styles.contains(Text.Style.EMPHASIS)) {
            property("font-style", "italic");
        }
        if (styles.contains(Text.Style.MARK)) {
            property("background-color", theme.background());
        }
        if (styles.contains(Text.Style.STRIKETHROUGH)) {
            property("text-decoration", "line-through");
        }
        if (styles.contains(Text.Style.SUB) || styles.contains(Text.Style.SUP)) {
            property("baseline-shift", styles.contains(Text.Style.SUP) ? "super" : "sub");
            property("font-size", "75%");
        }
    }

    private void inline(final Element element) {
        inlineDepth++;
        // A normal paragraph in a heading/link is an inline container, not a styled span.
        if (element instanceof Paragraph paragraph) {
            final var id = sibling.id(paragraph.options());
            if (id != null) {
                fo.append("<fo:inline");
                destination(substitute(id));
                fo.append('>');
            }
            visitParagraph(paragraph);
            if (id != null) {
                fo.append("</fo:inline>");
            }
        } else {
            visitElement(element);
        }
        inlineDepth--;
    }

    @Override
    public void visitLineBreak(final LineBreak lineBreak) {
        fo.append("<fo:inline linefeed-treatment=\"preserve\" white-space-collapse=\"false\">&#10;</fo:inline>");
    }

    @Override
    public void visitCode(final Code code) {
        if (code.inline()) {
            fo.append("<fo:inline");
            if (!tableHeader) {
                property("font-family", theme.mono());
                property("font-size", "90%");
                if (tableCellDepth == 0) {
                    property("color", metadata("pdf-inline-code-color", "#7D2493"));
                    property("background-color", metadata("pdf-inline-code-background", "#F7EDFC"));
                    property("border", "0.5pt solid " + metadata("pdf-inline-code-border-color", "#E9C7F3"));
                }
            }
            fo.append('>').append(bodyText(code.value())).append("</fo:inline>");
            return;
        }
        literalStart();
        if (syntaxHighlighting) {
            highlightedCode(code);
        } else {
            plainCode(code);
        }
        fo.append("</fo:block>");
        final var descriptions = code.callOuts();
        if (!descriptions.isEmpty()) {
            fo.append("<fo:list-block provisional-distance-between-starts=\"24pt\" provisional-label-separation=\"6pt\" space-after=\"7pt\">");
            for (final var callout : descriptions) {
                fo.append("<fo:list-item space-after=\"3pt\"><fo:list-item-label end-indent=\"label-end()\"><fo:block font-weight=\"bold\">(")
                        .append(callout.number()).append(")</fo:block></fo:list-item-label>")
                        .append("<fo:list-item-body start-indent=\"body-start()\"><fo:block>");
                visitElement(callout.text());
                fo.append("</fo:block></fo:list-item-body></fo:list-item>");
            }
            fo.append("</fo:list-block>");
        }
    }

    private void highlightedCode(final Code code) {
        final var tokens = highlighter().highlight(sibling.language(code.options()), code.value());
        int line = 0;
        for (final var token : tokens) {
            int start = 0;
            int newline;
            while ((newline = token.text().indexOf('\n', start)) >= 0) {
                codeToken(token.kind(), token.text().substring(start, newline));
                callouts(code, line++);
                fo.append('\n');
                start = newline + 1;
            }
            codeToken(token.kind(), token.text().substring(start));
        }
        callouts(code, line);
    }

    private void plainCode(final Code code) {
        final var source = code.value();
        int line = 0;
        int start = 0;
        int newline;
        while ((newline = source.indexOf('\n', start)) >= 0) {
            fo.append(xml(source.substring(start, newline)));
            callouts(code, line++);
            fo.append('\n');
            start = newline + 1;
        }
        fo.append(xml(source.substring(start)));
        callouts(code, line);
    }

    private SyntaxHighlighter highlighter() {
        if (highlighter == null) {
            try {
                highlighter = new SyntaxHighlighter();
            } catch (final NoClassDefFoundError missing) {
                throw new IllegalStateException("PDF syntax highlighting requires io.yupiik.maven:simple-language-tokenizer; " +
                        "add the asciidoc-pdf-dependencies POM dependency", missing);
            }
        }
        return highlighter;
    }

    private void callouts(final Code code, final int line) {
        if (line < code.lineCallOuts().size()) {
            for (final var callout : code.lineCallOuts().get(line)) {
                fo.append(" (").append(callout.number()).append(')');
            }
        }
    }

    private void codeToken(final Kind kind, final String text) {
        if (text.isEmpty()) {
            return;
        }
        if (kind == Kind.PLAIN) {
            fo.append(bodyText(text));
            return;
        }
        final var color = switch (kind) {
            case KEYWORD, NUMBER -> theme.primary();
            case STRING, ANNOTATION -> "#46F72F";
            case COMMENT -> "#B8B8B8";
            case TAG, TYPE, ATTRIBUTE -> "#02ABEA";
            default -> metadata("pdf-code-text-color", "#F4F4F4");
        };
        fo.append("<fo:inline");
        property("color", metadata("pdf-code-" + kind.name().toLowerCase(Locale.ROOT) + "-color", color));
        fo.append('>').append(bodyText(text)).append("</fo:inline>");
    }

    @Override
    public void visitListing(final Listing listing) {
        if (tableHeader) {
            fo.append("<fo:inline>").append(bodyText(listing.value())).append("</fo:inline>");
            return;
        }
        literalStart();
        fo.append(bodyText(listing.value())).append("</fo:block>");
    }

    private void literalStart() {
        fo.append("<fo:block font-size=\"8.5pt\" line-height=\"1.25\" padding=\"7pt\" space-before=\"4pt\" space-after=\"7pt\""
                + " space-before.conditionality=\"retain\" space-after.conditionality=\"retain\""
                + " white-space-collapse=\"false\" white-space-treatment=\"preserve\" linefeed-treatment=\"preserve\" wrap-option=\"wrap\"");
        property("font-family", theme.mono());
        property("color", metadata("pdf-code-text-color", "#F4F4F4"));
        property("background-color", metadata("pdf-code-background", "#212121"));
        fo.append('>');
    }

    @Override
    public void visitUnOrderedList(final UnOrderedList list) {
        list(list.children(), null);
    }

    @Override
    public void visitOrderedList(final OrderedList list) {
        final var start = sibling.listStart(list.options());
        list(list.children(), start == null ? 1 : start);
    }

    private void list(final List<Element> children, final Integer start) {
        if (children.isEmpty()) {
            return;
        }
        fo.append("<fo:list-block provisional-distance-between-starts=\"24pt\" provisional-label-separation=\"6pt\" space-after=\"7pt\">");
        int number = start == null ? 1 : start;
        for (final var child : children) {
            fo.append("<fo:list-item><fo:list-item-label end-indent=\"label-end()\"><fo:block>");
            if (sibling.isChecklistItem(child)) {
                fo.append(sibling.isChecked(child) ? "[x]" : "[ ]");
            } else {
                fo.append(start == null ? "&#8226;" : number++ + ".");
            }
            fo.append("</fo:block></fo:list-item-label><fo:list-item-body start-indent=\"body-start()\"><fo:block>");
            visitElement(child);
            fo.append("</fo:block></fo:list-item-body></fo:list-item>");
        }
        fo.append("</fo:list-block>");
    }

    @Override
    public void visitDescriptionList(final DescriptionList list) {
        list.children().forEach((term, description) -> {
            fo.append("<fo:block font-weight=\"bold\" keep-with-next=\"always\">");
            inline(term);
            fo.append("</fo:block><fo:block start-indent=\"14pt\" space-after=\"6pt\">");
            if (description != null) {
                visitElement(description);
            }
            fo.append("</fo:block>");
        });
    }

    @Override
    public void visitAdmonition(final Admonition admonition) {
        fo.append("<fo:block padding=\"8pt\" space-before=\"6pt\" space-after=\"8pt\"");
        property("border-left", "3pt solid " + theme.primary());
        property("background-color", theme.background());
        fo.append("><fo:block font-weight=\"bold\" keep-with-next=\"always\">")
                .append(xml(admonition.level().name())).append("</fo:block>");
        visitElement(admonition.content());
        fo.append("</fo:block>");
    }

    @Override
    public void visitOpenBlock(final OpenBlock block) {
        fo.append("<fo:block>");
        block.children().forEach(this::visitElement);
        fo.append("</fo:block>");
    }

    @Override
    public void visitQuote(final Quote quote) {
        fo.append("<fo:block start-indent=\"12pt\" padding-left=\"8pt\" font-style=\"italic\" space-after=\"8pt\"");
        property("border-left", "2pt solid " + theme.border());
        fo.append('>');
        quote.children().forEach(this::visitElement);
        final var attribution = quote.options().get("attribution");
        final var citation = quote.options().get("citetitle");
        if (attribution != null || citation != null) {
            fo.append("<fo:block text-align=\"right\" font-size=\"9pt\">-- ")
                    .append(xml(substitute(attribution == null ? "" : attribution)));
            if (citation != null) {
                fo.append(attribution == null ? "" : ", ").append(xml(substitute(citation)));
            }
            fo.append("</fo:block>");
        }
        fo.append("</fo:block>");
    }

    @Override
    public void visitTable(final Table table) {
        if (table.elements().isEmpty()) {
            fo.append("<fo:block/>");
            return;
        }
        final int columns = table.elements().get(0).size();
        if (columns == 0 || table.elements().stream().anyMatch(row -> row.size() != columns)) {
            throw unsupported(table, "empty or ragged table rows");
        }
        fo.append("<fo:table table-layout=\"fixed\" border-collapse=\"collapse\" space-after=\"9pt\"");
        if (table.elements().size() > 2) {
            property("font-size", "9.5pt");
        }
        final var width = substitute(table.options().getOrDefault("width", "100%")).strip();
        if (!LENGTH.matcher(width).matches()) {
            throw unsupported(table, "table width '" + width + "'");
        }
        property("width", Character.isDigit(width.charAt(width.length() - 1)) ? width + "%" : width);
        fo.append('>');
        for (int i = 0; i < columns; i++) {
            final var column = table.column(i);
            fo.append("<fo:table-column");
            property("column-width", "proportional-column-width(" + (column == null ? 1 : Math.max(1, column.width())) + ")");
            fo.append("/>");
        }
        final boolean header = sibling.hasOption(table.options(), "header") && !sibling.hasOption(table.options(), "noheader");
        int firstBody = 0;
        if (header) {
            fo.append("<fo:table-header>");
            tableRow(table, 0, true);
            fo.append("</fo:table-header>");
            firstBody = 1;
        }
        fo.append("<fo:table-body>");
        if (firstBody == table.elements().size()) {
            // FO requires a non-empty table body, even for an AsciiDoc table with just its header.
            fo.append("<fo:table-row><fo:table-cell");
            property("number-columns-spanned", Integer.toString(columns));
            fo.append("><fo:block/></fo:table-cell></fo:table-row>");
        }
        for (int i = firstBody; i < table.elements().size(); i++) {
            tableRow(table, i, false);
        }
        fo.append("</fo:table-body></fo:table>");
    }

    private void tableRow(final Table table, final int index, final boolean header) {
        fo.append("<fo:table-row>");
        final var row = table.elements().get(index);
        for (int i = 0; i < row.size(); i++) {
            final var column = table.column(i);
            fo.append("<fo:table-cell padding=\"5pt\"");
            property("border", "0.5pt solid " + theme.border());
            if (header) {
                property("background-color", theme.background());
                property("font-weight", "bold");
                property("font-family", theme.sans());
            }
            if (column != null && column.valign() != null) {
                property("display-align", switch (column.valign()) {
                    case "middle" -> "center";
                    case "bottom" -> "after";
                    default -> "before";
                });
            }
            fo.append("><fo:block start-indent=\"0pt\" end-indent=\"0pt\" wrap-option=\"wrap\"");
            if (column != null && column.halign() != null) {
                property("text-align", column.halign());
            }
            fo.append('>');
            tableCellDepth++;
            final boolean previousHeader = tableHeader;
            tableHeader = header;
            try {
                visitElement(row.get(i));
            } finally {
                tableCellDepth--;
                tableHeader = previousHeader;
            }
            fo.append("</fo:block></fo:table-cell>");
        }
        fo.append("</fo:table-row>");
    }

    @Override
    public void visitPageBreak(final PageBreak pageBreak) {
        fo.append("<fo:block break-before=\"page\"/>");
    }

    @Override
    public void visitHorizontalRule(final HorizontalRule rule) {
        fo.append("<fo:block space-before=\"8pt\" space-after=\"8pt\"");
        property("border-bottom", "0.5pt solid " + theme.border());
        fo.append("/>");
    }

    @Override
    public void visitAnchor(final Anchor anchor) {
        final var reference = sibling.shorthandCrossReference(substitute(anchor.value()).strip(), state.asciidocExtensions());
        if (reference.id() == null) {
            crossReference(substitute(anchor.value()).strip(), anchor.label());
        } else {
            crossReference("#" + reference.id(), anchor.label());
        }
    }

    @Override
    public void visitLink(final Link link) {
        final var target = substitute(link.url());
        fo.append("<fo:basic-link");
        property("color", theme.secondary());
        property("text-decoration", "underline");
        if (target.startsWith("#")) {
            final var id = localDestination(target.substring(1));
            references.add(id);
            property("internal-destination", id);
        } else {
            property("external-destination", target);
        }
        fo.append('>');
        if (link.label() == null) {
            fo.append(bodyText(target));
        } else {
            inline(link.label());
        }
        fo.append("</fo:basic-link>");
    }

    private void crossReference(final String target, final String label) {
        final var resolved = substitute(target).strip();
        final var localId = sibling.crossReferenceId(resolved);
        final String id;
        if (localId != null) {
            id = localDestination(localId);
        } else {
            final var reference = sibling.crossReference(resolved, state.asciidocExtensions());
            final var document = includedDocument(reference.file());
            if (document == null) {
                throw new IllegalArgumentException("Unsupported AsciiDoc PDF cross-document reference: " + target);
            }
            id = reference.fragment().isEmpty() ? document.id() : document.anchors().get(reference.fragment());
            if (id == null) {
                throw new IllegalArgumentException("Unresolved AsciiDoc PDF cross-document reference: " + target);
            }
        }
        references.add(id);
        final var referenceText = referenceTexts.get(id);
        fo.append("<fo:basic-link");
        property("color", theme.secondary());
        property("text-decoration", "underline");
        property("internal-destination", id);
        fo.append('>').append(bodyText(substitute(label == null || label.isBlank()
                ? referenceText == null ? "[" + id + "]" : referenceText : label))).append("</fo:basic-link>");
    }

    @Override
    public void visitMacro(final Macro macro) {
        switch (macro.name()) {
            case "xref" -> crossReference(macro.label(), macro.options().get(""));
            case "image" -> image(macro);
            case "toc" -> {
                if (inlineDepth > 0) {
                    throw unsupported(macro, "inline table of contents");
                }
                toc(macro.options().get("levels"));
            }
            default -> {
                switch (unknownMacro()) {
                    case FAIL -> throw new IllegalArgumentException(
                            "Unknown macro '" + macro.name() + "' in '" + sibling.macroSource(macro) + "', set the attribute '" +
                                    UNKNOWN_MACRO_ATTRIBUTE + "' or PdfConfiguration.setUnknownMacro() to 'text' or 'ignore' to render it");
                    case IGNORE -> {
                        // nothing to write
                    }
                    case TEXT -> {
                        if (!macro.inline()) {
                            fo.append("<fo:block space-after=\"7pt\">");
                        }
                        fo.append(xml(sibling.macroSource(macro)));
                        if (!macro.inline()) {
                            fo.append("</fo:block>");
                        }
                    }
                }
            }
        }
    }

    private UnknownMacro unknownMacro() {
        return sibling.unknownMacro(UNKNOWN_MACRO_ATTRIBUTE, context(), configuration.getUnknownMacro());
    }

    private void image(final Macro macro) {
        final String source;
        if (configuration.getResolver() != null) {
            final var resolved = new Macro(macro.name(), substitute(macro.label()), macro.options(), macro.inline());
            final var target = substitute(sibling.imageTarget(resolved, context()));
            source = configuration.getResolver().apply(target).base64();
        } else {
            final var resolved = new Macro(macro.name(), substitute(macro.label()), macro.options(), macro.inline());
            final var target = substitute(sibling.imageTarget(resolved, context()));
            if (target.startsWith("data:")) {
                source = embeddedImage(target);
            } else if (target.startsWith("classpath:")) {
                source = classpathImage(target.substring("classpath:".length()));
            } else if (target.startsWith("jar:")) {
                final var uri = URI.create(target);
                final var nested = uri.getRawSchemeSpecificPart();
                final int separator = nested.indexOf("!/");
                if (separator < 0) {
                    throw new IllegalArgumentException("Invalid AsciiDoc PDF JAR image: " + target);
                }
                // A JAR URL must not bypass the default prohibition on remote images.
                localPath(nested.substring(0, separator));
                source = uri.toASCIIString();
            } else {
                final var path = localPath(target);
                final var absolute = baseDirectory.resolve(path).normalize();
                if (Files.isRegularFile(absolute)) {
                    source = absolute.toUri().toASCIIString();
                } else {
                    source = classpathImage(target);
                }
            }
        }
        if (!macro.inline()) {
            fo.append("<fo:block space-after=\"7pt\">");
        }
        fo.append("<fo:external-graphic");
        property("src", source);
        property("scaling", "uniform");
        final var width = macro.options().get("width");
        final var height = macro.options().get("height");
        if (width != null) {
            property("content-width", imageLength(width));
        } else {
            property("content-width", macro.inline() ? "auto" : "scale-down-to-fit");
            if (!macro.inline()) {
                property("width", "100%");
            }
        }
        if (height != null) {
            property("content-height", imageLength(height));
        }
        fo.append("/>");
        if (!macro.inline()) {
            fo.append("</fo:block>");
        }
    }

    private String embeddedImage(final String target) {
        final int comma = target.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("Invalid AsciiDoc PDF image data URI");
        }
        final var metadata = target.substring("data:".length(), comma);
        final int parameter = metadata.indexOf(';');
        final var mimeType = parameter < 0 ? metadata : metadata.substring(0, parameter);
        if (!mimeType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image data URIs are supported by AsciiDoc PDF: " + mimeType);
        }
        final var payload = target.substring(comma + 1);
        final var bytes = metadata.endsWith(";base64") ? Base64.getDecoder().decode(payload) :
                percentDecoded(payload);
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private byte[] percentDecoded(final String payload) {
        final var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < payload.length();) {
            if (payload.charAt(i) == '%') {
                final int high = i + 1 < payload.length() ? Character.digit(payload.charAt(i + 1), 16) : -1;
                final int low = i + 2 < payload.length() ? Character.digit(payload.charAt(i + 2), 16) : -1;
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid percent escape in AsciiDoc PDF image data URI");
                }
                bytes.write((high << 4) | low);
                i += 3;
            } else {
                final int next = payload.indexOf('%', i);
                final int end = next < 0 ? payload.length() : next;
                bytes.writeBytes(payload.substring(i, end).getBytes(StandardCharsets.UTF_8));
                i = end;
            }
        }
        return bytes.toByteArray();
    }

    private String classpathImage(final String target) {
        final var name = target.startsWith("/") ? target.substring(1) : target;
        final var resource = configuration.getClassLoader().getResource(name);
        if (resource == null) {
            throw new IllegalArgumentException("AsciiDoc PDF image does not exist on the filesystem or classpath: " + target);
        }
        return resource.toExternalForm();
    }

    private Path localPath(final String value) {
        if (value.startsWith("//") || value.startsWith("\\\\")) {
            throw new IllegalArgumentException("Remote AsciiDoc PDF images are not supported: " + value);
        }
        if (SCHEME.matcher(value).matches()) {
            final var uri = URI.create(value);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getRawAuthority() != null) {
                throw new IllegalArgumentException("Only local AsciiDoc PDF images are supported: " + value);
            }
            return Path.of(uri);
        }
        return Path.of(value);
    }

    private String imageLength(final String raw) {
        final var value = substitute(raw).strip();
        if (!LENGTH.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsupported AsciiDoc PDF image dimension: " + raw);
        }
        return Character.isDigit(value.charAt(value.length() - 1)) ? value + "px" : value;
    }

    @Override
    public void visitPassthroughBlock(final PassthroughBlock block) {
        throw unsupported(block, "passthrough block");
    }

    private void destination(final String id) {
        if (id.isBlank() || !ids.add(id)) {
            throw new IllegalArgumentException("Empty or duplicate AsciiDoc PDF destination: " + id);
        }
        property("id", id);
    }

    private String substitute(final String value) {
        return sibling.substitute(value, context());
    }

    private void property(final String name, final String value) {
        fo.append(' ').append(name).append("=\"").append(xml(value)).append('"');
    }

    private String bodyText(final String text) {
        if (tableCellDepth == 0) {
            return xml(text);
        }
        // FOP cannot wrap long identifiers/URLs at a cell boundary without discretionary breaks.
        // A zero-width space provides a break without a visible hyphen; \X keeps graphemes intact.
        // Some monospace fonts give the invisible glyph a fallback advance in FOP, so force its size to zero.
        final var out = new StringBuilder(text.length());
        final var characters = GRAPHEME.matcher(text);
        while (characters.find()) {
            final var character = characters.group();
            out.append(xml(character));
            if (!Character.isWhitespace(character.codePointAt(0)) && characters.end() < text.length() &&
                    !Character.isWhitespace(text.codePointAt(characters.end()))) {
                out.append("<fo:inline font-size=\"0pt\">&#x200B;</fo:inline>");
            }
        }
        return out.toString();
    }

    private static IllegalArgumentException unsupported(final Element element, final String detail) {
        return new IllegalArgumentException("Unsupported AsciiDoc PDF " + detail + ": " + element);
    }

    private static String entities(final String text) {
        return ENTITIES.matcher(text).replaceAll(match -> {
            final var entity = match.group(1);
            final String decoded;
            switch (entity) {
                case "amp": decoded = "&"; break;
                case "lt": decoded = "<"; break;
                case "gt": decoded = ">"; break;
                case "quot": decoded = "\""; break;
                case "apos": decoded = "'"; break;
                case "nbsp": decoded = "\u00a0"; break;
                case "vert": decoded = "|"; break;
                case "lbrack": decoded = "["; break;
                case "rbrack": decoded = "]"; break;
                case "num": decoded = "#"; break;
                default:
                    try {
                        final int point = Integer.parseInt(entity.substring(entity.startsWith("#x") ? 2 : 1), entity.startsWith("#x") ? 16 : 10);
                        decoded = new String(Character.toChars(point));
                    } catch (final IllegalArgumentException invalid) {
                        return Matcher.quoteReplacement(match.group());
                    }
            }
            return Matcher.quoteReplacement(decoded);
        });
    }

    private static String xml(final String text) {
        Objects.requireNonNull(text, "XML value");
        final var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            final int point = text.codePointAt(i);
            i += Character.charCount(point);
            if (!(point == 9 || point == 10 || point == 13 || point >= 32 && point <= 0xD7FF
                    || point >= 0xE000 && point <= 0xFFFD || point >= 0x10000 && point <= 0x10FFFF)) {
                throw new IllegalArgumentException("Invalid XML character in AsciiDoc PDF: " + point);
            }
            switch (point) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                case '\n': out.append("&#10;"); break;
                case '\r': out.append("&#13;"); break;
                case '\t': out.append("&#9;"); break;
                default: out.appendCodePoint(point);
            }
        }
        return out.toString();
    }
}
