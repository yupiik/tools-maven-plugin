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
package io.yupiik.language.tokenizer;

import io.yupiik.language.tokenizer.languages.GenericTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericTokenizerTest {
    @Test
    void genericIsExplicitImmutableAndLossless() {
        final var tokenizer = new GenericTokenizer();
        final var source = "@Example const value = \"é😀\" + 12.5; // comment\r\nreturn `value` # rest\n";
        final var tokens = tokenizer.tokenize(source);
        assertEquals(source, tokens.stream().map(Token::text).collect(joining()));
        for (final var token : List.of(new Token(Kind.ANNOTATION, "@Example"), new Token(Kind.KEYWORD, "const"),
                new Token(Kind.STRING, "\"é😀\""), new Token(Kind.NUMBER, "12.5"), new Token(Kind.COMMENT, "// comment"))) {
            assertTrue(tokens.contains(token), token.toString());
        }
        assertThrows(UnsupportedOperationException.class, () -> tokens.add(new Token(Kind.PLAIN, "x")));
        assertEquals(List.of(new Token(Kind.PLAIN, source)), new SyntaxHighlighter().highlight("unknown", source));
        assertTrue(tokenizer.tokenize("").isEmpty());
        assertThrows(NullPointerException.class, () -> tokenizer.tokenize(null));
        assertEquals(List.of(new Token(Kind.KEYWORD, "return")), tokenizer.tokenize("return"));
    }

    @Test
    @Timeout(5)
    void longMalformedInputsMakeForwardProgress() {
        final var tokenizer = new GenericTokenizer();
        for (final var source : List.of("[".repeat(250_000), "/*" + "*".repeat(250_000),
                "\"" + "\\\"".repeat(100_000), "9".repeat(250_000), "😀".repeat(100_000))) {
            assertEquals(source, tokenizer.tokenize(source).stream().map(Token::text).collect(joining()));
        }
    }
}
