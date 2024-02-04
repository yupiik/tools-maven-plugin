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
package io.yupiik.dev.provider.zulu;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@DefaultScoped
public class ZuluCdnClient implements Provider {
    private final String suffix;
    private final Archives archives;
    private final YemHttpClient client;
    private final URI base;
    private final Path local;
    private final boolean enabled;
    private final boolean preferJre;

    public ZuluCdnClient(final YemHttpClient client, final ZuluCdnConfiguration configuration, final Os os, final Archives archives) {
        this.client = client;
        this.archives = archives;
        this.base = URI.create(configuration.base());
        this.local = Path.of(configuration.local());
        this.enabled = configuration.enabled();
        this.preferJre = configuration.preferJre();
        this.suffix = ofNullable(configuration.platform())
                .filter(i -> !"auto".equalsIgnoreCase(i))
                .orElseGet(() -> switch (os.findOs()) { // not all cases are managed compared to sdkman
                    case "windows" -> "win_x64.zip";
                    case "linux" -> os.isAarch64() ? "linux_aarch64.tar.gz" : "linux_x64.tar.gz";
                    case "mac" -> os.isArm() ? "macosx_aarch64.tar.gz" : "macosx_x64.tar.gz";
                    default -> "linux_x64.tar.gz";
                });
    }

    @Override
    public String name() {
        return "zulu";
    }

    @Override
    public void delete(final String tool, final String version) {
        final var archivePath = local.resolve(version).resolve(version + '-' + suffix);
        if (Files.notExists(archivePath)) {
            return;
        }
        final var exploded = archivePath.getParent().resolve("distribution_exploded");
        if (Files.notExists(exploded)) {
            return;
        }

        if (!enabled) {
            throw new IllegalStateException("Zulu support not enabled (by configuration)");
        }
        archives.delete(exploded.getParent());
    }

    @Override
    public Path install(final String tool, final String version, final ProgressListener progressListener) {
        final var archivePath = local.resolve(version).resolve(version + '-' + suffix);
        final var exploded = archivePath.getParent().resolve("distribution_exploded");
        if (Files.exists(exploded)) {
            return exploded;
        }

        if (!enabled) {
            throw new IllegalStateException("Zulu support not enabled (by configuration)");
        }

        try {
            Files.createDirectories(archivePath.getParent());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final var archive = Files.notExists(archivePath) ?
                download(tool, version, archivePath, progressListener) :
                new Archive(suffix.endsWith(".zip") ? "zip" : "tar.gz", archivePath);
        return archives.unpack(archive, exploded);
    }

    @Override
    public Map<Candidate, List<Version>> listLocal() {
        if (Files.notExists(local)) {
            return Map.of();
        }
        return listTools().stream().collect(toMap(identity(), tool -> {
            try (final var versions = Files.list(local)) {
                return versions
                        .map(v -> {
                            final var version = v.getFileName().toString();
                            final int start = preferJre ? version.indexOf("-jre") : version.indexOf("-jdk");
                            if (start > 0) {
                                return new Version("Azul", version.substring(start + 4), "zulu", version);
                            }
                            return new Version("Azul", version, "zulu", version);
                        })
                        .toList();
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    @Override
    public Optional<Path> resolve(final String tool, final String version) {
        final var location = local.resolve(version).resolve(version + '-' + suffix);
        if (Files.notExists(location)) {
            return Optional.empty();
        }

        final var exploded = location.getParent().resolve("distribution_exploded");
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
    public Archive download(final String tool, final String id, final Path target, final ProgressListener progressListener) {
        if (!enabled) {
            throw new IllegalStateException("Zulu support not enabled (by configuration)");
        }

        final var res = client.getFile(HttpRequest.newBuilder()
                        .uri(base.resolve("zulu" + id + '-' + suffix))
                        .build(),
                target, progressListener);
        ensure200(res);
        return new Archive(suffix.endsWith(".zip") ? "zip" : "tar.gz", target);
    }

    @Override
    public List<Candidate> listTools() {
        if (!enabled) {
            return List.of();
        }
        return List.of(new Candidate("java", "java", "Java JRE or JDK downloaded from Azul CDN.", base.toASCIIString()));
    }

    @Override
    public List<Version> listVersions(final String tool) {
        if (!enabled) {
            return List.of();
        }
        final var res = client.send(HttpRequest.newBuilder().GET().uri(base).build());
        ensure200(res);
        return parseVersions(res.body());
    }

    private void ensure200(final HttpResponse<?> res) {
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Invalid response: " + res + "\n" + res.body());
        }
    }

    private List<Version> parseVersions(final String body) {
        try (final var reader = new BufferedReader(new StringReader(body))) {
            return reader.lines()
                    .filter(it -> it.contains("<a href=\"/zulu/bin/zulu"))
                    .map(it -> {
                        final var from = it.indexOf("<a href=\"/zulu/bin/zulu") + "<a href=\"/zulu/bin/".length();
                        return it.substring(from, it.indexOf('"', from)).strip();
                    })
                    .filter(it -> it.endsWith(suffix) && (preferJre ? it.contains("jre") : it.contains("jdk")))
                    .map(v -> { // ex: "zulu21.32.17-ca-jre21.0.2-linux_x64.zip"
                        // path for the download directly without the prefix and suffix
                        final var identifier = v.substring("zulu".length(), v.length() - suffix.length() - 1);
                        final var distroType = preferJre ? "-jre" : "-jdk";
                        final int versionStart = identifier.lastIndexOf(distroType);
                        final var version = identifier.substring(versionStart + distroType.length()).strip();
                        return new Version("Azul", version, "zulu", identifier);
                    })
                    .distinct()
                    .toList();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
