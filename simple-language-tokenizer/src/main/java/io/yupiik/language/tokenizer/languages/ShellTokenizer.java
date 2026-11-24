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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.namePart;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.nameStart;
import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.oneOf;

/**
 * Conservative, lossless shell lexer; each tokenization uses a fresh session.
 * Substitutions are opaque ATTRIBUTE spans, not recursively parsed shell programs.
 */
public final class ShellTokenizer implements Tokenizer {
    private static final Set<String> SHELL_WORDS = Set.of(
            "if", "then", "else", "elif", "fi", "case", "esac", "in", "for", "while", "until", "do", "done",
            "select", "function", "time", "coproc", "break", "continue", "return", "exit", "export", "readonly",
            "local", "declare", "typeset", "unset", "shift", "set", "source", "alias", "unalias", "eval", "exec",
            "read", "printf", "echo", "cd", "pwd", "test", "true", "false", "trap", "wait", "umask", "getopts");

    private RangeBuffer b;
    private String s;
    private boolean vocabulary;
    private char continuation;
    private final ArrayDeque<HereDoc> documents = new ArrayDeque<>();

    @Override
    public List<Token> tokenize(final String source) {
        final var buffer = new RangeBuffer(source);
        scanRange(buffer, true, '\\', 0, source.length());
        return buffer.tokens();
    }

    /** Scans embedded shell arguments, including heredoc bodies beyond the command range. */
    public static int scanRange(final RangeBuffer buffer, final boolean vocabulary,
                               final char continuation, final int from, final int limit) {
        final var session = new ShellTokenizer();
        session.b = buffer;
        session.s = buffer.text();
        session.vocabulary = vocabulary;
        session.continuation = continuation;
        return session.tokenize(from, limit);
    }

    // Can return beyond limit when heredoc bodies follow the last command line.
    private int tokenize(final int from, final int limit) {
        var i = from;
        while (i < limit) {
            final var c = s.charAt(i);
            if (c == '\r' || c == '\n') {
                i = b.nextLine(i);
                while (!documents.isEmpty()) {
                    i = body(i, documents.removeFirst());
                }
            } else if (c == '#' && (i == from || Character.isWhitespace(s.charAt(i - 1)) || ";|&()".contains(s.substring(i - 1, i)))) {
                final var end = Math.min(limit, b.lineEnd(i));
                b.mark(i, end, Kind.COMMENT);
                i = end;
            } else if (c == '\'' || c == '"') {
                i = quoted(i, limit);
            } else if (c == continuation && i + 1 < limit && oneOf(s.charAt(i + 1), "\r\n")) {
                b.mark(i, i + 1, Kind.OPERATOR);
                i = b.nextLine(i + 1);
            } else if (c == '$' || c == '`') {
                final var end = expansion(i, limit);
                if (end > i) {
                    b.mark(i, end, Kind.ATTRIBUTE);
                    i = end;
                } else {
                    i++;
                }
            } else if (c == '\\') {
                b.mark(i, Math.min(limit, i + 2), Kind.PLAIN);
                i = Math.min(limit, i + 2);
                if (i < limit && s.charAt(i - 1) == '\r' && s.charAt(i) == '\n') {
                    i++;
                }
            } else if (s.startsWith("<<<", i)) {
                b.mark(i, i + 3, Kind.OPERATOR);
                i += 3;
            } else if (s.startsWith("<<", i)) {
                i = heredoc(i, limit);
            } else if (oneOf(c, ";&|<>(){}[]=!")) {
                b.mark(i, i + 1, Kind.OPERATOR);
                i++;
            } else if (nameStart(c) && boundary(i, from)) {
                var end = i + 1;
                while (end < limit && namePart(s.charAt(end))) {
                    end++;
                }
                if (end < limit && s.charAt(end) == '=') {
                    b.mark(i, end, Kind.ATTRIBUTE);
                } else if (vocabulary && (end == limit || shellBoundary(s.charAt(end))) && SHELL_WORDS.contains(s.substring(i, end))) {
                    b.mark(i, end, Kind.KEYWORD);
                }
                i = end;
            } else {
                i++;
            }
        }
        return i;
    }

    private boolean boundary(final int i, final int from) {
        return i == from || shellBoundary(s.charAt(i - 1));
    }

    private boolean shellBoundary(final char c) {
        return Character.isWhitespace(c) || oneOf(c, ";&|<>(){}");
    }

