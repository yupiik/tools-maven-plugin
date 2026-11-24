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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-invocation cursor and token accumulation shared by lexical tokenizers. */
public abstract class AbstractTokenizer {
    private final String source;
    private final List<Token> result = new ArrayList<>();
    private int position;
    private int start;
    private Kind pending = Kind.PLAIN;

    protected AbstractTokenizer(final String source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    protected final String source() {
        return source;
    }

    protected final int position() {
        return position;
    }

    protected abstract void step();

    protected final List<Token> tokens() {
        while (position < source.length()) {
            step();
        }
        flush();
        return List.copyOf(result);
    }

    // Coalesce adjacent categories by offsets, never by repeated string concatenation.
    protected final void emit(final Kind kind, final int end) {
        if (pending != kind) {
            flush();
            pending = kind;
            start = position;
        }
        position = end;
    }

    private void flush() {
        if (position > start) {
            result.add(new Token(pending, source.substring(start, position)));
        }
    }

    protected final void lineComment() {
        var end = position;
        while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
            end++;
        }
        emit(Kind.COMMENT, end);
    }

    protected final int annotationEnd(final boolean useSiteTarget) {
        var end = identifierEnd(position + 1);
        if (useSiteTarget && end + 1 < source.length() && source.charAt(end) == ':' &&
                Character.isJavaIdentifierStart(source.codePointAt(end + 1))) {
            end = identifierEnd(end + 1);
        }
        while (end + 1 < source.length() && source.charAt(end) == '.' &&
                Character.isJavaIdentifierStart(source.codePointAt(end + 1))) {
            end = identifierEnd(end + 1);
        }
        return end;
    }

    protected boolean interpolated(final String delimiter) {
        return false;
    }

    protected boolean templateBackticks() {
        return false;
    }

    protected final int identifierEnd(final int from) {
        var end = from;
        while (end < source.length() && Character.isJavaIdentifierPart(source.codePointAt(end))) {
            end += Character.charCount(source.codePointAt(end));
        }
        return end;
    }

    protected final boolean at(final String text) {
        return source.startsWith(text, position);
    }

    protected final int through(final String terminator, final int from) {
        final var end = source.indexOf(terminator, from);
        return end < 0 ? source.length() : end + terminator.length();
    }

    protected final int quoted(final String delimiter, final boolean escapes, final boolean doubled) {
        return opaqueEnd(position + delimiter.length(),
                new OpaqueFrame(delimiter, escapes, doubled, interpolated(delimiter)));
    }

    protected final int templateEnd(final int from) {
        return opaqueEnd(from, new OpaqueFrame(null, false, false, false));
    }

    private int opaqueEnd(final int from, final OpaqueFrame initial) {
        // Quotes and interpolation expressions share one forward-only cursor. Keeping
        // their continuation state on the heap avoids recursion at arbitrary nesting
        // depths: O(source length) work and O(nesting depth) auxiliary space for the
        // fixed-size delimiters used by the tokenizers. All nested content stays opaque.
        final var frames = new ArrayDeque<OpaqueFrame>();
        frames.push(initial);
        var end = from;
        while (end < source.length() && !frames.isEmpty()) {
            final var frame = frames.peek();
            if (frame.delimiter != null) {
                if (frame.escapes && source.charAt(end) == '\\') {
                    end = Math.min(source.length(), end + 2);
                } else if (frame.templates && source.startsWith("${", end)) {
                    end += 2;
                    frames.push(new OpaqueFrame(null, false, false, false));
                } else if (source.startsWith(frame.delimiter, end)) {
                    end += frame.delimiter.length();
                    if (frame.doubled && source.startsWith(frame.delimiter, end)) {
                        end += frame.delimiter.length();
                    } else {
                        frames.pop();
                    }
                } else {
                    end++;
                }
                continue;
            }

            final var c = source.charAt(end);
            if (source.startsWith("/*", end)) {
                end = through("*/", end + 2);
            } else if (source.startsWith("//", end)) {
                while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
                    end++;
                }
            } else if (c == '\'' || c == '"' || (templateBackticks() && c == '`')) {
                final var quote = String.valueOf(c);
                final var triple = c != '`' && source.startsWith(quote.repeat(3), end);
                final var delimiter = triple ? quote.repeat(3) : quote;
                frames.push(new OpaqueFrame(delimiter,
                        c != '`' && !(templateBackticks() && triple), false, interpolated(delimiter)));
                end += delimiter.length();
            } else {
                end++;
                if (c == '{') {
                    frame.depth++;
                } else if (c == '}' && --frame.depth == 0) {
                    frames.pop();
                }
            }
        }
        return end;
    }

    private static final class OpaqueFrame {
        private final String delimiter; // null denotes a balanced interpolation expression
        private final boolean escapes;
        private final boolean doubled;
        private final boolean templates;
        private int depth = 1;

        private OpaqueFrame(final String delimiter, final boolean escapes,
                            final boolean doubled, final boolean templates) {
            this.delimiter = delimiter;
            this.escapes = escapes;
            this.doubled = doubled;
            this.templates = templates;
        }
    }
}
