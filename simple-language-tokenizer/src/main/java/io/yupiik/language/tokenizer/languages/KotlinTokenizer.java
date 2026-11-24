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

public final class KotlinTokenizer implements Tokenizer {
    private static final Pattern JVM_NUMBER = Pattern.compile(
            "(?:0[xX][0-9a-fA-F][0-9a-fA-F_]*|0[bB][01][01_]*|" +
            "(?:[0-9][0-9_]*(?:\\.(?!\\.)[0-9_]*)?|\\.[0-9][0-9_]*)(?:[eE][+-]?[0-9][0-9_]*)?)[uU]?[lLfFdDgG]?");
    private static final Set<String> KOTLIN_KEYWORDS = Set.of(
            "abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion", "const", "constructor",
            "continue", "crossinline", "data", "delegate", "do", "dynamic", "else", "enum", "expect", "external", "false", "field",
            "file", "final", "finally", "for", "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface",
            "internal", "is", "lateinit", "noinline", "null", "object", "open", "operator", "out", "override", "package", "param",
            "private", "property", "protected", "public", "receiver", "reified", "return", "sealed", "set", "setparam", "super",
            "suspend", "tailrec", "this", "throw", "true", "try", "typealias", "typeof", "val", "value", "var", "vararg", "when",
            "where", "while");
    private static final Set<String> KOTLIN_TYPES = Set.of(
            "Any", "Array", "Boolean", "Byte", "Char", "Double", "Float", "Int", "Long", "Nothing", "Number", "Short", "String",
            "Unit", "UByte", "UInt", "ULong", "UShort");

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
                emit(Kind.COMMENT, nestedComment());
            } else if (c == '`') {
                emit(Kind.PLAIN, quoted("`", false, false));
            } else if (c == '"' || c == '\'') {
                final var triple = c == '"' && source.startsWith(String.valueOf(c).repeat(3), position);
                final var end = quoted(triple ? String.valueOf(c).repeat(3) : String.valueOf(c), !triple, false);
                emit(Kind.STRING, end);
            } else if (c == '@' && position + 1 < source.length() &&
                    Character.isJavaIdentifierStart(source.codePointAt(position + 1))) {
                emit(Kind.ANNOTATION, annotationEnd(true));
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
            if (KOTLIN_KEYWORDS.contains(word)) {
                return Kind.KEYWORD;
            }
            return KOTLIN_TYPES.contains(word) ? Kind.TYPE : Kind.PLAIN;
        }

        private int nestedComment() {
            final var source = source();
            final var position = position();
            var depth = 1;
            var end = position + 2;
            while (end < source.length()) {
                if (source.startsWith("/*", end)) {
                    depth++;
                    end += 2;
                } else if (source.startsWith("*/", end)) {
                    end += 2;
                    if (--depth == 0) {
                        return end;
                    }
                } else {
                    end++;
                }
            }
            return end;
        }

        @Override
        protected boolean interpolated(final String delimiter) {
            return delimiter.startsWith("\"") || delimiter.equals("/");
        }

        @Override
        protected boolean templateBackticks() {
            return true;
        }
    }
}
