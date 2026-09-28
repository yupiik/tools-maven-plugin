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

import java.nio.file.Path;

/**
 * Where a block-level element came from, reported to a {@link SourceListener}.
 *
 * @param file      the file the lines were read from, {@code null} when the parser was given a string or a reader without a path.
 * @param startLine first line of the element in that file, 1-based; for list items and table cells, relative to the item or cell.
 * @param endLine   last line of the element, 1-based and inclusive, trailing blank lines excluded.
 * @param source    the raw lines of the element joined with a line feed, before attribute substitution,
 *                  without the attribute list and block title lines preceding the element.
 */
public record SourceSpan(Path file, int startLine, int endLine, String source) {
}
