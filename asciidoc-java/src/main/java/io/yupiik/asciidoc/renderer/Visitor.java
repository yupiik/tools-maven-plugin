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

import io.yupiik.asciidoc.model.Admonition;
import io.yupiik.asciidoc.model.Anchor;
import io.yupiik.asciidoc.model.Attribute;
import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.ConditionalBlock;
import io.yupiik.asciidoc.model.DescriptionList;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Header;
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

import java.util.Collection;

/**
 * Basic API to visit a document, it is not required to use it but convenient by multiple methods.
 * It also enables to decorate it with another visitor handling transversal converns (replacements for ex).
 *
 * @param <R> the type of result this visitor computes.
 */
public interface Visitor<R> {
    default void visit(final Document document) {
        visitHeader(document.header());
        visitBody(document.body());
    }

    default void visitHeader(final Header header) {
        if (!header.title().isBlank()) {
            visitTitle(header.title());
        }
        if (!header.author().name().isBlank()) {
            visitAuthor(header.author().name(), header.author().mail());
        }
        if (!header.revision().number().isBlank()) {
            visitRevision(header.revision().number(), header.revision().date(), header.revision().revmark());
        }
    }

    default void visitBody(final Body body) {
        body.children().forEach(this::visitElement);
    }

    default void visitElement(final Element element) {
        switch (element.type()) {
            case PASS_BLOCK -> visitPassthroughBlock((PassthroughBlock) element);
            case OPEN_BLOCK -> visitOpenBlock((OpenBlock) element);
            case QUOTE -> visitQuote((Quote) element);
            case TABLE -> visitTable((Table) element);
            case ANCHOR -> visitAnchor((Anchor) element);
            case ADMONITION -> visitAdmonition((Admonition) element);
            case MACRO -> visitMacro((Macro) element);
            case DESCRIPTION_LIST -> visitDescriptionList((DescriptionList) element);
            case ORDERED_LIST -> visitOrderedList((OrderedList) element);
            case UNORDERED_LIST -> visitUnOrderedList((UnOrderedList) element);
            case LINK -> visitLink((Link) element);
            case CODE -> visitCode((Code) element);
            case TEXT -> visitText((Text) element);
            case LINE_BREAK -> visitLineBreak((LineBreak) element);
            case PAGE_BREAK -> visitPageBreak((PageBreak) element);
            case PARAGRAPH -> visitParagraph((Paragraph) element);
            case SECTION -> visitSection((Section) element);
            case CONDITIONAL_BLOCK -> visitConditionalBlock((ConditionalBlock) element);
            case ATTRIBUTE -> visitAttribute((Attribute) element);
            case LISTING -> visitListing((Listing) element);
        }
    }

    default void visitListing(Listing element) {
        // no-op
    }

    default void visitPageBreak(final PageBreak element) {
        // no-op
    }

    default void visitAttribute(final Attribute element) {
        final var attribute = context().attribute(element.attribute());
        if (attribute != null) {
            element.evaluator().apply(attribute).forEach(this::visitElement);
        }
    }

    default void visitConditionalBlock(final ConditionalBlock element) {
        if (element.evaluator().test(context())) {
            element.children().forEach(this::visitElement);
        }
    }

    default ConditionalBlock.Context context() {
        return key -> null;
    }

    default void visitSection(final Section element) {
        element.children().forEach(this::visitElement);
    }

    default void visitParagraph(final Paragraph element) {
        element.children().forEach(this::visitElement);
    }

    default void visitLineBreak(final LineBreak element) {
        // no-op
    }

    default void visitText(final Text element) {
        // no-op
    }

    default void visitCode(final Code element) {
        // no-op
    }

    default void visitLink(final Link element) {
        // no-op
    }

    default void visitUnOrderedList(final UnOrderedList element) {
        element.children().forEach(this::visitElement);
    }

    default void visitOrderedList(final OrderedList element) {
        element.children().forEach(this::visitElement);
    }

    default void visitDescriptionList(final DescriptionList element) {
        element.children().forEach((key, value) -> {
            visitElement(key);
            visitElement(value);
        });
    }

    default void visitMacro(final Macro element) {
        // no-op
    }

    default void visitAdmonition(final Admonition element) {
        visitElement(element.content());
    }

    default void visitAnchor(final Anchor element) {
        // no-op
    }

    default void visitTable(final Table element) {
        element.elements().stream()
                .flatMap(Collection::stream)
                .forEach(this::visitElement);
    }

    default void visitQuote(final Quote element) {
        element.children().forEach(this::visitElement);
    }

    default void visitOpenBlock(final OpenBlock element) {
        element.children().forEach(this::visitElement);
    }

    default void visitPassthroughBlock(final PassthroughBlock element) {
        // no-op
    }

    default void visitRevision(final String number, final String date, final String remark) {
        // no-op
    }

    default void visitAuthor(final String name, final String mail) {
        // no-op
    }

    default void visitTitle(final String title) {
        // no-op
    }

    default R result() {
        return null;
    }
}
