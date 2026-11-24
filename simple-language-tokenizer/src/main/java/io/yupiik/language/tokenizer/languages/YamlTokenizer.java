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
import io.yupiik.language.tokenizer.Tokenizer;

import java.util.List;

import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.horizontal;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.oneOf;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.quoteEnd;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.scalar;

/** Conservative, lossless yaml lexer; each tokenization uses a fresh session. */
public final class YamlTokenizer implements Tokenizer {
    private RangeBuffer b;
    private String s;
    private int flow;

    @Override
    public List<Token> tokenize(final String source) {
        final var session = new YamlTokenizer();
        session.b = new RangeBuffer(source);
        session.s = source;
        session.tokenize();
        return session.b.tokens();
    }

    private void tokenize() {
        var i = 0;
        while (i < s.length()) {
            final var line = i;
            final var end = b.lineEnd(i);
            i = b.spaces(i, end);
            final var indent = i - line;
            var blockIndent = indent;
            if ((s.startsWith("---", i) || s.startsWith("...", i)) && (i + 3 == end || Character.isWhitespace(s.charAt(i + 3)))) {
                b.mark(i, i + 3, Kind.OPERATOR);
                i += 3;
            }
            while (i < end) {
                final var c = s.charAt(i);
                if (horizontal(c)) {
                    i++;
                } else if (c == '#' && (i == line || Character.isWhitespace(s.charAt(i - 1)))) {
                    b.mark(i, end, Kind.COMMENT);
                    i = end;
                } else if (c == '\'' || c == '"') {
                    final var stop = quoteEnd(s, i, true, false);
                    final var after = b.spaces(stop, s.length());
                    final var key = after < s.length() && s.charAt(after) == ':';
                    b.mark(i, stop, key ? Kind.ATTRIBUTE : Kind.STRING);
                    if (key && flow == 0) {
                        blockIndent = i - line;
                    }
                    i = stop;
                } else if (oneOf(c, "[]{}") || c == ',' && flow > 0) {
                    if (c == '[' || c == '{') {
                        flow++;
                    } else if (c == ']' || c == '}') {
                        flow = Math.max(0, flow - 1);
                    }
                    b.mark(i, ++i, Kind.OPERATOR);
                } else if ((c == ':' || c == '-' || c == '?') && separated(i + 1, end)) {
                    b.mark(i, ++i, Kind.OPERATOR);
                } else if (c == '&' || c == '*' || c == '!') {
                    var stop = i + 1;
                    if (c == '!' && stop < end && s.charAt(stop) == '<') {
                        stop++;
                        while (stop < end && s.charAt(stop) != '>') {
                            stop++;
                        }
                        if (stop < end) {
                            stop++;
                        }
                    } else {
                        while (stop < end && !Character.isWhitespace(s.charAt(stop)) && !oneOf(s.charAt(stop), "[]{},")) {
                            stop++;
                        }
                    }
                    b.mark(i, stop, Kind.ANNOTATION);
                    i = stop;
                } else if ((c == '|' || c == '>') && flow == 0 && blockHeader(i, end)) {
                    var stop = i + 1;
                    var explicit = 0;
                    while (stop < end && oneOf(s.charAt(stop), "+-123456789")) {
                        if (Character.isDigit(s.charAt(stop))) {
                            explicit = s.charAt(stop) - '0';
                        }
                        stop++;
                    }
                    b.mark(i, stop, Kind.OPERATOR);
                    final var comment = b.spaces(stop, end);
                    if (comment < end && s.charAt(comment) == '#') {
                        b.mark(comment, end, Kind.COMMENT);
                    }
                    i = block(b.nextLine(end), blockIndent, explicit);
                    break;
                } else {
                    var stop = i;
                    while (stop < end && !(s.charAt(stop) == ':' && separated(stop + 1, end)) &&
                            !(s.charAt(stop) == '#' && stop > i && Character.isWhitespace(s.charAt(stop - 1))) &&
                            !(flow > 0 && oneOf(s.charAt(stop), "[]{},"))) {
                        stop++;
                    }
                    if (stop == i) {
                        i++;
                        continue;
                    }
                    var trimmed = stop;
                    while (trimmed > i && horizontal(s.charAt(trimmed - 1))) {
                        trimmed--;
                    }
                    final var key = stop < end && s.charAt(stop) == ':';
                    b.mark(i, trimmed, key ? Kind.ATTRIBUTE : scalar(s.substring(i, trimmed)));
                    if (key && flow == 0) {
                        blockIndent = i - line;
                    }
                    i = stop;
                }
            }
            if (i <= end) {
                i = b.nextLine(end);
            }
        }
    }

    private boolean separated(final int i, final int end) {
        return i == end || Character.isWhitespace(s.charAt(i)) || flow > 0 && oneOf(s.charAt(i), ",[]{}");
    }

    private boolean blockHeader(final int start, final int end) {
        var i = start + 1;
        while (i < end && oneOf(s.charAt(i), "+-123456789")) {
            i++;
        }
        return i == end || horizontal(s.charAt(i));
    }

    private int block(final int start, final int parentIndent, final int explicit) {
        var i = start;
        var required = explicit == 0 ? -1 : parentIndent + explicit;
        while (i < s.length()) {
            final var end = b.lineEnd(i);
            final var content = b.spaces(i, end);
            if (content < end) {
                final var indent = content - i;
                if (required < 0) {
                    if (indent <= parentIndent) {
                        break;
                    }
                    required = indent;
                }
                if (indent < required) {
                    break;
                }
            }
            final var next = b.nextLine(end);
            b.mark(i, next, Kind.STRING);
            i = next;
        }
        return i;
    }
}
