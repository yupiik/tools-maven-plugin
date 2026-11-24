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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static io.yupiik.language.tokenizer.languages.ConfigurationTokenizerSupport.horizontal;

/** Shared range painting preserves every source character while allowing style overrides. */
public final class RangeBuffer {
    private final String text;
    private final Kind[] kinds;

    public RangeBuffer(final String text) {
        this.text = Objects.requireNonNull(text, "source");
        kinds = new Kind[text.length()];
    }

    public String text() {
        return text;
    }

    public void mark(final int from, final int to, final Kind kind) {
        Arrays.fill(kinds, from, to, kind);
    }

    public int lineEnd(final int from) {
        var end = from;
        while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') {
            end++;
        }
        return end;
    }

    public int nextLine(final int end) {
        var next = end;
        if (next < text.length() && text.charAt(next) == '\r') {
            next++;
        }
        if (next < text.length() && text.charAt(next) == '\n') {
            next++;
        }
        return next;
    }

    public int spaces(final int from, final int end) {
        var i = from;
        while (i < end && horizontal(text.charAt(i))) {
            i++;
        }
        return i;
    }

    public List<Token> tokens() {
        final var result = new ArrayList<Token>();
        var start = 0;
        while (start < text.length()) {
            final var kind = kinds[start] == null ? Kind.PLAIN : kinds[start];
            var end = start + 1;
            while (end < text.length() && (kinds[end] == null ? Kind.PLAIN : kinds[end]) == kind) {
                end++;
            }
            result.add(new Token(kind, text.substring(start, end)));
            start = end;
        }
        return List.copyOf(result);
    }
}
