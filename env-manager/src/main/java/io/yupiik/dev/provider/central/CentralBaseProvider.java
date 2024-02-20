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
package io.yupiik.dev.provider.central;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.model.Archive;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.http.Cache;
import io.yupiik.dev.shared.http.YemHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

public class CentralBaseProvider implements Provider {
    private final YemHttpClient client;
    private final Archives archives;
    private final URI base;
    private final Cache cache;
    private final Gav gav;
    private final Path local;
    private final boolean enabled;
    private final Map<String, String> meta;

    private volatile CompletionStage<List<Version>> pendingRequest = null;

    public CentralBaseProvider(final YemHttpClient client,
                               final CentralConfiguration conf, // children must use SingletonCentralConfiguration to avoid multiple creations
                               final Archives archives,
                               final Cache cache,
                               final Gav gav,
                               final boolean enabled,
                               final Map<String, String> meta) {
        this.client = client;
        this.archives = archives;
        this.cache = cache;
        this.base = URI.create(conf.base());
        this.local = Path.of(conf.local());
        this.gav = gav;
        this.enabled = enabled;
        this.meta = meta;
    }

    public Gav gav() {
        return gav;
    }

    @Override
    public String name() {
        return gav.groupId() + ":" + gav.artifactId(); // assume it is sufficient for now, else it can be overriden
    }

    @Override
    public void delete(final String tool, final String version) {
        final var archivePath = local.resolve(relativePath(version));
        if (Files.notExists(archivePath)) {
            return;
        }
        final var exploded = archivePath.getParent().resolve(archivePath.getFileName() + "_exploded");
        if (Files.notExists(exploded)) {
            return;
        }

        if (!enabled) {
            throw new IllegalStateException(gav + " support not enabled (by configuration)");
        }

        try {
            Files.delete(archivePath);
            archives.delete(exploded);
        } catch (final IOException e) {
            throw new IllegalStateException("Can't delete " + tool + "@" + version, e);
        }
    }

