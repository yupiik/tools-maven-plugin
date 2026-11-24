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
import java.util.List;
import java.util.Set;

/** CSS lexer, also covering simple SCSS/LESS variables and nesting, not preprocessor evaluation. */
public final class CssTokenizer extends LineTokenizerSupport {
    private static final Set<String> AT_RULES = Set.of(
            "charset", "import", "namespace", "media", "supports", "layer", "container", "scope",
            "font-face", "page", "keyframes", "-webkit-keyframes", "property", "starting-style",
            "mixin", "include", "extend", "use", "forward", "if", "else", "for", "each", "while", "function", "return");
    private static final Set<String> GROUPS = Set.of(
            "media", "supports", "layer", "container", "scope", "keyframes", "-webkit-keyframes", "starting-style");

    @Override
    public List<Token> tokenize(final String source) {
        final var buffer = new Buffer(source);
        final var blocks = new ArrayDeque<Boolean>();
        var declarations = false;
        var statementStart = true;
        var group = false;
        var value = false;
        var parentheses = 0;
        var i = 0;
        while (i < source.length()) {
            final var c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (source.startsWith("/*", i)) {
                final var close = source.indexOf("*/", i + 2);
                final var end = close < 0 ? source.length() : close + 2;
                buffer.mark(i, end, Kind.COMMENT);
                i = end;
            } else if (source.startsWith("//", i) && parentheses == 0 &&
                    (i == 0 || Character.isWhitespace(source.charAt(i - 1)) || source.charAt(i - 1) == ';')) {
                final var end = lineEnd(source, i);
                buffer.mark(i, end, Kind.COMMENT);
                i = end;
            } else if (c == '\'' || c == '"') {
                final var end = quotedEnd(source, i, source.length());
                buffer.mark(i, end, Kind.STRING);
                i = end;
            } else if (c == '@' || c == '$') {
                var end = i + 1;
                while (end < source.length() && name(source.charAt(end))) {
                    end++;
                }
                if (end > i + 1) {
                    final var word = source.substring(i + 1, end);
                    final var rule = c == '@' && AT_RULES.contains(word);
                    buffer.mark(i, end, rule ? Kind.KEYWORD : Kind.ATTRIBUTE);
                    if (rule && statementStart) {
                        group = GROUPS.contains(word);
                    }
                    i = end;
                    statementStart = false;
                } else {
                    i++;
                }
            } else if (c == '{' || c == '}' || c == ';') {
                buffer.mark(i, i + 1, Kind.OPERATOR);
                if (c == '{') {
                    blocks.push(declarations);
                    declarations = !group;
                } else if (c == '}') {
                    declarations = !blocks.isEmpty() && blocks.pop();
                }
                statementStart = true;
                group = false;
                value = false;
                i++;
            } else if (c == '(' || c == ')') {
                parentheses = Math.max(0, parentheses + (c == '(' ? 1 : -1));
                buffer.mark(i, i + 1, Kind.OPERATOR);
                i++;
            } else if (c == ':' && declarations) {
                buffer.mark(i, i + 1, Kind.OPERATOR);
                value = true;
                statementStart = false;
                i++;
            } else if (value && c == '#' && i + 1 < source.length() && Character.digit(source.charAt(i + 1), 16) >= 0) {
                var end = i + 1;
                while (end < source.length() && Character.digit(source.charAt(end), 16) >= 0) {
                    end++;
                }
                buffer.mark(i, end, Kind.NUMBER);
                i = end;
            } else if (Character.isDigit(c) || (c == '.' || c == '+' || c == '-') && i + 1 < source.length() &&
                    Character.isDigit(source.charAt(i + 1))) {
                var end = i + 1;
                while (end < source.length() && (Character.isDigit(source.charAt(end)) || source.charAt(end) == '.')) {
                    end++;
                }
                while (end < source.length() && (Character.isLetter(source.charAt(end)) || source.charAt(end) == '%')) {
                    end++;
                }
                buffer.mark(i, end, Kind.NUMBER);
                i = end;
            } else if (name(c) || c == '.' || c == '#' || c == '&' || c == '*') {
                var end = i + 1;
                while (end < source.length() && name(source.charAt(end))) {
                    end++;
                }
                final var after = spaces(source, end, source.length());
                if (declarations && statementStart && after < source.length() && source.charAt(after) == ':') {
                    buffer.mark(i, end, Kind.ATTRIBUTE);
                } else if (!value && (c == '.' || c == '#' || c == '&' || c == '*' || Character.isLetter(c))) {
                    buffer.mark(i, end, Kind.TYPE);
                }
                statementStart = false;
                i = end;
            } else {
                // Unknown punctuation is intentionally left plain.
                i++;
            }
        }
        return buffer.finish();
    }
}
