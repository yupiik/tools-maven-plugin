/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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

import lombok.Data;
import org.apache.johnzon.mapper.reflection.JohnzonParameterizedType;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.annotation.JsonbProperty;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Fetch versions of the defined artifacts on a nexus and ensures it is set as github release artifacts.
 */
@Mojo(name = "synchronize-github-releases")
public class SynchronizeReleasesToGithubReleasesMojo extends AbstractMojo {
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";

    @Parameter(property = "yupiik.synchronize-github-releases.attachIfExists", defaultValue = "false")
    private boolean attachIfExists;

    @Parameter(property = "yupiik.synchronize-github-releases.artifacts")
    private List<ReleaseSpec> artifacts;

    @Parameter(property = "yupiik.synchronize-github-releases.githubServerId", defaultValue = "github.com")
    private String githubServerId;

    @Parameter(property = "yupiik.synchronize-github-releases.nexusServerId")
    private String nexusServerId;

    @Parameter(property = "yupiik.synchronize-github-releases.mavenRepositoryBaseUrl", defaultValue = CENTRAL)
    private String mavenRepositoryBaseUrl;

    @Parameter(property = "yupiik.synchronize-github-releases.githubRepository")
    private String githubRepository;

    @Parameter(property = "yupiik.synchronize-github-releases.githubRepository", defaultValue = "https://api.github.com/")
    private String githubBaseApi;

    @Parameter(property = "yupiik.synchronize-github-releases.threads", defaultValue = "16")
    private int threads;

    @Parameter(property = "yupiik.synchronize-github-releases.workdir", defaultValue = "${project.build.directory}/synchronize-github-releases-workdir")
    private String workdir;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${localRepository}", readonly = true)
    protected ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    protected List<ArtifactRepository> remoteRepositories;

    @Component
    private SettingsDecrypter settingsDecrypter;

    private final Map<String, Optional<Server>> servers = new ConcurrentHashMap<>();

