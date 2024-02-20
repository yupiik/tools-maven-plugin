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
package io.yupiik.tools.injector.versioning;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
@RequiredArgsConstructor
public class VersioningInjector implements Runnable {
    private static final String SELECT_PLACEHOLDER = "<!-- generated_versions_select -->";

    private final VersioningInjectorConfiguration configuration;

    @Override
    public void run() {
        validateConfiguration();
        try {
            if (configuration.getTarget() != null && !Files.exists(configuration.getTarget())) {
                Files.createDirectories(configuration.getTarget());
            }

            final var matcher = createMatcher();
            final var listVersions = findVersions();
            final var replacer = createReplacer(listVersions);
            Files.walkFileTree(configuration.getSource(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    if (!matcher.test(file)) {
                        log.finest(() -> "Skipping '" + file + "'");
                        return super.visitFile(file, attrs);
                    }

                    final var out = configuration.isInplace() ?
                            file :
                            configuration.getTarget().resolve(configuration.getSource().relativize(file));
                    log.info(() -> "Rewriting '" + file + "' to '" + out + "'");
                    final var content = replacer.apply(Files.readString(file, configuration.getCharset()));
                    Files.writeString(out, content, configuration.getCharset());
                    return super.visitFile(file, attrs);
                }
            });
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    private int findNextVersion(final String content) {
        final var start = content.indexOf("data-versioninjector-generation-iteration=\"");
        if (start < 0) {
            return 1;
        }
        final var end = content.indexOf('"', start + "data-versioninjector-generation-iteration=\"".length() + 1);
        if (end < 0) {
            throw new IllegalArgumentException("Invalid data-versioninjector-generation-iteration value in\n" + content);
        }
        return Integer.parseInt(content.substring(start + "data-versioninjector-generation-iteration=\"".length(), end).strip()) + 1;
    }

    private List<String> findVersions() throws IOException {
        try (final var list = Files.list(configuration.getVersionFolderParent())) {
            final var ignored = createMatcher(configuration.getIgnoredVersionFolders(), false);
            final var matcher = configuration.getVersionFoldersPattern() == null || configuration.getVersionFoldersPattern().isBlank() ?
                    (Predicate<String>) p -> true :
                    Pattern.compile(configuration.getVersionFoldersPattern()).asMatchPredicate();
            final Predicate<Path> filter = p -> !ignored.test(p) && matcher.test(p.getFileName().toString());
            return list
                    .filter(filter)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .sorted(this::compareVersions)
                    .collect(toList());
        }
    }

    // todo: make it configurable somehow
    private int compareVersions(final String v1, final String v2) {
        if (v1.equalsIgnoreCase(v2)) {
            return 0;
        }

        final var from1 = sanitizeVersion(v1).split("\\.");
        final var from2 = sanitizeVersion(v2).split("\\.");
        for (int i = 0; i < Math.min(from1.length, from2.length); i++) {
            try {
                final int i1 = Integer.parseInt(from1[i]);
                final int i2 = Integer.parseInt(from2[i]);
                final int diff = i1 - i2;
                if (diff != 0) {
                    return diff;
                }
            } catch (final NumberFormatException nfe) {
                return v1.compareTo(v2);
            }
        }
        if (from1.length < from2.length) {
            return -1;
        }
        if (from2.length < from1.length) {
            return 1;
        }

        return v1.compareTo(v2);
    }

    private String sanitizeVersion(final String v) {
        final var builder = new StringBuilder(v.length());
        for (final var c : v.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private Function<String, String> createReplacer(final List<String> versions) {
        final var replacedString = configuration.getReplacedString();
        final var originalReplacingContent = configuration.getReplacingContent();

        final Function<Integer, String> replacingContent;
        final Function<Integer, String> selectInjector;
        if (originalReplacingContent.contains(SELECT_PLACEHOLDER)) {
            final int start = originalReplacingContent.indexOf(SELECT_PLACEHOLDER);
            final int end = start + SELECT_PLACEHOLDER.length();
            final var options = versions.stream()
                    .map(it -> "<option>" + it + "</option>")
                    .collect(joining());
            final var props = selectProps();
            final var prefix = originalReplacingContent.substring(0, start);
            final var suffix = originalReplacingContent.substring(end + "/>".length());
            selectInjector = v -> wrapWithComments("" +
                            "<select" + props + " data-versioninjector-generation-iteration=\"" + v + "\">" +
                            options +
                            "</select>",
                    SELECT_PLACEHOLDER);
            replacingContent = v -> prefix +
                    selectInjector.apply(v) +
                    suffix;
        } else {
            selectInjector = v -> "";
            replacingContent = v -> originalReplacingContent;
        }

        if (replacedString.startsWith("regex:")) {
            final var pattern = Pattern.compile(replacedString.substring("regex:".length()));
            return s -> doReplace(replacingContent, selectInjector, pattern, s);
        }
        if (replacedString.startsWith("r:")) { // shortcut
            final var pattern = Pattern.compile(replacedString.substring("r:".length()));
            return s -> doReplace(replacingContent, selectInjector, pattern, s);
        }
        return s -> {
            final var sanitized = sanitize(s);
            if (sanitized.sanitized) {
                return sanitized.value.replace(SELECT_PLACEHOLDER, selectInjector.apply(findNextVersion(s)));
            }
            return s.replace(replacedString, replacingContent.apply(findNextVersion(s)));
        };
    }

    private String doReplace(final Function<Integer, String> replacingContent,
                             final Function<Integer, String> dataInjector,
                             final Pattern pattern,
                             final String content) {
        final var sanitized = sanitize(content);
        if (!sanitized.sanitized) {
            final var version = findNextVersion(content);
            final var replacement = replacingContent.apply(version);
            return pattern.matcher(content).replaceAll(replacement);
        }
        return sanitized.value.replace(SELECT_PLACEHOLDER, dataInjector.apply(findNextVersion(content)));
    }

    private String wrapWithComments(final String content, final String placeholder) {
        final String id = placeholder.substring("<!--".length(), placeholder.length() - "-->".length()).strip();
        return "" +
                "<!-- START " + id + " -->" +
                content +
                "<!-- END " + id + " -->";
    }

    private SanitizationResult sanitize(final String content) {
        final int start = content.indexOf("<!-- START generated_versions_select -->");
        if (start < 0) {
            return new SanitizationResult(false, content);
        }

        final int end = content.indexOf("<!-- END generated_versions_select -->", start);
        if (end < 0) {
            return new SanitizationResult(false, content);
        }

        // for now we can only inject it once, todo: add an id to enable 2 passes/injections
        final var stripped = content.substring(0, start) +
                SELECT_PLACEHOLDER +
                content.substring(end + "<!-- END generated_versions_select -->".length());
        return new SanitizationResult(true, stripped);
    }

    private String selectProps() {
        if (configuration.getSelectProperties().isEmpty()) {
            return "";
        }
        return configuration.getSelectProperties().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(it -> it.getKey() + "=\"" + it.getValue() + "\"")
                .collect(joining(" ", " ", ""));
    }

    private Predicate<Path> createMatcher() {
        final var include = createMatcher(configuration.getIncludes(), true);
        final var exclude = createMatcher(configuration.getExcludes(), false);
        return p -> include.test(p) && !exclude.test(p);
    }

    private Predicate<Path> createMatcher(final List<String> list, final boolean defaultIfEmpty) {
        if (list == null || list.isEmpty()) {
            return p -> defaultIfEmpty;
        }
        return list.stream()
                .map(it -> {
                    if (it.startsWith("regex:")) {
                        return Pattern.compile(it.substring("regex:".length())).asMatchPredicate();
                    }
                    if (it.startsWith("r:")) { // shortcut
                        return Pattern.compile(it.substring("r:".length())).asMatchPredicate();
                    }
                    if (it.startsWith("i:")) {
                        return (Predicate<String>) it::equalsIgnoreCase;
                    }
                    return (Predicate<String>) it::equals;
                })
                .map(p -> (Predicate<Path>) it -> p.test(it.getFileName().toString()))
                .reduce(p -> false, Predicate::or);
    }

    private void validateConfiguration() {
        if (configuration.getSource() == null || !Files.exists(configuration.getSource())) {
            throw new IllegalArgumentException("Invalid source: '" + configuration.getSource() + "'");
        }
        if (configuration.getTarget() == null && !configuration.isInplace()) {
            throw new IllegalArgumentException("Invalid target: '" + configuration.getTarget() + "'");
        }
        if (configuration.getReplacedString() == null) {
            throw new IllegalArgumentException("Invalid replaced string: '" + configuration.getReplacedString() + "'");
        }
        if (configuration.getReplacingContent() == null) {
            throw new IllegalArgumentException("Invalid replacing content: '" + configuration.getReplacingContent() + "'");
        }
        if (configuration.getVersionFolderParent() == null) {
            throw new IllegalArgumentException("Invalid version folder: '" + configuration.getVersionFolderParent() + "'");
        }
    }

    @RequiredArgsConstructor
    private static class SanitizationResult {
        private final boolean sanitized;
        private final String value;
    }
}
