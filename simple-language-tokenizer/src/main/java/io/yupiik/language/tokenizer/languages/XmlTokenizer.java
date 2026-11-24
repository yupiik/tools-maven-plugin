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

public final class XmlTokenizer implements Tokenizer {
    @Override
    public List<Token> tokenize(final String source) {
        return new Tokenization(source).tokens();
    }

    private static final class Tokenization extends AbstractTokenizer {
        private boolean inTag;
        private boolean tagName;

        private Tokenization(final String source) {
            super(source);
        }

        @Override
        protected void step() {
            final var source = source();
            final var position = position();
            final var c = source.charAt(position);
            if (!inTag && at("<!--")) {
                emit(Kind.COMMENT, through("-->", position + 4));
            } else if (!inTag && at("<![CDATA[")) {
                emit(Kind.STRING, through("]]>", position + 9));
            } else if (!inTag && at("<?")) {
                emit(Kind.PLAIN, through("?>", position + 2));
            } else if (!inTag && at("<!")) {
                emit(Kind.PLAIN, through(">", position + 2));
            } else if (!inTag && c == '<' && position + 1 < source.length() &&
                    (xmlNameStart(source.charAt(position + 1)) ||
                            (source.charAt(position + 1) == '/' && position + 2 < source.length() && xmlNameStart(source.charAt(position + 2))))) {
                inTag = true;
                tagName = true;
                emit(Kind.OPERATOR, position + (at("</") ? 2 : 1));
            } else if (inTag && (at("/>") || c == '>')) {
                inTag = false;
                tagName = false;
                emit(Kind.OPERATOR, position + (at("/>") ? 2 : 1));
            } else if (inTag && (c == '\'' || c == '"')) {
                emit(Kind.STRING, quoted(String.valueOf(c), false, false));
            } else if (inTag && xmlNameStart(c)) {
                var end = position + 1;
                while (end < source.length() && (xmlNameStart(source.charAt(end)) ||
                        Character.isDigit(source.charAt(end)) || source.charAt(end) == '-' || source.charAt(end) == '.')) {
                    end++;
                }
                emit(tagName ? Kind.TAG : Kind.ATTRIBUTE, end);
                tagName = false;
            } else {
                emit(inTag && c == '=' ? Kind.OPERATOR : Kind.PLAIN, position + 1);
            }
        }

        private boolean xmlNameStart(final char c) {
            return Character.isLetter(c) || c == '_' || c == ':';
        }

    }
}