    @Override
    public void execute() throws MojoExecutionException {
        if (artifacts == null || artifacts.isEmpty()) {
            getLog().info("No artifact defined, skipping");
            return;
        }

        final var threadPool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
                final var thread = new Thread(r, SynchronizeReleasesToGithubReleasesMojo.class.getSimpleName() + '-' + counter.incrementAndGet());
                thread.setContextClassLoader(SynchronizeReleasesToGithubReleasesMojo.class.getClassLoader());
                return thread;
            }
        });
        final var httpClient = HttpClient.newBuilder()
                .executor(threadPool)
                .build();
        try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            final var workDir = Files.createDirectories(Paths.get(workdir));
            allOf(artifacts.stream()
                    .map(spec -> safe(() -> updateArtifact(httpClient, threadPool, jsonb, spec, workDir)))
                    .toArray(CompletableFuture<?>[]::new))
                    .whenComplete((r, e) -> {
                        if (e != null) {
                            getLog().error(e);
                        }
                    })
                    .toCompletableFuture().get();
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

    private CompletableFuture<?> synchronizeReleases(final HttpClient httpClient, final Jsonb jsonb,
                                                     final ReleaseSpec spec, final String version,
                                                     final Map<String, GithubRelease> githubExistingReleases, final Path workDir) {
        // todo: flag to update anyway (force=true?), for now the release(s) can be deleted to this mojo rerun
        final var existing = githubExistingReleases.get(version);
        if (existing != null && !attachIfExists) {
            getLog().info(version + " already exists on github, skipping");
            return completedFuture(true);
        }

        final CompletableFuture<GithubRelease> ghRelease;
        if (existing == null) {
            getLog().info("Creating release " + version);

            final var release = new GithubRelease();
            release.setBody("Release " + version);
            release.setName(version);
            release.setDraft(false);
            release.setPrerelease(false);
            release.setTagName(spec.getArtifactId() + '-' + version); // convention for now
            release.setTargetCommitish("master");

            final var url = URI.create(githubBaseApi + (githubBaseApi.endsWith("/") ? "" : "/") + "repos/" + githubRepository + "/releases");
            final var reqBuilder = HttpRequest.newBuilder();
            findServer(githubServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));
            ghRelease = httpClient.sendAsync(reqBuilder
                            .POST(HttpRequest.BodyPublishers.ofString(jsonb.toJson(release), StandardCharsets.UTF_8))
                            .uri(url)
                            .header("accept", "application/vnd.github.v3+json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 201) {
                            throw new IllegalArgumentException("Invalid response from " + url + ": " + response + "\n" + response.body());
                        }
                        final var created = jsonb.fromJson(response.body(), GithubRelease.class);
                        getLog().info("Created release " + version + ", will now upload assets");
                        return created;
                    });
        } else {
            getLog().info("Release " + version + " already exists");
            ghRelease = completedFuture(existing);
        }

        return ghRelease.thenCompose(release -> {
            final var existingAssets = ofNullable(release.getAssets()).stream().flatMap(Collection::stream)
                    .map(GithubAsset::getName)
                    .collect(toSet());
            return allOf(ofNullable(spec.getArtifacts()).stream().flatMap(Collection::stream)
                    .filter(a -> !existingAssets.contains(toFilename(spec, a, version)))
                    .map(artifact -> attachArtifactToRelease(httpClient, release, spec, artifact, workDir))
                    .toArray(CompletableFuture<?>[]::new));
        });
    }

    private CompletableFuture<?> attachArtifactToRelease(final HttpClient httpClient, final GithubRelease release,
                                                         final ReleaseSpec spec, final Artifact artifact, final Path workDir) {
        return download(httpClient, spec, artifact, release.getName(), workDir).thenCompose(file -> {
            final var url = URI.create(release.getUploadUrl().replaceAll("\\{[^}]+}", "") + "?" +
                    "name=" + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8) + "&" +
                    "label=" + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8));
            final var reqBuilder = HttpRequest.newBuilder();
            findServer(githubServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));
            try {
                return httpClient.sendAsync(reqBuilder
                                .POST(HttpRequest.BodyPublishers.ofFile(file))
                                .uri(url)
                                .header("content-type", findContentType(file))
                                .header("accept", "application/vnd.github.v3+json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                        .thenApply(response -> {
                            if (response.statusCode() != 201) {
                                throw new IllegalArgumentException("Invalid response from " + url + ": " + response + "\n" + response.body());
                            }
                            getLog().info("Attached " + spec + ':' + release.getName() + ':' + artifact + " to release " + release.getName());
                            return true;
                        });
            } catch (final FileNotFoundException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private String findContentType(final Path file) {
        final var name = file.getFileName().toString();
        final var ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(ROOT);
        switch (ext) {
            case "yml":
            case "yaml":
                return "text/plain";
            case "pom":
            case "xml":
                return "application/xml";
            case "gzip":
                return "application/gzip";
            case "zip":
            case "jar":
                return "application/zip";
            case "so":
            case "bin": // arthur/graalvm
            default:
                return "application/octet-stream";
        }
    }

    // bypass maven resolver, we know where it is, don't want to cache it generally - or we can use maven itself
    private CompletableFuture<Path> download(final HttpClient httpClient,
                                             final ReleaseSpec spec, final Artifact artifact, final String version,
                                             final Path workDir) {
        final var filename = toFilename(spec, artifact, version);
        final var url = URI.create(mavenRepositoryBaseUrl + (mavenRepositoryBaseUrl.endsWith("/") ? "" : "/") +
                spec.getGroupId().replace('.', '/') + '/' + spec.getArtifactId() + '/' +
                version + '/' + filename);
        getLog().info("Fetching " + url);
        final var reqBuilder = HttpRequest.newBuilder();
        if (nexusServerId != null && !CENTRAL.equals(mavenRepositoryBaseUrl)) {
            findServer(nexusServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));
        }
        final var output = workDir.resolve(filename);
        return httpClient.sendAsync(reqBuilder.GET().uri(url).build(), HttpResponse.BodyHandlers.ofFile(output))
                .thenApply(r -> {
                    ensure200(url, r);
                    return output;
                });
    }

    private String toFilename(final ReleaseSpec spec, final Artifact artifact, final String version) {
        return spec.getArtifactId() + '-' + version + (artifact.getClassifier().isBlank() ? "" :
                ('-' + artifact.getClassifier())) + '.' + artifact.getType();
    }

    private CompletableFuture<?> updateArtifact(final HttpClient httpClient, final ExecutorService executorService, final Jsonb jsonb,
                                                final ReleaseSpec spec, final Path workDir) {
        final var availableVersions = findAvailableVersions(httpClient, spec);
        final var existingReleases = findExistingReleases(httpClient, jsonb, "");
        return availableVersions
                .thenComposeAsync(versions -> existingReleases.thenCompose(ghReleases -> allOf(versions.stream()
                                .map(it -> synchronizeReleases(httpClient, jsonb, spec, it, ghReleases, workDir))
                                .toArray(CompletableFuture<?>[]::new))),
                        executorService);
    }

    private CompletableFuture<Map<String, GithubRelease>> findExistingReleases(final HttpClient httpClient, final Jsonb jsonb, final String nextUrl) {
        if (nextUrl == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        final var url = URI.create(nextUrl.isBlank() ?
                githubBaseApi + (githubBaseApi.endsWith("/") ? "" : "/") + "repos/" + githubRepository + "/releases?per_page=100" :
                nextUrl);
        final var reqBuilder = HttpRequest.newBuilder();
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
                    final var releaseNames = releases.stream().collect(toMap(GithubRelease::getName, identity()));
                    return findNextLink(r.headers().firstValue("Link").orElse(null))
                            .map(next -> findExistingReleases(httpClient, jsonb, next)
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

    private CompletableFuture<List<String>> findAvailableVersions(final HttpClient httpClient, final ReleaseSpec spec) {
        final var url = URI.create(mavenRepositoryBaseUrl + (mavenRepositoryBaseUrl.endsWith("/") ? "" : "/") +
                spec.getGroupId().replace('.', '/') + '/' + spec.getArtifactId() + "/maven-metadata.xml");
        getLog().info("Fetching " + url);
        final var reqBuilder = HttpRequest.newBuilder();
        if (nexusServerId != null && !CENTRAL.equals(mavenRepositoryBaseUrl)) {
            findServer(nexusServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));
        }
        return httpClient.sendAsync(reqBuilder.GET().uri(url).build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    ensure200(url, r);
                    try (final var reader = ReaderFactory.newXmlReader(
                            new ByteArrayInputStream(r.body().getBytes(StandardCharsets.UTF_8)))) {
                        return new MetadataXpp3Reader().read(reader, false);
                    } catch (final IOException | XmlPullParserException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .thenApply(meta -> {
                    if (meta.getVersioning() == null) {
                        getLog().error("No version yet");
                        return List.of();
                    }
                    getLog().debug("Found versions " + meta.getVersioning().getVersions() + " for " + spec);
                    return meta.getVersioning().getVersions();
                });
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
}
