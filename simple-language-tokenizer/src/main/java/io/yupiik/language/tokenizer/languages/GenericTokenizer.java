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
import java.util.Set;

/**
 * Explicit opt-in, language-agnostic lexical highlighting of common programming constructs.
 * This is not language detection and is deliberately not the dispatcher's unknown-language fallback.
 * Tokenization is forward-only, with per-invocation state and no cached source or tokens.
 */
public final class GenericTokenizer implements Tokenizer {
    private static final Set<String> KEYWORDS = Set.of(
            "return", "if", "else", "for", "while", "switch", "case", "break", "continue", "class", "interface",
            "enum", "record", "function", "def", "import", "export", "var", "let", "const", "new", "try", "catch",
            "finally", "throw", "this", "super", "null", "true", "false", "async", "await", "void");

    @Override
    public List<Token> tokenize(final String source) {
        return new Tokenization(source).tokens();
    }

    private static final class Tokenization extends AbstractTokenizer {
        private Tokenization(final String source) {
            super(source);
        }

        @Override
        protected void step() {
            final var source = source();
            final var position = position();
            final var c = source.charAt(position);
            if (at("//") || at("--") || c == '#') {
                lineComment();
            } else if (at("/*")) {
                emit(Kind.COMMENT, through("*/", position + 2));
            } else if (at("<!--")) {
                emit(Kind.COMMENT, through("-->", position + 4));
            } else if (c == '"' || c == '\'' || c == '`') {
                emit(Kind.STRING, quoted(String.valueOf(c), true, false));
            } else if (c == '@' && position + 1 < source.length() &&
                    Character.isJavaIdentifierStart(source.codePointAt(position + 1))) {
                emit(Kind.ANNOTATION, annotationEnd(false));
            } else if (Character.isDigit(c)) {
                var end = position + 1;
                while (end < source.length() && Character.isDigit(source.charAt(end))) {
                    end++;
                }
                if (end + 1 < source.length() && source.charAt(end) == '.' && Character.isDigit(source.charAt(end + 1))) {
                    end += 2;
                    while (end < source.length() && Character.isDigit(source.charAt(end))) {
                        end++;
                    }
                }
                emit(Kind.NUMBER, end);
            } else if (Character.isJavaIdentifierStart(source.codePointAt(position))) {
                final var end = identifierEnd(position);
                emit(KEYWORDS.contains(source.substring(position, end)) ? Kind.KEYWORD : Kind.PLAIN, end);
            } else {
                emit("+-*/%=!<>|&^~?:;.,(){}[]".contains(String.valueOf(c)) ? Kind.OPERATOR : Kind.PLAIN, position + 1);
            }
        }
    }
}
