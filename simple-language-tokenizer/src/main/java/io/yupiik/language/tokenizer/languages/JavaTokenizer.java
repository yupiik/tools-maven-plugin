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

public final class JavaTokenizer implements Tokenizer {
    private static final Pattern NUMBER = Pattern.compile(
            "(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|(?:[0-9][0-9_]*(?:\\.[0-9_]*)?|\\.[0-9_]+)(?:[eE][+-]?[0-9_]+)?)[fFdDlLnN]?");
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default", "do", "else", "enum", "exports",
            "extends", "final", "finally", "for", "if", "implements", "import", "instanceof", "interface", "module", "native",
            "new", "non-sealed", "open", "opens", "package", "permits", "private", "protected", "provides", "public", "record",
            "requires", "return", "sealed", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "to",
            "transient", "transitive", "try", "uses", "var", "volatile", "when", "while", "with", "yield", "true", "false", "null");
    private static final Set<String> JAVA_TYPES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void", "Boolean", "Byte", "Character", "Double",
            "Float", "Integer", "Long", "Short", "String", "Object", "Class", "Exception", "RuntimeException", "List", "Map", "Set",
            "Collection", "Optional", "CharSequence", "StringBuilder", "StringBuffer", "ArrayList", "LinkedList", "HashMap",
            "LinkedHashMap", "HashSet", "OptionalInt", "OptionalLong", "OptionalDouble", "Stream", "Collectors",
            "CompletableFuture", "BigDecimal", "BigInteger", "Arrays", "Objects", "Path", "Iterable");

    @Override
    public List<Token> tokenize(final String source) {
        return new Tokenization(source).tokens();
    }

    private static final class Tokenization extends AbstractTokenizer {
        private final Matcher number;

        private Tokenization(final String source) {
            super(source);
            this.number = NUMBER.matcher(source);
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
            } else if (c == '"' || c == '\'') {
                final var triple = c == '"' && source.startsWith(String.valueOf(c).repeat(3), position);
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
            if (JAVA_KEYWORDS.contains(word)) {
                return Kind.KEYWORD;
            }
            return JAVA_TYPES.contains(word) ? Kind.TYPE : Kind.PLAIN;
        }
    }
}
