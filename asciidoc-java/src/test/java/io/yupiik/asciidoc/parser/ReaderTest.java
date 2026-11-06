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

import io.yupiik.asciidoc.parser.internal.ParserSibling;
import io.yupiik.asciidoc.parser.internal.Reader;
import io.yupiik.asciidoc.parser.internal.TextScanner;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    void evaluatedBlockKeepsTheBranchThatHolds() { // the parser evaluated the conditionals before reading, each line keeps its number
        final var reader = evaluated(List.of("a", "ifdef::x[]", "x", "y", "else::[]", "z", "endif::[]", "b"), "x");
        assertEquals("a", reader.nextLine());
        assertEquals(2, reader.getLineNumber()); // the last line read is "a", line 1: the directive line is not part of what it ends
        assertEquals("x", reader.nextLine());
        assertEquals("y", reader.nextLine());
        assertEquals(5, reader.getLineNumber()); // after the last line of the branch, line 4
        assertEquals("b", reader.nextLine()); // the else branch and the endif are dropped
        assertEquals(9, reader.getLineNumber()); // after "b", line 8
        reader.rewind();
        assertEquals(5, reader.getLineNumber()); // "b" given back: the last line read is "y" again
        assertEquals("b", reader.nextLine());
        assertNull(reader.nextLine());
        assertEquals(List.of("a", "ifdef::x[]", "x", "y", "else::[]", "z", "endif::[]", "b"), reader.getOriginalLines());
    }

    @Test
    void evaluatedNestedBlock() { // a conditional inside the branch that holds drops its own lines
        final var reader = evaluated(List.of("ifdef::x[]", "x", "ifdef::y[]", "y", "endif::[]", "z", "endif::[]", "b"), "x", "y");
        assertEquals("x", reader.nextLine());
        assertEquals("y", reader.nextLine());
        assertEquals("z", reader.nextLine()); // the inner directive lines are dropped, the outer branch goes on
        assertEquals("b", reader.nextLine()); // the outer endif is dropped
        assertNull(reader.nextLine());
    }

    @Test
    void evaluatedNestedBlockEndingWithTheBranch() { // the inner endif::[] is right before the outer one: both are dropped
        final var reader = evaluated(List.of("ifdef::a[]", "ifdef::b[]", "x", "endif::[]", "endif::[]", "after"), "a", "b");
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
    void evaluatedBlocksNestedThreeDeep() { // the devtools includes of quarkus.io nest three directives in one listing
        final var reader = evaluated(List.of("ifdef::a[]", "ifdef::b[]", "ifdef::c[]", "x", "endif::[]", "endif::[]", "endif::[]", "after"), "a", "b", "c");
        assertEquals("x", reader.nextLine());
        assertEquals("after", reader.nextLine()); // the three endif::[] lines are dropped
        assertEquals(9, reader.getLineNumber());
        assertNull(reader.nextLine());
    }

    @Test
    void evaluatedBlocksNestedFourDeep() { // a fourth level reads as the others
        final var reader = evaluated(List.of("ifdef::a[]", "ifdef::b[]", "ifdef::c[]", "ifdef::d[]", "x", "endif::[]", "endif::[]", "endif::[]", "endif::[]", "after"),
                "a", "b", "c", "d");
        assertEquals("x", reader.nextLine());
        assertEquals("after", reader.nextLine()); // the four endif::[] lines are dropped
        assertEquals(11, reader.getLineNumber());
        assertNull(reader.nextLine());
    }

    @Test
    void evaluatedEmptyBranch() { // nothing to read: the block is dropped, so a rewind never reaches its directive lines
        final var reader = evaluated(List.of("ifdef::x[]", "endif::[]", "after", "later"), "x");
        assertEquals("after", reader.nextLine());
        assertEquals(4, reader.getLineNumber());
        reader.rewind();
        reader.rewind();
        assertEquals("after", reader.nextLine());
        assertEquals("later", reader.nextLine());
    }

    @Test
    void rewindBeforeABranch() { // the line before the branch is the one before the directive, which is never read
        final var reader = evaluated(List.of("a", "ifdef::x[]", "b", "endif::[]", "c"), "x");
        assertEquals("a", reader.nextLine());
        assertEquals("b", reader.nextLine());
        reader.rewind();
        reader.rewind();
        assertEquals("a", reader.nextLine());
        assertEquals("b", reader.nextLine());
        assertEquals("c", reader.nextLine());
        assertNull(reader.nextLine());
    }

    @Test
    void rewindAfterABlock() { // the line before the first one after the block is the last one of the branch
        final var reader = evaluated(List.of("ifdef::x[]", "x", "endif::[]", "b", "c"), "x");
        assertEquals("x", reader.nextLine());
        assertEquals("b", reader.nextLine());
        reader.rewind();
        reader.rewind();
        assertEquals("x", reader.nextLine());
        assertEquals("b", reader.nextLine());
        assertEquals("c", reader.nextLine());
        assertEquals(6, reader.getLineNumber());
        reader.resetOffset();
        assertEquals("x", reader.nextLine()); // the dropped lines stay dropped, a reset reads the evaluated lines again
    }

    @Test
    void skipCommentsAndEmptyLinesAfterAnEvaluatedBlock() {
        final var reader = evaluated(List.of("ifdef::x[]", "x", "", "endif::[]", "", "b"), "x");
        assertEquals("x", reader.skipCommentsAndEmptyLines());
        assertEquals("b", reader.skipCommentsAndEmptyLines());
        assertNull(reader.skipCommentsAndEmptyLines());
    }

    @Test
    void replaceOneBlockOfTheNextLines() { // the header evaluates the block of its directive line only, the lines after it keep their numbers
        final var reader = new Reader(List.of("a", "ifdef::x[]", "b", "endif::[]", "c", "ifdef::y[]", "d", "endif::[]"), null, (element, span) -> {});
        assertEquals("a", reader.nextLine());
        conditionals().evaluate(reader, 1, new HashMap<>(Map.of("x", "")));
        assertEquals(List.of("b", "c", "ifdef::y[]", "d", "endif::[]"), reader.getNextLines());
        assertEquals("b", reader.nextLine());
        assertEquals(4, reader.getLineNumber());
        assertEquals("c", reader.nextLine());
        assertEquals(6, reader.getLineNumber());
        assertEquals("ifdef::y[]", reader.nextLine());
        assertEquals(7, reader.getLineNumber());
    }

    @Test
    void lineNumbersWithoutListener() { // the ranges cost one entry per dropped block, so an error names the line in the document
        final var reader = new Reader(List.of("ifdef::x[]", "hidden", "endif::[]", "a"));
        conditionals().evaluate(reader, 4, new HashMap<>());
        assertEquals("a", reader.nextLine());
        assertEquals(5, reader.getLineNumber());
        assertNull(reader.getOriginalLines());
    }

    @Test
    void inlineDirectiveRewritesItsLineInPlace() { // nothing dropped, so the lines keep their positions as numbers
        final var reader = new Reader(List.of("a", "ifdef::x[b]", "c"), null, (element, span) -> {});
        conditionals().evaluate(reader, 3, new HashMap<>(Map.of("x", "")));
        assertEquals(List.of("a", "b", "c"), reader.getNextLines());
        reader.nextLine();
        reader.nextLine();
        assertEquals(3, reader.getLineNumber());
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

    // a reader with a listener, so each line keeps its number, after the parser evaluated its conditionals
    private Reader evaluated(final List<String> lines, final String... attributes) {
        final var reader = new Reader(lines, null, (element, span) -> {});
        final var known = new HashMap<String, String>();
        for (final var name : attributes) {
            known.put(name, "");
        }
        conditionals().evaluate(reader, lines.size(), known);
        return reader;
    }

    // the pass as the parser runs it, without parser attributes
    private ConditionalParser conditionals() {
        final var scanner = new TextScanner();
        return new ConditionalParser(scanner, new ParserSibling(Map.of(), scanner), Map.of(), message -> {
        });
    }
}
