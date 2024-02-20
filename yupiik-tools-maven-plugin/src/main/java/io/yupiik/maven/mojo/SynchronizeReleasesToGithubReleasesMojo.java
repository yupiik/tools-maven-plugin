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
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.Locale.ROOT;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Fetch versions of the defined artifacts on a nexus and ensures it is set as github release artifacts.
 */
@Mojo(name = "synchronize-github-releases", threadSafe = true)
public class SynchronizeReleasesToGithubReleasesMojo extends AbstractMojo {
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";

    @Parameter(property = "yupiik.synchronize-github-releases.force", defaultValue = "false")
    private boolean force;

    @Parameter(property = "yupiik.synchronize-github-releases.forceHttpV1", defaultValue = "true")
    private boolean forceHttpV1;

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

    @Parameter(property = "yupiik.synchronize-github-releases.tagPattern")
    private String tagPattern;

    @Parameter(property = "yupiik.synchronize-github-releases.dryRun")
    private boolean dryRun;

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
            findTags(httpClient, jsonb, "")
                    .thenCompose(tags -> findExistingReleases(httpClient, jsonb, "")
                            .thenCompose(releases -> allOf(artifacts.stream()
                                    .map(spec -> safe(() -> updateArtifact(httpClient, threadPool, jsonb, spec, workDir, tags, releases)))
                                    .toArray(CompletableFuture<?>[]::new))
                                    .whenComplete((r, e) -> {
                                        if (e != null) {
                                            getLog().error(e);
                                        }
                                    })))
                    .toCompletableFuture().get();
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

