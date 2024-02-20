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
package io.yupiik.asciidoc.parser;

import io.yupiik.asciidoc.parser.internal.Reader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReaderTest {
    @Test
    void skipCommentsAndEmptyLines() {
        assertEquals(
                "here",
                new Reader(List.of("", "", "", "// foo", "", "////", "multi", "////", "here", "")).skipCommentsAndEmptyLines());
    }

    @Test
    void nextLine() {
        final var reader = new Reader(List.of("a", "b"));
        assertEquals("a", reader.nextLine());
        assertEquals("b", reader.nextLine());
        assertNull(reader.nextLine());
    }

    @Test
    void rewind() {
        final var reader = new Reader(List.of("a", "b"));
        assertEquals("a", reader.nextLine());
        reader.rewind();
        assertEquals("a", reader.nextLine());
        assertEquals("b", reader.nextLine());
        assertNull(reader.nextLine());
    }
}
