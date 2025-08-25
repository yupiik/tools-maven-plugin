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
package io.yupiik.tools.minisite.action.builtin;

import io.yupiik.uship.backbone.reflect.ParameterizedTypeImpl;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.annotation.JsonbProperty;
import lombok.Data;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

// enables to fetch github contributors for a project and dump them as json
// can then be read from minisite to build a light frontend (preact for ex) to render them
public class GithubContributorGenerator implements Runnable {
    private final String token;
    private final String project;
    private final String base;
    private final String output;

    public GithubContributorGenerator(final Map<String, String> configuration) {
        this.token = configuration.getOrDefault("token", "skip");
        this.base = configuration.getOrDefault("base", "https://api.github.com");
        this.project = requireNonNull(configuration.get("project"), "no project set, use org/name format");
        this.output = requireNonNull(configuration.get("output"), "no output location of the json model with contributors");
    }

    @Override
    public void run() {
        URI uri = URI.create(base + "/repos/" + project + "/contributors?per_page=100");
        final ScheduledExecutorService lowPool = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, GithubContributorGenerator.class.getName());
            thread.setContextClassLoader(GithubContributorGenerator.class.getClassLoader());
            return thread;
        });
        final HttpClient client = HttpClient.newBuilder()
                .executor(lowPool)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            final List<Contributor> all = new ArrayList<>(8);
            while (uri != null) {
                final HttpRequest.Builder request = HttpRequest.newBuilder()
                        .GET()
                        .uri(uri)
                        .header("accept", "application/json");
                if (!"skip".equals(token)) {
                    request.header("authorization", "Bearer " + token);
                }
                final HttpResponse<String> res = client.send(request.build(), ofString());
                if (res.statusCode() != 200) {
                    if (pauseOnRateLimit(res)) {
                        continue;
                    }
                    throw new IllegalStateException("Invalid response: HTTP " + res.statusCode() + "\n" + res.body());
                }
                final Type contributorType = new ParameterizedTypeImpl(List.class, Contributor.class);
                final List<Contributor> contributors = jsonb.fromJson(res.body(), contributorType);
                all.addAll(contributors.stream().filter(it -> it.getUrl() != null && !it.getUrl().isBlank()).toList());
                uri = res.headers()
                        .allValues("link").stream()
                        .filter(it -> it.contains("rel=\"next\""))
                        .findFirst()
                        .map(it -> {
                            final int nextIdx = it.indexOf("rel=\"next\"");
                            final int start = it.lastIndexOf('<', nextIdx);
                            final int end = it.lastIndexOf('>', nextIdx);
                            return URI.create(it.substring(start + 1, end));
                        })
                        .orElse(null);
            }

            final Map<Long, User> details = new ConcurrentHashMap<>(all.size());
            if (!all.isEmpty()) {
                final int chunkSize = 16;
                do {
                    final List<Contributor> nextChunk = new ArrayList<>(all.size() <= chunkSize ? all : all.subList(0, chunkSize));
                    all.removeAll(nextChunk);
                    allOf(nextChunk.stream()
                            .map(it -> {
                                final HttpRequest.Builder request = HttpRequest.newBuilder()
                                        .GET()
                                        .uri(URI.create(it.getUrl()))
                                        .header("accept", "application/json");
                                if (!"skip".equals(token)) {
                                    request.header("authorization", "Bearer " + token);
                                }
                                return withRateLimiting(lowPool, client, jsonb, request.build(), u -> u.setContributions(it.getContributions()))
                                        .thenAccept(u -> details.put(u.getId(), u));
                            })
                            .toArray(CompletableFuture<?>[]::new))
                            .get();
                } while (details.size() < all.size());
            }

            final Path outputPath = Path.of(output);
            if (outputPath.getParent() != null) {
                Files.createDirectory(outputPath.getParent());
            }

            // todo: add format in options and import fusion-handlerbars to enable to provide a template
            Files.writeString(outputPath, jsonb.toJson(details.values().stream()
                    .sorted(comparing(User::getId))
                    .toList()));
        } catch (final RuntimeException re) {
            throw re;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        } finally {
            lowPool.shutdownNow();
            try {
                lowPool.awaitTermination(1, TimeUnit.MINUTES);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (AutoCloseable.class.isInstance(client)) { // java 21 compat
                try {
                    AutoCloseable.class.cast(client).close();
                } catch (final Exception e) {
                    // no-op
                }
            }
        }
    }

    private CompletableFuture<User> withRateLimiting(final ScheduledExecutorService scheduler,
                                                     final HttpClient client,
                                                     final Jsonb jsonb,
                                                     final HttpRequest httpRequest,
                                                     final Consumer<User> callback) {
        final CompletableFuture<User> result = new CompletableFuture<>();
        try {
            client
                    .sendAsync(httpRequest, ofString())
                    .thenAccept(res -> {
                        switch (res.statusCode()) {
                            case 429:
                                final long delay = findDelay(res);
                                scheduler.schedule(
                                        () -> withRateLimiting(scheduler, client, jsonb, httpRequest, callback)
                                                .whenComplete((ok, ko) -> {
                                                    if (ko != null) {
                                                        result.completeExceptionally(ko);
                                                    } else {
                                                        result.complete(ok);
                                                    }
                                                }),
                                        delay, MILLISECONDS);
                                break;
                            case 200:
                                final User user = jsonb.fromJson(res.body(), User.class);
                                callback.accept(user);
                                result.complete(user);
                                break;
                            default:
                                result.completeExceptionally(new IllegalStateException("Invalid response: HTTP " + res.statusCode() + "\n" + res.body()));
                        }
                    })
                    .exceptionally(e -> {
                        result.completeExceptionally(e);
                        throw new IllegalStateException(e);
                    });
        } catch (final RuntimeException re) {
            result.completeExceptionally(re);
        }
        return result;
    }

    private boolean pauseOnRateLimit(final HttpResponse<String> res) throws InterruptedException {
        if (res.statusCode() != 429) {
            return false;
        }
        final long delay = findDelay(res);
        Thread.sleep(delay);
        return true;
    }

    private long findDelay(final HttpResponse<String> res) {
        final Instant resetTime = res.headers().firstValue("x-ratelimit-reset")
                .map(it -> Instant.ofEpochSecond(Long.parseLong(it)))
                // unlikely
                .orElseGet(() -> Instant.now().plus(1, MINUTES));
        return Duration.between(Instant.now(), resetTime).toMillis() + 1_000;
    }

    @Data
    public static class Contributor {
        private String login;
        private long id;
        private int contributions;
        private String url;
    }

    @Data
    public static class User {
        private String login;
        private String name;
        private long id;
        private String company;
        private String blog;
        private String location;
        private String email;
        private String bio;

        // not in user model but easier for the goal of this class
        private int contributions;

        @JsonbProperty("twitter_username")
        private String twitterUsername;

        @JsonbProperty("avatar_url")
        private String avatarUrl;

        @JsonbProperty("html_url")
        private String htmlUrl;
    }
}
