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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiTest {
    @Test
    void discoveryReflectsExactImmutableRegistryNotResolver() {
        final var registry = new HashMap<String, Tokenizer>();
        registry.put(" Custom ", source -> new ArrayList<>(List.of(new Token(Kind.KEYWORD, source))));
        final var highlighter = new SyntaxHighlighter(new SyntaxHighlighter.Configuration()
                .setRegisteredTokenizers(registry)
                .setLanguageResolver(info -> " CUSTOM "));
        registry.clear();
        assertEquals(Set.of("custom"), highlighter.supportedLanguages());
        assertTrue(highlighter.supportsLanguage(" CUSTOM "));
        assertFalse(highlighter.supportsLanguage("custom 21"));
        assertFalse(highlighter.supportsLanguage("java"));
        assertFalse(highlighter.supportsLanguage(null));
        assertFalse(highlighter.supportsLanguage(" "));
        assertThrows(UnsupportedOperationException.class, () -> highlighter.supportedLanguages().clear());
        final var result = highlighter.highlight("custom 21", "source");
        assertEquals(List.of(new Token(Kind.KEYWORD, "source")), result);
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test
    void defaultConstructorsStayStrictAndResolverIsOptIn() {
        final var strict = new SyntaxHighlighter();
        final var configuredDefaults = new SyntaxHighlighter(new SyntaxHighlighter.Configuration());
        final var resolving = new SyntaxHighlighter(new SyntaxHighlighter.Configuration()
                .setLanguageResolver(strict::resolveLanguage));
        final var source = "public class Demo {}";
        assertEquals(strict.supportedLanguages(), configuredDefaults.supportedLanguages());
        for (final var alias : strict.supportedLanguages()) {
            assertEquals(strict.highlight(alias, source), configuredDefaults.highlight(alias, source), alias);
        }
        assertEquals(List.of(new Token(Kind.PLAIN, source)), configuredDefaults.highlight("Java 21", source));
        assertEquals(List.of(new Token(Kind.PLAIN, source)), strict.highlight("Java 21", source));
        assertEquals(strict.highlight("java", source), resolving.highlight("Java 21", source));
        assertEquals(List.of(new Token(Kind.PLAIN, source)), resolving.highlight("unknown java", source));
        final var rejectsNull = new SyntaxHighlighter(new SyntaxHighlighter.Configuration()
                .setRegisteredTokenizers(Map.of("custom", text -> List.of()))
                .setLanguageResolver(info -> {
                    throw new AssertionError("Null/blank languages must bypass the resolver");
                }));
        for (final var language : new String[]{null, "", " "}) {
            assertEquals(List.of(new Token(Kind.PLAIN, source)), rejectsNull.highlight(language, source));
            assertEquals(List.of(), rejectsNull.highlight(language, ""));
            assertThrows(NullPointerException.class, () -> rejectsNull.highlight(language, null));
        }
        assertThrows(NullPointerException.class, () -> new SyntaxHighlighter(null));
        assertThrows(NullPointerException.class, () -> new SyntaxHighlighter.Configuration().setRegisteredTokenizers(null));
        assertThrows(NullPointerException.class, () -> new SyntaxHighlighter.Configuration().setLanguageResolver(null));
    }

    @Test
    void configurationCanBeReusedWithoutChangingExistingInstances() {
        final var registry = new HashMap<String, Tokenizer>();
        registry.put("first", source -> List.of(new Token(Kind.KEYWORD, source)));
        final var configuration = new SyntaxHighlighter.Configuration()
                .setRegisteredTokenizers(registry)
                .setLanguageResolver(info -> "first");
        final var first = new SyntaxHighlighter(configuration);
        final var reused = new SyntaxHighlighter(configuration);
        registry.clear();
        configuration.setRegisteredTokenizers(Map.of("second", source -> List.of(new Token(Kind.TYPE, source))))
                .setLanguageResolver(info -> "second");
        final var second = new SyntaxHighlighter(configuration);
        configuration.setRegisteredTokenizers(Map.of()).setLanguageResolver(info -> null);
        for (final var existing : List.of(first, reused)) {
            assertEquals(Set.of("first"), existing.supportedLanguages());
            assertEquals(List.of(new Token(Kind.KEYWORD, "source")), existing.highlight("anything", "source"));
        }
        assertEquals(Set.of("second"), second.supportedLanguages());
        assertEquals(List.of(new Token(Kind.TYPE, "source")), second.highlight("anything", "source"));
        final var empty = new SyntaxHighlighter(configuration);
        assertTrue(empty.supportedLanguages().isEmpty());
        assertEquals(List.of(new Token(Kind.PLAIN, "source")), empty.highlight("anything", "source"));
    }

    @Test
    void builtInAliasesResolveToSupportedCanonicalNames() {
        final var highlighter = new SyntaxHighlighter();
        for (final var alias : highlighter.supportedLanguages()) {
            final var canonical = highlighter.resolveLanguage(alias);
            assertTrue(highlighter.supportsLanguage(canonical), alias);
            assertEquals(canonical, highlighter.guessLanguage("dir/file." + alias), alias);
        }
        for (final var alias : Set.of("text", "txt", "plain", "plaintext", "console", "log")) {
            assertTrue(highlighter.supportsLanguage(alias));
            assertEquals("plain", highlighter.resolveLanguage(alias));
            assertEquals(List.of(new Token(Kind.PLAIN, "<tag> public\r\n")), highlighter.highlight(alias, "<tag> public\r\n"));
            assertEquals(List.of(), highlighter.highlight(alias, ""));
        }
        assertEquals(highlighter.highlight("javascript", "const n = 1"), highlighter.highlight("jsx", "const n = 1"));
        assertEquals(highlighter.highlight("typescript", "interface X {}"), highlighter.highlight("tsx", "interface X {}"));
        assertTrue(highlighter.highlight("kotlin", "fun main() = true").contains(new Token(Kind.KEYWORD, "fun")));
        assertTrue(highlighter.highlight("properties", "name=value").contains(new Token(Kind.ATTRIBUTE, "name")));
        assertTrue(highlighter.highlight("ini", "[section]\nname=value").contains(new Token(Kind.ATTRIBUTE, "name")));
        assertTrue(highlighter.highlight("toml", "name=true").contains(new Token(Kind.ATTRIBUTE, "name")));
    }

    @Test
    void pathGuessingAndFenceInfoUseDeterministicWholeIdentifiers() {
        final var highlighter = new SyntaxHighlighter();
        final var paths = Map.ofEntries(
                Map.entry("src/Example.JAVA", "java"),
                Map.entry("C:\\src\\example.KTS", "kotlin"),
                Map.entry("dir.with.dot/file.kt", "kotlin"),
                Map.entry("settings.properties", "properties"),
                Map.entry("settings.ini", "ini"), Map.entry("settings.toml", "toml"),
                Map.entry("a.jsonc", "jsonc"), Map.entry("a.json5", "json5"),
                Map.entry(" /build/Dockerfile ", "dockerfile"),
                Map.entry("C:\\build\\Dockerfile.dev", "dockerfile"),
                Map.entry("README", "plain"), Map.entry("notes.TXT", "plain"),
                Map.entry("view.JSX", "javascript"), Map.entry("view.tsx", "typescript"));
        paths.forEach((path, expected) -> assertEquals(expected, highlighter.guessLanguage(path), path));
        final var infoCases = Map.ofEntries(
                Map.entry(" Java 21 ", "java"), Map.entry("JAVASCRIPT", "javascript"),
                Map.entry("javascript title=java", "javascript"), Map.entry("```TSX title=demo", "typescript"),
                Map.entry("{.KT #example}", "kotlin"), Map.entry("java-properties", "properties"),
                Map.entry("JSONC title=demo", "jsonc"), Map.entry("JSON5", "json5"),
                Map.entry("~~~Py", "python"), Map.entry("txt linenums", "plain"));
        infoCases.forEach((info, expected) -> assertEquals(expected, highlighter.resolveLanguage(info), info));
        for (final var unknown : new String[]{null, "", " ", "unknown", "notjavascript", "javaish", "unknown java", "21 java", "title=java"}) {
            assertNull(highlighter.resolveLanguage(unknown), unknown);
        }
        for (final var unknown : new String[]{null, "", " ", "java", "folder.java/file", "file.unknown", "file.", "Dockerfileish", "dir.java/"}) {
            assertNull(highlighter.guessLanguage(unknown), unknown);
        }
    }

    @Test
    void helpersUseOnlyTheirRegistryAndNeverInvokeTheResolver() {
        final Tokenizer tokenizer = source -> List.of(new Token(Kind.KEYWORD, source));
        final var highlighter = new SyntaxHighlighter(new SyntaxHighlighter.Configuration()
                .setRegisteredTokenizers(Map.of(" CuStOm ", tokenizer, "custom/key", tokenizer, " KT ", tokenizer))
                .setLanguageResolver(info -> {
                    throw new AssertionError("Registry helpers must not invoke the resolver");
                }));
        assertEquals(Set.of("custom", "custom/key", "kt"), highlighter.supportedLanguages());
        for (final var info : Set.of("CUSTOM", " Custom title=java ", "```CUSTOM", "{.Custom #id}")) {
            assertEquals("custom", highlighter.resolveLanguage(info), info);
        }
        assertEquals("custom/key", highlighter.resolveLanguage("CUSTOM/KEY"));
        assertEquals("custom/key", highlighter.resolveLanguage("CUSTOM/KEY title=java"));
        assertEquals("custom", highlighter.guessLanguage("dir/example.CUSTOM"));
        assertEquals("custom", highlighter.guessLanguage("C:\\dir\\example.CUSTOM"));
        assertEquals("kt", highlighter.resolveLanguage("KT"));
        assertEquals("kt", highlighter.resolveLanguage("{.KT #id}"));
        assertEquals("kt", highlighter.guessLanguage("example.KT"));
        for (final var info : new String[]{null, "", " ", "kotlin", "kts", "java", "JavaScript title", "unknown custom"}) {
            assertNull(highlighter.resolveLanguage(info), info);
        }
        for (final var path : new String[]{null, "", " ", "example.kotlin", "example.kts", "example.java", "README", "Dockerfile", "Dockerfile.dev", "file.txt"}) {
            assertNull(highlighter.guessLanguage(path), path);
        }
    }

    @Test
    void canonicalNamesAreUsedOnlyWhenRegistered() {
        final Tokenizer tokenizer = source -> List.of(new Token(Kind.KEYWORD, source));
        final var canonicalOnly = new SyntaxHighlighter(new SyntaxHighlighter.Configuration()
                .setRegisteredTokenizers(Map.of(" Kotlin ", tokenizer)));
        final var both = new SyntaxHighlighter(new SyntaxHighlighter.Configuration()
                .setRegisteredTokenizers(Map.of("kt", tokenizer, "kotlin", tokenizer)));
        for (final var highlighter : List.of(canonicalOnly, both)) {
            assertEquals("kotlin", highlighter.resolveLanguage("KT title=demo"));
            assertEquals("kotlin", highlighter.guessLanguage("source.kt"));
        }
        final var empty = new SyntaxHighlighter(new SyntaxHighlighter.Configuration().setRegisteredTokenizers(Map.of()));
        assertNull(empty.resolveLanguage("java"));
        assertNull(empty.guessLanguage("file.java"));
        assertNull(empty.guessLanguage("Dockerfile"));
        assertNull(empty.guessLanguage("README"));
        final var defaults = new SyntaxHighlighter();
        assertEquals("kotlin", defaults.resolveLanguage("kt"));
        assertEquals("javascript", defaults.resolveLanguage("JavaScript title"));
        assertFalse(defaults.resolveLanguage("JavaScript title").equals("java"));
    }

    @Test
    void xmlElementNamesAreTagsNotTypesOrAttributes() {
        final var highlighter = new SyntaxHighlighter();
        final var source = "<ns:root id=\"value\"><child/></ns:root>";
        final var tokens = highlighter.highlight("xml", source);
        assertEquals(List.of(new Token(Kind.OPERATOR, "<"), new Token(Kind.TAG, "ns:root"),
                new Token(Kind.PLAIN, " "), new Token(Kind.ATTRIBUTE, "id"), new Token(Kind.OPERATOR, "="),
                new Token(Kind.STRING, "\"value\""), new Token(Kind.OPERATOR, "><"),
                new Token(Kind.TAG, "child"), new Token(Kind.OPERATOR, "/></"),
                new Token(Kind.TAG, "ns:root"), new Token(Kind.OPERATOR, ">")), tokens);
        assertFalse(tokens.stream().anyMatch(token -> token.kind() == Kind.TYPE));
    }

    @Test
    void lineSplittingRemovesAllNewlineFormsAndPreservesKinds() {
        assertEquals(List.of(List.of()), Tokenizing.lines(List.of()));
        assertEquals(List.of(List.of()), Tokenizing.lines(List.of(new Token(Kind.PLAIN, ""))));
        assertEquals(List.of(List.of(), List.of()), Tokenizing.lines(List.of(new Token(Kind.PLAIN, "\r\n"))));
        assertEquals(List.of(List.of(), List.of(), List.of(), List.of()),
                Tokenizing.lines(List.of(new Token(Kind.PLAIN, "\n\r\r\n"))));
        final var tokens = List.of(new Token(Kind.COMMENT, "a\r\nb\rc\nd"), new Token(Kind.NUMBER, "2\n"));
        final var lines = Tokenizing.lines(tokens);
        assertEquals(List.of(List.of(new Token(Kind.COMMENT, "a")), List.of(new Token(Kind.COMMENT, "b")),
                List.of(new Token(Kind.COMMENT, "c")), List.of(new Token(Kind.COMMENT, "d"), new Token(Kind.NUMBER, "2")),
                List.of()), lines);
        assertThrows(UnsupportedOperationException.class, () -> lines.clear());
        assertThrows(UnsupportedOperationException.class, () -> lines.get(0).clear());
        assertEquals("a\r\nb\rc\nd", tokens.get(0).text());
        assertThrows(NullPointerException.class, () -> Tokenizing.lines(null));
        assertThrows(NullPointerException.class, () -> Tokenizing.lines(Arrays.asList((Token) null)));
    }

    @Test
    void crlfAcrossTokensIsOneNewlineWithoutCoalescing() {
        assertEquals(List.of(List.of(new Token(Kind.STRING, "a")),
                        List.of(new Token(Kind.COMMENT, "b"), new Token(Kind.COMMENT, "c")), List.of()),
                Tokenizing.lines(List.of(new Token(Kind.STRING, "a\r"), new Token(Kind.PLAIN, ""),
                        new Token(Kind.COMMENT, "\nb"), new Token(Kind.COMMENT, "c\r"), new Token(Kind.NUMBER, "\n"))));
        assertEquals(List.of(List.of(), List.of(new Token(Kind.STRING, "x")), List.of()),
                Tokenizing.lines(List.of(new Token(Kind.PLAIN, "\r"), new Token(Kind.STRING, "x\n"))));
    }
}
