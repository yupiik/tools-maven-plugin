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
package io.yupiik.language.tokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure token transformations; no source evaluation, execution, or caching. */
public final class Tokenizing {
    private Tokenizing() {
        // utility class
    }

    /**
     * Splits tokens into immutable lines, removing line terminators while
     * preserving the kind of every remaining fragment. CRLF is one newline,
     * even across token boundaries (and intervening empty tokens); lone CR and
     * LF are also newlines. Empty input yields one empty line and a trailing
     * terminator yields an extra empty line. Empty fragments are omitted;
     * adjacent fragments are not coalesced, even if they have the same kind.
     * Neither the input list nor its tokens are modified.
     *
     * @param tokens non-null tokens in source order, with no null entries
     * @return immutable outer list containing immutable line lists
     * @throws NullPointerException if the list or any entry is null
     */
    public static List<List<Token>> lines(final List<Token> tokens) {
        Objects.requireNonNull(tokens, "tokens");
        final var lines = new ArrayList<List<Token>>();
        final var line = new ArrayList<Token>();
        var afterCr = false;
        for (final var token : tokens) {
            final var text = token.text();
            var start = 0;
            for (var i = 0; i < text.length(); i++) {
                final var c = text.charAt(i);
                if (afterCr) {
                    afterCr = false;
                    if (c == '\n') {
                        start = i + 1;
                        continue;
                    }
                }
                if (c == '\r' || c == '\n') {
                    if (start < i) {
                        line.add(new Token(token.kind(), text.substring(start, i)));
                    }
                    lines.add(List.copyOf(line));
                    line.clear();
                    start = i + 1;
                    afterCr = c == '\r';
                }
            }
            if (start < text.length()) {
                line.add(new Token(token.kind(), text.substring(start)));
            }
        }
        lines.add(List.copyOf(line));
        return List.copyOf(lines);
    }
}