    @Override
    public CompletionStage<Path> install(final String tool, final String version, final ProgressListener progressListener) {
        final var archivePath = local.resolve(relativePath(version));
        final var exploded = archivePath.getParent().resolve(archivePath.getFileName() + "_exploded");
        if (Files.exists(exploded)) {
            return completedFuture(exploded);
        }

        if (!enabled) {
            throw new IllegalStateException(gav + " support not enabled (by configuration)");
        }

        try {
            Files.createDirectories(archivePath.getParent());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return (Files.notExists(archivePath) ? download(tool, version, archivePath, progressListener) : completedFuture(new Archive(gav.type(), archivePath)))
                .thenApply(archive -> archives.unpack(archive, exploded));
    }

    @Override
    public CompletionStage<Map<Candidate, List<Version>>> listLocal() {
        return listTools().thenApply(candidates -> candidates.stream()
                .collect(toMap(identity(), it -> {
                    final var artifactDir = local.resolve(relativePath("ignored")).getParent().getParent();
                    if (Files.notExists(artifactDir)) {
                        return List.of();
                    }
                    try (final var versions = Files.list(artifactDir)) {
                        return versions
                                .filter(Files::isDirectory)
                                .flatMap(f -> {
                                    try (final var exploded = Files.list(f)) {
                                        return exploded
                                                .filter(Files::isDirectory)
                                                .filter(child -> child.getFileName().toString().endsWith("_exploded"))
                                                .map(distro -> {
                                                    final var filename = distro.getFileName().toString();
                                                    final var version = filename.substring(0, filename.length() - "_exploded".length());
                                                    return new Version(gav.groupId(), version, gav.artifactId(), version);
                                                })
                                                .toList() // materialize otherwise exploded will be closed and lazy evaluation will fail
                                                .stream();
                                    } catch (final IOException e) {
                                        return Stream.of();
                                    }
                                })
                                .toList();
                    } catch (final IOException e) {
                        return List.of();
                    }
                })));
    }

    @Override
    public Optional<Path> resolve(final String tool, final String version) {
        final var location = local.resolve(relativePath(version));
        if (Files.notExists(location)) {
            return Optional.empty();
        }

        final var exploded = location.getParent().resolve(location.getFileName() + "_exploded");
        if (Files.notExists(exploded)) {
            return Optional.empty();
        }

        final var maybeMac = exploded.resolve("Contents/Home");
        if (Files.isDirectory(maybeMac)) {
            return Optional.of(maybeMac);
        }

        return Optional.of(exploded);
    }

    @Override
    public CompletionStage<List<Candidate>> listTools() {
        if (!enabled) {
            return completedFuture(List.of());
        }

        final var gavString = Stream.of(gav.groupId(), gav.artifactId(), gav.type(), gav.classifier())
                .filter(Objects::nonNull)
                .collect(joining(":"));

        return completedFuture(List.of(new Candidate(
                gav.artifactId().startsWith("apache-") ? gav.artifactId().substring("apache-".length()) : gavString,
                toName(gav.artifactId()), gavString + " downloaded from central.", base.toASCIIString(), meta)));
    }

    @Override
    public CompletionStage<Archive> download(final String tool, final String version, final Path target, final ProgressListener progressListener) {
        if (!enabled) {
            throw new IllegalStateException(gav + " support not enabled (by configuration)");
        }

        return client.getFile(
                        HttpRequest.newBuilder()
                                .uri(base.resolve(relativePath(version)))
                                .build(),
                        target, progressListener)
                .thenApply(res -> {
                    ensure200(res);
                    return new Archive(gav.type().endsWith(".zip") || gav.type().endsWith(".jar") ? "zip" : "tar.gz", target);
                });
    }

    @Override
    public CompletionStage<List<Version>> listVersions(final String tool) {
        if (!enabled) {
            return completedFuture(List.of());
        }

        final var entry = cache.lookup(base.toASCIIString());
        if (entry != null && entry.hit() != null) {
            return completedFuture(parseVersions(entry.hit().payload()));
        }

        return pendingRequest == null ? pendingRequest = doListVersions(entry) : pendingRequest;
    }

    private CompletionStage<List<Version>> doListVersions(final Cache.CachedEntry entry) {
        return client.sendAsync(HttpRequest.newBuilder()
                        .GET()
                        .uri(base
                                .resolve(gav.groupId().replace('.', '/') + '/')
                                .resolve(gav.artifactId() + '/')
                                .resolve("maven-metadata.xml"))
                        .build())
                .thenApply(this::ensure200)
                .thenApply(res -> {
                    final var filtered = parseVersions(res.body());
                    if (entry != null) {
                        cache.save(entry.key(), Map.of(), filtered.stream()
                                .map(it -> "<version>" + it.version() + "</version>")
                                .collect(joining("\n", " " /* while (idx>0) */, "\n")));
                    }
                    return parseVersions(res.body());
                })
                .whenComplete((ok, ko) -> pendingRequest = null);
    }

    private String toName(final String artifactId) {
        final var out = new StringBuilder();
        boolean up = true;
        for (int i = 0; i < artifactId.length(); i++) {
            final var c = artifactId.charAt(i);
            if (up) {
                out.append(Character.toUpperCase(c));
                up = false;
            } else if (c == '-' || c == '_') {
                out.append(' ');
                up = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String relativePath(final String version) {
        return gav.groupId().replace('.', '/') + '/' +
                gav.artifactId() + '/' +
                version + '/' +
                gav.artifactId() + '-' + version + (gav.classifier() != null ? '-' + gav.classifier() : "") + "." + gav.type();
    }

    private List<Version> parseVersions(final String body) {
        final var out = new ArrayList<Version>(2);
        int from = body.indexOf("<version>");
        while (from > 0) {
            from += "<version>".length();
            final int end = body.indexOf("</version>", from);
            if (end < 0) {
                break;
            }

            final var version = body.substring(from, end).strip();
            out.add(new Version(gav.groupId(), version, gav.artifactId(), version));
            from = body.indexOf("<version>", end + "</version>".length());
        }
        return out;
    }

    private <A> HttpResponse<A> ensure200(final HttpResponse<A> res) {
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Invalid response: " + res + "\n" + res.body());
        }
        return res;
    }
}
