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
package io.yupiik.asciidoc.renderer;

import io.yupiik.asciidoc.model.Anchor;
import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.DescriptionList;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.FloatingTitle;
import io.yupiik.asciidoc.model.Header;
import io.yupiik.asciidoc.model.Link;
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.Revision;
import io.yupiik.asciidoc.model.Section;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of a rendering, kept by a visitor the renderer delegates to: {@link #visit(Document)} starts a document,
 * {@link #visitBody(Body)} indexes its body, and the renderer queries the result while it writes its output.
 * <p>
 * It holds the document and its attributes, the title of each section by id, the ids the document links to, the
 * sections a table of contents lists, and the footnotes met so far. The index is a first walk over the body, since a
 * link can point at a section written later. Before any {@link #visit(Document)}, the document is empty, so a renderer
 * visiting only a body reads the fallback attributes.
 * <p>
 * Like the renderers, it is not thread safe.
 */
public class VisitorState implements Visitor<Void> {
    protected static final Document EMPTY_DOCUMENT = new Document(
            new Header("", List.of(), new Revision("", "", ""), Map.of()), new Body(List.of()));

    protected final VisitorSibling sibling;
    protected final ConditionalBlock.Context attributes; // document attributes first, then the fallback ones
    protected Document document = EMPTY_DOCUMENT;
    protected final Map<String, String> referenceTexts = new HashMap<>(); // by id, see referenceText(String)
    protected final Set<String> referencedIds = new HashSet<>();
    protected final List<TocSection> tocSections = new ArrayList<>(); // in document order, following the rendered conditional branches
    protected List<String> asciidocExtensions; // read from the attributes on first use, see asciidocExtensions()
    protected final List<Footnote> footnotes = new ArrayList<>(); // definitions, in order
    protected final Map<String, Footnote> footnotesById = new HashMap<>();
    protected final Set<String> footnoteLabels = new HashSet<>();
    protected int anonymousFootnotes;

    /**
     * @param sibling  the readings the index uses (ids, titles, cross reference targets).
     * @param fallback the attributes resolved when the document does not define them, such as the configuration ones.
     */
    public VisitorState(final VisitorSibling sibling, final ConditionalBlock.Context fallback) {
        this.sibling = sibling;
        this.attributes = key -> {
            final var header = document().header();
            final var value = header == null || header.attributes() == null ? null : header.attributes().get(key);
            return value != null ? value : fallback.attribute(key);
        };
    }

    /**
     * Starts a document: forgets the previous one, its index and its footnotes. It does not index the body, the
     * renderer calls {@link #visitBody(Body)} for that.
     */
    @Override
    public void visit(final Document document) {
        this.document = document;
        referenceTexts.clear();
        referencedIds.clear();
        tocSections.clear();
        asciidocExtensions = null;
        resetFootnotes();
    }

    /**
     * Forgets the footnotes, as a new document does.
     */
    protected void resetFootnotes() {
        footnotes.clear();
        footnotesById.clear();
        footnoteLabels.clear();
        anonymousFootnotes = 0;
    }

    /**
     * Indexes the body in one walk, following only the conditional branches that are rendered: the ids the document
     * links to, with {@code <<id>>} or {@code xref:id[]}, the text of a cross reference to each id, see
     * {@link #referenceText(String)}, and the sections a table of contents lists. The sections the table of contents
     * lists count as linked, since it links them.
     */
    @Override
    public void visitBody(final Body body) {
        referenceTexts.clear();
        referencedIds.clear();
        tocSections.clear();
        final var index = new Index();
        index.visitBody(body);
        final var placement = sibling.tocPlacement(context());
        if (placement != null) {
            final int levels = "macro".equals(placement) ? index.tocMacroLevels : sibling.tocLevels(null, context());
            for (final var section : tocSections) {
                if (section.level() <= levels) {
                    referencedIds.add(section.id());
                }
            }
        }
    }

    /**
     * @return the attributes of the document, then the fallback ones.
     */
    public ConditionalBlock.Context attributes() {
        return attributes;
    }

    /**
     * @return the attributes the state reads: {@link #attributes()}, unless a subclass reads them elsewhere, as a
     * renderer whose own context is overridden does.
     */
    @Override
    public ConditionalBlock.Context context() {
        return attributes;
    }

    /**
     * @return the document being rendered, an empty one before {@link #visit(Document)}; a renderer that keeps the
     * document elsewhere returns it from an override, the attributes are read from it.
     */
    public Document document() {
        return document;
    }

    /**
     * @return the value of the attribute in {@link #context()}, or the default value.
     */
    public String attribute(final String key, final String defaultValue) {
        final var value = context().attribute(key);
        return value == null ? defaultValue : value;
    }

    /**
     * @return the text of a cross reference to this id that gives no text of its own, on one line: the
     * {@code reftext} of the element, else the title of a section or of a floating title, else the title of the block;
     * {@code null} when the element has none of them, the renderer then writing the id between square brackets as
     * asciidoctor does.
     */
    public String referenceText(final String id) {
        return referenceTexts.get(id);
    }

    /**
     * @return true when the document links to this id, or lists it in its table of contents.
     */
    public boolean isReferenced(final String id) {
        return referencedIds.contains(id);
    }

    /**
     * @return the sections a table of contents can list, in document order.
     */
    public List<TocSection> tocSections() {
        return Collections.unmodifiableList(tocSections);
    }

    /**
     * @return the extensions of the AsciiDoc documents of this document, read once, see
     * {@link VisitorSibling#asciidocExtensions(ConditionalBlock.Context)}.
     */
    public List<String> asciidocExtensions() {
        if (asciidocExtensions == null) {
            asciidocExtensions = sibling.asciidocExtensions(context());
        }
        return asciidocExtensions;
    }

    /**
     * Registers a footnote macro. An anonymous footnote is a new footnote. The first use of an id creates the footnote,
     * its text coming with that use or a later one; the other uses refer to it.
     *
     * @param id   the id of the footnote, empty for an anonymous one, see {@link VisitorSibling#footnoteId(Macro)}.
     * @param text its text, empty for a reference, see {@link VisitorSibling#footnoteText(Macro)}.
     * @return the footnote.
     */
    public Footnote footnote(final String id, final String text) {
        if (id.isEmpty()) {
            String label;
            do {
                label = Integer.toString(++anonymousFootnotes);
            } while (!footnoteLabels.add(label));
            final var footnote = new Footnote(null, label, null);
            define(footnote, text);
            return footnote;
        }
        var footnote = footnotesById.get(id);
        if (footnote == null) { // first use of the id, a definition or a reference to a later definition
            var label = id;
            for (int i = 1; !footnoteLabels.add(label); i++) {
                label = id + "-" + i;
            }
            footnote = new Footnote(id, label, null);
            footnotesById.put(id, footnote);
        }
        if (footnote.text == null && !text.isEmpty()) { // later uses of the id only refer to it
            define(footnote, text);
        }
        return footnote;
    }

    /**
     * @return the footnote this id was used for, {@code null} when the document did not use it yet.
     */
    public Footnote footnote(final String id) {
        return footnotesById.get(id);
    }

    private void define(final Footnote footnote, final String text) {
        footnote.text = text;
        footnotes.add(footnote);
        footnote.index = footnotes.size();
    }

    /**
     * @return the footnotes with a text, in the order their text was met.
     */
    public List<Footnote> footnotes() {
        return Collections.unmodifiableList(footnotes);
    }

    /**
     * A section a table of contents can list.
     *
     * @param level its asciidoctor level, 1 for {@code ==}.
     * @param id    its explicit or generated id.
     * @param title its title on one line, see {@link VisitorSibling#titleText(Element, ConditionalBlock.Context)}.
     */
    public record TocSection(int level, String id, String title) {
    }

    /**
     * A footnote, as asciidoctor keeps it: its index in the order of the definitions, its id and its text, plus a label
     * unique in the document (the id, or the number of an anonymous footnote, suffixed with {@code -1}, {@code -2}...
     * when another footnote already uses it).
     */
    public static class Footnote {
        private final String id;
        private final String label;
        private String text;
        private int index;

        protected Footnote(final String id, final String label, final String text) {
            this.id = id;
            this.label = label;
            this.text = text;
        }

        /**
         * @return the position of the footnote in {@link VisitorState#footnotes()}, from 1; 0 while it has no text.
         */
        public int index() {
            return index;
        }

        /**
         * @return the id of the footnote, {@code null} for an anonymous one.
         */
        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /**
         * @return the text of the footnote, {@code null} while it is only referenced.
         */
        public String text() {
            return text;
        }
    }

    // walks the body once, separately from the state's own visit methods, so a renderer can delegate element visits to the state
    private final class Index implements Visitor<Void> {
        private int tocMacroLevels;

        @Override
        public ConditionalBlock.Context context() {
            return VisitorState.this.context();
        }

        @Override
        public void visitElement(final Element element) {
            final var id = sibling.id(sibling.blockOptions(element));
            if (id != null) {
                final var text = sibling.referenceText(element);
                if (text != null && !text.isBlank()) {
                    referenceTexts.putIfAbsent(id, text);
                }
            }
            Visitor.super.visitElement(element);
        }

        @Override
        public void visitAnchor(final Anchor element) {
            if (element.value() != null && !element.value().isBlank()) {
                referencedIds.add(element.value().strip());
            }
        }

        @Override
        public void visitMacro(final Macro element) {
            if ("xref".equals(element.name()) && element.label() != null) {
                final var id = sibling.crossReferenceId(sibling.substitute(element.label(), context()).strip());
                if (id != null) {
                    referencedIds.add(id);
                }
            } else if ("toc".equals(element.name())) {
                tocMacroLevels = Math.max(tocMacroLevels, sibling.tocLevels(element.options() == null ? null : element.options().get("levels"), context()));
            }
        }

        @Override
        public void visitSection(final Section element) {
            final var id = sibling.sectionId(element.options(), element.title(), context());
            final var title = sibling.titleText(element.title(), context());
            referenceTexts.putIfAbsent(id, title);
            tocSections.add(new TocSection(element.level() - 1, id, title));
            visitElement(element.title());
            Visitor.super.visitSection(element);
        }

        @Override
        public void visitFloatingTitle(final FloatingTitle element) {
            referenceTexts.putIfAbsent(sibling.sectionId(element.options(), element.title(), context()), sibling.titleText(element.title(), context()));
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
            sibling.renderedChildren(element, context()).forEach(this::visitElement);
        }
    }
}
