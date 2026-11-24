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

import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.horizontal;

/** Conservative, lossless properties lexer; each tokenization uses a fresh session. */
public final class PropertiesTokenizer implements Tokenizer {
    private RangeBuffer b;
    private String s;

    @Override
    public List<Token> tokenize(final String source) {
        final var session = new PropertiesTokenizer();
        session.b = new RangeBuffer(source);
        session.s = source;
        session.tokenize();
        return session.b.tokens();
    }

    private void tokenize() {
        var line = 0;
        var continued = false;
        var key = true;
        while (line < s.length()) {
            final var end = b.lineEnd(line);
            var i = b.spaces(line, end);
            if (!continued) {
                key = true;
                if (i < end && (s.charAt(i) == '#' || s.charAt(i) == '!')) {
                    b.mark(i, end, Kind.COMMENT);
                    line = b.nextLine(end);
                    continue;
                }
            }
            var slashes = end;
            while (slashes > i && s.charAt(slashes - 1) == '\\') {
                slashes--;
            }
            continued = (end - slashes) % 2 != 0;
            final var contentEnd = continued ? end - 1 : end;
            if (key) {
                final var start = i;
                while (i < contentEnd) {
                    final var c = s.charAt(i);
                    if (c == '\\') {
                        i = Math.min(contentEnd, i + 2);
                    } else if (horizontal(c) || c == ':' || c == '=') {
                        break;
                    } else {
                        i++;
                    }
                }
                b.mark(start, i, Kind.ATTRIBUTE);
                if (i < contentEnd) {
                    key = false;
                    i = b.spaces(i, contentEnd);
                    if (i < contentEnd && (s.charAt(i) == ':' || s.charAt(i) == '=')) {
                        b.mark(i, i + 1, Kind.OPERATOR);
                        i++;
                    }
                    i = b.spaces(i, contentEnd);
                }
            }
            if (!key) {
                b.mark(i, contentEnd, Kind.STRING);
            }
            if (continued) {
                b.mark(contentEnd, end, Kind.OPERATOR);
            }
            line = b.nextLine(end);
        }
    }
}