    private CompletableFuture<?> synchronizeReleases(final HttpClient httpClient, final Jsonb jsonb,
                                                     final ReleaseSpec spec, final String version,
                                                     final Map<String, GithubRelease> githubExistingReleases,
                                                     final Map<String, GithubTag> tags, final Path workDir) {
        final var existing = dryRun ? null : githubExistingReleases.get(version);
        if (!force && existing != null && !attachIfExists) {
            getLog().info(version + " already exists on github, skipping");
            return completedFuture(true);
        }

        final CompletableFuture<GithubRelease> ghRelease;
        if (existing == null || force) {
            getLog().info("Creating release " + version);

            var tagName = tagPattern == null || tagPattern.isBlank() ?
                    spec.getArtifactId() + '-' + version :
                    tagPattern
                            .replace("${groupId}", spec.getGroupId())
                            .replace("${artifactId}", spec.getArtifactId())
                            .replace("${version}", version)
                            .replace("{groupId}", spec.getGroupId())
                            .replace("{artifactId}", spec.getArtifactId())
                            .replace("{version}", version);
            if (!tags.containsKey(tagName) && tagName.contains("-parent") && tags.containsKey(tagName.replace("-parent", ""))) {
                tagName = tagName.replace("-parent", "");
            }

            final var release = new GithubRelease();
            if (existing != null) {
                release.setId(existing.getId());
            }
            release.setBody("Release " + version);
            release.setName(version);
            release.setDraft(false);
            release.setPrerelease(false);
            release.setTagName(tagName);
            release.setTargetCommitish("master");

            final var url = URI.create(
                    githubBaseApi + (githubBaseApi.endsWith("/") ? "" : "/") + "repos/" + githubRepository + "/releases" +
                            (existing != null ? "/" + existing.getId() : ""));
            final var reqBuilder = HttpRequest.newBuilder();
            findServer(githubServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));

            ghRelease = ofNullable(tags.get(tagName))
                    // concatenate to release version the list of commits
                    .map(tag -> fetchTagCommits(httpClient, jsonb, tags, spec, tag)
                            .thenApply(commits -> commits.stream()
                                    // todo: config?
                                    .filter(c -> !c.getCommit().getMessage().startsWith("[maven-release-plugin]") &&
                                            !c.getCommit().getMessage().startsWith("skip changelog"))
                                    .sorted(comparing(it -> OffsetDateTime.parse(it.getCommit().getCommitter().getDate())))
                                    .map(c -> {
                                        final var author1 = c.getCommit().getAuthor();
                                        final var author2 = c.getAuthor();
                                        return "" +
                                                "* " + (author2 == null ? author1.getName() : ("[" + ofNullable(author1)
                                                .map(GithubCommitAuthor::getName)
                                                .orElseGet(author2::getLogin) + "](" + author2.getHtmlUrl() + ")")) + ": " +
                                                c.getCommit().getMessage() + (c.getCommit().getMessage().endsWith(".") ? "" : ".") + " [link](" + c.getHtmlUrl() + ").";
                                    })
                                    .collect(joining("\n")))
                            .thenAccept(msg -> release.setBody(release.getBody() + "\n\n" + msg)))
                    .orElseGet(() -> completedFuture(null))
                    .thenCompose(ignored -> {
                        if (dryRun) {
                            return completedFuture(release);
                        }
                        if (existing != null) {
                            return httpClient.sendAsync(reqBuilder
                                                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonb.toJson(release), StandardCharsets.UTF_8))
                                                    .uri(url)
                                                    .header("accept", "application/vnd.github.v3+json")
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .thenApply(response -> {
                                        if (response.statusCode() != 200) {
                                            throw new IllegalArgumentException("Invalid response from " + url + ": " + response + "\n" + response.body());
                                        }
                                        final var created = jsonb.fromJson(response.body(), GithubRelease.class);
                                        getLog().info("Updated release " + version);
                                        return created;
                                    });
                        }
                        return httpClient.sendAsync(reqBuilder
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
                    });
        } else {
            getLog().info("Release " + version + " already exists");
            ghRelease = completedFuture(existing);
        }
        if (dryRun) {
            try {
                getLog().info("[DRYRUN] skipping the actual release creation: " + ghRelease.toCompletableFuture().get());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final ExecutionException e) {
                getLog().info("[DRYRUN] skipping the actual release creation: " + version);
            }
            return ghRelease;
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

    private CompletableFuture<Collection<GithubCommit>> fetchTagCommits(final HttpClient httpClient, final Jsonb jsonb,
                                                                        final Map<String, GithubTag> tags,
                                                                        final ReleaseSpec spec, final GithubTag tag) {
        final Function<String, String> versionExtractor = tagPattern == null || tagPattern.isBlank() ?
                (s -> s.length() > (spec.getArtifactId().length() + 1) ? s.substring((spec.getArtifactId() + '-').length()) : s) :
                s -> extractVersionFromTagPattern(spec, s);
        final List<GithubTag> sorted = tags.values().stream()
                .sorted((t1, t2) -> compareVersions(versionExtractor.apply(t1.getName()), versionExtractor.apply(t2.getName())))
                .collect(toList());
        final int idx = sorted.indexOf(tag);
        if (idx == 0) {
            return fetchCommits(httpClient, jsonb, null, tag.getCommit());
        }
        return fetchCommits(httpClient, jsonb, sorted.get(idx - 1).getCommit(), tag.getCommit());
    }

    private CompletableFuture<Collection<GithubCommit>> fetchCommits(final HttpClient httpClient, final Jsonb jsonb,
                                                                     final GithubTagCommit from, final GithubTagCommit to) {
        return ofNullable(from)
                .map(c -> fetchCommit(httpClient, jsonb, c).thenApply(it -> "since=" + it.getCommitter().getDate()))
                .orElseGet(() -> completedFuture(""))
                .thenCompose(since -> ofNullable(to)
                        .map(c -> fetchCommit(httpClient, jsonb, c).thenApply(it -> "until=" + it.getCommitter().getDate()))
                        .orElseGet(() -> completedFuture(""))
                        .thenApply(until -> URI.create(githubBaseApi + (githubBaseApi.endsWith("/") ? "" : "/") + "repos/" + githubRepository + "/commits?" +
                                String.join("&", Stream.of(since, until).filter(Objects::nonNull).collect(toList())))))
                .thenCompose(uri -> {
                    final var reqBuilder = HttpRequest.newBuilder();
                    findServer(githubServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));
                    return httpClient.sendAsync(reqBuilder
                                            .GET()
                                            .uri(uri)
                                            .header("accept", "application/vnd.github.v3+json")
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .thenApply(response -> {
                                if (response.statusCode() != 200) {
                                    throw new IllegalArgumentException("Invalid response from " + uri + ": " + response + "\n" + response.body());
                                }
                                return jsonb.<List<GithubCommit>>fromJson(response.body(), new JohnzonParameterizedType(List.class, GithubCommit.class));
                            });
                });
    }

    private CompletableFuture<GithubCommitCommit> fetchCommit(final HttpClient httpClient, final Jsonb jsonb,
                                                              final GithubTagCommit commit) {
        final var reqBuilder = HttpRequest.newBuilder();
        findServer(githubServerId).ifPresent(s -> reqBuilder.header("Authorization", toAuthorizationHeaderValue(s)));
        final var uri = URI.create(githubBaseApi + (githubBaseApi.endsWith("/") ? "" : "/") + "repos/" + githubRepository + "/git/commits/" + commit.getSha());
        return httpClient.sendAsync(reqBuilder
                                .GET()
                                .uri(uri)
                                .header("accept", "application/vnd.github.v3+json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalArgumentException("Invalid response from " + uri + ": " + response + "\n" + response.body());
                    }
                    return jsonb.fromJson(response.body(), GithubCommitCommit.class);
                });
    }

    private int compareVersions(final String n1, final String n2) {
        if (n1.equals(n2)) {
            return 0;
        }

        final var v1 = parseVersion(n1);
        final var v2 = parseVersion(n2);
        if (v1 == null && v2 != null) {
            getLog().warn("'" + n1 + "' is not a parseable version");
            return 1;
        }
        if (v2 == null && v1 != null) {
            getLog().warn("'" + n2 + "' is not a parseable version");
            return -1;
        }
        if (v1 != null) {
            if (v1.getMajor() == v2.getMajor()) {
                if (v1.getMinor() == v2.getMinor()) {
                    return v1.getPatch() - v2.getPatch();
                }
                return v1.getMinor() - v2.getMinor();
            }
            return v1.getMajor() - v2.getMajor();
        }
        return n1.compareTo(n2);
    }

