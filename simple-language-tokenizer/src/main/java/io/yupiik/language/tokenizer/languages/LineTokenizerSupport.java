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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Range-based extension support for line-oriented lexical tokenizers. */
public abstract class LineTokenizerSupport implements Tokenizer {
    protected LineTokenizerSupport() {
        // Extension point for line-oriented tokenizers.
    }

    protected static boolean horizontal(final char c) {
        return c == ' ' || c == '\t';
    }

    protected static boolean name(final char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    protected static int lineEnd(final String source, final int start) {
        var end = start;
        while (end < source.length() && source.charAt(end) != '\r' && source.charAt(end) != '\n') {
            end++;
        }
        return end;
    }

    protected static int nextLine(final String source, final int end) {
        if (end == source.length()) {
            return end;
        }
        return end + (source.charAt(end) == '\r' && end + 1 < source.length() && source.charAt(end + 1) == '\n' ? 2 : 1);
    }

    protected static int spaces(final String source, final int start, final int end) {
        var i = start;
        while (i < end && horizontal(source.charAt(i))) {
            i++;
        }
        return i;
    }

    protected static int trimEnd(final String source, final int start, final int end) {
        var i = end;
        while (i > start && horizontal(source.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    protected static int runEnd(final String source, final int start, final int end) {
        var i = start + 1;
        while (i < end && source.charAt(i) == source.charAt(start)) {
            i++;
        }
        return i;
    }

    /** Returns the position after a quote, or the supplied boundary for an unterminated quote. */
    protected static int quotedEnd(final String source, final int start, final int end) {
        var i = start + 1;
        while (i < end) {
            final var c = source.charAt(i++);
            if (c == '\\') {
                i = Math.min(end, i + 1);
            } else if (c == source.charAt(start)) {
                break;
            }
        }
        return i;
    }

    protected static int closing(final String source, final String delimiter, final int start, final int end) {
        var i = start;
        while (i < end) {
            if (source.charAt(i) == '\\') {
                i += 2;
            } else if (source.startsWith(delimiter, i) && i + delimiter.length() <= end) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** Inline candidates share one 512-character window, including their opening delimiter. */
    protected static int inlineEnd(final int start, final int end) {
        return start + Math.min(512, end - start);
    }

    /** Searches only the supplied window for an exact, unescaped delimiter run, without allocating it. */
    protected static int closingRun(final String source, final char delimiter, final int length,
                                    final int start, final int end) {
        var i = start;
        while (i < end) {
            if (source.charAt(i) == '\\') {
                i = Math.min(end, i + 2);
            } else if (source.charAt(i) == delimiter) {
                final var stop = runEnd(source, i, end);
                if (stop - i == length && (stop == source.length() || source.charAt(stop) != delimiter)) {
                    return i;
                }
                i = stop;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** Ordered, non-overlapping highlights; all gaps retain their original plain text. */
    protected static final class Buffer {
        private final String source;
        private final List<Token> tokens = new ArrayList<>();
        private int end;

        public Buffer(final String source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        public void mark(final int start, final int stop, final Kind kind) {
            if (start == stop) {
                return;
            }
            if (start < end || stop < start || stop > source.length()) {
                throw new IllegalArgumentException("Overlapping or invalid token range");
            }
            if (start > end) {
                tokens.add(new Token(Kind.PLAIN, source.substring(end, start)));
            }
            tokens.add(new Token(kind, source.substring(start, stop)));
            end = stop;
        }

        public List<Token> finish() {
            if (end < source.length()) {
                tokens.add(new Token(Kind.PLAIN, source.substring(end)));
            }
            return List.copyOf(tokens);
        }
    }
}
