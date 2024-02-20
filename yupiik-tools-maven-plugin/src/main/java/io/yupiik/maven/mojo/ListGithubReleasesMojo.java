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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.time.LocalTime.MIN;
import static java.time.Month.JANUARY;
import static java.time.ZoneOffset.UTC;
import static java.util.Comparator.comparing;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Fetch github releases between two dates.
 */
@Mojo(name = "list-github-releases", threadSafe = true)
public class ListGithubReleasesMojo extends AbstractMojo {
    @Parameter(property = "yupiik.list-github-releases.forceHttpV1", defaultValue = "true")
    private boolean forceHttpV1;

    @Parameter(property = "yupiik.list-github-releases.artifacts")
    private List<String> projects;

    @Parameter(property = "yupiik.list-github-releases.githubServerId", defaultValue = "github.com")
    private String githubServerId;

    @Parameter(property = "yupiik.list-github-releases.githubRepository", defaultValue = "https://api.github.com/")
    private String githubBaseApi;

    @Parameter(property = "yupiik.list-github-releases.threads", defaultValue = "16")
    private int threads;

    @Parameter(property = "yupiik.list-github-releases.from", defaultValue = "auto")
    private String fromDate;

    @Parameter(property = "yupiik.list-github-releases.to", defaultValue = "auto")
    private String toDate;

    @Component
    private SettingsDecrypter settingsDecrypter;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    private final Map<String, Optional<Server>> servers = new ConcurrentHashMap<>();

