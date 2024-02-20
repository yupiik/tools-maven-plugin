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
package io.yupiik.tools.codec.properties;

import lombok.Data;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Collector;

/**
 * This class intends to be able to load properties but keep the original layout as much as possible
 * and rewrite it with the comments in place.
 */
public class LightProperties {
    private final Consumer<String> warn;
    private final List<Line> lines = new ArrayList<>();
    private final Properties properties = new Properties();

    public LightProperties(final Consumer<String> warn) {
        this.warn = warn;
    }

    public void load(final BufferedReader reader, final boolean usePlainProperties) throws IOException {
        if (usePlainProperties) {
            properties.load(reader);
        } else {
            doLoad(reader);
        }
    }

    public Properties toWorkProperties() {
        return lines.isEmpty() ? properties : lines.stream()
                .filter(it -> it.key != null)
                .collect(Collector.of(Properties::new, (p, l) -> {
                    final var tmp = new Properties();
                    try (final var r = new StringReader(l.key + '=' + l.value)) { // unescape
                        tmp.load(r);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                    final var key = tmp.stringPropertyNames().iterator().next();
                    p.setProperty(key, tmp.getProperty(key));
                }, (p1, p2) -> {
                    p1.putAll(p2);
                    return p1;
                }));
    }

    public void write(final Properties transformed, final Writer outputWriter) throws IOException {
        for (final var line : lines) {
            if (line.key == null) {
                outputWriter.write(line.comment + '\n');
            } else {
                final var newValue = transformed.getProperty(line.key);
                if (newValue == null) {
                    warn.accept("Missing value for '" + line.key + "'");
                    continue; // ignore
                }

                // ensure we encode right
                final var tmp = new Properties();
                tmp.setProperty(line.key, newValue);
                final var writer = new StringWriter();
                try (final var out = new SimplePropertiesWriter(writer)) {
                    tmp.store(out, "ignored");
                }
                outputWriter.write((line.comment != null && !line.comment.isBlank() ? line.comment + '\n' : "") + writer.toString().strip() + '\n');
            }
        }
    }

    private void doLoad(final BufferedReader reader) {
        final var iterator = reader.lines().iterator();
        final var comments = new ArrayList<String>();
        while (iterator.hasNext()) {
            final var line = iterator.next();
            if (line.startsWith("#")) {
                comments.add(line);
            } else if (line.strip().isBlank()) {
                if (!comments.isEmpty()) {
                    lines.add(new Line(String.join("\n", comments), null, null, null));
                    comments.clear();
                }
                lines.add(new Line(line, null, null, null));
            } else {
                boolean found = false;
                for (int i = 0; i < line.length(); i++) {
                    switch (line.charAt(i)) {
                        case '\\':
                            i++; // escaped so don't check next char, can't be a separator
                            break;
                        case ':':
                        case '=':
                            final var value = new StringBuilder(line.substring(i + 1));
                            while (value.toString().endsWith("\\") && iterator.hasNext()) {
                                value.append('\n').append(iterator.next());
                            }
                            lines.add(new Line(
                                    String.join("\n", comments),
                                    line.substring(0, i).strip(),
                                    Character.toString(line.charAt(i)),
                                    value.toString()));
                            comments.clear();
                            found = true;
                            break;
                        default:
                    }
                    if (found) {
                        break;
                    }
                }
                if (!found) {
                    lines.add(new Line(line, null, null, null));
                }
            }
        }
        if (!comments.isEmpty()) {
            lines.add(new Line(String.join("\n", comments), null, null, null));
        }
    }

    public LightProperties load(final Path from, final boolean skipComments) throws IOException {
        try (final var read = Files.newBufferedReader(from)) {
            load(read, skipComments);
        }
        return this;
    }

    @Data
    private static class Line {
        private final String comment;
        private final String key;
        private final String separator;
        private final String value;
    }

    public static class SimplePropertiesWriter extends BufferedWriter {
        private int remainingNewLinesToSkip = 2;

        public SimplePropertiesWriter(final Writer outputWriter) {
            super(outputWriter);
        }

        @Override
        public void write(final String str) throws IOException {
            if (remainingNewLinesToSkip == 0) {
                super.write(str);
            }
        }

        @Override
        public void newLine() throws IOException {
            if (remainingNewLinesToSkip > 0) {
                remainingNewLinesToSkip--;
            } else {
                super.newLine();
            }
        }
    }
}
