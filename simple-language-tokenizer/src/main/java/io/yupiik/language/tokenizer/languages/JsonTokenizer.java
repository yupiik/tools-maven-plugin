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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonTokenizer implements Tokenizer {
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final Pattern JSON5_NUMBER = Pattern.compile("[+-]?(?:Infinity|NaN|0[xX][0-9a-fA-F]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private final boolean comments;
    private final boolean extended;

    public JsonTokenizer() {
        this(false, false);
    }

    protected JsonTokenizer(final boolean comments, final boolean extended) {
        this.comments = comments;
        this.extended = extended;
    }

    @Override
    public List<Token> tokenize(final String source) {
        return new Tokenization(source, comments, extended).tokens();
    }

    private static final class Tokenization extends AbstractTokenizer {
        private final Matcher number;
        private final boolean comments;
        private final boolean extended;

        private Tokenization(final String source, final boolean comments, final boolean extended) {
            super(source);
            this.comments = comments;
            this.extended = extended;
            this.number = (extended ? JSON5_NUMBER : JSON_NUMBER).matcher(source);
        }

        @Override
        protected void step() {
            final var source = source();
            final var position = position();
            final var c = source.charAt(position);
            if (comments && at("//")) {
                lineComment();
            } else if (comments && at("/*")) {
                emit(Kind.COMMENT, through("*/", position + 2));
            } else if (c == '"' || (extended && c == '\'')) {
                final var end = quoted(String.valueOf(c), true, false);
                var next = end;
                while (next < source.length() && Character.isWhitespace(source.charAt(next))) {
                    next++;
                }
                emit(next < source.length() && source.charAt(next) == ':' ? Kind.ATTRIBUTE : Kind.STRING, end);
            } else if (Character.isDigit(c) || (c == '.' && position + 1 < source.length() &&
                    Character.isDigit(source.charAt(position + 1))) || c == '-' || (extended && c == '+')) {
                if (number.region(position, source.length()).lookingAt()) {
                    emit(Kind.NUMBER, number.end());
                } else {
                    emit(Kind.PLAIN, position + 1);
                }
            } else if (Character.isJavaIdentifierStart(source.codePointAt(position))) {
                final var end = identifierEnd(position);
                var next = end;
                while (next < source.length() && Character.isWhitespace(source.charAt(next))) {
                    next++;
                }
                final var word = source.substring(position, end);
                emit(extended && next < source.length() && source.charAt(next) == ':' ? Kind.ATTRIBUTE :
                        extended && (word.equals("Infinity") || word.equals("NaN")) ? Kind.NUMBER : wordKind(word), end);
            } else {
                emit("{}[]:,".contains(String.valueOf(c)) ?
                        Kind.OPERATOR : Kind.PLAIN, position + 1);
            }
        }

        private Kind wordKind(final String word) {
            if (word.equals("true") || word.equals("false") || word.equals("null")) {
                return Kind.KEYWORD;
            }
            return Kind.PLAIN;
        }
    }
}
