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
package io.yupiik.dev.provider.sdkman;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.model.Archive;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.Os;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toMap;

@DefaultScoped
public class SdkManClient implements Provider {
    private final String platform;
    private final Archives archives;
    private final YemHttpClient client;
    private final URI base;
    private final Path local;
    private final boolean enabled;
    private final Pattern oldVersionsSplitter = Pattern.compile(" +");
    private final Map<String, CompletionStage<HttpResponse<String>>> pendingRequests = new ConcurrentHashMap<>();

    public SdkManClient(final YemHttpClient client, final SdkManConfiguration configuration, final Os os, final Archives archives) {
        this.client = client;
        this.archives = archives;
        this.base = URI.create(configuration.base());
        this.local = Path.of(configuration.local());
        this.enabled = configuration.enabled();
        this.platform = ofNullable(configuration.platform())
                .filter(i -> !"auto".equalsIgnoreCase(i))
                .orElseGet(() -> switch (os.findOs()) {
                    case "windows" -> "windowsx64";
                    case "linux" -> os.is32Bits() ?
                            (os.isArm() ? "linuxarm32hf" : "linuxx32") :
                            (os.isAarch64() ? "linuxarm64" : "linuxx64");
                    case "mac" -> os.isArm() ? "darwinarm64" : "darwinx64";
                    case "solaris" -> "linuxx64";
                    default -> "exotic";
                });
    }

    @Override
    public String name() {
        return "sdkman";
    }

    @Override
    public void delete(final String tool, final String version) {
        if (!enabled) {
            throw new IllegalStateException("SDKMan support not enabled (by configuration)");
        }

        final var target = local.resolve(tool).resolve(version);
        if (Files.exists(target)) {
            archives.delete(target);
        }
        // todo: check current symbolic link?
    }

    @Override
    public CompletionStage<Path> install(final String tool, final String version, final ProgressListener progressListener) {
        final var target = local.resolve(tool).resolve(version);
        if (Files.exists(target)) {
            final var maybeMac = target.resolve("Contents/Home"); // todo: check it is the case
            if (Files.isDirectory(maybeMac)) {
                return completedFuture(maybeMac);
            }
            return completedFuture(target);
        }

        if (!enabled) {
            throw new IllegalStateException("SDKMan support not enabled (by configuration)");
        }

        try {
            final var toDelete = Files.createDirectories(local.resolve(tool).resolve(version + ".yem.tmp"));
            Files.createDirectories(local.resolve(tool).resolve(version));
            return download(tool, version, toDelete.resolve("distro.archive"), progressListener)
                    .thenApply(archive -> archives.unpack(archive, target))
                    .exceptionally(e -> {
                        archives.delete(local.resolve(tool).resolve(version));
                        throw new IllegalStateException(e);
                    })
                    .whenComplete((ok, ko) -> archives.delete(toDelete));
        } catch (final IOException e) {
            archives.delete(local.resolve(tool).resolve(version));
            throw new IllegalStateException(e);
        }
    }

