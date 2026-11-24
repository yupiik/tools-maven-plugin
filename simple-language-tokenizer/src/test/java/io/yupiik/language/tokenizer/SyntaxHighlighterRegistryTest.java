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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.stream.Collectors.joining;

class SyntaxHighlighterRegistryTest {
    @Test
    void everyRequestedAliasSelectsAnActualTokenizer() {
        final var cases = Map.ofEntries(
                Map.entry(List.of("java"), "public class Example {}"),
                Map.entry(List.of("kt", "kts", "kotlin"), "fun main() = true"),
                Map.entry(List.of("xml", "html", "htm", "svg", "xhtml"), "<root name=\"value\"/>"),
                Map.entry(List.of("css", "scss", "less"), "/* comment */ .box { color: red; }"),
                Map.entry(List.of("json", "jsonc", "json5"), "{\"value\": true}"),
                Map.entry(List.of("yaml", "yml"), "name: true\n"),
                Map.entry(List.of("js", "mjs", "cjs", "javascript"), "const value = true;"),
                Map.entry(List.of("ts", "mts", "cts", "typescript"), "interface Example {}"),
                Map.entry(List.of("py", "pyw", "python"), "def example(): return True"),
                Map.entry(List.of("sh", "bash", "zsh", "shell"), "echo \"$HOME\""),
                Map.entry(List.of("sql"), "SELECT * FROM example"),
                Map.entry(List.of("md", "markdown"), "# Heading\n"),
                Map.entry(List.of("adoc", "asciidoc"), "= Heading\n"),
                Map.entry(List.of("properties", "java-properties"), "name=value\n"),
                Map.entry(List.of("ini"), "[section]\nname=value\n"),
                Map.entry(List.of("toml"), "[section]\nname=true\n"),
                Map.entry(List.of("gradle", "groovy"), "def value = true"),
                Map.entry(List.of("go", "golang"), "package main\nfunc main() {}"),
                Map.entry(List.of("docker", "dockerfile"), "FROM alpine\n"));
        final var highlighter = new SyntaxHighlighter();
        cases.forEach((aliases, source) -> {
            for (final var alias : aliases) {
                final var tokens = highlighter.highlight(" " + alias.toUpperCase(Locale.ROOT) + " ", source);
                assertEquals(source, tokens.stream().map(Token::text).collect(joining()), alias);
                assertTrue(tokens.stream().anyMatch(token -> token.kind() != Kind.PLAIN), alias);
                assertEquals(highlighter.highlight(aliases.get(0), source), tokens, alias);
            }
        });
    }

    @Test
    void jsonVariantsHaveDedicatedLiteralAndCommentRules() {
        final var highlighter = new SyntaxHighlighter();
        final var jsonc = "{ // comment\r\n\"value\": true, /* block\ncomment */ }";
        final var json5 = "{unquoted: 'value', hex: 0xFF, other: +Infinity, nan: NaN, fraction: .5, // comment\n}";
        for (final var input : Map.of("jsonc", jsonc, "json5", json5).entrySet()) {
            final var tokens = highlighter.highlight(input.getKey(), input.getValue());
            assertEquals(input.getValue(), tokens.stream().map(Token::text).collect(joining()));
            assertTrue(tokens.stream().anyMatch(token -> token.kind() == Kind.COMMENT));
            assertThrows(UnsupportedOperationException.class, () -> tokens.add(new Token(Kind.PLAIN, "")));
        }
        assertTrue(highlighter.highlight("json", jsonc).stream().noneMatch(token -> token.kind() == Kind.COMMENT));
        assertTrue(highlighter.highlight("json5", json5).contains(new Token(Kind.ATTRIBUTE, "unquoted")));
        assertTrue(highlighter.highlight("json5", json5).contains(new Token(Kind.STRING, "'value'")));
        for (final var number : List.of("0xFF", "+Infinity", "NaN", ".5")) {
            assertTrue(highlighter.highlight("json5", json5).contains(new Token(Kind.NUMBER, number)), number);
        }
    }

    @Test
    void registryRejectsBlankAndAmbiguousAliases() {
        final Tokenizer tokenizer = source -> List.of(new Token(Kind.PLAIN, source));
        assertThrows(IllegalArgumentException.class, () -> new SyntaxHighlighter(
                new SyntaxHighlighter.Configuration().setRegisteredTokenizers(Map.of(" ", tokenizer))));
        assertThrows(IllegalArgumentException.class, () -> new SyntaxHighlighter(
                new SyntaxHighlighter.Configuration().setRegisteredTokenizers(Map.of("java", tokenizer, " JAVA ", tokenizer))));
    }

    @Test
    void suppliedRegistrySelectsTokenizerByNormalizedLanguageAndPassesSourceUnchanged() {
        final var calls = new AtomicInteger();
        final Tokenizer tokenizer = source -> {
            calls.incrementAndGet();
            return List.of(new Token(Kind.KEYWORD, source));
        };
        final var highlighter = new SyntaxHighlighter(
                new SyntaxHighlighter.Configuration().setRegisteredTokenizers(Map.of(" Custom ", tokenizer)));
        final var source = "unchanged\r\n\t<source>";
        assertEquals(List.of(new Token(Kind.KEYWORD, source)), highlighter.highlight(" CUSTOM ", source));
        assertEquals(1, calls.get());
        assertEquals(List.of(new Token(Kind.PLAIN, source)), highlighter.highlight("java", source),
                "A supplied registry replaces rather than implicitly extends the defaults");
        assertEquals(1, calls.get());
    }

    @Test
    void registryIsDefensivelyCopiedAndEmptyAndUnknownInputsStayPlain() {
        final var tokenizers = new HashMap<String, Tokenizer>();
        tokenizers.put("custom", source -> List.of(new Token(Kind.TYPE, source)));
        final var highlighter = new SyntaxHighlighter(
                new SyntaxHighlighter.Configuration().setRegisteredTokenizers(tokenizers));
        tokenizers.clear();
        assertEquals(List.of(new Token(Kind.TYPE, "text")), highlighter.highlight("custom", "text"));
        assertTrue(highlighter.highlight("custom", "").isEmpty());
        for (final var language : new String[]{null, "", " ", "unknown"}) {
            assertEquals(List.of(new Token(Kind.PLAIN, "text")), highlighter.highlight(language, "text"));
        }
        assertThrows(NullPointerException.class, () -> highlighter.highlight("custom", null));
    }
}
