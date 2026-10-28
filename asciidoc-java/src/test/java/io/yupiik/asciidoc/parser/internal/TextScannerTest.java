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
package io.yupiik.asciidoc.parser.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextScannerTest {
    private final TextScanner scanner = new TextScanner();

    @Test
    void escapes() {
        assertFalse(scanner.isEscaped("*a*", 0));
        assertTrue(scanner.isEscaped("\\*a*", 1));
        assertTrue(scanner.isEscaped("\\\\*a*", 2), "a second backslash does not undo the escape, as in asciidoctor");
        assertEquals(0, scanner.backslashesBefore("*a*", 0));
        assertEquals(2, scanner.backslashesBefore("\\\\*a*", 2));
    }

    @Test
    void nextUnescapedBracketSkipsOneBackslash() {
        assertEquals(7, scanner.nextUnescaped("[a \\] b]", ']', 1), "link:x[a \\] b]: the escaped ']' is not the end");
        assertEquals(8, scanner.nextUnescaped("[a \\\\] b]", ']', 1), "two backslashes still escape the ']'");
        assertEquals(-1, scanner.nextUnescaped("[a \\]", ']', 1));
        assertEquals(0, scanner.nextUnescaped("]", ']', 0));
    }

    @Test
    void nextUnescapedMarkdownCountsTheBackslashes() {
        assertEquals(7, scanner.nextUnescapedMarkdown("a \\| b | c", '|', 0), "\\| is text in a Markdown cell");
        assertEquals(4, scanner.nextUnescapedMarkdown("a \\\\| b | c", '|', 0), "\\\\| is a backslash then a separator");
        assertEquals(9, scanner.nextUnescapedMarkdown("a \\\\\\| b | c", '|', 0), "three backslashes escape again");
        assertEquals(0, scanner.nextUnescapedMarkdown("| a", '|', 0));
    }

    @Test
    void constrainedPair() {
        assertEquals(4, scanner.constrainedEnd("a *b* c", 2, '*'));
        assertEquals(-1, scanner.constrainedEnd("a*b*c", 1, '*'), "a word character before the mark");
        assertEquals(-1, scanner.constrainedEnd("snake_case_name", 5, '_'), "the closing mark is followed by a word character");
        assertEquals(-1, scanner.constrainedEnd("a * b* c", 2, '*'), "a space after the opening mark");
        assertEquals(-1, scanner.constrainedEnd("a *b * c", 2, '*'), "a space before the only closing mark");
        assertEquals(8, scanner.constrainedEnd("a *b * c* d", 2, '*'), "a mark that cannot close is skipped");
        assertEquals(-1, scanner.constrainedEnd("x}*b* c", 2, '*'), "asciidoctor refuses '}' before the mark");
        assertEquals(-1, scanner.constrainedEnd("a *b", 2, '*'));
    }

    @Test
    void unconstrainedPair() {
        assertEquals(3, scanner.unconstrainedEnd("H~2~O", 1, '~'), "opens and closes inside a word");
        assertEquals(-1, scanner.unconstrainedEnd("a ~ b~ c", 2, '~'));
        assertEquals(-1, scanner.unconstrainedEnd("a ~b ~ c", 2, '~'));
        assertEquals(5, scanner.unconstrainedEnd("a ~~b~~ c", 2, "~~", 4));
        assertEquals(-1, scanner.unconstrainedEnd("a ~~ b~~ c", 2, "~~", 4));
    }

    @Test
    void passthroughs() {
        assertEquals(new TextScanner.InlinePassthrough(2, 1, 5), scanner.passthroughAt("a +b+ c", 2, 7));
        assertEquals(new TextScanner.InlinePassthrough(2, 2, 9), scanner.passthroughAt("a ++b c++ d", 2, 11));
        assertEquals(new TextScanner.InlinePassthrough(2, 3, 11), scanner.passthroughAt("a +++b c+++ d", 2, 13));
        assertNull(scanner.passthroughAt("a \\+b+ c", 3, 8), "escaped");
        assertNull(scanner.passthroughAt("a+b+c", 1, 5), "a word character before the sign");
        assertNull(scanner.passthroughAt("a +b+c", 2, 6), "a word character after the closing sign");
        assertNull(scanner.passthroughAt("a + b+ c", 2, 8), "a blank after the opening sign");
        assertNull(scanner.passthroughAt("a +b+ c", 2, 4), "closes after the limit");
        assertEquals(new TextScanner.InlinePassthrough(1, 1, 4), scanner.openingPassthrough("`+a+ b`", 0));
        assertNull(scanner.openingPassthrough("`a+ b`", 0));
        assertEquals(List.of(new TextScanner.InlinePassthrough(1, 1, 4), new TextScanner.InlinePassthrough(7, 2, 12)),
                scanner.passthroughs("`+a+ b ++c++ d`", 1, 14, scanner.openingPassthrough("`+a+ b ++c++ d`", 0)));
        assertEquals(List.of(), scanner.passthroughs("`a b`", 1, 4, null));
    }

    @Test
    void codeSpans() {
        assertTrue(scanner.hasOpenCodeSpan("a `b"));
        assertFalse(scanner.hasOpenCodeSpan("a `b` c"));
        assertFalse(scanner.hasOpenCodeSpan("a \\` c"), "an escaped backtick is text");
        assertTrue(scanner.hasOpenCodeSpan("a `+b`c+ d"), "the passthrough the span opens with holds the backtick");
        assertNull(scanner.codeSpanPassthroughs("a `b` c"), "no '+'");
        assertNull(scanner.codeSpanPassthroughs("a + `b` c"), "no passthrough in a span");
        final var indices = scanner.codeSpanPassthroughs("a `+{b}+` c");
        assertTrue(indices.get(3) && indices.get(7) && !indices.get(2) && !indices.get(8));
    }

    @Test
    void nestedMacrosAndPassthroughs() {
        assertEquals(7, scanner.macroOrPassthroughEnd("x[a,b=1] y", 1));
        assertEquals(4, scanner.macroOrPassthroughEnd("a +b+ c", 2));
        assertEquals(-1, scanner.macroOrPassthroughEnd("a b", 0));
        assertEquals(-1, scanner.macroOrPassthroughEnd("a + b", 2));
        assertTrue(scanner.containsOutsideMacrosAndPassthroughs("a=1", "="));
        assertFalse(scanner.containsOutsideMacrosAndPassthroughs("image:a.png[alt=x]", "="), "the '=' belongs to the image");
        assertFalse(scanner.containsOutsideMacrosAndPassthroughs("a ++b=1++ c", "="), "the '=' belongs to the passthrough");
        assertTrue(scanner.containsOutsideMacrosAndPassthroughs("image:a.png[alt=x],role=y", ","));
        assertFalse(scanner.containsOutsideMacrosAndPassthroughs("a b", "="));
    }

    @Test
    void runsAndBlanks() {
        assertEquals(3, scanner.runLength("=== a", 0, '='));
        assertEquals(0, scanner.runLength("a ===", 0, '='));
        assertEquals(2, scanner.runLength("###", 1, '#'));
        assertEquals(0, scanner.runLength("#", 1, '#'), "from the end of the text");
        assertTrue(scanner.isBlank(' '));
        assertTrue(scanner.isBlank('\t'));
        assertFalse(scanner.isBlank('a'));
        assertTrue(scanner.holdsBlank("a\tb"));
        assertFalse(scanner.holdsBlank("a-b"));
        assertTrue(scanner.holdsWhitespace("a\u000bb"));
        assertFalse(scanner.holdsWhitespace("ab"));
        assertEquals(5, scanner.skipBlanks("a \t  b", 1));
        assertEquals(3, scanner.skipBlanks("a  ", 1), "the length when only blanks remain");
        assertEquals(1, scanner.trailingBlanksStart("a \t ", 4));
        assertEquals(1, scanner.trailingBlanksStart("ab", 1), "the index itself when nothing precedes it");
    }

    @Test
    void targets() {
        assertTrue(scanner.isMacroTarget("image:a.png[alt]", 6));
        assertFalse(scanner.isMacroTarget("image:a b.png[alt]", 6));
        assertFalse(scanner.isMacroTarget("image:a.png", 6));
        assertTrue(scanner.isBlankTarget("image:a b.png[alt]", 6, 13));
        assertFalse(scanner.isBlankTarget("image: a.png[alt]", 6, 12), "starts with a blank");
        assertFalse(scanner.isBlankTarget("image:a.png [alt]", 6, 12), "ends with a blank");
        assertFalse(scanner.isBlankTarget("image::a.png[alt]", 6, 12), "starts with a colon");
        assertFalse(scanner.isBlankTarget("image:a[b.png[alt]", 6, 13), "holds another '['");
    }

    @Test
    void names() {
        assertTrue(scanner.isWordCharacter('_'));
        assertTrue(scanner.isWordCharacter('é'));
        assertFalse(scanner.isWordCharacter('-'));
        assertTrue(scanner.isMacroNameChar('-'));
        assertFalse(scanner.isMacroNameChar(':'));
        assertTrue(scanner.isAttributeName("source-highlighter"));
        assertTrue(scanner.isAttributeName("a.b"));
        assertTrue(scanner.isAttributeName("tag!"), "the include tag negation");
        assertFalse(scanner.isAttributeName("a!b"));
        assertFalse(scanner.isAttributeName("-a"));
        assertFalse(scanner.isAttributeName(""));
        assertTrue(scanner.isAttributeReferenceName("project-version"));
        assertFalse(scanner.isAttributeReferenceName("a.b"), "no '.' in a reference name");
        assertFalse(scanner.isAttributeReferenceName(""));
        assertTrue(scanner.isAnchorId("_a-b:c.d"));
        assertTrue(scanner.isAnchorId(":a"));
        assertFalse(scanner.isAnchorId("1a"), "starts with a digit");
        assertFalse(scanner.isAnchorId("a b"));
        assertFalse(scanner.isAnchorId(""));
    }
}
