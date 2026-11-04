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
    void rereadReadsTheBranchAgainThenSkipsTheRestOfTheBlock() { // the parser evaluated a conditional and gives its branch back
        final var reader = new Reader(List.of("a", "ifdef::x[]", "x", "y", "else::[]", "z", "endif::[]", "b"), null, (element, span) -> {});
        assertEquals("a", reader.nextLine());
        assertEquals("ifdef::x[]", reader.nextLine());
        for (int i = 0; i < 5; i++) { // the parser reads the block up to its endif::[]
            reader.nextLine();
        }
        assertEquals(7, reader.getLineOffset());
        reader.reread(2, 4);
        assertEquals(3, reader.getLineNumber()); // each line keeps its own number
        assertEquals("x", reader.nextLine());
        assertEquals("y", reader.nextLine());
        assertEquals(5, reader.getLineNumber()); // after the last line of the branch, line 4
        assertEquals("b", reader.nextLine()); // the else branch and the endif are skipped
        assertEquals(9, reader.getLineNumber()); // after "b", line 8
        reader.rewind();
        assertEquals(5, reader.getLineNumber()); // "b" given back: the last line read is "y" again
        assertEquals("b", reader.nextLine());
        assertNull(reader.nextLine());
        assertEquals(List.of("a", "ifdef::x[]", "x", "y", "else::[]", "z", "endif::[]", "b"), reader.getOriginalLines());
    }

    @Test
    void rereadNested() { // a conditional inside the branch read again adds its own skip
        final var reader = new Reader(List.of("ifdef::x[]", "x", "ifdef::y[]", "y", "endif::[]", "z", "endif::[]", "b"));
        reader.nextLine();
        for (int i = 0; i < 6; i++) {
            reader.nextLine();
        }
        reader.reread(1, 6);
        assertEquals("x", reader.nextLine());
        assertEquals("ifdef::y[]", reader.nextLine());
        reader.nextLine(); // y
        reader.nextLine(); // endif::[]
        reader.reread(3, 4);
        assertEquals("y", reader.nextLine());
        assertEquals("z", reader.nextLine()); // the inner endif is skipped, the outer branch goes on
        assertEquals("b", reader.nextLine()); // the outer endif is skipped
        assertNull(reader.nextLine());
    }

    @Test
    void rereadNestedBlockEndingWithTheBranch() { // the inner endif::[] is right before the outer one: one skip to the end of the outer block
        final var reader = new Reader(List.of("ifdef::a[]", "ifdef::b[]", "x", "endif::[]", "endif::[]", "after"));
        for (int i = 0; i < 5; i++) {
            reader.nextLine();
        }
        reader.reread(1, 4);
        assertEquals("ifdef::b[]", reader.nextLine());
        reader.nextLine(); // x
        reader.nextLine(); // the inner endif::[]
        reader.reread(2, 3);
        assertEquals("x", reader.nextLine());
        assertEquals("after", reader.nextLine());
        assertEquals(7, reader.getLineNumber());
        reader.rewind();
        assertEquals(4, reader.getLineNumber()); // the line before "after" is "x", line 3
        reader.rewind();
        assertEquals("x", reader.nextLine());
        assertEquals("after", reader.nextLine());
        assertNull(reader.nextLine());
    }

    @Test
    void rereadNestedTwice() { // the devtools includes of quarkus.io nest three directives in one listing: two enclosing skips fill the array
        final var reader = new Reader(List.of("ifdef::a[]", "ifdef::b[]", "ifdef::c[]", "x", "endif::[]", "endif::[]", "endif::[]", "after"));
        for (int i = 0; i < 7; i++) {
            reader.nextLine();
        }
        reader.reread(1, 6);
        assertEquals("ifdef::b[]", reader.nextLine());
        for (int i = 0; i < 4; i++) {
            reader.nextLine();
        }
        reader.reread(2, 5);
        assertEquals("ifdef::c[]", reader.nextLine());
        reader.nextLine(); // x
        reader.nextLine(); // the innermost endif::[]
        reader.reread(3, 4);
        assertEquals("x", reader.nextLine());
        assertEquals("after", reader.nextLine()); // the three endif::[] lines are skipped, one block at a time
        assertEquals(9, reader.getLineNumber());
        assertNull(reader.nextLine());
    }

    @Test
    void rereadNestedThreeTimes() { // a fourth level grows the array of enclosing skips and reads as the others
        final var reader = new Reader(List.of("ifdef::a[]", "ifdef::b[]", "ifdef::c[]", "ifdef::d[]", "x", "endif::[]", "endif::[]", "endif::[]", "endif::[]", "after"));
        for (int i = 0; i < 9; i++) {
            reader.nextLine();
        }
        reader.reread(1, 8);
        assertEquals("ifdef::b[]", reader.nextLine());
        for (int i = 0; i < 6; i++) {
            reader.nextLine();
        }
        reader.reread(2, 7);
        assertEquals("ifdef::c[]", reader.nextLine());
        for (int i = 0; i < 4; i++) {
            reader.nextLine();
        }
        reader.reread(3, 6);
        assertEquals("ifdef::d[]", reader.nextLine());
        reader.nextLine(); // x
        reader.nextLine(); // the innermost endif::[]
        reader.reread(4, 5);
        assertEquals("x", reader.nextLine());
        assertEquals("after", reader.nextLine()); // the four endif::[] lines are skipped, one block at a time
        assertEquals(11, reader.getLineNumber());
        assertNull(reader.nextLine());
    }

    @Test
    void rereadAnEmptyBranch() { // nothing to read again, no skip: a rewind gives the line before back, not the directive
        final var reader = new Reader(List.of("ifdef::x[]", "endif::[]", "after", "later"));
        reader.nextLine();
        reader.nextLine();
        reader.reread(1, 1);
        assertEquals("after", reader.nextLine());
        assertEquals(4, reader.getLineNumber());
        reader.rewind();
        reader.rewind();
        assertEquals("endif::[]", reader.nextLine());
        assertEquals("after", reader.nextLine());
        assertEquals("later", reader.nextLine());
    }

    @Test
    void rewindAfterABlock() { // the line before the first one after the block is the last one of the branch
        final var reader = new Reader(List.of("ifdef::x[]", "x", "endif::[]", "b", "c"));
        reader.nextLine();
        reader.nextLine();
        reader.nextLine();
        reader.reread(1, 2);
        assertEquals("x", reader.nextLine());
        assertEquals("b", reader.nextLine());
        reader.rewind();
        reader.rewind();
        assertEquals("x", reader.nextLine());
        assertEquals("b", reader.nextLine());
        assertEquals("c", reader.nextLine());
        assertEquals(6, reader.getLineNumber());
        reader.reset();
        assertEquals("ifdef::x[]", reader.nextLine()); // a reset drops the skips
    }

    @Test
    void skipCommentsAndEmptyLinesHonoursTheBlockEnd() {
        final var reader = new Reader(List.of("ifdef::x[]", "x", "", "endif::[]", "", "b"));
        for (int i = 0; i < 4; i++) {
            reader.nextLine();
        }
        reader.reread(1, 3);
        assertEquals("x", reader.skipCommentsAndEmptyLines());
        assertEquals("b", reader.skipCommentsAndEmptyLines());
        assertNull(reader.skipCommentsAndEmptyLines());
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
