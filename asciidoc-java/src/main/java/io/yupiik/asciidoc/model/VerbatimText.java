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
package io.yupiik.asciidoc.model;

import java.util.List;
import java.util.Map;

// the parts of a verbatim block (Code, Listing, PassthroughBlock): how a text becomes parts and parts become the text
final class VerbatimText {
    private VerbatimText() {
        // no-op
    }

    // a block built from its text: one part, none for an empty text
    static List<Element> parts(final String value) {
        return value.isEmpty() ? List.of() : List.of(new Text(List.of(), value, Map.of()));
    }

    // the parts in order, the lines of every conditional branch included
    static String text(final List<Element> parts) {
        if (parts.size() == 1 && parts.get(0) instanceof Text text) {
            return text.value();
        }
        final var out = new StringBuilder();
        append(parts, out);
        return out.toString();
    }

    private static void append(final List<Element> parts, final StringBuilder out) {
        for (final var part : parts) {
            if (part instanceof Text text) {
                out.append(text.value());
            } else if (part instanceof ConditionalBlock block) {
                append(block.children(), out);
                for (final var branch : block.elseBranches()) {
                    append(branch.children(), out);
                }
            } else {
                throw new IllegalArgumentException("Not a part of a verbatim block: " + part);
            }
        }
    }
}
