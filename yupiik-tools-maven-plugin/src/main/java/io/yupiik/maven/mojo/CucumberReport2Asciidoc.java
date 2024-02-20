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
package io.yupiik.maven.mojo;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.annotation.JsonbProperty;
import lombok.Data;
import org.apache.johnzon.mapper.reflection.JohnzonParameterizedType;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * Convert cucumber JSON report(s) into an asciidoc file which can be rendered with PDF or HTML goals.
 */
@Mojo(name = "cucumber2asciidoc", threadSafe = true)
public class CucumberReport2Asciidoc extends AbstractMojo {
    @Parameter(property = "yupiik.cucumber.source", defaultValue = "${project.build.directory/cucumber-reports/")
    protected File source;

    @Parameter(property = "yupiik.cucumber.target", defaultValue = "${project.build.directory}/generated-adoc/cucumber.report.adoc")
    protected File target;

    @Parameter(property = "yupiik.cucumber.prefix", defaultValue = "= Cucumber Report")
    protected String prefix;

    @Parameter(property = "yupiik.cucumber.suffix")
    protected String suffix;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var out = target.toPath();
        try (final var jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            final var report = generateReport(jsonb);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, "" +
                    ofNullable(prefix).map(p -> p.strip() + "\n\n").orElse("") +
                    report +
                    ofNullable(suffix).map(s -> "\n\n" + s.strip()).orElse(""));
        } catch (final Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private String generateReport(final Jsonb jsonb) throws IOException {
        final var src = source.toPath();
        if (Files.isDirectory(src)) {
            return Files.list(src)
                    .filter(it -> it.getFileName().toString().endsWith(".json"))
                    .map(it -> {
                        try {
                            return generateReport(jsonb, it);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(joining("\n\n"));
        }
        return generateReport(jsonb, src);
    }

    private String generateReport(final Jsonb jsonb, final Path file) throws IOException {
        final var statusColors = Map.of(
                "passed", "#2CB14A",
                "skipped", "#C8C8C8",
                "failed", "#BB0000",
                "undefined", "#FFAD33");
        final var reports = readReport(jsonb, file).stream()
                .sorted(comparing(Report::getName))
                .collect(toList());
        final var totalScenarii = scenariiOf(reports).count();
        final var perStatus = scenariiOf(reports)
                .map(s -> ofNullable(s.getSteps())
                        .filter(it -> !it.isEmpty())
                        .map(it -> it.get(it.size() - 1).getResult())
                        .map(Result::getStatus)
                        .map(it -> it.toLowerCase(Locale.ROOT))
                        .orElse("undefined"))
                .map(it -> it.toLowerCase(Locale.ROOT))
                .collect(groupingBy(identity(), counting()));
        final var passingPercentage = perStatus.getOrDefault("passed", 0L) * 1. / Math.max(1 /*if so passed = 0 so % is ok*/, totalScenarii);
        final var duration = scenariiOf(reports)
                .flatMap(s -> ofNullable(s).map(Scenario::getSteps).stream())
                .flatMap(Collection::stream)
                .map(Step::getResult)
                .filter(Objects::nonNull)
                .mapToLong(Result::getDuration)
                .sum();

        final var lastExecution = scenariiOf(reports)
                .map(Scenario::getStartTimestamp)
                .filter(Objects::nonNull)
                .min(OffsetDateTime::compareTo)
                .orElse(null);

        final var statusLine = "" +
                "[grid=none,frame=none,cols=\"" +
                Stream.of("passed", "skipped", "failed", "undefined")
                        .map(perStatus::get)
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .collect(joining(",")) +
                "\"]\n" +
                "|===\n" +
                Stream.of("passed", "skipped", "failed", "undefined")
                        .map(k -> ofNullable(perStatus.get(k)).map(v -> Map.entry(k, v)).orElse(null))
                        .filter(Objects::nonNull)
                        .map(e -> "|{set:cellbgcolor:" + statusColors.getOrDefault(e.getKey(), "transparent") + "} " + e.getValue() + " " + e.getKey())
                        .collect(joining(" ", "", "\n")) +
                "|===\n" +
                "";

        final var summaryLine = "" +
                "[cols=\"^,^,^\",options=\"header\"]\n" +
                "|====\n" +
                "|{set:cellbgcolor:inherit} *" + new Formatter(Locale.ROOT).format("%2.2f %% passed", passingPercentage) + "*" +
                "|*" + ofNullable(lastExecution).map(OffsetDateTime::toString).orElse("-") + "*" +
                "|*" + toHumanReadableTime(duration) + "*\n" +
                "\n" +
                "|{set:cellbgcolor:inherit}" + totalScenarii + " executed|Execution time|Duration\n" +
                "|====\n" +
                "";

        final var testRan = "" +
                "== Test Runs\n" +
                "\n" +
                "[options=\"header\",cols=\"1,3,1\"]\n" +
                "|===\n" +
                "|Name|Description|Status\n" +
                scenariiOf(reports)
                        .map(s -> {
                            final var status = ofNullable(s.getSteps())
                                    .filter(it -> !it.isEmpty())
                                    .map(it -> it.get(it.size() - 1))
                                    .map(Step::getResult)
                                    .map(Result::getStatus)
                                    .orElse("undefined")
                                    .toLowerCase(Locale.ROOT);
                            return "" +
                                    "|{set:cellbgcolor:inherit} <<" + normalizeId(s.getId()) + "," + s.getName() + ">> " +
                                    "|{set:cellbgcolor:inherit} " + ofNullable(s.getDescription()).orElse("-") + ' ' +
                                    "|{set:cellbgcolor:" + statusColors.get(status) + "} " + status.toUpperCase(Locale.ROOT);
                        })
                        .collect(joining("\n", "", "\n")) +
                "|===\n";

        final var details = "" +
                "== Tests details\n" +
                "\n" +
                scenariiOf(reports)
                        .map(s -> "" +
                                "[#" + normalizeId(s.getId()) + "]\n" +
                                "=== " + s.getName().replace("\n", " ") + "\n" +
                                "\n" +
                                "==== Description\n" +
                                "\n" +
                                ofNullable(s.getDescription()).filter(it -> !it.isBlank()).orElse("No description.").strip() + '\n' +
                                "\n" +
                                "==== Tags\n" +
                                "\n" +
                                ofNullable(s.getTags())
                                        .filter(t -> !t.isEmpty())
                                        .map(t -> t.stream()
                                                .map(it -> ofNullable(it.getName()).orElse("?"))
                                                .map(it -> it.startsWith("@") ? it.substring(1) : it)
                                                .sorted()
                                                .map(it -> "* " + it)
                                                .collect(joining("\n")))
                                        .orElse("No tags.") + '\n' +
                                "\n" +
                                "==== Steps\n" +
                                "\n" +
                                ofNullable(s.getSteps())
                                        .filter(it -> !it.isEmpty())
                                        .map(it -> it.stream()
                                                .map(step -> {
                                                    final var seconds = TimeUnit.MILLISECONDS.toSeconds(ofNullable(step.getResult()).map(Result::getDuration).orElse(0L));
                                                    return "===== " + s.getName().replace("\n", " ") + '\n' +
                                                            "\n" +
                                                            "Status:: " + ofNullable(step.getResult()).map(Result::getStatus).orElse("undefined") + '\n' +
                                                            "Duration:: " + seconds + " second" + (seconds == 0 ? "" : "s") + "\n" +
                                                            "\n" +
                                                            ofNullable(step.getResult())
                                                                    .map(Result::getErrorMessage)
                                                                    .map(e -> "" +
                                                                            "[source]\n" +
                                                                            "----\n" +
                                                                            e.strip() + "\n" +
                                                                            "----\n")
                                                                    .orElse("") +
                                                            "\n";
                                                })
                                                .collect(joining("\n\n", "", "\n")))
                                        .orElse("No step.") + '\n' +
                                "\n" +
                                "")
                        .collect(joining("\n\n", "", "\n"));

        return String.join("\n",
                statusLine,
                summaryLine,
                testRan,
                details);
    }

    private String normalizeId(final String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String toHumanReadableTime(final long duration) {
        if (duration == 0) {
            return "0";
        }
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(duration);
        final var mnMs = TimeUnit.MINUTES.toMinutes(minutes);
        final long seconds = TimeUnit.MILLISECONDS.toMinutes(duration - mnMs);
        final long milliseconds = TimeUnit.MILLISECONDS.toMinutes(duration - mnMs - TimeUnit.SECONDS.toMinutes(seconds));
        return minutes == 0 ?
                seconds + "." + milliseconds + " second" + (seconds != 1 || milliseconds != 0 ? "s" : "") :
                (minutes + ":" + seconds + (milliseconds > 0 ? "." + milliseconds : "") +
                        " minute" + (minutes != 1 || seconds != 0 || milliseconds != 0 ? "s" : ""));
    }

    private Stream<Scenario> scenariiOf(final List<Report> reports) {
        return reports.stream()
                .flatMap(it -> ofNullable(it.getElements()).stream().flatMap(Collection::stream));
    }

    private List<Report> readReport(Jsonb jsonb, Path file) throws IOException {
        try (final var reader = Files.newBufferedReader(file)) {
            return jsonb.fromJson(reader, new JohnzonParameterizedType(List.class, Report.class));
        }
    }

    @Data
    public static class Scenario {
        private int line;
        private String id;
        private String type;
        private String keyword;
        private String name;
        private String description;

        @JsonbProperty("start_timestamp")
        private OffsetDateTime startTimestamp;

        private List<Step> steps;
        private List<Tag> tags;
    }

    @Data
    public static class Result {
        @JsonbProperty("error_message")
        private String errorMessage;
        private long duration;
        private String status; // failed, skipped, passed
    }

    @Data
    public static class Step {
        private Result result;
        private int line;
        private String name;
        private String keyword;
        private Match match;
    }

    @Data
    public static class Match {
        private String location;
    }

    @Data
    public static class Tag {
        private String name;
        private String type;
        private Location location;
    }

    @Data
    public static class Location {
        private int line;
        private int column;
    }

    @Data
    public static class Report {
        private int line;
        private String id;
        private String name;
        private String description;
        private String keyword;
        private String uri;
        private List<Scenario> elements;
        private List<Tag> tags;
    }
}