    private SimpleVersion parseVersion(final String n1) {
        final var segments = n1.split("\\.");
        if (segments.length <= 1) {
            return null;
        }
        try {
            return new SimpleVersion(
                    Integer.parseInt(segments[0]),
                    Integer.parseInt(segments[1]),
                    segments.length >= 3 ? Integer.parseInt(segments[2]) : -1 /* before 0 ;)*/);
        } catch (final NumberFormatException nfe) {
            // no-op
        }
        return null;
    }

    private String extractVersionFromTagPattern(final ReleaseSpec spec, final String value) {
        var replaced = tagPattern
                .replace("${groupId}", spec.getGroupId())
                .replace("${artifactId}", spec.getArtifactId())
                .replace("{groupId}", spec.getGroupId())
                .replace("{artifactId}", spec.getArtifactId());
        if (replaced.contains("-parent") && !value.contains("-parent")) { // root and tag name is <artifactid>-parent
            replaced = replaced.replace("-parent", "");
        }
        try {
            var start = replaced.indexOf("${version}");
            int offset = 0;
            if (start < 0) {
                offset = 1;
                start = replaced.indexOf("{version}");
            }
            if (start < 0) {
                throw new IllegalArgumentException("No '${version}' or '{version}' in '" + this.tagPattern + "'");
            }
            final var end = start + "${version}".length() - offset;
            return value.substring(start, value.length() - (replaced.length() - end));
        } catch (final RuntimeException re) {
            getLog().debug(re.getMessage());
            if (tagPattern.endsWith("-{version}") || tagPattern.endsWith("-${version}")) { // try last segment of the string
                return value.substring(value.lastIndexOf('-') + 1);
            }
            throw re;
        }
    }

    private CompletableFuture<?> attachArtifactToRelease(final HttpClient httpClient, final GithubRelease release,
                                                         final ReleaseSpec spec, final Artifact artifact, final Path workDir) {
        return download(httpClient, spec, artifact, release.getName(), workDir).thenCompose(file -> {
            if (file == null) {
                getLog().info(spec + ":" + artifact + " does not exist in version " + release.getName() + ", skipping");
                return completedFuture(null);
            }
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
                    if (r.statusCode() == 404) {
                        return null;
                    }
                    ensure200(url, r);
                    return output;
                });
    }

    private String toFilename(final ReleaseSpec spec, final Artifact artifact, final String version) {
        return spec.getArtifactId() + '-' + version + (artifact.getClassifier().isBlank() ? "" :
                ('-' + artifact.getClassifier())) + '.' + artifact.getType();
    }

    private CompletableFuture<?> updateArtifact(final HttpClient httpClient, final ExecutorService executorService, final Jsonb jsonb,
                                                final ReleaseSpec spec, final Path workDir,
                                                final Map<String, GithubTag> tags, final Map<String, GithubRelease> ghReleases) {
        final var availableVersions = findAvailableVersions(httpClient, spec);
        return availableVersions
                .thenComposeAsync(versions -> allOf(versions.stream()
                                .map(it -> synchronizeReleases(httpClient, jsonb, spec, it, ghReleases, tags, workDir))
                                .toArray(CompletableFuture<?>[]::new)),
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
                    final var releaseNames = releases.stream().collect(toMap(GithubRelease::getName, identity(), (a, b) -> a.id < b.id ? a : b));
                    return findNextLink(r.headers().firstValue("Link").orElse(null))
                            .map(next -> findExistingReleases(httpClient, jsonb, next)
                                    .thenApply(added -> Stream.concat(releaseNames.entrySet().stream(), added.entrySet().stream())
                                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a))))
                            .orElseGet(() -> completedFuture(releaseNames));
                });
    }

    private CompletableFuture<Map<String, GithubTag>> findTags(final HttpClient httpClient, final Jsonb jsonb, final String nextUrl) {
        if (nextUrl == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        final var url = URI.create(nextUrl.isBlank() ?
                githubBaseApi + (githubBaseApi.endsWith("/") ? "" : "/") + "repos/" + githubRepository + "/tags?per_page=100" :
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
                    final List<GithubTag> releases = jsonb.fromJson(r.body().trim(), new JohnzonParameterizedType(List.class, GithubTag.class));
                    final var tags = releases.stream().collect(toMap(GithubTag::getName, identity()));
                    return findNextLink(r.headers().firstValue("Link").orElse(null))
                            .map(next -> findTags(httpClient, jsonb, next)
                                    .thenApply(added -> Stream.concat(tags.entrySet().stream(), added.entrySet().stream())
                                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a))))
                            .orElseGet(() -> completedFuture(tags));
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
