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
import java.util.Locale;
import java.util.Set;

import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.horizontal;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.oneOf;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.quoteEnd;

/**
 * Conservative, lossless Dockerfile lexer; each tokenization uses a fresh session.
 * RUN uses the shared shell subset (except JSON form); other instructions only
 * recognize flags, strings and heredocs, not arbitrary embedded languages.
 */
public final class DockerfileTokenizer implements Tokenizer {
    private static final Set<String> DOCKER_WORDS = Set.of(
            "FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT",
            "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL");

    private RangeBuffer b;
    private String s;
    private char escape = '\\';

    @Override
    public List<Token> tokenize(final String source) {
        final var session = new DockerfileTokenizer();
        session.b = new RangeBuffer(source);
        session.s = source;
        session.tokenize();
        return session.b.tokens();
    }

    private void tokenize() {
        var line = 0;
        var directives = true;
        while (line < s.length()) {
            final var end = b.lineEnd(line);
            var i = b.spaces(line, end);
            if (i == end) {
                directives = false;
                line = b.nextLine(end);
                continue;
            }
            if (s.charAt(i) == '#') {
                b.mark(i, end, Kind.COMMENT);
                if (directives) {
                    final var directive = s.substring(i + 1, end).strip();
                    if (directive.equalsIgnoreCase("escape=`")) {
                        escape = '`';
                    } else if (directive.equalsIgnoreCase("escape=\\")) {
                        escape = '\\';
                    } else if (!directive.toLowerCase(Locale.ROOT).startsWith("syntax=")) {
                        directives = false;
                    }
                }
                line = b.nextLine(end);
                continue;
            }
            directives = false;
            final var start = i;
            while (i < end && Character.isLetter(s.charAt(i))) {
                i++;
            }
            final var instruction = s.substring(start, i).toUpperCase(Locale.ROOT);
            if (!DOCKER_WORDS.contains(instruction) || i < end && !horizontal(s.charAt(i))) {
                line = b.nextLine(end);
                continue;
            }
            b.mark(start, i, Kind.KEYWORD);
            final var last = instructionEnd(line, end);
            final var limit = b.nextLine(last);
            i = b.spaces(i, last);
            while (i + 2 < last && s.startsWith("--", i)) {
                var stop = i + 2;
                while (stop < last && !Character.isWhitespace(s.charAt(stop))) {
                    if (s.charAt(stop) == '\'' || s.charAt(stop) == '"') {
                        stop = Math.min(last, quoteEnd(s, stop, false, false));
                    } else {
                        stop++;
                    }
                }
                b.mark(i, stop, Kind.ATTRIBUTE);
                i = b.spaces(stop, last);
            }
            if (i < last && s.charAt(i) == '[') {
                json(i, limit);
                line = limit;
            } else {
                // No shell interpretation for ENV, COPY, etc. '#' inside an argument
                // is not a Dockerfile comment; the generic argument lexer keeps it plain.
                line = instruction.equals("RUN") ? ShellTokenizer.scanRange(b, true, escape, i, limit) : arguments(i, limit);
            }
            // Dockerfile comment lines in a continuation are ignored even inside
            // quoted arguments; restore their category after tokenizing the arguments.
            var physical = b.nextLine(end);
            while (physical < limit) {
                final var physicalEnd = b.lineEnd(physical);
                final var content = b.spaces(physical, physicalEnd);
                if (content < physicalEnd && s.charAt(content) == '#') {
                    b.mark(content, physicalEnd, Kind.COMMENT);
                }
                physical = b.nextLine(physicalEnd);
            }
        }
    }

    private int instructionEnd(final int start, final int firstEnd) {
        var line = start;
        var end = firstEnd;
        while (end < s.length()) {
            var escapes = end;
            while (escapes > line && s.charAt(escapes - 1) == escape) {
                escapes--;
            }
            if ((end - escapes) % 2 == 0) {
                break;
            }
            b.mark(end - 1, end, Kind.OPERATOR);
            line = b.nextLine(end);
            // Comment-only lines do not terminate a continued instruction.
            while (line < s.length()) {
                end = b.lineEnd(line);
                final var content = b.spaces(line, end);
                if (content < end && s.charAt(content) != '#') {
                    break;
                }
                line = b.nextLine(end);
            }
            end = b.lineEnd(line);
        }
        return end;
    }

    private int arguments(final int start, final int limit) {
        var i = start;
        while (i < limit) {
            final var c = s.charAt(i);
            if (c == '\'' || c == '"') {
                final var end = Math.min(limit, quoteEnd(s, i, false, false));
                b.mark(i, end, Kind.STRING);
                i = end;
            } else if (s.startsWith("<<", i)) {
                return ShellTokenizer.scanRange(b, false, '\\', i, limit);
            } else {
                i++;
            }
        }
        return i;
    }

    private void json(final int start, final int limit) {
        var i = start;
        while (i < limit) {
            final var c = s.charAt(i);
            if (c == '"') {
                final var end = Math.min(limit, quoteEnd(s, i, false, false));
                b.mark(i, end, Kind.STRING);
                i = end;
            } else if (oneOf(c, "[],")) {
                b.mark(i, ++i, Kind.OPERATOR);
            } else {
                i++;
            }
        }
    }
}
