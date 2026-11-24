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

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(30)
class NestedLiteralsTest {
    @Test
    void nestedQuotedInterpolationsRemainOneString() {
        for (final var tokenizer : interpolatingTokenizers()) {
            for (final var delimiter : List.of("\"", "\"\"\"")) {
                final var literal = nested(delimiter, 4);
                singleString(tokenizer, literal);
                boundary(tokenizer, literal);
            }
        }
    }

    @Test
    void bracesCommentsAndQuotesInsideExpressionsStayOpaque() {
        final var literal = "\"before ${ { key: '}', other: \"inner ${'leaf'}\" } " +
                "/* } \" ${ */ // } \" ${\r\n } after\"";
        for (final var tokenizer : interpolatingTokenizers()) {
            singleString(tokenizer, literal);
            boundary(tokenizer, literal);
        }
        singleString(new KotlinTokenizer(), "\"${ `identifier}with{braces` }\"");
        singleString(new GroovyTokenizer(), "$/before ${\"inner ${'leaf'}\"} after/$");
        singleString(new GroovyTokenizer(), "/before ${\"inner ${'leaf'}\"} after/");
    }

    @Test
    void escapesAndNonInterpolatingQuotesRetainTheirBoundaries() {
        for (final var tokenizer : interpolatingTokenizers()) {
            boundary(tokenizer, "\"escaped \\${notAnExpression} and \\\"quote\\\"\"");
            boundary(tokenizer, "'${notAnExpression}'");
        }
        // Raw Kotlin triple quotes do not consume the backslash before the closer.
        boundary(new KotlinTokenizer(), "\"\"\"raw \\\"\"\"");
        boundary(new GroovyTokenizer(), "\"\"\"escaped \\\"\"\" still inside\"\"\"");
        boundary(new SqlTokenizer(), "'doubled '' quote'");
    }

    @Test
    void javascriptRetainsItsExistingOpaqueTemplateClassification() {
        // JavaScript currently treats backtick contents as opaque, without balancing
        // interpolation expressions. Do not add different language semantics here.
        final var tokenizer = new JavaScriptTokenizer();
        final var literal = "`before ${ { key: \"nested ${'leaf'}\" } } after`";
        singleString(tokenizer, literal);
        boundary(tokenizer, literal);
        boundary(tokenizer, "`escaped \\` delimiter ${\"quoted\"}`");
    }

    @Test
    void deeplyNestedInterpolationUsesNoJavaRecursion() {
        for (final var depth : new int[]{1_000, 10_000, 30_000}) {
            for (final var tokenizer : interpolatingTokenizers()) {
                for (final var delimiter : List.of("\"", "\"\"\"")) {
                    boundary(tokenizer, nested(delimiter, depth));
                }
            }
            // Exercises the direct templateEnd entry used by dollar-slashy literals.
            boundary(new GroovyTokenizer(), "$/x${" + nested("\"", depth) + "}x/$");
            boundary(new JavaScriptTokenizer(), "`" + nested("\"", depth) + "`");
        }
    }

    @Test
    void unterminatedDeepNestingConsumesTheRemainderExactly() {
        for (final var tokenizer : interpolatingTokenizers()) {
            final var prefix = "\"x${".repeat(30_000);
            for (final var ending : List.of("", "'unfinished", "/* unfinished", "// unfinished\r\n")) {
                singleString(tokenizer, prefix + ending);
            }
            singleString(tokenizer, "\"x${" + "{".repeat(30_000) + "'leaf'" + "}".repeat(29_999));
        }
        singleString(new GroovyTokenizer(), "$/x${" + "\"x${".repeat(30_000));
        singleString(new JavaScriptTokenizer(), "`" + "${\"x".repeat(30_000));
    }

    @Test
    void repeatedQuotesAndExpressionsRemainLossless() {
        for (final var tokenizer : List.<Tokenizer>of(new JavaScriptTokenizer(), new KotlinTokenizer(), new GroovyTokenizer())) {
            for (final var quotes : List.of("'", "\"", "`", "\\\"", "\"'`")) {
                lossless(tokenizer, quotes.repeat(60_000));
            }
            final var body = "'' \"x\" '''y''' \"\"\"z\"\"\" /* } */ ".repeat(20_000);
            final var delimiter = tokenizer instanceof JavaScriptTokenizer ? "`" : "\"";
            boundary(tokenizer, delimiter + "${" + body + "}" + delimiter);
        }
    }

    private List<Tokenizer> interpolatingTokenizers() {
        return List.of(new KotlinTokenizer(), new GroovyTokenizer());
    }

    private String nested(final String delimiter, final int depth) {
        return (delimiter + "x${").repeat(depth) + "'leaf'" + ("}x" + delimiter).repeat(depth);
    }

    private void singleString(final Tokenizer tokenizer, final String literal) {
        assertEquals(List.of(new Token(Kind.STRING, literal)), lossless(tokenizer, literal),
                tokenizer.getClass().getSimpleName());
    }

    private void boundary(final Tokenizer tokenizer, final String literal) {
        final var tokens = lossless(tokenizer, literal + " + 7");
        assertEquals(new Token(Kind.STRING, literal), tokens.get(0), tokenizer.getClass().getSimpleName());
        assertEquals(new Token(Kind.NUMBER, "7"), tokens.get(tokens.size() - 1), tokenizer.getClass().getSimpleName());
    }

    private List<Token> lossless(final Tokenizer tokenizer, final String source) {
        final var tokens = tokenizer.tokenize(source);
        assertEquals(source, tokens.stream().map(Token::text).collect(joining()), tokenizer.getClass().getSimpleName());
        return tokens;
    }
}
