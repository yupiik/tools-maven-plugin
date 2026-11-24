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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared lexical predicates for the configuration tokenizers. */
public final class ConfigurationTokenizerSupport {
    private static final Set<String> YAML_LITERALS = Set.of("true", "false", "null", "~");
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+|(?:[0-9][0-9_]*(?:\\.[0-9_]*)?|\\.[0-9_]+)(?:[eE][+-]?[0-9_]+)?|inf|nan|\\.inf|\\.nan)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOML_NUMBER = Pattern.compile(
            "(?:0x[0-9a-fA-F_]+|0o[0-7_]+|0b[01_]+|" +
                    "[+-]?(?:(?:0|[1-9][0-9_]*)(?:\\.[0-9_]+)?(?:[eE][+-]?[0-9_]+)?|inf|nan))");

    private ConfigurationTokenizerSupport() {
        // No instances.
    }

    public static boolean horizontal(final char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    public static boolean oneOf(final char c, final String options) {
        return options.contains(String.valueOf(c));
    }

    public static boolean nameStart(final char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
    }

    public static boolean namePart(final char c) {
        return nameStart(c) || c >= '0' && c <= '9';
    }

    public static Kind scalar(final String text) {
        if (YAML_LITERALS.contains(text.toLowerCase(Locale.ROOT))) {
            return Kind.KEYWORD;
        }
        return NUMBER.matcher(text).matches() ? Kind.NUMBER : Kind.PLAIN;
    }

    public static Kind tomlScalar(final String value) {
        if (value.equals("true") || value.equals("false")) {
            return Kind.KEYWORD;
        }
        if (!TOML_NUMBER.matcher(value).matches()) {
            return Kind.PLAIN;
        }
        // Validate separators iteratively rather than a repeated regex group, which
        // can exhaust the regex engine's stack on very long numeric scalars.
        final var radix = value.startsWith("0x") ? 16 : value.startsWith("0o") ? 8 : value.startsWith("0b") ? 2 : 10;
        for (var i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '_' && (i == 0 || i + 1 == value.length() ||
                    Character.digit(value.charAt(i - 1), radix) < 0 || Character.digit(value.charAt(i + 1), radix) < 0)) {
                return Kind.PLAIN;
            }
        }
        return Kind.NUMBER;
    }

    /** Shared quotes for YAML and TOML; shell has different escape/interpolation rules. */
    public static int quoteEnd(final String text, final int start, final boolean yaml, final boolean triple) {
        final var quote = text.charAt(start);
        final var width = triple ? 3 : 1;
        var i = start + width;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && quote == '"') {
                i = Math.min(text.length(), i + 2);
            } else if (text.charAt(i) == quote) {
                if (triple) {
                    var end = i;
                    while (end < text.length() && text.charAt(end) == quote) {
                        end++;
                    }
                    if (end - i >= 3) {
                        return end;
                    }
                    i = end;
                } else if (yaml && quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return text.length();
    }
}
