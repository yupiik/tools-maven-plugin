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

import io.yupiik.asciidoc.parser.SourceListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple helper reader for the parser, should rarely (never) be needed by end users and this is not part of the public API.
 */
public class Reader {
    private final List<String> lines;
    // the lines as read, kept only when a listener is set: setPreviousValue() rewrites a line with its attribute
    // references substituted, and the parser reports the text before that substitution, see Parser#sourceSpan
    private final List<String> original;
    private final Path file;
    private final SourceListener listener;
    private int lineOffset = 0;
    private boolean skippedParagraphEnd;
    // where the lines come from once keepNextLines() dropped some, one range per run of lines that follow each other
    // in the document, sorted by offset, the first one at offset 0; null while the lines are the ones given
    private List<LineRange> ranges;

    public Reader(final List<String> lines) {
        this(lines, null, null);
    }

    public Reader(final List<String> lines, final Path file, final SourceListener listener) {
        this.lines = new ArrayList<>(lines);
        this.original = listener == null ? null : new ArrayList<>(lines);
        this.file = file;
        this.listener = listener;
    }

    /**
     * @return the file the lines were read from, {@code null} for a string or for content without a path.
     */
    public Path getFile() {
        return file;
    }

    /**
     * @return the listener the parser reports the source of each block to, {@code null} when none is set.
     */
    public SourceListener getSourceListener() {
        return listener;
    }

    /**
     * @return the lines as read, before {@link #setPreviousValue(String)} rewrote any of them, {@code null} when no listener is set.
     */
    public List<String> getOriginalLines() {
        return original;
    }

    // human indexed: the number after the one of the last line read, so the number of the next line when no line was
    // dropped; when the last lines read are blank lines right after dropped lines, the number after the line before the
    // dropped ones, so a block that ends there does not take them
    public int getLineNumber() {
        if (ranges == null || lineOffset == 0) {
            return lineOffset + 1;
        }
        int offset = lineOffset - 1;
        while (offset > 0 && lines.get(offset).isBlank()) {
            final int previous = lineNumber(offset - 1);
            if (previous + 1 != lineNumber(offset)) {
                return previous + 1;
            }
            offset--;
        }
        return lineNumber(lineOffset - 1) + 1;
    }

    /**
     * @return the offset of the next line, so one past the offset of the last line read.
     */
    public int getLineOffset() {
        return lineOffset;
    }

    /**
     * @return the lines not read yet, a view the parser rewrites in place when it evaluates their conditional directives,
     * see {@link #keepNextLines(int, int, List)}.
     */
    public List<String> getNextLines() {
        return lines.subList(lineOffset, lines.size());
    }

    /**
     * Drops lines before they are read: the parser evaluated the conditional directives of the next {@code count} lines
     * and wrote the {@code kept} lines it keeps at their start, through {@link #getNextLines()}, so the lines after them
     * up to {@code count} are dropped. Each line keeps its number in the document.
     *
     * @param kept    how many lines the parser kept, written at the start of the next lines.
     * @param count   how many of the next lines the parser evaluated.
     * @param origins where the kept lines come from, one range per run of lines that follow each other among the lines
     *                evaluated, sorted by offset, the first one at offset 0.
     */
    public void keepNextLines(final int kept, final int count, final List<LineRange> origins) {
        final var updated = new ArrayList<LineRange>(origins.size() + 2 + (ranges == null ? 0 : ranges.size()));
        if (ranges == null) {
            updated.add(new LineRange(0, 0));
        } else {
            for (final var range : ranges) {
                if (range.offset() < lineOffset) {
                    updated.add(range);
                }
            }
        }
        for (final var range : origins) {
            updated.add(new LineRange(lineOffset + range.offset(), lineNumber(lineOffset + range.origin()) - 1));
        }
        final int after = lineOffset + count;
        if (after < lines.size()) {
            final int shift = kept - count;
            updated.add(new LineRange(after + shift, lineNumber(after) - 1));
            if (ranges != null) {
                for (final var range : ranges) {
                    if (range.offset() > after) {
                        updated.add(new LineRange(range.offset() + shift, range.origin()));
                    }
                }
            }
        }
        ranges = updated;
        lines.subList(lineOffset + kept, after).clear();
    }

    /**
     * Moves the reader back to its first line; the lines that {@link #keepNextLines(int, int, List)} dropped stay dropped.
     */
    public void resetOffset() {
        lineOffset = 0;
    }

    public void rewind() {
        if (lineOffset > 0) {
            lineOffset--;
        }
    }

    public String nextLine() {
        if (lineOffset >= lines.size()) {
            return null;
        }

        final var line = lines.get(lineOffset);
        lineOffset++;
        return line;
    }

    public String skipCommentsAndEmptyLines() {
        skippedParagraphEnd = false;
        while (true) {
            if (lineOffset >= lines.size()) {
                return null;
            }
            final var line = lines.get(lineOffset);
            lineOffset++;

            if (line.isBlank()) {
                skippedParagraphEnd = true;
                continue;
            }
            if (isCommentBlockDelimiter(line)) { // go to the end of the comment
                skippedParagraphEnd = true;
                for (int i = lineOffset + 1; i < lines.size(); i++) {
                    if (isCommentBlockDelimiter(lines.get(i))) {
                        lineOffset = i + 1;
                        break;
                    }
                }
                continue;
            }
            if (line.startsWith("//")) {
                continue;
            }

            return line;
        }
    }

    public boolean isComment(final String line) {
        return line.startsWith("//") || isCommentBlockDelimiter(line);
    }

    /**
     * @param line a line.
     * @return whether the line opens or closes a comment block: {@code ////} from the first column.
     */
    public boolean isCommentBlockDelimiter(final String line) {
        return line.startsWith("////");
    }

    /**
     * @return whether the last {@link #skipCommentsAndEmptyLines()} call went over a blank line or a {@code ////}
     * comment block, which end a paragraph as in asciidoctor. A {@code //} line comment does not end it.
     */
    public boolean skippedParagraphEnd() {
        return skippedParagraphEnd;
    }

    public void setPreviousValue(final String newValue) {
        lines.set(lineOffset - 1, newValue);
    }

    @Override
    public String toString() {
        return "Reader[current=" + (lineOffset >= lines.size() ? "<none>" : lines.get(lineOffset)) + ", total=" + lines.size() + ", offset=" + lineOffset + "]";
    }

    // human indexed, the number in the document of the line at this offset, from the last range starting at or before it
    private int lineNumber(final int offset) {
        if (ranges == null) {
            return offset + 1;
        }
        int low = 0;
        int high = ranges.size() - 1;
        while (low < high) {
            final int middle = (low + high + 1) >>> 1;
            if (ranges.get(middle).offset() <= offset) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        final var range = ranges.get(low);
        return range.origin() + offset - range.offset() + 1;
    }

    /**
     * Lines that follow each other in the lines they come from: from {@code offset} on, they are the lines from
     * {@code origin} on, both counted from 0.
     *
     * @param offset the offset of the first line of the range.
     * @param origin the offset of that line in the lines it comes from.
     */
    public record LineRange(int offset, int origin) {
    }
}
