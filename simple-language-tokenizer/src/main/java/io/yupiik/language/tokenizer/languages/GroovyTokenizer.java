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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GroovyTokenizer implements Tokenizer {
    private static final Pattern JVM_NUMBER = Pattern.compile(
            "(?:0[xX][0-9a-fA-F][0-9a-fA-F_]*|0[bB][01][01_]*|" +
            "(?:[0-9][0-9_]*(?:\\.(?!\\.)[0-9_]*)?|\\.[0-9][0-9_]*)(?:[eE][+-]?[0-9][0-9_]*)?)[uU]?[lLfFdDgG]?");
    private static final Set<String> GROOVY_KEYWORDS = Set.of(
            "abstract", "as", "assert", "break", "case", "catch", "class", "const", "continue", "def", "default", "do", "else",
            "enum", "extends", "false", "final", "finally", "for", "goto", "if", "implements", "import", "in", "instanceof",
            "interface", "native", "new", "non-sealed", "null", "package", "permits", "private", "protected", "public", "record",
            "return", "sealed", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "trait",
            "transient", "true", "try", "var", "void", "volatile", "while", "yield");
    private static final Set<String> JAVA_TYPES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void", "Boolean", "Byte", "Character", "Double",
            "Float", "Integer", "Long", "Short", "String", "Object", "Class", "Exception", "RuntimeException", "List", "Map", "Set",
            "Collection", "Optional");

    @Override
    public List<Token> tokenize(final String source) {
        return new Tokenization(source).tokens();
    }

    private static final class Tokenization extends AbstractTokenizer {
        private final Matcher number;

        private Tokenization(final String source) {
            super(source);
            this.number = JVM_NUMBER.matcher(source);
        }

        @Override
        protected void step() {
            final var source = source();
            final var position = position();
            final var c = source.charAt(position);
            if (at("//")) {
                lineComment();
            } else if (at("/*")) {
                emit(Kind.COMMENT, through("*/", position + 2));
            } else if (at("$/")) {
                emit(Kind.STRING, dollarSlashy());
            } else if (c == '/' && slashyStart()) {
                emit(Kind.STRING, quoted("/", true, false));
            } else if (c == '"' || c == '\'') {
                final var triple = source.startsWith(String.valueOf(c).repeat(3), position);
                final var end = quoted(triple ? String.valueOf(c).repeat(3) : String.valueOf(c), true, false);
                emit(Kind.STRING, end);
            } else if (c == '@' && position + 1 < source.length() &&
                    Character.isJavaIdentifierStart(source.codePointAt(position + 1))) {
                emit(Kind.ANNOTATION, annotationEnd(false));
            } else if (Character.isDigit(c) || (c == '.' && position + 1 < source.length() &&
                    Character.isDigit(source.charAt(position + 1)))) {
                if (number.region(position, source.length()).lookingAt()) {
                    emit(Kind.NUMBER, number.end());
                } else {
                    emit(Kind.PLAIN, position + 1);
                }
            } else if (Character.isJavaIdentifierStart(source.codePointAt(position))) {
                final var end = identifierEnd(position);
                emit(wordKind(source.substring(position, end)), end);
            } else {
                emit("+-*/%=!<>|&^~?:;.,(){}[]".contains(String.valueOf(c)) ?
                        Kind.OPERATOR : Kind.PLAIN, position + 1);
            }
        }

        private Kind wordKind(final String word) {
            if (GROOVY_KEYWORDS.contains(word)) {
                return Kind.KEYWORD;
            }
            return JAVA_TYPES.contains(word) ? Kind.TYPE : Kind.PLAIN;
        }

        private int dollarSlashy() {
            final var source = source();
            final var position = position();
            var end = position + 2;
            while (end < source.length()) {
                if (source.startsWith("/$", end)) {
                    return end + 2;
                }
                if (source.charAt(end) == '$' && end + 1 < source.length() &&
                        (source.charAt(end + 1) == '$' || source.charAt(end + 1) == '/')) {
                    end += 2;
                } else if (source.startsWith("${", end)) {
                    end = templateEnd(end + 2);
                } else {
                    end++;
                }
            }
            return end;
        }

        private boolean slashyStart() {
            final var source = source();
            final var position = position();
            // Only an unambiguous expression prefix; never guess that division is a string.
            var previous = position - 1;
            while (previous >= 0 && Character.isWhitespace(source.charAt(previous))) {
                previous--;
            }
            if (previous < 0 || "=([{,:;!~?".contains(String.valueOf(source.charAt(previous)))) {
                return true;
            }
            final var end = previous + 1;
            while (previous >= 0 && Character.isJavaIdentifierPart(source.charAt(previous))) {
                previous--;
            }
            final var word = source.substring(previous + 1, end);
            return word.equals("return") || word.equals("case") || word.equals("throw");
        }

        @Override
        protected boolean interpolated(final String delimiter) {
            return delimiter.startsWith("\"") || delimiter.equals("/");
        }
    }
}