    @Override
    public void execute() throws MojoExecutionException {
        if (projects == null || projects.isEmpty()) {
            getLog().info("No project defined, skipping");
            return;
        }

        final var threadPool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
                final var thread = new Thread(r, ListGithubReleasesMojo.class.getSimpleName() + '-' + counter.incrementAndGet());
                thread.setContextClassLoader(ListGithubReleasesMojo.class.getClassLoader());
                return thread;
            }
        });
        final var httpClientBuilder = HttpClient.newBuilder();
        if (forceHttpV1) {
            httpClientBuilder.version(HttpClient.Version.HTTP_1_1);
        }
        final var httpClient = httpClientBuilder
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(threadPool)
                .build();

        final var base = githubBaseApi + (githubBaseApi.endsWith("/") ? "" : "/");
        final List<GithubRelease> releases;
        try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            final var all = projects.stream()
                    .map(project -> safe(() -> findExistingReleases(httpClient, jsonb, base + "repos/" + project + "/releases?per_page=100")))
                    .collect(toList());
            releases = allOf(all.toArray(new CompletableFuture<?>[0]))
                    .thenApply(ok -> all.stream().map(i -> i.getNow(Map.of()))
                            .reduce(new HashMap<>(), (a, b) -> {
                                a.putAll(b);
                                return a;
                            }))
                    .whenComplete((r, e) -> {
                        if (e != null) {
                            getLog().error(e);
                        }
                    })
                    .toCompletableFuture().get()
                    .values().stream()
                    .sorted(comparing(GithubRelease::getPublisedAt))
                    .collect(toList());

            if ("skip".equals(fromDate) && "skip".equals(toDate)) {
                // no filtering by date
            } else if ("auto".equals(fromDate) && "auto".equals(toDate)) { // default to current year (start at first day of the current year)
                final var start = Year.now().atMonth(JANUARY).atDay(1).atTime(MIN).atOffset(UTC);
                filterByDate(releases, start, start.plusYears(1));
            } else if ("auto".equals(toDate)) { // one year from the set date
                final var start = OffsetDateTime.parse(fromDate);
                filterByDate(releases, start, start.plusYears(1));
            } else if ("auto".equals(fromDate)) {
                final var end = OffsetDateTime.parse(toDate);
                filterByDate(releases, end.minusYears(1), end);
            } else {
                filterByDate(releases, OffsetDateTime.parse(fromDate), OffsetDateTime.parse(toDate));
            }

            onReleases(releases);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (final Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            threadPool.shutdownNow();
            try {
                if (!threadPool.awaitTermination(1, TimeUnit.SECONDS)) {
                    getLog().warn("Thread pool didn't shut down fast enough, exiting with some hanging threads");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    protected void onReleases(final List<GithubRelease> releases) {
        final var content = releases.stream()
                .map(r -> {
                    var body = r.getBody().replace("\n*", "\n**").strip();
                    if (body.startsWith("Release " + r.getName())) {
                        body = body.substring("Release ".length() + r.getName().length()).strip();
                    }
                    return "* " + project(r.getUrl()) + ' ' + r.getName() + " (" + r.getPublisedAt() + ")\n" + body + "\n";
                })
                .collect(joining("\n"));
        getLog().info("" +
                "= Total: " + releases.size() + "\n" +
                "\n" +
                content);
    }

    private String project(final String url) {
        var out = url;
        for (int i = 0; i < 2; i++) {
            final int slash = out.lastIndexOf('/');
            if (slash > 0) {
                out = out.substring(0, slash);
            }
        }
        return out.substring(out.lastIndexOf('/') + 1);
    }

    private void filterByDate(final List<GithubRelease> releases, final OffsetDateTime start, final OffsetDateTime end) {
        releases.removeIf(i -> {
            final var publisedAt = i.getPublisedAt();
            return (publisedAt.isBefore(start) || publisedAt.isAfter(end)) &&
                    !Objects.equals(start, publisedAt) && !Objects.equals(end, publisedAt);
        });
    }

    private CompletableFuture<Map<String, GithubRelease>> findExistingReleases(
            final HttpClient httpClient, final Jsonb jsonb, final String url) {
        if (url == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        final var reqBuilder = HttpRequest.newBuilder();
        findServer(githubServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));
        final var uri = URI.create(url);
        return httpClient.sendAsync(reqBuilder
                                .GET()
                                .uri(uri)
                                .header("accept", "application/vnd.github.v3+json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenCompose(r -> {
                    ensure200(uri, r);
                    final List<GithubRelease> releases = jsonb.fromJson(r.body().trim(), new JohnzonParameterizedType(List.class, GithubRelease.class));
                    final var releaseNames = releases.stream().collect(toMap(GithubRelease::getName, identity(), (a, b) -> a.id < b.id ? a : b));
                    return findNextLink(r.headers().firstValue("Link").orElse(null))
                            .map(next -> findExistingReleases(httpClient, jsonb, next)
                                    .thenApply(added -> Stream.concat(releaseNames.entrySet().stream(), added.entrySet().stream())
                                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a))))
                            .orElseGet(() -> completedFuture(releaseNames));
                });
    }

    private Optional<String> findNextLink(final String link) {
        return ofNullable(link)
                .flatMap(l -> Stream.of(l.split(","))
                        .map(String::trim)
                        .filter(it -> it.contains("rel=\"next\""))
                        .flatMap(next -> Stream.of(next.split(";")))
                        .map(String::trim)
                        .filter(it -> it.startsWith("<") && it.endsWith(">"))
                        .map(it -> it.substring(1, it.length() - 1))
                        .findFirst());
    }

    private <T> CompletableFuture<T> safe(final Supplier<CompletableFuture<T>> provider) {
        try {
            return provider.get();
        } catch (final RuntimeException re) {
            final CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(re);
            return future;
        }
    }

    private Optional<Server> findServer(final String id) {
        return servers.computeIfAbsent(id, k -> {
            final Server server = session.getSettings().getServer(k);
            if (server == null) {
                getLog().info("No server '" + k + "' defined, ignoring");
                return empty();
            }
            getLog().info("Found server '" + k + "'");
            return ofNullable(settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server)).getServer())
                    .or(() -> of(server));
        });
    }

    private void ensure200(final URI url, final HttpResponse<?> response) {
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException("Invalid response from " + url + ": " + response + "\n" + response.body());
        }
    }

    private String toAuthorizationHeaderValue(final Server server) {
        return server.getUsername() == null ?
                server.getPassword() : // assumed raw header value, enables "Token xxx" cases
                ("Basic " + Base64.getEncoder().encodeToString(
                        (server.getUsername() + ':' + server.getPassword()).getBytes(StandardCharsets.UTF_8)));
    }

    @Data
    public static class GithubRelease {
        private long id;
        private String url;

        private boolean draft;
        private boolean prerelease;

        private String name;
        private String body;

        @JsonbProperty("target_commitish")
        private String targetCommitish = "master";

        @JsonbProperty("published_at")
        private OffsetDateTime publisedAt;
    }
}
