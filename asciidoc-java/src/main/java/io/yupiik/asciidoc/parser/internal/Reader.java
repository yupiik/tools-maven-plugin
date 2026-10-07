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
    // references substituted, and the parser reports the text before that substitution, see Parser#reportSource
    private final List<String> original;
    private final Path file;
    private final SourceListener listener;
    private int lineOffset = 0;
    // the parser evaluated a conditional directive block and reads the branch that holds again, see reread():
    // from the end of that branch the reader skips to the end of the block; a block nested in that branch takes the
    // first field and the enclosing one moves to the second, and two is the whole depth: fields, not a list
    private Skip skip;
    private Skip enclosingSkip;

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

    // human indexed: the number after the one of the last line read, so the number of the next line; right after a
    // skipped block, blank lines left out, the last line read is the last one of the branch read again, see reread()
    public int getLineNumber() {
        if (skip != null && lineOffset >= skip.to()) {
            int offset = lineOffset;
            while (offset > skip.to() && lines.get(offset - 1).isBlank()) {
                offset--;
            }
            if (offset == skip.to()) {
                return skip.from() + 1;
            }
        }
        return lineOffset + 1;
    }

    /**
     * @return the offset of the next line, so one past the offset of the last line read.
     */
    public int getLineOffset() {
        return lineOffset;
    }

    public void reset() {
        lineOffset = 0;
        skip = null;
        enclosingSkip = null;
    }

    /**
     * Reads the lines from {@code from} (included) to {@code to} (excluded) again, then goes on after the last line read:
     * the parser read a conditional directive block, evaluated it and gives the branch that holds back to its loop.
     * Each line keeps its offset, so its number and its text as read are the ones of the document.
     *
     * @param from the offset of the first line to read again.
     * @param to   the offset after the last line to read again.
     * @throws IllegalStateException when the block is nested in two branches read again, which the two fields do not hold.
     */
    public void reread(final int from, final int to) {
        if (from == to) { // an empty branch: nothing to read again, the reader is already after the block
            return;
        }
        while (skip != null && lineOffset > skip.to()) { // the blocks already passed
            skip = enclosingSkip;
            enclosingSkip = null;
        }
        if (skip != null) { // nested in the branch read again
            if (enclosingSkip != null) {
                throw new IllegalStateException("Conditional block nested in two branches read again, at line " + getLineNumber());
            }
            enclosingSkip = skip;
        }
        skip = new Skip(to, lineOffset);
        lineOffset = from;
    }

    public void rewind() {
        if (lineOffset > 0) {
            if (skip != null && lineOffset == skip.to()) { // the line before the first one after a block is the last one of its branch
                lineOffset = skip.from();
            }
            lineOffset--;
        }
    }

    public String nextLine() {
        skipToBlockEnd();
        if (lineOffset >= lines.size()) {
            return null;
        }

        final var line = lines.get(lineOffset);
        lineOffset++;
        return line;
    }

    public String skipCommentsAndEmptyLines() {
        while (true) {
            skipToBlockEnd();
            if (lineOffset >= lines.size()) {
                return null;
            }
            final var line = lines.get(lineOffset);
            lineOffset++;

            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("////")) { // go to the end of the comment
                for (int i = lineOffset + 1; i < lines.size(); i++) {
                    if (lines.get(i).startsWith("////")) {
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
        return line.startsWith("//") || line.startsWith("////");
    }

    public void setPreviousValue(final String newValue) {
        lines.set(lineOffset - 1, newValue);
    }

    @Override
    public String toString() {
        return "Reader[current=" + (lineOffset >= lines.size() ? "<none>" : lines.get(lineOffset)) + ", total=" + lines.size() + ", offset=" + lineOffset + "]";
    }

    // at the end of a branch read again, go to the end of its block; a block passed (the line after it was read, or a
    // comment block went over it) is dropped, and the skip of the block just left stays until then for rewind()
    private void skipToBlockEnd() {
        while (skip != null && lineOffset >= skip.from()) {
            if (lineOffset < skip.to()) {
                lineOffset = skip.to();
            } else if (lineOffset > skip.to()) {
                skip = enclosingSkip;
                enclosingSkip = null;
            } else if (enclosingSkip != null && skip.to() == enclosingSkip.from()) { // the nested block ends where the branch ends: one skip
                skip = new Skip(skip.from(), enclosingSkip.to());
                enclosingSkip = null;
            } else {
                break;
            }
        }
    }

    // the lines of a conditional block after the branch read again: from the end of the branch (included) to the line
    // after the block (excluded)
    private record Skip(int from, int to) {
    }
}
