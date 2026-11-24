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
import java.util.Set;

/**
 * Lexical AsciiDoc subset; listing/literal/comment bodies are opaque, never parsed as an AST.
 * Inline spans, anchors, attributes and macros must fit in a 512-character window; longer
 * candidates stay plain. Candidate searches cost at most O(512); delimiter runs and words
 * are consumed once by the outer cursor. Total tokenization is O(n * 512), with O(n) output,
 * even for repeated unmatched macro/anchor openers. Whole-line constructs remain unlimited.
 */
public final class AsciidocTokenizer extends LineTokenizerSupport {
    private static final Set<String> DELIMITERS = Set.of("----", "....", "////", "====", "****", "____", "++++", "--", "|===");
    private static final Set<String> MACROS = Set.of("image", "include", "link", "xref", "mailto", "icon", "footnote", "kbd", "btn", "menu", "video", "audio");

    @Override
    public List<Token> tokenize(final String source) {
        final var buffer = new Buffer(source);
        String opaque = null;
        var opaqueKind = Kind.STRING;
        var line = 0;
        while (line < source.length()) {
            final var end = lineEnd(source, line);
            final var next = nextLine(source, end);
            final var start = spaces(source, line, end);
            final var trimmed = trimEnd(source, start, end);
            final var text = source.substring(start, trimmed);
            if (opaque != null) {
                if (start == line && text.equals(opaque)) {
                    buffer.mark(start, end, Kind.OPERATOR);
                    opaque = null;
                } else {
                    buffer.mark(line, next, opaqueKind);
                }
            } else if (start == line && DELIMITERS.contains(text)) {
                buffer.mark(start, end, Kind.OPERATOR);
                if (text.equals("----") || text.equals("....") || text.equals("////") || text.equals("++++")) {
                    opaque = text;
                    opaqueKind = text.equals("////") ? Kind.COMMENT : Kind.STRING;
                }
            } else if (start == line && source.startsWith("//", start)) {
                buffer.mark(start, end, Kind.COMMENT);
            } else if (start == line && start < end && source.charAt(start) == '=' &&
                    runEnd(source, start, end) - start <= 6 && runEnd(source, start, end) < end &&
                    horizontal(source.charAt(runEnd(source, start, end)))) {
                buffer.mark(start, end, Kind.KEYWORD);
            } else if (start == line && start < end && source.charAt(start) == ':' && attributeEnd(source, start, end) >= 0) {
                final var stop = attributeEnd(source, start, end);
                buffer.mark(start, stop, Kind.ATTRIBUTE);
                final var valueStart = spaces(source, stop, end);
                if (valueStart < trimmed) {
                    buffer.mark(valueStart, trimmed, Kind.STRING);
                }
            } else if (start == line && text.startsWith("[") && text.endsWith("]")) {
                buffer.mark(start, trimmed, Kind.ANNOTATION);
            } else {
                inline(source, line, end, buffer);
            }
            line = next;
        }
        return buffer.finish();
    }

    private int attributeEnd(final String source, final int start, final int end) {
        var i = start + 1;
        if (i < end && source.charAt(i) == '!') {
            i++;
        }
        final var nameStart = i;
        while (i < end && name(source.charAt(i))) {
            i++;
        }
        if (i == nameStart) {
            return -1;
        }
        if (i < end && source.charAt(i) == '!') {
            i++;
        }
        return i < end && source.charAt(i) == ':' ? i + 1 : -1;
    }

    private void inline(final String source, final int start, final int end, final Buffer buffer) {
        var i = start;
        while (i < end) {
            final var c = source.charAt(i);
            final var limit = inlineEnd(i, end);
            if (c == '\\') {
                i = Math.min(end, i + 2);
            } else if (c == '`' || c == '+' || c == '*' || c == '_') {
                final var run = runEnd(source, i, end);
                final var length = run - i;
                final var code = c == '`' || c == '+';
                final var close = run < limit && (code || length <= 2) ? closingRun(source, c, length, run, limit) : -1;
                if (close > run && (code || !Character.isWhitespace(source.charAt(run)) &&
                        !Character.isWhitespace(source.charAt(close - 1)))) {
                    buffer.mark(i, close + length, code ? Kind.STRING : Kind.KEYWORD);
                    i = close + length;
                } else {
                    i = run;
                }
            } else if (c == '[' && i + 1 < end && source.charAt(i + 1) == '[') {
                final var close = closing(source, "]]", i + 2, limit);
                if (close > i + 2) {
                    buffer.mark(i, close + 2, Kind.ANNOTATION);
                    i = close + 2;
                } else {
                    i++;
                }
            } else if (c == '{') {
                var stop = i + 1;
                while (stop < limit && name(source.charAt(stop))) {
                    stop++;
                }
                if (stop > i + 1 && stop < limit && source.charAt(stop) == '}') {
                    buffer.mark(i, stop + 1, Kind.ATTRIBUTE);
                    i = stop + 1;
                } else {
                    i++;
                }
            } else if (Character.isLetter(c) && (i == start || !name(source.charAt(i - 1)))) {
                var stop = i + 1;
                while (stop < end && name(source.charAt(stop))) {
                    stop++;
                }
                if (stop < end && source.charAt(stop) == ':' && MACROS.contains(source.substring(i, stop))) {
                    var bracket = stop + 1;
                    while (bracket < limit && !Character.isWhitespace(source.charAt(bracket)) && source.charAt(bracket) != '[') {
                        bracket++;
                    }
                    final var close = bracket < limit && source.charAt(bracket) == '[' ? closing(source, "]", bracket + 1, limit) : -1;
                    if (close >= 0) {
                        buffer.mark(i, close + 1, Kind.ANNOTATION);
                        i = close + 1;
                        continue;
                    }
                }
                i = stop;
            } else {
                i++;
            }
        }
    }
}
