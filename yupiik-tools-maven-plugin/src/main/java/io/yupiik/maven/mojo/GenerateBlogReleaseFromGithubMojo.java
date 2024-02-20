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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

/**
 * Fetch releases from github repo to generate a blog post.
 * The search is between 2 dates.
 */
@Mojo(name = "generate-blog-post-releases", threadSafe = true)
public class GenerateBlogReleaseFromGithubMojo extends AbstractMojo {
    @Parameter(property = "yupiik.generate-blog-post-releases.forceHttpV1", defaultValue = "true")
    protected boolean forceHttpV1;

    @Parameter(property = "yupiik.generate-blog-post-releases.useMavenCredentials", defaultValue = "true")
    protected boolean useMavenCredentials;

    @Parameter(property = "yupiik.generate-blog-post-releases.githubServerId", defaultValue = "github.com")
    protected String githubServerId;

    @Parameter(property = "yupiik.generate-blog-post-releases.githubRepository")
    protected List<String> githubRepositories;

    @Parameter(property = "yupiik.generate-blog-post-releases.githubRepository", defaultValue = "https://api.github.com/")
    protected String githubBaseApi;

    @Parameter(property = "yupiik.generate-blog-post-releases.threads", defaultValue = "16")
    protected int threads;

    @Parameter(property = "yupiik.generate-blog-post-releases.workdir", defaultValue = "${project.build.directory}/generate-blog-post-releases-workdir")
    protected String workdir;

    @Parameter(property = "yupiik.generate-blog-post-releases.from")
    protected String from;

    @Parameter(property = "yupiik.generate-blog-post-releases.to")
    protected String to;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Component
    private SettingsDecrypter settingsDecrypter;

    private final Map<String, Optional<Server>> servers = new ConcurrentHashMap<>();

