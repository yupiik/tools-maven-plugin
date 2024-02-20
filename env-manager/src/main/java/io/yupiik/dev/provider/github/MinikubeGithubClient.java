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
package io.yupiik.dev.provider.github;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.model.Archive;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.Os;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

// todo: complete to make it functional with --driver=none?
public class MinikubeGithubClient implements Provider {
    private final YemHttpClient client;
    private final JsonMapper jsonMapper;
    private final String assetName;
    private final URI base;
    private final Path local;
    private final boolean enabled;
    private final Archives archives;

    public MinikubeGithubClient(final SingletonGithubConfiguration githubConfiguration, final MinikubeConfiguration conf,
                                final YemHttpClient client, final JsonMapper jsonMapper, final Os os, final Archives archives) {
        this.client = client;
        this.enabled = conf.enabled();
        this.jsonMapper = jsonMapper;
        this.archives = archives;
        this.base = URI.create(githubConfiguration.configuration().base());
        this.local = Path.of(githubConfiguration.configuration().local());
        this.assetName = "minikube-" + switch (os.findOs()) {
            case "windows" -> "windows";
            case "macos" -> "darwin";
            default -> "linux";
        } + '-' + (os.isArm() ? "arm64" : "amd64") + ".tar.gz";

        if (githubConfiguration.configuration().header() != null && !githubConfiguration.configuration().header().isBlank()) {
            client.registerAuthentication(base.getHost(), base.getPort(), githubConfiguration.configuration().header());
        }
    }

    @Override
    public String name() {
        return "minikube-github";
    }

    @Override
    public CompletionStage<List<Candidate>> listTools() {
        if (!enabled) {
            return completedFuture(List.of());
        }
        return completedFuture(List.of(new Candidate(
                "minikube", "Minikube", "Local development Kubernetes binary.", "https://minikube.sigs.k8s.io/docs/",
                Map.of("emoji", "☸️"))));
    }

    @Override
    public CompletionStage<List<Version>> listVersions(final String tool) {
        if (!enabled) {
            return completedFuture(List.of());
        }

        return findReleases()
                .thenApply(releases -> releases.stream()
                        .filter(r -> r.name().startsWith("v") && r.assets() != null && r.assets().stream()
                                .anyMatch(a -> Objects.equals(a.name(), assetName)))
                        .map(r -> {
                            var version = r.name();
                            if (version.startsWith("v")) {
                                version = version.substring(1);
                            }
                            return new Version("Kubernetes", version, "minikube", version);
                        })
                        .toList());
    }

    @Override
    public CompletionStage<Archive> download(final String tool, final String version, final Path target, final ProgressListener progressListener) {
        if (!enabled) {
            throw new IllegalStateException("Minikube support not enabled (by configuration)");
        }

        // todo: we can simplify that normally
        return findReleases()
                .thenCompose(versions -> {
                    final var assets = versions.stream()
                            .filter(it -> Objects.equals(version, it.name()) ||
                                    (it.name().startsWith("v") && Objects.equals(version, it.name().substring(1))))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("No version '" + version + "' matched, availables:" + versions.stream()
                                    .map(Release::name)
                                    .map(v -> "- " + v)
                                    .collect(joining("\n", "", "\n"))))
                            .assets();
                    final var uri = URI.create(assets.stream()
                            .filter(a -> Objects.equals(a.name(), assetName))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("No matching asset for this version:" + assets.stream()
                                    .map(Release.Asset::name)
                                    .map(a -> "- " + a)
                                    .collect(joining("\n", "\n", "\n"))))
                            .browserDownloadUrl());
                    return client.getFile(HttpRequest.newBuilder().uri(uri).build(), target, progressListener)
                            .thenApply(res -> {
                                if (res.statusCode() != 200) {
                                    throw new IllegalArgumentException("Can't download " + uri + ": " + res + "\n" + res.body());
                                }
                                return new Archive("tar.gz", target);
                            });
                });
    }

    @Override
    public CompletionStage<Path> install(final String tool, final String version, final ProgressListener progressListener) {
        final var archivePath = local.resolve("minikube").resolve(version).resolve(assetName);
        final var exploded = archivePath.getParent().resolve("distribution_exploded");
        if (Files.exists(exploded)) {
            return completedFuture(exploded);
        }

        if (!enabled) {
            throw new IllegalStateException("Minikube support not enabled (by configuration)");
        }

        try {
            Files.createDirectories(archivePath.getParent());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return (Files.notExists(archivePath) ? download(tool, version, archivePath, progressListener) : completedFuture(new Archive("tar.gz", archivePath)))
                .thenApply(archive -> {
                    final var unpacked = archives.unpack(archive, exploded);
                    try (final var list = Files.list(unpacked)) {
                        final var bin = assetName.substring(0, assetName.length() - ".tar.gz".length());
                        list
                                .filter(Files::isRegularFile)
                                .filter(it -> Objects.equals(it.getFileName().toString(), bin))
                                .forEach(f -> {
                                    try {
                                        Files.setPosixFilePermissions(
                                                Files.move(f, f.getParent().resolve("minikube")), // rename to minikube
                                                Stream.of(
                                                                OWNER_READ, OWNER_EXECUTE, OWNER_WRITE,
                                                                GROUP_READ, GROUP_EXECUTE,
                                                                OTHERS_READ, OTHERS_EXECUTE)
                                                        .collect(toSet()));
                                    } catch (final UnsupportedOperationException | IOException e) {
                                        // no-op
                                    }
                                });
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return unpacked;
                });
    }

    @Override
    public void delete(final String tool, final String version) {
        if (!enabled) {
            throw new IllegalStateException("Minikube support not enabled (by configuration)");
        }

        final var base = local.resolve("minikube").resolve(version);
        if (Files.exists(base)) {
            archives.delete(base);
        }
    }

    @Override
    public CompletionStage<Map<Candidate, List<Version>>> listLocal() {
        final var root = local.resolve("minikube");
        if (Files.notExists(root)) {
            return completedFuture(Map.of());
        }
        return listTools().thenApply(candidates -> candidates.stream()
                .collect(toMap(identity(), t -> {
                    try (final var children = Files.list(root)) {
                        return children
                                .filter(r -> Files.exists(r.resolve("distribution_exploded")))
                                .map(v -> {
                                    final var version = v.getFileName().toString();
                                    return new Version("Kubernetes", version, "minikube", version);
                                })
                                .toList();
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })));
    }

    @Override
    public Optional<Path> resolve(final String tool, final String version) {
        final var distribution = local.resolve("minikube").resolve(version).resolve("distribution_exploded");
        if (Files.notExists(distribution)) {
            return Optional.empty();
        }
        return Optional.of(distribution);
    }

    private CompletionStage<List<Release>> findReleases() {
        return client.sendAsync(
                        HttpRequest.newBuilder()
                                .GET()
                                .header("accept", "application/vnd.github+json")
                                .header("X-GitHub-Api-Version", "2022-11-28")
                                .uri(base.resolve("/repos/kubernetes/minikube/releases"))
                                .build())
                .thenApply(res -> {
                    if (res.statusCode() != 200) {
                        throw new IllegalStateException("Invalid response: " + res + "\n" + res.body());
                    }
                    return jsonMapper.fromString(new Types.ParameterizedTypeImpl(List.class, Release.class), res.body());
                });
    }
}