    @Override
    public CompletionStage<Map<Candidate, List<Version>>> listLocal() {
        if (Files.notExists(local)) {
            return completedFuture(Map.of());
        }
        try (final var tool = Files.list(local)) {
            // todo: if we cache in listTools we could reuse the meta there
            return completedFuture(tool.collect(toMap(
                    it -> {
                        final var name = it.getFileName().toString();
                        return new Candidate(name, name, "", "", toMetadata(name));
                    },
                    it -> {
                        if (Files.notExists(it)) {
                            return List.of();
                        }
                        try (final var versions = Files.list(it)) {
                            return versions
                                    .filter(v -> !Files.isSymbolicLink(v) && !"current".equals(v.getFileName().toString()))
                                    .map(v -> { // todo: same
                                        final var version = v.getFileName().toString();
                                        return new Version("sdkman", version, "", version);
                                    })
                                    .toList();
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<Path> resolve(final String tool, final String version) { // don't disable since it is 100% local
        final var location = local.resolve(tool).resolve(version);
        if (Files.notExists(location)) {
            return Optional.empty();
        }
        return of(location);
    }

    @Override // warn: zip for windows often and tar.gz for linux
    public CompletionStage<Archive> download(final String tool, final String version, final Path target, final ProgressListener progressListener) { // todo: checksum (x-sdkman header) etc
        if (!enabled) {
            throw new IllegalStateException("SDKMan support not enabled (by configuration)");
        }
        return client.getFile(
                        HttpRequest.newBuilder()
                                .uri(base.resolve("broker/download/" + tool + "/" + version + "/" + platform))
                                .build(),
                        target, progressListener)
                .thenApply(res -> {
                    ensure200(res);
                    return new Archive(
                            StreamSupport.stream(Spliterators.spliteratorUnknownSize(new Iterator<HttpResponse<?>>() {
                                        private HttpResponse<?> current = res;

                                        @Override
                                        public boolean hasNext() {
                                            return current != null;
                                        }

                                        @Override
                                        public HttpResponse<?> next() {
                                            try {
                                                return current;
                                            } finally {
                                                current = current.previousResponse().orElse(null);
                                            }
                                        }
                                    }, Spliterator.IMMUTABLE), false)
                                    .filter(Objects::nonNull)
                                    .map(r -> r.headers().firstValue("x-sdkman-archivetype").orElse(null))
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse("tar.gz"),
                            target);
                });
    }

    @Override
    public CompletionStage<List<Candidate>> listTools() { // todo: cache in sdkman folder a sdkman.yem.properties? refresh once per day?
        if (!enabled) {
            return completedFuture(List.of());
        }

        // note: we could use ~/.sdkman/var/candidates too but
        //       this would assume sdkman keeps updating this list which is likely not true

        return doListTools().thenApply(res -> {
            ensure200(res);
            return parseList(res.body());
        });
    }

    @Override
    public CompletionStage<List<Version>> listVersions(final String tool) {
        if (!enabled) {
            return completedFuture(List.of());
        }
        return doListVersions(tool).thenApply(res -> {
            ensure200(res);
            return parseVersions(tool, res.body());
        });
    }

    private CompletionStage<HttpResponse<String>> doListTools() {
        return pendingRequests.computeIfAbsent("list-tools", k -> client.sendAsync(
                        HttpRequest.newBuilder()
                                .uri(base.resolve("candidates/list"))
                                .build()))
                .whenComplete((ok, ko) -> pendingRequests.remove("list-tools"));
    }

    private CompletionStage<HttpResponse<String>> doListVersions(final String tool) {
        return pendingRequests.computeIfAbsent("list-versions-" + tool, k -> client.sendAsync(
                        HttpRequest.newBuilder()
                                .uri(base.resolve("candidates/" + tool + "/" + platform + "/versions/list?current=&installed="))
                                .build()))
                .whenComplete((ok, ko) -> pendingRequests.remove("list-versions-" + tool));
    }

    private List<Version> parseVersions(final String tool, final String body) {
        final var markerStart = "--------------------------------------------------------------------------------";
        final var markerEnd = "================================================================================";

        final var allLines = lines(body);
        final var lines = allLines.subList(allLines.indexOf(markerStart) + 1, allLines.size());
        final var versions = new ArrayList<Version>(16);

        {
            String lastVendor = null;
            for (final var next : lines) {
                if (Objects.equals(markerEnd, next)) {
                    break;
                }

                final var segments = next.strip().split("\\|");
                if (segments.length == 6) {
                    // Vendor        | Use | Version      | Dist    | Status     | Identifier
                    if (!segments[0].isBlank()) {
                        lastVendor = segments[0].strip();
                    }
                    versions.add(new Version(lastVendor, segments[2].strip(), segments[3].strip(), segments[5].strip()));
                }
            }
        }

        if (versions.isEmpty()) { // try old style
            var data = lines;
            for (int i = 0; i < 2; i++) {
                final int marker = data.indexOf(markerEnd);
                if (marker < 0) {
                    break;
                }
                data = data.subList(marker + 1, data.size());
            }

            for (final var next : data) {
                if (next.isBlank() || next.charAt(0) != ' ') {
                    continue;
                }
                if (Objects.equals(markerEnd, next)) {
                    break;
                }

                final var segments = oldVersionsSplitter.split(next.strip());
                if (segments.length > 0) {
                    versions.addAll(Stream.of(segments)
                            .filter(Predicate.not(String::isBlank))
                            .map(v -> new Version(tool, v, "sdkman", v))
                            .toList());
                }
            }
        }

        return versions;
    }

    private List<Candidate> parseList(final String body) {
        final var allLines = lines(body);

        final var marker = "--------------------------------------------------------------------------------";

        final var from = allLines.iterator();
        final var candidates = new ArrayList<Candidate>(16);
        while (from.hasNext()) {
            if (!Objects.equals(marker, from.next()) || !from.hasNext()) {
                continue;
            }

            // first line: Java (21.0.2-tem)        https://projects.eclipse.org/projects/adoptium.temurin/
            final var line1 = from.next();
            final int sep1 = line1.lastIndexOf(" (");
            final int sep2 = line1.indexOf(')', sep1);
            if (sep1 < 0 || sep2 < 0) {
                throw new IllegalArgumentException("Invalid first line: '" + line1 + "'");
            }
            final int link = line1.indexOf("h", sep2);

            String tool = null;
            final var description = new StringBuilder();
            while (from.hasNext()) {
                final var next = from.next();
                if (next.strip().startsWith("$ sdk install ")) {
                    tool = next.substring(next.lastIndexOf(' ') + 1).strip();
                    break;
                }
                if (next.isBlank()) {
                    continue;
                }
                if (!description.isEmpty()) {
                    description.append(' ');
                }
                description.append(next.strip());
            }
            candidates.add(new Candidate(
                    tool, line1.substring(0, sep1), // version=line1.substring(sep1 + 2, sep2),
                    description.toString(), link > 0 ? line1.substring(link) : "",
                    toMetadata(tool)));
        }
        return candidates;
    }

    private Map<String, String> toMetadata(final String tool) {
        if (tool == null) {
            return Map.of();
        }
        return switch (tool) {
            case "java" -> Map.of("emoji", "☕");
            case "maven" -> Map.of("emoji", "\uD83E\uDD89");
            default -> Map.of();
        };
    }

    private List<String> lines(final String body) {
        final List<String> allLines;
        try (final var reader = new BufferedReader(new StringReader(body))) {
            allLines = reader.lines().toList();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return allLines;
    }

    private void ensure200(final HttpResponse<?> res) {
        if (res.statusCode() != 200) {
            throw new IllegalArgumentException("Invalid response: " + res + "\n" + res.body());
        }
    }
}
