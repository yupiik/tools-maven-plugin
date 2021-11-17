/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.tools.minisite.action.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

public class OpenMetricsToAsciidoc implements Runnable {
    private final Path source;
    private final Path to;
    private final String levelPrefix;
    private final boolean legend;
    private final String header;

    public OpenMetricsToAsciidoc(final Map<String, String> configuration) {
        this.source = Path.of(requireNonNull(configuration.get("source"), () -> "no  source found: " + configuration));
        this.to = Path.of(requireNonNull(configuration.get("to"), () -> "no  source found: " + configuration));
        this.levelPrefix = configuration.getOrDefault("levelPrefix", "== ");
        this.legend = Boolean.parseBoolean(configuration.getOrDefault("legend", "true"));
        this.header = configuration.getOrDefault("header", "");
    }

    @Override
    public void run() {
        try {
            final var lines = Files.readAllLines(source);
            final var rendered = render(lines);
            if (to.getParent() != null) {
                Files.createDirectories(to.getParent());
            }
            Files.writeString(to, rendered, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // enables to run it with exec maven plugin
    public static void main(final String... args) {
        if (args.length == 0 || args.length % 2 != 0) {
            throw new IllegalArgumentException("Missing argument, at least source and to");
        }
        new OpenMetricsToAsciidoc(IntStream.range(0, args.length / 2)
                .mapToObj(i -> new String[]{args[2 * i], args[2 * i + 1]})
                .collect(toMap(i -> i[0], i -> i[1])))
                .run();
    }

    private String render(final List<String> lines) {
        return header + "\n" +
                parseMetrics(lines).stream()
                        .sorted(comparing(m -> m.name))
                        .map(this::renderMetric)
                        .collect(joining("\n"));
    }

    private String renderMetric(final Metric nameAndDetails) {
        return levelPrefix + " " + nameAndDetails.name + "\n" +
                "\n" +
                (nameAndDetails.help == null ? "" : nameAndDetails.help) + "\n" +
                "\n" +
                (nameAndDetails.values.size() == 1 && nameAndDetails.values.get(0).tags.isEmpty() ?
                        "Value: `" + nameAndDetails.values.get(0).value + "`.\n\n" :
                        toTable(nameAndDetails));
    }

    private String toTable(final Metric metric) {
        return "[cols=\"2a,6\", options=\"header\"]\n" +
                (legend ? "." + metric.name + "\n" : "") +
                "|===\n" +
                "| Tag(s) | Value\n" +
                metric.values.stream()
                        .map(line -> "" +
                                "| " + (line.tags.isEmpty() ?
                                "-" :
                                line.tags.entrySet().stream()
                                        .map(e -> "" +
                                                "! " + e.getKey().replace("!", "\\!").replace("|", "\\|") +
                                                " ! " + e.getValue())
                                        .collect(joining("\n", "\n[stripes=none,cols=\"a,m\"]\n!===\n", "\n!===\n"))) +
                                "| " + line.value)
                        .collect(joining("\n\n")) +
                "\n|===\n";
    }

    private ArrayList<Metric> parseMetrics(List<String> lines) {
        final var metrics = new ArrayList<Metric>(1 + lines.size() / 4);
        Metric current = null;
        for (final var line : lines) {
            if (line.startsWith("# HELP ")) {
                current = newMetricInstanceIfNeeded(metrics, current, line, "# HELP ", " ");
                final var endOfName = line.indexOf(' ', "# HELP ".length() + 1);
                current.help = line.substring(endOfName + 1).strip();
                if (current.name == null) {
                    current.name = line.substring("# HELP ".length(), endOfName).strip();
                }
            } else if (line.startsWith("# TYPE ")) {
                current = newMetricInstanceIfNeeded(metrics, current, line, "# TYPE ", " ");
                final var endOfName = line.indexOf(' ', "# TYPE ".length() + 1);
                current.type = line.substring(endOfName + 1).strip();
                if (current.name == null) {
                    current.name = line.substring("# HELP ".length(), endOfName).strip();
                }
            } else if ((line.isBlank() || line.startsWith("# ")) && current != null) { // just an eager flush
                metrics.add(current);
                current = null;
            } else { // a value line so forward previous metrics meta and read line data to merge it in a Metric instance
                if (current == null) {
                    current = new Metric();
                }

                final var value = new MetricValue();
                final var space = line.indexOf(' ');
                final var bracket = line.indexOf('{');
                if (bracket > 0 && bracket < space) {
                    final var end = line.indexOf('}', bracket);
                    value.tags = Stream.of(line.substring(bracket + 1, end).split(","))
                            .collect(toMap(
                                    t -> t.substring(0, t.indexOf('=')),
                                    t -> {
                                        final var tagValue = t.substring(t.indexOf('=') + 1);
                                        if (tagValue.startsWith("\"") && tagValue.endsWith("\"")) {
                                            return tagValue.substring(1, tagValue.length() - 1);
                                        }
                                        return tagValue;
                                    },
                                    (a, b) -> a + "," + b, LinkedHashMap::new));

                    final var name = line.substring(0, bracket);
                    if (current.name == null) {
                        current.name = name;
                    }
                    if (!current.name.equals(name) && current.name.length() + 1 < name.length()) { // consider the suffix as a tag for the redenring
                        value.tags.put("_type_", name.substring(current.name.length() + 1));
                    }
                } else {
                    final var name = line.substring(0, space);
                    if (current.name == null) {
                        current.name = name;
                    }

                    if (!current.name.equals(name) && current.name.length() + 1 < name.length()) { // consider the suffix as a tag for the redenring
                        value.tags = Map.of("_type_", name.substring(current.name.length() + 1));
                    } else {
                        value.tags = Map.of();
                    }
                }

                value.value = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                current.values.add(value);
            }
        }
        if (current != null) {
            metrics.add(current);
        }
        return metrics;
    }

    private Metric newMetricInstanceIfNeeded(final List<Metric> metrics, final Metric current,
                                             final String line, final String expectedPrefix, final String... potentialSuffixes) {
        if (current == null || Stream.of(potentialSuffixes).noneMatch(s -> line.startsWith(expectedPrefix + current.name + s))) {
            if (current != null) {
                metrics.add(current);
            }
            return new Metric();
        }
        return current;
    }

    private static class MetricValue {
        private double value;
        private Map<String, String> tags = new LinkedHashMap<>();

        @Override
        public String toString() {
            return new StringJoiner(", ", MetricValue.class.getSimpleName() + "[", "]")
                    .add("value=" + value)
                    .add("tags=" + tags)
                    .toString();
        }
    }

    private static class Metric {
        private String name;
        private String help;
        private String type;
        private List<MetricValue> values = new ArrayList<>();

        @Override
        public String toString() {
            return new StringJoiner(", ", Metric.class.getSimpleName() + "[", "]")
                    .add("name='" + name + "'")
                    .add("help='" + help + "'")
                    .add("type='" + type + "'")
                    .add("values=" + values)
                    .toString();
        }
    }
}
