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

import io.yupiik.language.tokenizer.languages.DockerfileTokenizer;
import io.yupiik.language.tokenizer.languages.PropertiesTokenizer;
import io.yupiik.language.tokenizer.languages.ShellTokenizer;
import io.yupiik.language.tokenizer.languages.TomlTokenizer;
import io.yupiik.language.tokenizer.languages.YamlTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationTokenizersTest {
    private final SyntaxHighlighter highlighter = new SyntaxHighlighter();
    private final List<String> languages = List.of("shell", "yaml", "properties", "toml", "dockerfile");

    @Test
    void tokenizerInstancesAreReusableAndReturnImmutableLosslessTokens() {
        final var tokenizers = List.<Tokenizer>of(new ShellTokenizer(), new YamlTokenizer(), new PropertiesTokenizer(),
                new TomlTokenizer(), new DockerfileTokenizer());
        final var normal = "key = 12\r\n# normal\n";
        final var unfinished = List.of("cat <<'EOF'\nno end\n", "flow: [{\n", "key\\\r\n",
                "key = { a = [\n", "# escape=`\nRUN echo first `\n");
        for (var i = 0; i < tokenizers.size(); i++) {
            final var tokenizer = tokenizers.get(i);
            final var expected = checked(languages.get(i), normal);
            assertEquals(expected, tokenizer.tokenize(normal));
            final var tokens = tokenizer.tokenize(unfinished.get(i));
            assertEquals(unfinished.get(i), tokens.stream().map(Token::text).collect(joining()));
            assertTrue(tokens.stream().noneMatch(token -> token.text().isEmpty()));
            assertEquals(expected, tokenizer.tokenize(normal));
            assertEquals(List.of(), tokenizer.tokenize(""));
            assertThrows(UnsupportedOperationException.class, () -> tokens.add(new Token(Kind.PLAIN, "!")));
            assertThrows(UnsupportedOperationException.class, () -> tokenizer.tokenize("").add(new Token(Kind.PLAIN, "!")));
            assertThrows(NullPointerException.class, () -> tokenizer.tokenize(null));
            assertEquals(expected, tokenizer.tokenize(normal));
        }
    }

    @Test
    void aliasesAndDelegationContract() {
        for (final var aliases : List.of(List.of("sh", "shell", "bash", "zsh"), List.of("yaml", "yml"),
                List.of("properties", "java-properties"), List.of("toml"), List.of("docker", "dockerfile"))) {
            for (final var alias : aliases) {
                assertEquals(checked(aliases.get(0), "key: 'value' # comment\r\n"),
                        checked(" \t" + alias.toUpperCase(Locale.ROOT) + "\n", "key: 'value' # comment\r\n"));
                assertEquals(List.of(), checked(alias, ""));
            }
        }
        for (final var unsupported : List.of("", " ", "bash-session", "dotenv", "docker-compose", "ini")) {
            assertEquals(List.of(new Token(Kind.PLAIN, "x")), highlighter.highlight(unsupported, "x"));
            assertEquals(List.of(), highlighter.highlight(unsupported, ""));
        }
        assertEquals(List.of(new Token(Kind.PLAIN, "x")), highlighter.highlight(null, "x"));
        assertThrows(NullPointerException.class, () -> highlighter.highlight("yaml", null));
        assertThrows(UnsupportedOperationException.class, () -> checked("sh", "echo ok").add(new Token(Kind.PLAIN, "!")));
    }

    @Test
    void shellCommentsQuotesVariablesAndControlFlow() {
        final var source = "#!/usr/bin/env bash\r\n# heading\nif test -n \"$USER\"; then\n" +
                "  export name='literal $USER # no comment'\n" +
                "  echo \"hi ${name:-guest} $(printf '%s' \"$USER\") $((1 + (2))) `pwd` \\$HOME\"\n" +
                "fi # done\necho https://host/#fragment \\#literal\n";
        final var tokens = checked("bash", source);
        has(tokens, Kind.COMMENT, "#!/usr/bin/env bash");
        has(tokens, Kind.COMMENT, "# heading");
        has(tokens, Kind.KEYWORD, "if");
        has(tokens, Kind.KEYWORD, "test");
        has(tokens, Kind.KEYWORD, "then");
        has(tokens, Kind.KEYWORD, "export");
        has(tokens, Kind.ATTRIBUTE, "name");
        has(tokens, Kind.STRING, "'literal $USER # no comment'");
        has(tokens, Kind.ATTRIBUTE, "${name:-guest}");
        has(tokens, Kind.ATTRIBUTE, "$(printf '%s' \"$USER\")");
        has(tokens, Kind.ATTRIBUTE, "$((1 + (2)))");
        has(tokens, Kind.ATTRIBUTE, "`pwd`");
        has(tokens, Kind.STRING, "\\$HOME\"");
        has(tokens, Kind.PLAIN, "https://host/#fragment \\#literal");
        has(tokens, Kind.OPERATOR, "=");
        has(tokens, Kind.COMMENT, "# done");
    }

    @Test
    void shellSingleQuoteDoesNotTreatBackslashAsEscape() {
        final var tokens = checked("zsh", "echo 'a\\' $value \"\\q $other\" ${outer:-${inner}}\n");
        has(tokens, Kind.STRING, "'a\\'");
        has(tokens, Kind.ATTRIBUTE, "$value");
        has(tokens, Kind.STRING, "\"\\q ");
        has(tokens, Kind.ATTRIBUTE, "$other");
        has(tokens, Kind.ATTRIBUTE, "${outer:-${inner}}");
    }

    @Test
    void shellHeredocsQuoteRemovalTabStrippingAndMultipleBodies() {
        final var source = "cat <<-EOF <<'STOP' <<E\"N\"D\r\n" +
                "\tvalue $USER \\$literal # data\r\n\tEOF\r\n" +
                "$notExpanded # data\r\nSTOP\r\n${alsoLiteral}\r\nEND\r\necho done\r\n";
        final var tokens = checked("sh", source);
        has(tokens, Kind.OPERATOR, "<<-");
        has(tokens, Kind.STRING, "'STOP'");
        has(tokens, Kind.STRING, "E\"N\"D");
        has(tokens, Kind.ATTRIBUTE, "$USER");
        has(tokens, Kind.STRING, "\\$literal # data");
        has(tokens, Kind.STRING, "$notExpanded # data\r\nSTOP");
        has(tokens, Kind.STRING, "${alsoLiteral}\r\nEND");
        has(tokens, Kind.KEYWORD, "echo");
        assertFalse(tokens.stream().anyMatch(token -> token.kind() == Kind.COMMENT));
    }

    @Test
    void shellHereStringIsNotAHeredocAndEscapedDelimiterIsLiteral() {
        final var tokens = checked("bash", "cat <<<\"$name\"\necho done\ncat <<\\END\n$literal\nEND\necho after\n");
        has(tokens, Kind.OPERATOR, "<<<");
        has(tokens, Kind.ATTRIBUTE, "$name");
        has(tokens, Kind.KEYWORD, "echo");
        has(tokens, Kind.STRING, "$literal\nEND");
        assertEquals(2, tokens.stream().filter(token -> token.kind() == Kind.KEYWORD && token.text().equals("echo")).count());
    }

    @Test
    void yamlKeysScalarsQuotesAndHashSeparation() {
        final var source = "---\r\n\"quoted key\": 'it''s # literal'\n" +
                "plain key: https://example.org/a#fragment # real comment\n" +
                "number: -1.25e+3\nboolean: true\nnil: null\n" +
                "flow: {name: \"value # data\", numbers: [1, 2, false]}\n...\n";
        final var tokens = checked("yml", source);
        has(tokens, Kind.OPERATOR, "---");
        has(tokens, Kind.OPERATOR, "...");
        has(tokens, Kind.ATTRIBUTE, "\"quoted key\"");
        has(tokens, Kind.ATTRIBUTE, "plain key");
        has(tokens, Kind.STRING, "'it''s # literal'");
        has(tokens, Kind.PLAIN, "https://example.org/a#fragment");
        has(tokens, Kind.COMMENT, "# real comment");
        has(tokens, Kind.NUMBER, "-1.25e+3");
        has(tokens, Kind.KEYWORD, "true");
        has(tokens, Kind.KEYWORD, "null");
        has(tokens, Kind.ATTRIBUTE, "numbers");
        has(tokens, Kind.NUMBER, "2");
        has(tokens, Kind.STRING, "\"value # data\"");
        has(tokens, Kind.KEYWORD, "false");
    }

    @Test
    void yamlAnchorsAliasesTagsAndBlockScalarDedentation() {
        final var source = "defaults: &base {enabled: true}\ncopy: *base\n" +
                "tagged: !!str 123\ncustom: !<tag:example.org,2026:thing> value\n" +
                "literal: |2- # header\r\n  # literal, not comment\r\n  key: false\r\n\r\n" +
                "folded: >+\n   first\n   second\n  dedented: true\nlast: 42\n";
        final var tokens = checked("yaml", source);
        has(tokens, Kind.ANNOTATION, "&base");
        has(tokens, Kind.ANNOTATION, "*base");
        has(tokens, Kind.ANNOTATION, "!!str");
        has(tokens, Kind.ANNOTATION, "!<tag:example.org,2026:thing>");
        has(tokens, Kind.OPERATOR, "|2-");
        has(tokens, Kind.COMMENT, "# header");
        has(tokens, Kind.STRING, "  # literal, not comment\r\n  key: false\r\n\r\n");
        has(tokens, Kind.STRING, "   first\n   second\n");
        has(tokens, Kind.ATTRIBUTE, "dedented");
        has(tokens, Kind.ATTRIBUTE, "last");
        has(tokens, Kind.NUMBER, "42");
    }

    @Test
    void yamlBlankAndNestedBlockScalars() {
        final var source = "outer:\n  - text: |\n      hello\n      # content\n    next: false\nempty: |\nnext: true\n";
        final var tokens = checked("yaml", source);
        has(tokens, Kind.STRING, "      hello\n      # content\n");
        has(tokens, Kind.ATTRIBUTE, "next");
        has(tokens, Kind.KEYWORD, "false");
        has(tokens, Kind.KEYWORD, "true");
    }

    @Test
    void yamlExplicitBlockIndentIsRelativeToSequenceMappingKey() {
        final var tokens = checked("yaml", "- 'text': |2\n    body\n  sibling: true\n- value: >2-\n    folded\n  next: false\n");
        has(tokens, Kind.STRING, "    body\n");
        has(tokens, Kind.STRING, "    folded\n");
        has(tokens, Kind.ATTRIBUTE, "sibling");
        has(tokens, Kind.ATTRIBUTE, "next");
        has(tokens, Kind.KEYWORD, "true");
        has(tokens, Kind.KEYWORD, "false");
    }

    @Test
    void propertiesEscapedKeysSeparatorsCommentsAndContinuation() {
        final var source = "  # comment\r\n! other\n" +
                "escaped\\ key\\:part\\=x : value # literal\n" +
                "white value\ncolon:value\nequal=value\n" +
                "long\\\r\n  Key = first\\\r\n\t# still value\\\r\n  last\r\n" +
                "even=two\\\\\nnext=ok\n";
        final var tokens = checked("java-properties", source);
        has(tokens, Kind.COMMENT, "# comment");
        has(tokens, Kind.COMMENT, "! other");
        has(tokens, Kind.ATTRIBUTE, "escaped\\ key\\:part\\=x");
        has(tokens, Kind.OPERATOR, ":");
        has(tokens, Kind.STRING, "value # literal");
        has(tokens, Kind.ATTRIBUTE, "white");
        has(tokens, Kind.STRING, "value");
        has(tokens, Kind.ATTRIBUTE, "long");
        has(tokens, Kind.ATTRIBUTE, "Key");
        has(tokens, Kind.STRING, "# still value");
        has(tokens, Kind.STRING, "two\\\\");
        has(tokens, Kind.ATTRIBUTE, "next");
        assertEquals(2, tokens.stream().filter(token -> token.kind() == Kind.COMMENT).count());
    }

    @Test
    void tomlTablesDottedKeysArraysAndInlineTables() {
        final var source = "# heading\n[server.\"example.org\"]\n" +
                "'quoted key'.enabled = true\nports = [80, 443, 0xFF, -2.5e+2]\n" +
                "meta = {name = 'app#literal', nested = {ok = false}, other = 3}\n" +
                "[[products]]\nname = \"thing\" # comment\n";
        final var tokens = checked("toml", source);
        has(tokens, Kind.ATTRIBUTE, "server");
        has(tokens, Kind.ATTRIBUTE, "\"example.org\"");
        has(tokens, Kind.ATTRIBUTE, "'quoted key'");
        has(tokens, Kind.ATTRIBUTE, "enabled");
        has(tokens, Kind.KEYWORD, "true");
        has(tokens, Kind.NUMBER, "0xFF");
        has(tokens, Kind.NUMBER, "-2.5e+2");
        has(tokens, Kind.ATTRIBUTE, "other");
        has(tokens, Kind.NUMBER, "3");
        has(tokens, Kind.ATTRIBUTE, "products");
        has(tokens, Kind.STRING, "'app#literal'");
        has(tokens, Kind.COMMENT, "# comment");
    }

    @Test
    void tomlMultilineBasicAndLiteralStrings() {
        final var basic = "\"\"\"\r\n# data\r\na = \\\"quoted\\\"\\\r\n  continued\r\n\"\"\"";
        final var literal = "'''\n# data\nC:\\path\\file\n'''";
        final var tokens = checked("toml", "basic = " + basic + "\nliteral = " + literal + "\nend = 7\n");
        has(tokens, Kind.STRING, basic);
        has(tokens, Kind.STRING, literal);
        has(tokens, Kind.ATTRIBUTE, "end");
        has(tokens, Kind.NUMBER, "7");
        assertFalse(tokens.stream().anyMatch(token -> token.kind() == Kind.COMMENT));
    }

    @Test
    void tomlLeavesUnrecognizedScalarFormsPlain() {
        final var tokens = checked("toml", "a = TRUE\nb = null\nc = .nan\nd = 123abc\ne = 01\nf = +inf\ng = 1_000\nh = 1__0\ni = 1_e2\nj = 0xE_F\n");
        has(tokens, Kind.PLAIN, "TRUE");
        has(tokens, Kind.PLAIN, "null");
        has(tokens, Kind.PLAIN, ".nan");
        has(tokens, Kind.PLAIN, "123abc");
        has(tokens, Kind.PLAIN, "01");
        has(tokens, Kind.NUMBER, "+inf");
        has(tokens, Kind.NUMBER, "1_000");
        has(tokens, Kind.PLAIN, "1__0");
        has(tokens, Kind.PLAIN, "1_e2");
        has(tokens, Kind.NUMBER, "0xE_F");
    }

    @Test
    void longNumericScalarsDoNotExhaustTheRegexStack() {
        final var number = "1".repeat(100_000);
        has(checked("toml", "key = " + number), Kind.NUMBER, number);
        has(checked("yaml", "key: " + number), Kind.NUMBER, number);
        has(checked("toml", "key = " + number + "_"), Kind.PLAIN, number + "_");
    }

    @Test
    void dockerInstructionsFlagsContinuationsAndConservativeEmbedding() {
        final var source = "# syntax=docker/dockerfile:1\r\nfrom alpine AS build\n" +
                "RUN --mount=type=cache,target=/cache echo \"$HOME\" && \\\r\n  printf '%s' ok\r\n" +
                "COPY --from=build /a /b\nENV URL=https://example.org/#fragment\n" +
                "CMD [\"sh\", \"-c\", \"echo $HOME # literal\"]\nunknown echo # plain\n";
        final var tokens = checked("docker", source);
        has(tokens, Kind.COMMENT, "# syntax=docker/dockerfile:1");
        has(tokens, Kind.KEYWORD, "from");
        has(tokens, Kind.KEYWORD, "RUN");
        has(tokens, Kind.ATTRIBUTE, "--mount=type=cache,target=/cache");
        has(tokens, Kind.ATTRIBUTE, "$HOME");
        has(tokens, Kind.KEYWORD, "printf");
        has(tokens, Kind.ATTRIBUTE, "--from=build");
        has(tokens, Kind.PLAIN, "URL=https://example.org/#fragment");
        has(tokens, Kind.STRING, "\"echo $HOME # literal\"");
        has(tokens, Kind.PLAIN, "unknown echo # plain");
    }

    @Test
    void dockerHeredocsAndEscapeDirective() {
        final var source = "# escape=`\nRUN echo first `\n  && echo second\n" +
                "COPY <<'EOF' /file\n$literal # data\nEOF\n" +
                "RUN <<SHELL\necho $USER\nSHELL\nWORKDIR /app\n";
        final var tokens = checked("dockerfile", source);
        has(tokens, Kind.KEYWORD, "echo");
        has(tokens, Kind.OPERATOR, "`");
        has(tokens, Kind.STRING, "$literal # data\nEOF");
        has(tokens, Kind.ATTRIBUTE, "$USER");
        has(tokens, Kind.KEYWORD, "WORKDIR");
    }

    @Test
    void dockerIgnoresCommentAndEmptyContinuationLines() {
        final var source = "RUN echo first \\\r\n# ignored\r\n\r\n && printf second\r\n" +
                "ENV value=first \\\n # another comment\n second\nWORKDIR /app\n";
        final var tokens = checked("dockerfile", source);
        has(tokens, Kind.COMMENT, "# ignored");
        has(tokens, Kind.COMMENT, "# another comment");
        has(tokens, Kind.KEYWORD, "printf");
        has(tokens, Kind.KEYWORD, "WORKDIR");
    }

    @Test
    void deeplyNestedShellExpansionsDoNotUseRecursiveTokenization() {
        final var expansion = "$(".repeat(10_000) + "echo ok" + ")".repeat(10_000);
        final var tokens = checked("shell", "echo " + expansion + "\necho end\n");
        has(tokens, Kind.ATTRIBUTE, expansion);
        assertEquals(2, tokens.stream().filter(token -> token.kind() == Kind.KEYWORD && token.text().equals("echo")).count());
    }

    @Test
    void malformedInputsAreLosslessAndStateNeverLeaks() {
        final var malformed = List.of("'unterminated\r\n# data", "\"\\", "${unclosed", "$(nested $(x)",
                "cat <<'EOF'\nno end\n", "x: |\n  # unfinished\r\n", "x = \"\"\"unfinished\n", "[[table", "key\\\r\n", "\u0000\ud800\udfff\t\r\n");
        for (final var language : languages) {
            final var expected = checked(language, "key = 12\n# normal\n");
            for (final var source : malformed) {
                checked(language, source);
                assertEquals(expected, checked(language, "key = 12\n# normal\n"));
                assertEquals(new SyntaxHighlighter().highlight(language, source), checked(language, source));
            }
        }
        has(checked("shell", "echo \"unclosed"), Kind.STRING, "\"unclosed");
        has(checked("toml", "key = '''unclosed"), Kind.STRING, "'''unclosed");
        has(checked("yaml", "key: 'unclosed"), Kind.STRING, "'unclosed");
    }

    @Test
    void deterministicFuzzReconstructsEveryUtf16CodeUnit() {
        final var random = new Random(0xC0FFEE);
        final var alphabet = "abcXYZ019_-. :;=#!&*|><[]{}()$?+/'\"`\\\t\r\n\u00e9\u03bb\ud83d\ude42\u0000";
        for (var sample = 0; sample < 1500; sample++) {
            final var source = new StringBuilder();
            final var length = random.nextInt(200);
            for (var i = 0; i < length; i++) {
                source.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            for (final var language : languages) {
                final var text = source.toString();
                assertEquals(checked(language, text), checked(language, text));
            }
        }
    }

    private List<Token> checked(final String language, final String source) {
        final var tokens = highlighter.highlight(language, source);
        assertNotNull(tokens);
        assertEquals(source, tokens.stream().map(Token::text).collect(joining()));
        assertTrue(tokens.stream().noneMatch(token -> token.text().isEmpty()));
        return tokens;
    }

    private void has(final List<Token> tokens, final Kind kind, final String text) {
        assertTrue(tokens.stream().anyMatch(token -> token.kind() == kind && token.text().contains(text)),
                () -> "Missing " + kind + " fragment <" + text + "> in " + tokens);
    }
}