    private int quoted(final int start, final int limit) {
        final var quote = s.charAt(start);
        var i = start + 1;
        final var expansions = new ArrayList<int[]>();
        while (i < limit) {
            final var c = s.charAt(i);
            if (c == quote) {
                i++;
                break;
            }
            if (quote == '"' && c == '\\' && i + 1 < limit && oneOf(s.charAt(i + 1), "$`\"\\\r\n")) {
                i += 2;
            } else if (quote == '"' && (c == '$' || c == '`')) {
                final var end = expansion(i, limit);
                if (end > i) {
                    expansions.add(new int[]{i, end});
                    i = end;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        b.mark(start, i, Kind.STRING);
        for (final var range : expansions) {
            b.mark(range[0], range[1], Kind.ATTRIBUTE);
        }
        return i;
    }

    // Iterative balancing avoids stack overflow for maliciously nested input. The
    // contents are deliberately opaque, including any quotes and nested expressions.
    private int expansion(final int start, final int limit) {
        if (s.charAt(start) == '`') {
            var i = start + 1;
            while (i < limit) {
                if (s.charAt(i) == '\\') {
                    i = Math.min(limit, i + 2);
                } else if (s.charAt(i++) == '`') {
                    break;
                }
            }
            return i;
        }
        if (start + 1 >= limit) {
            return start;
        }
        final var next = s.charAt(start + 1);
        if (next == '{' || next == '(') {
            final var closes = new ArrayDeque<Character>();
            closes.push(next == '{' ? '}' : ')');
            var i = start + 2;
            char quote = 0;
            while (i < limit && !closes.isEmpty()) {
                final var c = s.charAt(i++);
                if (c == '\\' && quote != '\'') {
                    i = Math.min(limit, i + 1);
                } else if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                } else if (c == '(' || c == '{') {
                    closes.push(c == '(' ? ')' : '}');
                } else if (c == closes.peek()) {
                    closes.pop();
                }
            }
            return i;
        }
        if (nameStart(next)) {
            var end = start + 2;
            while (end < limit && namePart(s.charAt(end))) {
                end++;
            }
            return end;
        }
        return oneOf(next, "0123456789@*#?$!-_") ? start + 2 : start;
    }

    private int heredoc(final int start, final int limit) {
        var i = start + 2;
        final var tabs = i < limit && s.charAt(i) == '-';
        if (tabs) {
            i++;
        }
        b.mark(start, i, Kind.OPERATOR);
        i = b.spaces(i, limit);
        final var word = i;
        final var delimiter = new StringBuilder();
        var quoted = false;
        while (i < limit && !shellBoundary(s.charAt(i))) {
            final var c = s.charAt(i++);
            if (c == '\'' || c == '"') {
                quoted = true;
                while (i < limit && s.charAt(i) != c && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
                    if (c == '"' && s.charAt(i) == '\\' && i + 1 < limit && oneOf(s.charAt(i + 1), "$`\"\\")) {
                        i++;
                    }
                    delimiter.append(s.charAt(i++));
                }
                if (i < limit && s.charAt(i) == c) {
                    i++;
                }
            } else if (c == '\\' && i < limit) {
                quoted = true;
                delimiter.append(s.charAt(i++));
            } else {
                delimiter.append(c);
            }
        }
        if (i > word) {
            b.mark(word, i, Kind.STRING);
            documents.addLast(new HereDoc(delimiter.toString(), quoted, tabs));
        }
        return i;
    }

    private int body(final int start, final HereDoc doc) {
        var i = start;
        while (i < s.length()) {
            final var end = b.lineEnd(i);
            var content = i;
            if (doc.tabs()) {
                while (content < end && s.charAt(content) == '\t') {
                    content++;
                }
            }
            if (s.substring(content, end).equals(doc.delimiter())) {
                b.mark(i, end, Kind.STRING);
                return b.nextLine(end);
            }
            final var next = b.nextLine(end);
            b.mark(i, next, Kind.STRING);
            if (!doc.quoted()) {
                var p = i;
                while (p < next) {
                    if (s.charAt(p) == '\\' && p + 1 < next && oneOf(s.charAt(p + 1), "$`\\\r\n")) {
                        p += 2;
                    } else if (s.charAt(p) == '$' || s.charAt(p) == '`') {
                        final var stop = expansion(p, next);
                        if (stop > p) {
                            b.mark(p, stop, Kind.ATTRIBUTE);
                            p = stop;
                        } else {
                            p++;
                        }
                    } else {
                        p++;
                    }
                }
            }
            i = next;
        }
        return i;
    }

    private record HereDoc(String delimiter, boolean quoted, boolean tabs) {
    }
}
