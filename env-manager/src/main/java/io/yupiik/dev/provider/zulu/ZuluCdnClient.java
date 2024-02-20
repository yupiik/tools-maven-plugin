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
package io.yupiik.dev.provider.zulu;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.model.Archive;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.Archives;
import io.yupiik.dev.shared.Os;
import io.yupiik.dev.shared.http.Cache;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@DefaultScoped
public class ZuluCdnClient implements Provider {
    private final String suffix;
    private final Archives archives;
    private final YemHttpClient client;
    private final Cache cache;
    private final URI base;
    private final URI apiBase;
    private final Path local;
    private final boolean enabled;
    private final boolean preferJre;
    private final boolean preferApi;
    private final JsonMapper jsonMapper;
    private final Map<Integer, CompletionStage<List<Version>>> pendingRequests = new ConcurrentHashMap<>();

    public ZuluCdnClient(final YemHttpClient client, final ZuluCdnConfiguration configuration, final Os os, final Archives archives,
                         final Cache cache, final JsonMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
        this.archives = archives;
        this.cache = cache;
        this.base = URI.create(configuration.base());
        this.local = Path.of(configuration.local());
        this.enabled = configuration.enabled();
        this.apiBase = URI.create(configuration.apiBase());
        this.preferApi = configuration.preferApi();
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
    public CompletionStage<Path> install(final String tool, final String version, final ProgressListener progressListener) {
        final var archivePath = local.resolve(version).resolve(version + '-' + suffix);
        final var exploded = archivePath.getParent().resolve("distribution_exploded");
        if (Files.exists(exploded)) {
            return completedFuture(exploded);
        }

        if (!enabled) {
            throw new IllegalStateException("Zulu support not enabled (by configuration)");
        }

        try {
            Files.createDirectories(archivePath.getParent());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return (Files.notExists(archivePath) ?
                download(tool, version, archivePath, progressListener) :
                completedFuture(new Archive(suffix.endsWith(".zip") ? "zip" : "tar.gz", archivePath)))
                .thenApply(archive -> archives.unpack(archive, exploded));
    }

    @Override
    public CompletionStage<Map<Candidate, List<Version>>> listLocal() {
        if (Files.notExists(local)) {
            return completedFuture(Map.of());
        }
        return listTools().thenApply(candidates -> candidates.stream().collect(toMap(identity(), tool -> {
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
        })));
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
    public CompletionStage<Archive> download(final String tool, final String id, final Path target, final ProgressListener progressListener) {
        if (!enabled) {
            throw new IllegalStateException("Zulu support not enabled (by configuration)");
        }

        return client.getFile(
                        HttpRequest.newBuilder()
                                .uri(base.resolve("zulu" + id + '-' + suffix))
                                .build(),
                        target, progressListener)
                .thenApply(res -> {
                    ensure200(res);
                    return new Archive(suffix.endsWith(".zip") ? "zip" : "tar.gz", target);
                });
    }

    @Override
    public CompletionStage<List<Candidate>> listTools() {
        if (!enabled) {
            return completedFuture(List.of());
        }
        return completedFuture(List.of(new Candidate(
                "java", "java", "Java JRE or JDK downloaded from Azul CDN.", base.toASCIIString(), Map.of("emoji", "☕"))));
    }

    @Override
    public CompletionStage<List<Version>> listVersions(final String tool) {
        if (!enabled) {
            return completedFuture(List.of());
        }

        // here the payload is >5M so we can let the client cache it but saving the result will be way more efficient on the JSON side
        final var entry = cache.lookup(base.toASCIIString());
        if (entry != null && entry.hit() != null) {
            return completedFuture(parseVersions(entry.hit().payload()));
        }

        if (preferApi) {
            final var baseUrl = apiBase.resolve("/metadata/v1/zulu/packages/") + "?" +
                    "os=" + (suffix.startsWith("win") ?
                    "windows" :
                    (suffix.startsWith("mac") ?
                            "macosx" :
                            (Files.exists(Path.of("/lib/ld-musl-x86_64.so.1")) ? "linux-musl" : "linux-glibc"))) + "&" +
                    "arch=" + (suffix.contains("_aarch64") ? "aarch64" : "x64") + "&" +
                    "archive_type=" + (suffix.endsWith(".tar.gz") ? "tar.gz" : "zip") + "&" +
                    "java_package_type=" + (preferJre ? "jre" : "jdk") + "&" +
                    "release_status=&" +
                    "availability_types=&" +
                    "certifications=&" +
                    "page_size=900";
            final var listOfVersionsType = new Types.ParameterizedTypeImpl(List.class, APiVersion.class);
            return fetchVersionPages(1, baseUrl, listOfVersionsType)
                    .thenApply(filtered -> {
                        if (entry != null) {
                            cache.save(entry.key(), Map.of(), filtered.stream()
                                    .map(it -> "<a href=\"/zulu/bin/zulu" + it.identifier() + '-' + suffix + "\">zulu" + it.identifier() + '-' + suffix + "</a>")
                                    .collect(joining("\n", "", "\n")));
                        }
                        return filtered;
                    });
        }

        return pendingRequests.computeIfAbsent(0, k -> client.sendAsync(HttpRequest.newBuilder().GET().uri(base).build())
                        .thenApply(res -> {
                            ensure200(res);

                            final var filtered = parseVersions(res.body());
                            if (entry != null) {
                                cache.save(entry.key(), Map.of(), filtered.stream()
                                        .map(it -> "<a href=\"/zulu/bin/zulu" + it.identifier() + '-' + suffix + "\">zulu" + it.identifier() + '-' + suffix + "</a>")
                                        .collect(joining("\n", "", "\n")));
                            }
                            return filtered;
                        }))
                .whenComplete((ok, ko) -> pendingRequests.remove(0));
    }

    private CompletionStage<List<Version>> fetchVersionPages(final int page, final String baseUrl,
                                                             final Type listOfVersionsType) {
        return pendingRequests.computeIfAbsent(page, pageNumber -> client.sendAsync(HttpRequest.newBuilder().GET()
                                .uri(URI.create(baseUrl + "&page=" + pageNumber))
                                .header("accept", "application/json")
                                .build())
                        .thenCompose(res -> {
                            ensure200(res);

                            final List<APiVersion> apiVersions = jsonMapper.fromString(listOfVersionsType, res.body());
                            final var pageVersions = apiVersions.stream().map(APiVersion::name).map(this::toProviderVersion).toList();

                            return res.headers()
                                    .firstValue("x-pagination")
                                    .map(p -> jsonMapper.fromString(Pagination.class, p.strip()))
                                    .filter(p -> p.last_page() <= page)
                                    .map(p -> completedFuture(pageVersions))
                                    .orElseGet(() -> fetchVersionPages(page + 1, baseUrl, listOfVersionsType)
                                            .thenApply(newVersions -> Stream.concat(newVersions.stream(), pageVersions.stream()).toList())
                                            .toCompletableFuture());
                        }))
                .whenComplete((ok, ko) -> pendingRequests.remove(page));
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
                    .map(this::toProviderVersion)
                    .distinct()
                    .toList();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ex: "zulu21.32.17-ca-jre21.0.2-linux_x64.zip"
    // path for the download directly without the prefix and suffix
    private Version toProviderVersion(final String name) {
        final var identifier = name.substring("zulu".length(), name.length() - suffix.length() - 1);
        final var distroType = preferJre ? "-jre" : "-jdk";
        final int versionStart = identifier.lastIndexOf(distroType);
        final var version = identifier.substring(versionStart + distroType.length()).strip();
        return new Version("Azul", version, "zulu", identifier);
    }

    @JsonModel
    public record Pagination(long last_page) {
    }

    @JsonModel
    public record APiVersion(String name) {
        /*
          {
            "availability_type": "CA",
            "distro_version": [
              21,
              32,
              17,
              0
            ],
            "download_url": "https://cdn.azul.com/zulu/bin/zulu21.32.17-ca-fx-jdk21.0.2-linux_x64.tar.gz",
            "java_version": [
              21,
              0,
              2
            ],
            "latest": true,
            "name": "zulu21.32.17-ca-fx-jdk21.0.2-linux_x64.tar.gz",
            "openjdk_build_number": 13,
            "package_uuid": "f282c770-a435-4053-9d10-b3434305eb78",
            "product": "zulu"
          }
         */
    }
}
