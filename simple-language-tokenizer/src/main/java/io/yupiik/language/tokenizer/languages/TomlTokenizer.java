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

import java.util.ArrayDeque;
import java.util.List;

import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.namePart;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.oneOf;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.quoteEnd;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.tomlScalar;

/** Conservative, lossless toml lexer; each tokenization uses a fresh session. */
public final class TomlTokenizer implements Tokenizer {
    private RangeBuffer b;
    private String s;
    private int keyLineEnd;
    private final ArrayDeque<Character> containers = new ArrayDeque<>();

    @Override
    public List<Token> tokenize(final String source) {
        final var session = new TomlTokenizer();
        session.b = new RangeBuffer(source);
        session.s = source;
        session.tokenize();
        return session.b.tokens();
    }

    private void tokenize() {
        var i = 0;
        var lineStart = true;
        var wantKey = true;
        while (i < s.length()) {
            final var c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (c == '\r' || c == '\n') {
                    lineStart = true;
                    wantKey = containers.isEmpty() || containers.peek() == '{';
                }
                i++;
                continue;
            }
            if (c == '#') {
                final var end = b.lineEnd(i);
                b.mark(i, end, Kind.COMMENT);
                i = end;
                continue;
            }
            if (lineStart && containers.isEmpty() && c == '[') {
                i = table(i);
                wantKey = false;
            } else if (wantKey) {
                final var end = keyEnd(i);
                if (end >= 0) {
                    keys(i, end);
                    b.mark(end, end + 1, Kind.OPERATOR);
                    i = end + 1;
                    wantKey = false;
                } else {
                    wantKey = false;
                    continue;
                }
            } else if (c == '\'' || c == '"') {
                final var triple = i + 2 < s.length() && s.charAt(i + 1) == c && s.charAt(i + 2) == c;
                final var end = quoteEnd(s, i, false, triple);
                b.mark(i, end, Kind.STRING);
                i = end;
            } else if (c == '[' || c == '{') {
                containers.push(c);
                wantKey = c == '{';
                b.mark(i, ++i, Kind.OPERATOR);
            } else if (c == ']' || c == '}') {
                if (!containers.isEmpty()) {
                    containers.pop();
                }
                b.mark(i, ++i, Kind.OPERATOR);
            } else if (c == ',') {
                wantKey = !containers.isEmpty() && containers.peek() == '{';
                b.mark(i, ++i, Kind.OPERATOR);
            } else {
                var end = i + 1;
                while (end < s.length() && !Character.isWhitespace(s.charAt(end)) && !oneOf(s.charAt(end), ",]}#")) {
                    end++;
                }
                b.mark(i, end, tomlScalar(s.substring(i, end)));
                i = end;
            }
            lineStart = false;
        }
    }

    private int table(final int start) {
        final var end = b.lineEnd(start);
        var i = start;
        while (i < end && s.charAt(i) == '[') {
            b.mark(i, ++i, Kind.OPERATOR);
        }
        final var key = i;
        while (i < end && s.charAt(i) != ']' && s.charAt(i) != '#') {
            if (s.charAt(i) == '\'' || s.charAt(i) == '"') {
                i = Math.min(end, quoteEnd(s, i, false, false));
            } else {
                i++;
            }
        }
        keys(key, i);
        while (i < end && s.charAt(i) == ']') {
            b.mark(i, ++i, Kind.OPERATOR);
        }
        return i;
    }

    private int keyEnd(final int start) {
        var i = start;
        // Inline tables can have many keys on one line. Tokenize its suffix only once.
        if (start >= keyLineEnd) {
            keyLineEnd = b.lineEnd(start);
        }
        final var end = keyLineEnd;
        while (i < end) {
            final var c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = quoteEnd(s, i, false, false);
            } else if (c == '=') {
                return i;
            } else if (c == '#' || c == ',' || c == '}') {
                break;
            } else {
                i++;
            }
        }
        return -1;
    }

    private void keys(final int start, final int end) {
        var i = start;
        while (i < end) {
            final var c = s.charAt(i);
            if (c == '\'' || c == '"') {
                final var stop = Math.min(end, quoteEnd(s, i, false, false));
                b.mark(i, stop, Kind.ATTRIBUTE);
                i = stop;
            } else if (c == '.') {
                b.mark(i, ++i, Kind.OPERATOR);
            } else if (namePart(c) || c == '-') {
                final var from = i++;
                while (i < end && (namePart(s.charAt(i)) || s.charAt(i) == '-')) {
                    i++;
                }
                b.mark(from, i, Kind.ATTRIBUTE);
            } else {
                i++;
            }
        }
    }
}
