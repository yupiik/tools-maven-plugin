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
package io.yupiik.asciidoc.model;

public sealed interface Element permits
        Code, DescriptionList, LineBreak, Link, Macro, OrderedList,
        Paragraph, Section, Text, UnOrderedList, Admonition, Anchor,
        Table, Quote, OpenBlock, PassthroughBlock, ConditionalBlock,
        Attribute, PageBreak, Listing {
    ElementType type();

    /**
     * Important: the order of this enum is not guaranteed today so ensure to use {@code ordinal()} in the cases
     * you know working (it is stable per JVM instance).
     */
    enum ElementType {
        // PREAMBLE, // not really supported/needed, if needed it can be detected by checking the paragraphs after first title and before next subtitle
        // EXAMPLE, // not really supported/needed, this is just a custom role
        // VERSE, // not really supported/needed, this is just a custom role
        ATTRIBUTE,
        PARAGRAPH,
        SECTION,
        LINE_BREAK,
        PAGE_BREAK,
        CODE, // including source blocks
        UNORDERED_LIST,
        ORDERED_LIST,
        DESCRIPTION_LIST,
        LINK,
        TEXT,
        LISTING,
        MACRO, // icon, image, audio, video, kbd, btn, menu, doublefootnote, footnote, stem, xref, pass
        ADMONITION,
        ANCHOR,
        TABLE,
        OPEN_BLOCK,
        QUOTE, // TODO: we only support the markdown style quotes
        PASS_BLOCK,
        CONDITIONAL_BLOCK
    }
}
