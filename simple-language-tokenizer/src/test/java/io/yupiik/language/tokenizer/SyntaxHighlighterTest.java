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

import io.yupiik.language.tokenizer.languages.GoTokenizer;
import io.yupiik.language.tokenizer.languages.GroovyTokenizer;
import io.yupiik.language.tokenizer.languages.JavaTokenizer;
import io.yupiik.language.tokenizer.languages.JavaScriptTokenizer;
import io.yupiik.language.tokenizer.languages.JsonTokenizer;
import io.yupiik.language.tokenizer.languages.KotlinTokenizer;
import io.yupiik.language.tokenizer.languages.PythonTokenizer;
import io.yupiik.language.tokenizer.languages.SqlTokenizer;
import io.yupiik.language.tokenizer.languages.TypeScriptTokenizer;
import io.yupiik.language.tokenizer.languages.XmlTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntaxHighlighterTest {
    @Test
    void commonJavaLibraryTypesHaveStableSemanticRoles() {
        final var tokenizer = new JavaTokenizer();
        for (final var type : List.of("CompletableFuture", "CharSequence", "ArrayList", "Stream", "Path", "BigDecimal")) {
            assertEquals(List.of(new Token(Kind.TYPE, type)), tokenizer.tokenize(type));
        }
    }

    private final SyntaxHighlighter highlighter = new SyntaxHighlighter();

    @Test
    public void directTokenizersAreReusableLosslessAndImmutable() {
        final var tokenizers = List.<Tokenizer>of(new JavaTokenizer(), new JavaScriptTokenizer(), new TypeScriptTokenizer(),
                new JsonTokenizer(), new XmlTokenizer(), new SqlTokenizer(), new PythonTokenizer(), new GoTokenizer(),
                new KotlinTokenizer(), new GroovyTokenizer());
        final var languages = List.of("java", "js", "ts", "json", "xml", "sql", "python", "go", "kotlin", "groovy");
        final var samples = List.of("return class func val def readonly String number int true null SELECT INTEGER",
                "/* unfinished", "<a value='unfinished", "\"unfinished\\", "`raw\r\n// text`",
                "\"${map.get(\"key\")}\"", "\t\r\n\u00e9\uD83D\uDE80", "", "return");
        for (var i = 0; i < tokenizers.size(); i++) {
            final var tokenizer = tokenizers.get(i);
            for (final var source : samples) {
                final var tokens = tokenizer.tokenize(source);
                assertEquals(highlighter.highlight(languages.get(i), source), tokens);
                assertEquals(source, tokens.stream().map(Token::text).collect(joining()));
                assertTrue(tokens.stream().noneMatch(token -> token.text().isEmpty()));
                assertThrows(UnsupportedOperationException.class, () -> tokens.add(new Token(Kind.PLAIN, "extra")));
            }
            assertThrows(NullPointerException.class, () -> tokenizer.tokenize(null));
        }
        assertEquals(List.of(new Token(Kind.PLAIN, "interface number")), new JavaScriptTokenizer().tokenize("interface number"));
        has(new TypeScriptTokenizer().tokenize("interface number"), Kind.KEYWORD, "interface");
        has(new TypeScriptTokenizer().tokenize("interface number"), Kind.TYPE, "number");
    }

    @Test
    void javaTokensAndMultilineState() {
        final var source = "@org.example.Mark\npublic class Demo {\n\tString s = \"a\\\"b\"; /* first\nreturn 99; */\nint n = 0x2A; }";
        final var tokens = checked("java", source);
        has(tokens, Kind.ANNOTATION, "@org.example.Mark");
        has(tokens, Kind.KEYWORD, "public");
        has(tokens, Kind.TYPE, "String");
        has(tokens, Kind.TYPE, "int");
        has(tokens, Kind.STRING, "\"a\\\"b\"");
        has(tokens, Kind.COMMENT, "/* first\nreturn 99; */");
        has(tokens, Kind.NUMBER, "0x2A");
        has(tokens, Kind.OPERATOR, "=");

        final var textBlock = "\"\"\"\n// not a comment\nclass Demo {}\n\"\"\"";
        assertEquals(List.of(new Token(Kind.STRING, textBlock)), checked("java", textBlock));
    }

    @Test
    void scriptTemplatesAndTypescriptVocabulary() {
        final var template = "`hello ${name}\n// still a string \\` !`";
        final var js = checked("js", "const message = " + template + "; // done\r\nlet n = 1.25e-3;");
        has(js, Kind.KEYWORD, "const");
        has(js, Kind.STRING, template);
        has(js, Kind.COMMENT, "// done");
        has(js, Kind.NUMBER, "1.25e-3");
        final var ts = checked("ts", "@sealed\ninterface Item { readonly value: number; }");
        has(ts, Kind.ANNOTATION, "@sealed");
        has(ts, Kind.KEYWORD, "interface");
        has(ts, Kind.KEYWORD, "readonly");
        has(ts, Kind.TYPE, "number");
        assertEquals(List.of(new Token(Kind.PLAIN, "interface")), checked("js", "interface"));
    }

    @Test
    void jsonKeysValuesAndNoCodeComments() {
        final var tokens = checked("json", "{\"key\" \r\n: \"value\", \"n\": -2.5e+3, \"ok\": true, \"nil\": null}");
        has(tokens, Kind.ATTRIBUTE, "\"key\"");
        has(tokens, Kind.STRING, "\"value\"");
        has(tokens, Kind.NUMBER, "-2.5e+3");
        has(tokens, Kind.KEYWORD, "true");
        has(tokens, Kind.KEYWORD, "null");
        assertFalse(checked("json", "// comment\n# comment\n/* comment */").stream()
                .anyMatch(token -> token.kind() == Kind.COMMENT));
    }

    @Test
    void xmlAndHtmlKeepTextAndEntitiesUnescaped() {
        final var comment = "<!-- first\n<ignored attr='x'> -->";
        final var cdata = "<![CDATA[<raw>\n&text]]>";
        final var tokens = checked("xml", "<?xml version='1.0'?>\n" + comment +
                "\n<ns:root data-id=\"a>\nb\">class 123 &amp; " + cdata + "</ns:root>");
        has(tokens, Kind.COMMENT, comment);
        has(tokens, Kind.TAG, "ns:root");
        has(tokens, Kind.ATTRIBUTE, "data-id");
        has(tokens, Kind.STRING, "\"a>\nb\"");
        has(tokens, Kind.PLAIN, "class 123 &amp; ");
        has(tokens, Kind.STRING, cdata);
        has(checked("html", "<input disabled value='x'/>"), Kind.ATTRIBUTE, "disabled");
        assertEquals(List.of(new Token(Kind.PLAIN, "x < y && z > 2")), checked("html", "x < y && z > 2"));
    }

    @Test
    void sqlIsCaseInsensitiveAndSupportsDoubledQuotes() {
        final var tokens = checked("sql", "sElEcT 'it''s\n-- text', CAST(n AS INTEGER) FROM t -- note\r\n/* a\nb */ WHERE n >= 12;");
        has(tokens, Kind.KEYWORD, "sElEcT");
        has(tokens, Kind.TYPE, "INTEGER");
        has(tokens, Kind.STRING, "'it''s\n-- text'");
        has(tokens, Kind.COMMENT, "-- note");
        has(tokens, Kind.COMMENT, "/* a\nb */");
        has(tokens, Kind.NUMBER, "12");
    }

    @Test
    void pythonTripleQuotedStringsAndDecorators() {
        final var literal = "'''first\n# not a comment\n\\'last'''";
        final var tokens = checked("python", "@app.route\ndef greeting():\n\ttext = " + literal + "\n\treturn True # yes\n");
        has(tokens, Kind.ANNOTATION, "@app.route");
        has(tokens, Kind.KEYWORD, "def");
        has(tokens, Kind.STRING, literal);
        has(tokens, Kind.KEYWORD, "True");
        has(tokens, Kind.COMMENT, "# yes");
        assertFalse(checked("python", "a // b").stream().anyMatch(token -> token.kind() == Kind.COMMENT));
    }

    @Test
    void goVocabularyNumbersAndLiteralForms() {
        final var raw = "`first\r\n// still raw \\\nlast`";
        final var tokens = checked("go", "package main\r\nfunc main() { var n complex128 = 0x1.fp+2i; " +
                "var count uint64 = 0o755; const yes = true; var empty = nil; var index = iota; " +
                "s := \"a\\\"b\"; r := '\\u03bb'; raw := " + raw + "; // end\r\n/* block */ }");
        has(tokens, Kind.KEYWORD, "package");
        has(tokens, Kind.KEYWORD, "func");
        has(tokens, Kind.TYPE, "complex128");
        has(tokens, Kind.TYPE, "uint64");
        has(tokens, Kind.KEYWORD, "true");
        has(tokens, Kind.KEYWORD, "nil");
        has(tokens, Kind.KEYWORD, "iota");
        has(tokens, Kind.NUMBER, "0x1.fp+2i");
        has(tokens, Kind.NUMBER, "0o755");
        has(tokens, Kind.STRING, "\"a\\\"b\"");
        has(tokens, Kind.STRING, "'\\u03bb'");
        has(tokens, Kind.STRING, raw);
        has(tokens, Kind.COMMENT, "// end");
        has(tokens, Kind.COMMENT, "/* block */");
        for (final var number : List.of("0b_101", "0x_FF", "0x.8p-1", "1_000", "2.5e-3i", ".5i", "077")) {
            assertEquals(List.of(new Token(Kind.NUMBER, number)), checked("go", number));
        }
        assertEquals(List.of(new Token(Kind.PLAIN, "public class String")), checked("go", "public class String"));
    }

    @Test
    void kotlinNestedCommentsRawStringsAndBacktickIdentifiers() {
        final var comment = "/* outer\r\n/* inner */ val hidden = 1; */";
        final var raw = "\"\"\"first\r\n\\\" ${name} // raw\"\"\"";
        final var template = "\"hello ${if (ok) \"yes\" else \"no\"} $name\"";
        final var tokens = checked("kotlin", "@org.example.Mark\nval `when` : String = " + raw +
                "\n" + comment + "\nfun demo() = " + template + "; val n: UInt = 12u; // end");
        has(tokens, Kind.ANNOTATION, "@org.example.Mark");
        has(tokens, Kind.KEYWORD, "val");
        has(tokens, Kind.KEYWORD, "fun");
        has(tokens, Kind.TYPE, "String");
        has(tokens, Kind.TYPE, "UInt");
        has(tokens, Kind.PLAIN, " `when` ");
        has(tokens, Kind.STRING, raw);
        has(tokens, Kind.STRING, template);
        has(tokens, Kind.COMMENT, comment);
        has(tokens, Kind.NUMBER, "12u");
        has(tokens, Kind.COMMENT, "// end");
        has(checked("kts", "@file:JvmName(\"Demo\")"), Kind.ANNOTATION, "@file:JvmName");
        has(checked("kt", "val n = 1..2"), Kind.NUMBER, "1");
        assertEquals(List.of(new Token(Kind.PLAIN, "static instanceof")), checked("kt", "static instanceof"));
    }

    @Test
    void groovyLiteralFormsAndConservativeSlashyStrings() {
        final var slashy = "/a\\/b\r\n${name}/";
        final var dollarSlashy = "$/a $$ $/ escaped\r\n${name}/$";
        final var literals = List.of("'a\\'b'", "\"hello $name\"", "'''first\n// literal'''",
                "\"\"\"${name}\r\n/* literal */\"\"\"", slashy, dollarSlashy,
                "\"${map.get(\"key\")}\"");
        for (final var literal : literals) {
            has(checked("groovy", "def value = " + literal + "; // end"), Kind.STRING, literal);
        }
        final var tokens = checked("gradle", "@groovy.transform.Immutable\ntrait Demo { def n = 42; /* block */ }");
        has(tokens, Kind.ANNOTATION, "@groovy.transform.Immutable");
        has(tokens, Kind.KEYWORD, "trait");
        has(tokens, Kind.KEYWORD, "def");
        has(tokens, Kind.NUMBER, "42");
        has(tokens, Kind.COMMENT, "/* block */");
        assertFalse(checked("groovy", "a / b / c; total /= 2; println /ambiguous/").stream()
                .anyMatch(token -> token.kind() == Kind.STRING));
        has(checked("groovy", "return /value/"), Kind.STRING, "/value/");
        has(checked("groovy", "x =~ /value/"), Kind.STRING, "/value/");
    }

    @Test
    void explicitAliasesOnly() {
        for (final var aliases : List.of(List.of("java"), List.of("js", "javascript"), List.of("ts", "typescript"),
                List.of("json"), List.of("xml", "html", "htm"), List.of("sql"), List.of("py", "python"),
                List.of("go", "golang"), List.of("kotlin", "kt", "kts"), List.of("groovy", "gradle"),
                List.of("sh", "shell", "bash", "zsh"), List.of("yaml", "yml"),
                List.of("properties", "java-properties"), List.of("toml"), List.of("docker", "dockerfile"))) {
            final var source = "class X { \"x\": 123 } <a value='x'/> # comment";
            final var expected = checked(aliases.get(0), source);
            for (final var alias : aliases) {
                assertEquals(expected, checked("  " + alias.toUpperCase(java.util.Locale.ROOT) + "\t", source), alias);
            }
        }
        final var source = "public class Demo {}\t\r\n<raw>& \u00e9 \uD83D\uDE80";
        for (final var language : new String[]{null, "", "  ", "unknown", "text", "plain", "java-extra", "java title=x",
                "go-extra", "kotlin title=x", "gradle-extra"}) {
            assertEquals(List.of(new Token(Kind.PLAIN, source)), checked(language, source));
        }
        assertEquals(List.of(), highlighter.highlight(null, ""));
        assertEquals(List.of(), highlighter.highlight("java", ""));
        assertThrows(NullPointerException.class, () -> highlighter.highlight("java", null));
    }

    @Test
    void unterminatedConstructsAndEscapesKeepTheirStateUntilEof() {
        for (final var language : List.of("java", "js", "ts", "sql", "go", "kotlin", "groovy")) {
            final var source = "/* unterminated\r\n\t'\" # <x> \uD83D\uDE80";
            assertEquals(List.of(new Token(Kind.COMMENT, source)), checked(language, source));
        }
        for (final var language : List.of("java", "js", "ts", "json", "sql", "python", "go", "kotlin", "groovy")) {
            final var source = "\"unterminated\n# // /* 123 \\";
            assertEquals(List.of(new Token(Kind.STRING, source)), checked(language, source));
        }
        for (final var source : List.of("<!-- unfinished\n<a>", "<![CDATA[unfinished\n<x>", "<a v='unfinished\n>x")) {
            checked("xml", source);
        }
        assertEquals(List.of(new Token(Kind.COMMENT, "<!-- unfinished\n<a>")), checked("xml", "<!-- unfinished\n<a>"));
        has(checked("xml", "<a v='unfinished\n>x"), Kind.STRING, "'unfinished\n>x");
        assertEquals(List.of(new Token(Kind.KEYWORD, "return")), checked("java", "return"), "No state leaks between calls");
        for (final var language : List.of("go", "kotlin", "groovy")) {
            final var literal = language.equals("groovy") ? "$/unfinished\r\n$$" : "`unfinished\r\n\\";
            assertEquals(List.of(new Token(language.equals("kotlin") ? Kind.PLAIN : Kind.STRING, literal)),
                    checked(language, literal));
            assertEquals(List.of(new Token(Kind.KEYWORD, "return")), checked(language, "return"));
        }
        final var nested = "/* outer /* inner */ still unfinished";
        assertEquals(List.of(new Token(Kind.COMMENT, nested)), checked("kt", nested));
        for (final var language : List.of("kt", "groovy")) {
            final var triple = "\"\"\"unfinished\r\n// not a comment";
            assertEquals(List.of(new Token(Kind.STRING, triple)), checked(language, triple));
            assertEquals(List.of(new Token(Kind.KEYWORD, "return")), checked(language, "return"));
        }
        assertEquals(List.of(new Token(Kind.STRING, "/unfinished\\/")), checked("groovy", "/unfinished\\/"));
    }

    @Test
    void exactUnicodeWhitespaceAndMalformedInputRoundTrips() {
        final var random = new Random(1234);
        final var alphabet = "ab19\t\r\n\u0000\u00e9\u03bb\uD83D\uDE80\"'`/*#@<>=:-[]{}\\$";
        for (final var language : List.of("java", "js", "ts", "json", "xml", "sql", "python", "go", "kt", "groovy", "unknown")) {
            checked(language, "\t\r\n\n\u00e9\u03bb\uD83D\uDE80 \uD801\uDC00class className returnValue\t");
            for (var sample = 0; sample < 100; sample++) {
                final var source = new StringBuilder();
                for (var i = 0; i < 200; i++) {
                    source.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                checked(language, source.toString());
            }
        }
        assertEquals(List.of(new Token(Kind.PLAIN, "className returnValue")), checked("java", "className returnValue"));
        for (final var language : List.of("go", "kt", "groovy")) {
            assertEquals(List.of(new Token(Kind.PLAIN, "className returnValue")), checked(language, "className returnValue"));
            final var tokens = checked(language, "return");
            assertThrows(UnsupportedOperationException.class, () -> tokens.add(new Token(Kind.PLAIN, "extra")));
        }
    }

    @Test
    void immutableResultsAndNoSynthesizedMarkup() {
        final var tokens = checked("java", "String s = \"<b>&\\n\"; // \u001b[31m");
        assertThrows(UnsupportedOperationException.class, () -> tokens.add(new Token(Kind.PLAIN, "extra")));
        assertThrows(NullPointerException.class, () -> new Token(null, "text"));
        assertThrows(NullPointerException.class, () -> new Token(Kind.PLAIN, null));
    }

    private List<Token> checked(final String language, final String source) {
        final var tokens = highlighter.highlight(language, source);
        assertEquals(source, tokens.stream().map(Token::text).collect(joining()), () -> "Lossless for " + language);
        for (final var token : tokens) {
            assertFalse(token.text().isEmpty(), "No empty tokens");
        }
        return tokens;
    }

    private void has(final List<Token> tokens, final Kind kind, final String text) {
        assertTrue(tokens.contains(new Token(kind, text)), () -> "Missing " + kind + " '" + text + "' in " + tokens);
    }
}
