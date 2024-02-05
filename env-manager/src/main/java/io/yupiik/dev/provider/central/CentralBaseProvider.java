/*
 * Copyright (c) 2020 - 2023 - Yupiik SAS - https://www.yupiik.com
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
import java.util.stream.Stream;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

public abstract class CentralBaseProvider implements Provider {
    private final YemHttpClient client;
    private final Archives archives;
    private final URI base;
    private final Gav gav;
    private final Path local;
    private final boolean enabled;

    protected CentralBaseProvider(final YemHttpClient client,
                                  final CentralConfiguration conf, // children must use SingletonCentralConfiguration to avoid multiple creations
                                  final Archives archives,
                                  final String gav,
                                  final boolean enabled) {
        this.client = client;
        this.archives = archives;
        this.base = URI.create(conf.base());
        this.local = Path.of(conf.local());
        this.gav = Gav.of(gav);
        this.enabled = enabled;
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
    public Path install(final String tool, final String version, final ProgressListener progressListener) {
        final var archivePath = local.resolve(relativePath(version));
        final var exploded = archivePath.getParent().resolve(archivePath.getFileName() + "_exploded");
        if (Files.exists(exploded)) {
            return exploded;
        }

        if (!enabled) {
            throw new IllegalStateException(gav + " support not enabled (by configuration)");
        }

        try {
            Files.createDirectories(archivePath.getParent());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final var archive = Files.notExists(archivePath) ?
                download(tool, version, archivePath, progressListener) :
                new Archive(gav.type(), archivePath);
        return archives.unpack(archive, exploded);
    }

    @Override
    public Map<Candidate, List<Version>> listLocal() {
        return listTools().stream()
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
                }));
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
    public List<Candidate> listTools() {
        if (!enabled) {
            return List.of();
        }

        final var gavString = Stream.of(gav.groupId(), gav.artifactId(), gav.type(), gav.classifier())
                .filter(Objects::nonNull)
                .collect(joining(":"));
        return List.of(new Candidate(gavString, gav.artifactId(), gavString + " downloaded from central.", base.toASCIIString()));
    }

    @Override
    public Archive download(final String tool, final String version, final Path target, final ProgressListener progressListener) {
        if (!enabled) {
            throw new IllegalStateException(gav + " support not enabled (by configuration)");
        }

        final var res = client.getFile(HttpRequest.newBuilder()
                        .uri(base.resolve(relativePath(version)))
                        .build(),
                target, progressListener);
        ensure200(res);
        return new Archive(gav.type().endsWith(".zip") || gav.type().endsWith(".jar") ? "zip" : "tar.gz", target);
    }

    @Override
    public List<Version> listVersions(final String tool) {
        if (!enabled) {
            return List.of();
        }

        final var res = client.send(HttpRequest.newBuilder()
                .GET()
                .uri(base
                        .resolve(gav.groupId().replace('.', '/') + '/')
                        .resolve(gav.artifactId() + '/')
                        .resolve("maven-metadata.xml"))
                .build());
        ensure200(res);
        return parseVersions(res.body());
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

    private void ensure200(final HttpResponse<?> res) {
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Invalid response: " + res + "\n" + res.body());
        }
    }

    public record Gav(String groupId, String artifactId, String type, String classifier) implements Comparable<Gav> {
        private static Gav of(final String gav) {
            final var segments = gav.split(":");
            return switch (segments.length) {
                case 2 -> new Gav(segments[0], segments[1], "jar", null);
                case 3 -> new Gav(segments[0], segments[1], segments[2], null);
                case 4 -> new Gav(segments[0], segments[1], segments[2], segments[3]);
                default -> throw new IllegalArgumentException("Invalid gav: '" + gav + "'");
            };
        }

        @Override
        public int compareTo(final Gav o) {
            if (this == o) {
                return 0;
            }
            final int g = groupId().compareTo(o.groupId());
            if (g != 0) {
                return g;
            }
            final int a = artifactId().compareTo(o.artifactId());
            if (a != 0) {
                return a;
            }
            final int t = type().compareTo(o.type());
            if (t != 0) {
                return t;
            }
            return (classifier == null ? "" : classifier).compareTo(o.classifier() == null ? "" : o.classifier());
        }
    }
}
