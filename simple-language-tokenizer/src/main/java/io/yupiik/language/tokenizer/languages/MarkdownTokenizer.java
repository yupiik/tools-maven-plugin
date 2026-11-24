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
package io.yupiik.language.tokenizer.languages;

import io.yupiik.language.tokenizer.Kind;
import io.yupiik.language.tokenizer.Token;

import java.util.List;

/**
 * Lexical Markdown subset: headings, list/table markers, emphasis, code and simple links.
 * Inline constructs must fit in a 512-character window; longer candidates stay plain.
 * Each candidate searches only that constant window, and failed delimiter runs are consumed once.
 * Tokenization is O(n * 512), with O(n) output, including repeated unmatched brackets and varying
 * backtick runs. Whole-line headings and opaque fences have no size limit.
 */
public final class MarkdownTokenizer extends LineTokenizerSupport {
    @Override
    public List<Token> tokenize(final String source) {
        final var buffer = new Buffer(source);
        var fence = '\0';
        var fenceLength = 0;
        var line = 0;
        while (line < source.length()) {
            final var end = lineEnd(source, line);
            final var next = nextLine(source, end);
            final var start = spaces(source, line, end);
            final var trimmed = trimEnd(source, start, end);
            if (fence != '\0') {
                final var run = start < end && source.charAt(start) == fence ? runEnd(source, start, end) : start;
                if (start - line <= 3 && run - start >= fenceLength && run == trimmed) {
                    buffer.mark(start, end, Kind.KEYWORD);
                    fence = '\0';
                } else {
                    // Include line endings: fenced contents remain completely opaque, even across blank lines.
                    buffer.mark(line, next, Kind.STRING);
                }
            } else if (start - line <= 3 && opensFence(source, start, end)) {
                fence = source.charAt(start);
                fenceLength = runEnd(source, start, end) - start;
                buffer.mark(start, end, Kind.KEYWORD);
            } else if (start < end && start - line <= 3 && source.charAt(start) == '#' &&
                    runEnd(source, start, end) - start <= 6 &&
                    (runEnd(source, start, end) == end || horizontal(source.charAt(runEnd(source, start, end))))) {
                buffer.mark(start, end, Kind.KEYWORD);
            } else {
                inline(source, line, end, buffer);
            }
            line = next;
        }
        return buffer.finish();
    }

    private boolean opensFence(final String source, final int start, final int end) {
        if (start == end || source.charAt(start) != '`' && source.charAt(start) != '~') {
            return false;
        }
        final var run = runEnd(source, start, end);
        if (run - start < 3) {
            return false;
        }
        if (source.charAt(start) == '`') {
            for (var i = run; i < end; i++) {
                if (source.charAt(i) == '`') {
                    return false;
                }
            }
        }
        return true;
    }

    private void inline(final String source, final int start, final int end, final Buffer buffer) {
        var i = start;
        final var first = spaces(source, start, end);
        final var table = first < end && source.charAt(first) == '|';
        if (first + 1 < end && (source.charAt(first) == '-' || source.charAt(first) == '+' || source.charAt(first) == '*') &&
                horizontal(source.charAt(first + 1))) {
            buffer.mark(first, first + 1, Kind.KEYWORD);
            i = first + 1;
        } else if (first < end && source.charAt(first) == '-' &&
                runEnd(source, first, end) == trimEnd(source, first, end) && runEnd(source, first, end) - first >= 3) {
            buffer.mark(first, end, Kind.KEYWORD);
            return;
        }
        while (i < end) {
            final var c = source.charAt(i);
            final var limit = inlineEnd(i, end);
            if (c == '\\') {
                i = Math.min(end, i + 2);
            } else if (c == '`' || c == '*' || c == '_') {
                final var run = runEnd(source, i, end);
                final var length = run - i;
                final var close = run < limit && (c == '`' || length <= 3) ?
                        closingRun(source, c, length, run, limit) : -1;
                if (close >= 0 && (c == '`' || close > run && !Character.isWhitespace(source.charAt(run)) &&
                        !Character.isWhitespace(source.charAt(close - 1)) &&
                        (c != '_' || i == start || !Character.isLetterOrDigit(source.charAt(i - 1))))) {
                    buffer.mark(i, close + length, c == '`' ? Kind.STRING : Kind.KEYWORD);
                    i = close + length;
                } else {
                    i = run;
                }
            } else if (c == '[' || c == '!' && i + 1 < end && source.charAt(i + 1) == '[') {
                final var label = c == '!' ? i + 1 : i;
                final var closeLabel = closing(source, "](", label + 1, limit);
                final var closeTarget = closeLabel < 0 ? -1 : closing(source, ")", closeLabel + 2, limit);
                if (closeTarget >= 0) {
                    buffer.mark(i, closeTarget + 1, Kind.ANNOTATION);
                    i = closeTarget + 1;
                } else {
                    i++;
                }
            } else if (table && c == '|') {
                buffer.mark(i, i + 1, Kind.KEYWORD);
                i++;
            } else {
                i++;
            }
        }
    }
}
