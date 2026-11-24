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

import io.yupiik.language.tokenizer.languages.TomlTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlAdversarialTest {
    @Test
    @Timeout(10)
    void manyInlineKeysDoNotRescanTheLineSuffix() {
        final var tokenizer = new TomlTokenizer();
        for (final var key : List.of("key", "\"quoted.key\"")) {
            final var source = "data = {" + (key + "=1,").repeat(50_000) + "last=true}\r\nnext=2\n";
            final var tokens = tokenizer.tokenize(source);
            assertEquals(source, tokens.stream().map(Token::text).collect(joining()));
            assertEquals(50_000, tokens.stream().filter(token -> token.equals(new Token(Kind.ATTRIBUTE, key))).count());
            assertTrue(tokens.contains(new Token(Kind.ATTRIBUTE, "next")));
            assertTrue(tokens.contains(new Token(Kind.NUMBER, "2")));
        }
    }

    @Test
    void cachedLineEndsDoNotChangeMalformedOrMultilineInput() {
        final var tokenizer = new TomlTokenizer();
        for (final var source : List.of("data={one=1,two=2}\r\nnext={three=3}\n", "\"broken\nkey=1\" = 3\nnext=2")) {
            assertEquals(source, tokenizer.tokenize(source).stream().map(Token::text).collect(joining()));
        }
    }
}
