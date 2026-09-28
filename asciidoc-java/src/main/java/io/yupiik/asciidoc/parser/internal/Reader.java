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

    // human indexed
    public int getLineNumber() {
        return lineOffset + 1;
    }

    public void reset() {
        lineOffset = 0;
    }

    public void insert(final List<String> lines) {
        this.lines.addAll(lineOffset, lines);
        if (original != null) {
            original.addAll(lineOffset, lines); // inserted in both lists so a line number means the same line in each
        }
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
        while (lineOffset < lines.size()) {
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

        // no line
        return null;
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
}
