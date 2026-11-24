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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdditionalTokenizersTest {
    @Test
    void markdownListsTablesAndCommonInlineMarkup() {
        final var tokens = lossless(new MarkdownTokenizer(), "# Title\r\n- item\n+ item\n* item\n" +
                "**bold** *em* _em2_ `code` [link](target)\n| a | **b** | `c|d` |\n| --- | --- |\n---\n");
        for (final var text : List.of("# Title", "-", "+", "*", "**bold**", "*em*", "_em2_", "|", "---")) {
            token(tokens, Kind.KEYWORD, text);
        }
        token(tokens, Kind.KEYWORD, "**b**");
        token(tokens, Kind.STRING, "`code`");
        token(tokens, Kind.STRING, "`c|d`");
        token(tokens, Kind.ANNOTATION, "[link](target)");
    }

    @Test
    void asciidocInlineParityAndDelimiters() {
        final var tokens = lossless(new AsciidocTokenizer(), "*bold* _italic_ **strong** __em__ +code+ `code2` " +
                "[[anchor]] and {value}\n[[whole-line]]\n" +
                "====\n****\n____\n--\n|===\n\\*escaped\\* \\+escaped\\+\n");
        for (final var text : List.of("*bold*", "_italic_", "**strong**", "__em__")) {
            token(tokens, Kind.KEYWORD, text);
        }
        token(tokens, Kind.STRING, "+code+");
        token(tokens, Kind.STRING, "`code2`");
        token(tokens, Kind.ANNOTATION, "[[anchor]]");
        token(tokens, Kind.ANNOTATION, "[[whole-line]]");
        token(tokens, Kind.ATTRIBUTE, "{value}");
        for (final var text : List.of("====", "****", "____", "--", "|===")) {
            token(tokens, Kind.OPERATOR, text);
        }
        assertFalse(tokens.stream().anyMatch(t -> t.kind() != Kind.PLAIN && t.text().contains("escaped")));
    }

    @Test
    void inlineWindowAndExactRunBoundaries() {
        for (final var tokenizer : List.of(new MarkdownTokenizer(), new AsciidocTokenizer())) {
            final var within = "`" + "x".repeat(510) + "`";
            token(lossless(tokenizer, within), Kind.STRING, within);
            final var beyond = "x `" + "a".repeat(511) + "`";
            assertEquals(List.of(new Token(Kind.PLAIN, beyond)), tokenizer.tokenize(beyond));
            final var truncatedRun = "x `" + "a".repeat(510) + "``";
            assertEquals(List.of(new Token(Kind.PLAIN, truncatedRun)), tokenizer.tokenize(truncatedRun));
            final var differentRuns = "x ``a` b``` c``";
            token(lossless(tokenizer, differentRuns), Kind.STRING, "``a` b``` c``");
        }
    }

    @Test
    void inlineLimitDoesNotLimitWholeLinesOrOpaqueBodies() {
        final var heading = "# " + "title".repeat(1_000);
        token(lossless(new MarkdownTokenizer(), heading), Kind.KEYWORD, heading);
        final var body = "[[ ` * + {name} image:x[".repeat(1_000) + "\r\n";
        final var fence = "`".repeat(1_024);
        token(lossless(new MarkdownTokenizer(), fence + "\r\n" + body + fence), Kind.STRING, body);
        token(lossless(new AsciidocTokenizer(), "----\r\n" + body + "----"), Kind.STRING, body);
        token(lossless(new AsciidocTokenizer(), "////\r\n" + body + "////"), Kind.COMMENT, body);
        final var annotation = "[" + "role,".repeat(1_000) + "]";
        token(lossless(new AsciidocTokenizer(), annotation), Kind.ANNOTATION, annotation);
    }

    @Test
    @Timeout(30)
    void adversarialInlineCandidatesAtIncreasingSizes() {
        // Absolute, generous ceiling rather than machine-dependent timing ratios.
        for (final var size : new int[]{10_000, 100_000, 500_000}) {
            for (final var fragment : List.of("[[", "[a](b", "link:", "image:x[", "xref:[", "*a", "_+", "{name")) {
                final var source = "x " + fragment.repeat(size / fragment.length());
                lossless(new MarkdownTokenizer(), source);
                lossless(new AsciidocTokenizer(), source);
            }
            for (final var delimiter : List.of("`", "*", "_", "+")) {
                final var varying = new StringBuilder("x ");
                for (var length = 1; varying.length() < size; length++) {
                    varying.append(delimiter.repeat(length)).append('x');
                }
                lossless(new MarkdownTokenizer(), varying.toString());
                lossless(new AsciidocTokenizer(), varying.toString());
                lossless(new MarkdownTokenizer(), "x " + delimiter.repeat(size));
                lossless(new AsciidocTokenizer(), "x " + delimiter.repeat(size));
            }
        }
    }

    @Test
    @Timeout(30)
    void realisticDocumentsAtIncreasingSizes() {
        final var markdown = "# Title\nSome **bold**, *em*, `code` and [link](https://host/a).\n" +
                "- item\n| a | b |\n| --- | --- |\n```java\n*opaque* [[\n```\n";
        final var asciidoc = "= Title\n:author: me\n*bold* _em_ +code+ [[anchor]] link:https://host[a]\n" +
                "[source,java]\n----\n*opaque* [[\n----\n";
        for (final var repetitions : new int[]{100, 1_000, 10_000}) {
            lossless(new MarkdownTokenizer(), markdown.repeat(repetitions));
            lossless(new AsciidocTokenizer(), asciidoc.repeat(repetitions));
        }
    }

    @Test
    void cssCategoriesAndPreprocessorVariables() {
        final var tokens = lossless(new CssTokenizer(), "/* theme */\r\n@media screen {\n.card, #main:hover {\n" +
                " color: #aabbcc; width: 12.5rem; content: \"a\\\"b\"; background: url(https://host/a);\n" +
                " $gap: 2px; margin: $gap; @tone: red; border-color: @tone; } }\n// scss comment\n");
        token(tokens, Kind.COMMENT, "/* theme */");
        token(tokens, Kind.COMMENT, "// scss comment");
        token(tokens, Kind.KEYWORD, "@media");
        token(tokens, Kind.TYPE, ".card");
        token(tokens, Kind.TYPE, "#main");
        token(tokens, Kind.ATTRIBUTE, "color");
        token(tokens, Kind.ATTRIBUTE, "width");
        token(tokens, Kind.ATTRIBUTE, "$gap");
        token(tokens, Kind.ATTRIBUTE, "@tone");
        token(tokens, Kind.NUMBER, "#aabbcc");
        token(tokens, Kind.NUMBER, "12.5rem");
        token(tokens, Kind.STRING, "\"a\\\"b\"");
        token(tokens, Kind.OPERATOR, ":");
        assertFalse(tokens.stream().anyMatch(t -> t.kind() == Kind.COMMENT && t.text().contains("host")));
    }

    @Test
    void cssKeepsStringsAndCommentsOpaque() {
        final var tokens = lossless(new CssTokenizer(), "a { content: '/* not comment */'; } /* \" $x: 2px");
        token(tokens, Kind.STRING, "'/* not comment */'");
        token(tokens, Kind.COMMENT, "/* \" $x: 2px");
        final var unclosed = lossless(new CssTokenizer(), "a { content: \"unterminated\r\n$variable }");
        token(unclosed, Kind.STRING, "\"unterminated\r\n$variable }");
    }

    @Test
    void markdownCategoriesAndEscapes() {
        final var tokens = lossless(new MarkdownTokenizer(), "## Heading\r\n*em* **strong** _other_ `a * b` " +
                "``a ` b`` [label](https://host/a) ![alt](img.png) \\*literal\\* snake_case_word\n");
        token(tokens, Kind.KEYWORD, "## Heading");
        token(tokens, Kind.KEYWORD, "*em*");
        token(tokens, Kind.KEYWORD, "**strong**");
        token(tokens, Kind.KEYWORD, "_other_");
        token(tokens, Kind.STRING, "`a * b`");
        token(tokens, Kind.STRING, "``a ` b``");
        token(tokens, Kind.ANNOTATION, "[label](https://host/a)");
        token(tokens, Kind.ANNOTATION, "![alt](img.png)");
        assertFalse(tokens.stream().anyMatch(t -> t.kind() != Kind.PLAIN && t.text().contains("literal")));
        assertFalse(tokens.stream().anyMatch(t -> t.kind() != Kind.PLAIN && t.text().contains("case")));
    }

    @Test
    void markdownFencesAreOpaqueAndRequireMatchingClosers() {
        final var source = "  ````java\r\n# not a heading\r\n`code` *not emphasis*\n```\n~~~~\n" +
                "```` trailing text\n\n  `````\r\n# heading\n";
        final var tokens = lossless(new MarkdownTokenizer(), source);
        token(tokens, Kind.KEYWORD, "````java");
        token(tokens, Kind.KEYWORD, "`````");
        token(tokens, Kind.KEYWORD, "# heading");
        for (final var text : List.of("# not a heading\r\n", "`code` *not emphasis*\n", "```\n", "~~~~\n", "```` trailing text\n", "\n")) {
            token(tokens, Kind.STRING, text);
        }
        final var unclosed = lossless(new MarkdownTokenizer(), "~~~txt\n# still code\r\n");
        token(unclosed, Kind.STRING, "# still code\r\n");
        token(lossless(new MarkdownTokenizer(), "~~~\nx\n~~~\n"), Kind.STRING, "x\n");
        token(lossless(new MarkdownTokenizer(), "```bad`info\n# heading\n"), Kind.KEYWORD, "# heading");
        token(lossless(new MarkdownTokenizer(), "    ```\n# heading\n"), Kind.KEYWORD, "# heading");
    }

    @Test
    void markdownMalformedMarkupIsPlain() {
        final var source = "#not-heading\n####### too many\n*unclosed [missing](target `unfinished\n\\`escaped\\`";
        assertEquals(List.of(new Token(Kind.PLAIN, source)), new MarkdownTokenizer().tokenize(source));
    }

    @Test
    void asciidocCategoriesAndOpaqueSourceBlock() {
        final var tokens = lossless(new AsciidocTokenizer(), "= Title\r\n:lang: java\n:empty:   \n:!unset:\n" +
                "[source,java]\n----\n= not a heading\n{lang} image::x[] `raw`\r\n----\n" +
                "Use {lang}, `code`, image::pic.png[Alt] and link:https://host[site].\n" +
                "// comment\n====\ninside\n====\n");
        token(tokens, Kind.KEYWORD, "= Title");
        token(tokens, Kind.ATTRIBUTE, ":lang:");
        token(tokens, Kind.ATTRIBUTE, ":empty:");
        token(tokens, Kind.ATTRIBUTE, ":!unset:");
        token(tokens, Kind.STRING, "java");
        token(tokens, Kind.ANNOTATION, "[source,java]");
        token(tokens, Kind.OPERATOR, "----");
        token(tokens, Kind.OPERATOR, "====");
        token(tokens, Kind.STRING, "= not a heading\n");
        token(tokens, Kind.STRING, "{lang} image::x[] `raw`\r\n");
        token(tokens, Kind.ATTRIBUTE, "{lang}");
        token(tokens, Kind.STRING, "`code`");
        token(tokens, Kind.ANNOTATION, "image::pic.png[Alt]");
        token(tokens, Kind.ANNOTATION, "link:https://host[site]");
        token(tokens, Kind.COMMENT, "// comment");
    }

    @Test
    void asciidocLiteralCommentsEscapesAndMalformedInput() {
        final var tokens = lossless(new AsciidocTokenizer(), "////\n= comment\r\n////\n....\n{raw}\n....\n" +
                "\\{escaped} \\`escaped\\` {broken image::no-bracket `unfinished\n");
        token(tokens, Kind.COMMENT, "= comment\r\n");
        token(tokens, Kind.STRING, "{raw}\n");
        assertFalse(tokens.stream().anyMatch(t -> t.kind() == Kind.ATTRIBUTE));
        assertFalse(tokens.stream().anyMatch(t -> t.kind() == Kind.ANNOTATION));
        token(lossless(new AsciidocTokenizer(), "[source]\n----\n= unterminated\n"), Kind.STRING, "= unterminated\n");
    }

    @Test
    void iniCategoriesQuotesUrlsAndComments() {
        final var tokens = lossless(new IniTokenizer(), " ; heading\r\n[server]\t# section\n" +
                "url = https://host/a#fragment;parameter\n" +
                "quoted = \"a # b ; c \\\" d\" ; comment\n" +
                "single: 'a;#b' # note\ncount= -12.5e2\nflag=ON\nempty= \t\n# comment\n");
        token(tokens, Kind.COMMENT, "; heading");
        token(tokens, Kind.TYPE, "[server]");
        token(tokens, Kind.COMMENT, "# section");
        token(tokens, Kind.ATTRIBUTE, "url");
        token(tokens, Kind.OPERATOR, "=");
        token(tokens, Kind.OPERATOR, ":");
        token(tokens, Kind.STRING, "https://host/a#fragment;parameter");
        token(tokens, Kind.STRING, "\"a # b ; c \\\" d\"");
        token(tokens, Kind.STRING, "'a;#b'");
        token(tokens, Kind.COMMENT, "; comment");
        token(tokens, Kind.COMMENT, "# note");
        token(tokens, Kind.NUMBER, "-12.5e2");
        token(tokens, Kind.KEYWORD, "ON");
    }

    @Test
    void iniMalformedInputAndUnterminatedQuotes() {
        final var tokens = lossless(new IniTokenizer(), "[unclosed\r\nno assignment\n=missing key\n" +
                "name=\"open # not comment;\r\nnext=value # real comment\n");
        token(tokens, Kind.STRING, "\"open # not comment;");
        token(tokens, Kind.STRING, "value");
        token(tokens, Kind.COMMENT, "# real comment");
        assertEquals(1, tokens.stream().filter(t -> t.kind() == Kind.COMMENT).count());
    }

    @Test
    void emptyUnknownCharactersImmutableAndNoStateLeaks() {
        for (final var tokenizer : tokenizers()) {
            assertEquals(List.of(), tokenizer.tokenize(""));
            final var source = "\t\u2603\ud83d\ude00\r\n\u0000";
            assertEquals(List.of(new Token(Kind.PLAIN, source)), tokenizer.tokenize(source));
            final var saved = tokenizer.tokenize("plain");
            assertThrows(UnsupportedOperationException.class, () -> saved.add(new Token(Kind.PLAIN, "x")));
            for (final var malformed : List.of("/*", "\"", "```\n", "----\n", "[broken", "'", "{", "\\\r\n")) {
                lossless(tokenizer, malformed);
                assertEquals(saved, tokenizer.tokenize("plain"));
            }
            assertThrows(NullPointerException.class, () -> tokenizer.tokenize(null));
            assertEquals(saved, tokenizer.tokenize("plain"));
        }
    }

    @Test
    void deterministicLosslessnessFuzz() {
        final var random = new Random(14381);
        final var alphabet = "abcXYZ09\t \r\n{}[]():;#@$/*_`~\\\"'!%.=+-|\u00e9\u2603\ud83d\ude00\u0000";
        for (var sample = 0; sample < 1500; sample++) {
            final var source = new StringBuilder();
            final var length = random.nextInt(160);
            for (var i = 0; i < length; i++) {
                source.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            for (final var tokenizer : tokenizers()) {
                lossless(tokenizer, source.toString());
            }
        }
    }

    @Test
    void sharedInstancesCanScanConcurrently() {
        final var tokenizers = tokenizers();
        final var sources = List.of("/* open", "~~~\r\n# opaque", "----\n{opaque}", "[section]\nx='a#b' ; comment");
        final var expected = IntStream.range(0, tokenizers.size())
                .mapToObj(i -> tokenizers.get(i).tokenize(sources.get(i))).toList();
        IntStream.range(0, 128).parallel().forEach(i -> {
            final var index = i % tokenizers.size();
            assertEquals(expected.get(index), tokenizers.get(index).tokenize(sources.get(index)));
        });
    }

    private List<Tokenizer> tokenizers() {
        return List.of(new CssTokenizer(), new MarkdownTokenizer(), new AsciidocTokenizer(), new IniTokenizer());
    }

    private List<Token> lossless(final Tokenizer tokenizer, final String source) {
        final var tokens = tokenizer.tokenize(source);
        assertEquals(source, tokens.stream().map(Token::text).collect(joining()), tokenizer.getClass().getSimpleName());
        assertTrue(tokens.stream().noneMatch(t -> t.text().isEmpty()));
        return tokens;
    }

    private void token(final List<Token> tokens, final Kind kind, final String text) {
        assertTrue(tokens.contains(new Token(kind, text)), () -> "Missing " + kind + " token: " + text + " in " + tokens);
    }
}