    @Override
    public void execute() throws MojoExecutionException {
        final var threadPool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
                final var thread = new Thread(r, GenerateBlogReleaseFromGithubMojo.class.getSimpleName() + '-' + counter.incrementAndGet());
                thread.setContextClassLoader(GenerateBlogReleaseFromGithubMojo.class.getClassLoader());
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
        try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            final var workDir = Files.createDirectories(Paths.get(workdir));

            final var promises = githubRepositories.stream()
                    .map(repository -> {
                        try {
                            return generateBlogPost(repository, httpClient, jsonb);
                        } catch (ExecutionException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(CompletableFuture::completedFuture)
                            .toArray(CompletableFuture[]::new);

            final var output = CompletableFuture.allOf(promises)
                    .thenApply(fn -> Arrays.stream(promises).map(CompletableFuture::join).collect(Collectors.toList())).get();

            Files.writeString(Path.of(workDir + "/releases-blog-post.adoc"), output.stream().map(Object::toString).collect(joining()), StandardCharsets.UTF_8);

        } catch (InterruptedException e) {
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

    private String generateBlogPost(final String githubRepository, final HttpClient httpClient, final Jsonb jsonb) throws ExecutionException, InterruptedException {
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        return "== ".concat(githubRepository.substring(githubRepository.indexOf('/') + 1)).concat("\n\n")

                .concat(

                findExistingReleases(httpClient, jsonb, githubRepository, "")
                    .get()
                    .values().stream().sorted((o1, o2) -> o1.getPublishedAt().compareTo(o2.publishedAt))
                    .map(githubRelease -> {
                        LocalDate publishedAt = LocalDate.parse(githubRelease.getPublishedAt(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
                        if (!publishedAt.isAfter(LocalDate.parse(from, df))
                                || !publishedAt.isBefore(LocalDate.parse(to, df))) {
                            return null;
                        }

                        return "=== ".concat(githubRelease.getTagName()).concat("\n\n").
                                concat("URL: ").concat(githubRelease.getUrl()).concat("\n\n").
                                concat("Published at: ").concat(df.format(publishedAt)).concat("\n\n").
                                concat(githubRelease.getBody()).concat("\n\n");
                        })
                    .filter(Objects::nonNull).collect(joining("\n")));
    }

    private CompletableFuture<Map<String, GithubRelease>> findExistingReleases(final HttpClient httpClient, final Jsonb jsonb, final String githubRepository, final String nextUrl) {
        if (nextUrl == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        final var url = URI.create(nextUrl.isBlank() ?
                githubBaseApi + (githubBaseApi.endsWith("/") ? "" : "/") + "repos/" + githubRepository + "/releases?per_page=100" :
                nextUrl);
        final var reqBuilder = HttpRequest.newBuilder();
        if (useMavenCredentials)
            findServer(githubServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));
        return httpClient.sendAsync(reqBuilder
                                .GET()
                                .uri(url)
                                .header("accept", "application/vnd.github.v3+json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenCompose(r -> {
                    ensure200(url, r);
                    final List<GithubRelease> releases = jsonb.fromJson(r.body().trim(), new JohnzonParameterizedType(List.class, GithubRelease.class));
                    final var releaseNames = releases.stream().collect(toMap(GithubRelease::getName, identity(), (a, b) -> a.id < b.id ? a : b));
                    return findNextLink(r.headers().firstValue("Link").orElse(null))
                            .map(next -> findExistingReleases(httpClient, jsonb, githubRepository, next)
                                    .thenApply(added -> Stream.concat(releaseNames.entrySet().stream(), added.entrySet().stream())
                                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a))))
                            .orElseGet(() -> completedFuture(releaseNames));
                });
    }

    /**
     * Example Link value:
     * <p>
     * {@code Link: <https://api.github.com/search/code?q=addClass+user%3Amozilla&page=15>; rel="next",
     * <https://api.github.com/search/code?q=addClass+user%3Amozilla&page=34>; rel="last",
     * <https://api.github.com/search/code?q=addClass+user%3Amozilla&page=1>; rel="first",
     * <https://api.github.com/search/code?q=addClass+user%3Amozilla&page=13>; rel="prev"}
     *
     * @param link the raw header value.
     * @return the first next link.
     */
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
    public static class ReleaseSpec {
        private String groupId;
        private String artifactId;
        private List<Artifact> artifacts;

        @Override
        public String toString() {
            return groupId + ':' + artifactId;
        }
    }

    @Data
    public static class Artifact {
        private String type = "jar";
        private String classifier = "";

        @Override
        public String toString() {
            return type + (classifier.isBlank() ? "" : ":") + classifier;
        }
    }

    @Data
    public static class GithubRelease {
        private long id;

        @JsonbProperty("html_url")
        private String url;

        @JsonbProperty("published_at")
        private String publishedAt;

        private boolean draft;
        private boolean prerelease;

        private String name;
        private String body;

        @JsonbProperty("upload_url")
        private String uploadUrl;

        @JsonbProperty("tag_name")
        private String tagName;

        @JsonbProperty("target_commitish")
        private String targetCommitish = "master";

        private List<GithubAsset> assets;
    }

    @Data
    public static class GithubAsset {
        private long id;
        private String url;
        private String name;
        private String label;
        private long size;

        @JsonbProperty("content_type")
        private String contentType;
    }

    @Data
    public static class GithubTag {
        private String name;
        private GithubTagCommit commit;
    }

    @Data
    public static class GithubTagCommit {
        private String sha;
        private String url;
    }

    @Data
    public static class GithubCommit {
        private GithubCommitCommit commit;
        private GithubAuthor author;

        @JsonbProperty("html_url")
        private String htmlUrl;
    }

    @Data
    public static class GithubCommitCommit {
        private GithubCommitAuthor author;
        private GithubCommitAuthor committer;
        private String url;
        private String message;
    }

    @Data
    public static class GithubCommitAuthor {
        private String name;
        private String date;
    }

    @Data
    public static class GithubAuthor {
        private long id;
        private String login;

        @JsonbProperty("html_url")
        private String htmlUrl;
    }

    @Data
    private static class SimpleVersion {
        private final int major;
        private final int minor;
        private final int patch;
    }
}
