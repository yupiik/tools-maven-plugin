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
import io.yupiik.asciidoc.model.Macro;
import io.yupiik.asciidoc.model.OpenBlock;
import io.yupiik.asciidoc.model.OrderedList;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.PassthroughBlock;
import io.yupiik.asciidoc.model.Quote;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Table;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.model.UnOrderedList;

/**
 * Simple base class to delegate the visit of a document to a visitor.
 * Enables to override only what you need (ex: value handling).
 *
 * @param <T> the type of result the delegate visitor computes.
 */
public class DelegatingVisitor<T> implements Visitor<T> {
    protected final Visitor<T> delegate;

    public DelegatingVisitor(final Visitor<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void visit(final Document document) {
        delegate.visit(document);
    }

    @Override
    public void visitHeader(final Header header) {
        delegate.visitHeader(header);
    }

    @Override
    public void visitBody(final Body body) {
        delegate.visitBody(body);
    }

    @Override
    public void visitElement(final Element element) {
        delegate.visitElement(element);
    }

    @Override
    public void visitConditionalBlock(final ConditionalBlock element) {
        delegate.visitConditionalBlock(element);
    }

    @Override
    public ConditionalBlock.Context context() {
        return delegate.context();
    }

    @Override
    public void visitSection(final Section element) {
        delegate.visitSection(element);
    }

    @Override
    public void visitParagraph(final Paragraph element) {
        delegate.visitParagraph(element);
    }

    @Override
    public void visitLineBreak(final LineBreak element) {
        delegate.visitLineBreak(element);
    }

    @Override
    public void visitText(final Text element) {
        delegate.visitText(element);
    }

    @Override
    public void visitCode(final Code element) {
        delegate.visitCode(element);
    }

    @Override
    public void visitLink(final Link element) {
        delegate.visitLink(element);
    }

    @Override
    public void visitUnOrderedList(final UnOrderedList element) {
        delegate.visitUnOrderedList(element);
    }

    @Override
    public void visitOrderedList(final OrderedList element) {
        delegate.visitOrderedList(element);
    }

    @Override
    public void visitDescriptionList(final DescriptionList element) {
        delegate.visitDescriptionList(element);
    }

    @Override
    public void visitMacro(final Macro element) {
        delegate.visitMacro(element);
    }

    @Override
    public void visitAdmonition(final Admonition element) {
        delegate.visitAdmonition(element);
    }

    @Override
    public void visitAnchor(final Anchor element) {
        delegate.visitAnchor(element);
    }

    @Override
    public void visitTable(final Table element) {
        delegate.visitTable(element);
    }

    @Override
    public void visitQuote(final Quote element) {
        delegate.visitQuote(element);
    }

    @Override
    public void visitOpenBlock(final OpenBlock element) {
        delegate.visitOpenBlock(element);
    }

    @Override
    public void visitPassthroughBlock(final PassthroughBlock element) {
        delegate.visitPassthroughBlock(element);
    }

    @Override
    public void visitRevision(final String number, final String date, final String remark) {
        delegate.visitRevision(number, date, remark);
    }

    @Override
    public void visitAuthor(final String name, final String mail) {
        delegate.visitAuthor(name, mail);
    }

    @Override
    public void visitAttribute(final Attribute element) {
        delegate.visitAttribute(element);
    }

    @Override
    public void visitTitle(final String title) {
        delegate.visitTitle(title);
    }

    @Override
    public T result() {
        return delegate.result();
    }
}
