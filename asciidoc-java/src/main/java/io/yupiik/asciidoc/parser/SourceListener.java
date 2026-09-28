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
package io.yupiik.asciidoc.parser;

import io.yupiik.asciidoc.model.Element;

/**
 * Called by the parser for each block-level element it builds, with the source the element was read from.
 * Register it with {@link Parser.ParserContext#ParserContext(io.yupiik.asciidoc.parser.resolver.ContentResolver, SourceListener)}.
 * Elements are records and compare by value, so a listener that keeps them keys its map by identity.
 */
@FunctionalInterface
public interface SourceListener {
    void onElement(Element element, SourceSpan span);
}
