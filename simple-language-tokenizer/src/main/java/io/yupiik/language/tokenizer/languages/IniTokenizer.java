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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** INI subset with '=' or ':' assignments and whitespace-separated ';'/'#' inline comments. */
public final class IniTokenizer extends LineTokenizerSupport {
    private static final Set<String> LITERALS = Set.of("true", "false", "yes", "no", "on", "off");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    @Override
    public List<Token> tokenize(final String source) {
        final var buffer = new Buffer(source);
        var line = 0;
        while (line < source.length()) {
            final var end = lineEnd(source, line);
            final var start = spaces(source, line, end);
            if (start < end && (source.charAt(start) == '#' || source.charAt(start) == ';')) {
                buffer.mark(start, end, Kind.COMMENT);
            } else if (start < end && source.charAt(start) == '[') {
                final var close = closing(source, "]", start + 1, end);
                if (close >= 0) {
                    final var after = spaces(source, close + 1, end);
                    if (after == end || source.charAt(after) == ';' || source.charAt(after) == '#') {
                        buffer.mark(start, close + 1, Kind.TYPE);
                        if (after < end) {
                            buffer.mark(after, end, Kind.COMMENT);
                        }
                    }
                }
            } else {
                assignment(source, start, end, buffer);
            }
            line = nextLine(source, end);
        }
        return buffer.finish();
    }

    private void assignment(final String source, final int start, final int end, final Buffer buffer) {
        var separator = start;
        while (separator < end && source.charAt(separator) != '=' && source.charAt(separator) != ':') {
            if (source.charAt(separator) == '#' || source.charAt(separator) == ';') {
                return;
            }
            separator++;
        }
        final var keyEnd = trimEnd(source, start, separator);
        if (separator == end || keyEnd == start) {
            return;
        }
        buffer.mark(start, keyEnd, Kind.ATTRIBUTE);
        buffer.mark(separator, separator + 1, Kind.OPERATOR);
        final var valueStart = spaces(source, separator + 1, end);
        var comment = end;
        var i = valueStart;
        while (i < end) {
            final var c = source.charAt(i);
            if (c == '\'' || c == '"') {
                i = quotedEnd(source, i, end);
            } else if (c == '\\') {
                i = Math.min(end, i + 2);
            } else if ((c == ';' || c == '#') && (i == valueStart || horizontal(source.charAt(i - 1)))) {
                comment = i;
                break;
            } else {
                i++;
            }
        }
        final var valueEnd = trimEnd(source, valueStart, comment);
        if (valueStart < valueEnd) {
            final var text = source.substring(valueStart, valueEnd);
            final var kind = NUMBER.matcher(text).matches() ? Kind.NUMBER :
                    LITERALS.contains(text.toLowerCase(Locale.ROOT)) ? Kind.KEYWORD : Kind.STRING;
            buffer.mark(valueStart, valueEnd, kind);
        }
        if (comment < end) {
            buffer.mark(comment, end, Kind.COMMENT);
        }
    }
}
