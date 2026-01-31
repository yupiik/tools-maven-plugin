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
package io.yupiik.tools.generator.generic.contributor.impl.gitlab;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.tools.generator.generic.contributor.impl.SharedHttpClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class GitlabService {
    private static final int MAX_RETRIES = 5;

    private final Logger logger = Logger.getLogger(getClass().getName());

    private final SharedHttpClient http;
    private final JsonMapper json;

    private final ConcurrentHashMap<ProjectKey, CompletionStage<Map<String, Object>>> projects = new ConcurrentHashMap<>();

    public GitlabService(final SharedHttpClient http, final JsonMapper jsonMapper) {
        this.http = http;
        this.json = jsonMapper;
    }

    public CompletionStage<Map<String, Object>> getOrFetchProjects(final boolean cache,
                                                                   final GitlabConfiguration gitlab,
                                                                   final List<String> topics,
                                                                   final Executor executor) {
        return cache ?
                projects.computeIfAbsent(new ProjectKey(gitlab, topics), k -> fetchProjects(k.gitlab(), k.topics(), executor)) :
                fetchProjects(gitlab, topics, executor);
    }

    private CompletionStage<Map<String, Object>> fetchProjects(
            final GitlabConfiguration gitlab,
            final List<String> topics,
            final Executor executor) {
        final var topicQueryParam = topics == null || topics.isEmpty() ?
                "" :
                topics.stream()
                        .map(it -> URLEncoder.encode(it, UTF_8))
                        .collect(joining(",", "&topic=", ""));
        return fetch(
                gitlab, executor,
                URI.create(gitlab.baseUrl()).resolve("/api/v4/projects?per_page=100" + topicQueryParam), false, true, 0,
                (p1, p2) -> Stream.concat(((Collection<?>) p1).stream(), ((Collection<?>) p2).stream()).toList())
                .thenApplyAsync(
                        // it is a list so comply to the signature this way
                        it -> Map.of("projects", it),
                        executor);
    }

    public CompletionStage<Map<String, Object>> fetchCommits(final GitlabConfiguration gitlab, final String projectId,
                                                             final List<String> branches, final boolean statistics,
                                                             final String since, final Executor executor) {
        final var query = new StringBuilder("with_stats=").append(statistics);
        if (since != null) {
            query
                    .append("&since=")
                    .append(
                            since.startsWith("now-") ?
                                    OffsetDateTime
                                            .now(ZoneId.of("UTC"))
                                            .minus(Period.parse(since.substring("now-".length())))
                                            .withNano(0)
                                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) :
                                    since);
        }

        if (branches.contains("all")) {
            return fetchCommits(gitlab, projectId, query.toString(), executor)
                    .thenApplyAsync(it -> Map.of(projectId, it), executor);
        }

        final var all = branches.stream()
                .map(it -> fetchCommits(
                        gitlab, projectId,
                        query + "&ref_name=" + URLEncoder.encode(it, UTF_8),
                        executor)
                        .thenApplyAsync(c -> entry(it, c))
                        .toCompletableFuture())
                .toList();
        return allOf(all.toArray(CompletableFuture<?>[]::new))
                .thenApplyAsync(
                        r -> Map.of(projectId, all.stream()
                                .map(e -> e.getNow(null))
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue))),
                        executor);
    }

    private CompletionStage<Object> fetchCommits(
            final GitlabConfiguration gitlab,
            final String projectId, final String query,
            final Executor executor) {
        return fetch(
                gitlab, executor,
                URI.create(gitlab.baseUrl()).resolve("/api/v4/projects/" + projectId + "/repository/commits?" + query),
                false, true, 0, this::flatten);
    }

    @SuppressWarnings("unchecked")
    public CompletionStage<Map<String, Object>> fetchEnvironment(
            final GitlabConfiguration gitlab,
            final String projectId, final boolean details,
            final Executor executor) {
        return fetch(
                gitlab, executor,
                URI.create(gitlab.baseUrl()).resolve("/api/v4/projects/" + projectId + "/environments"), false, true, 0,
                this::flatten)
                .thenComposeAsync(
                        it -> {
                            if (!details) {
                                return completedFuture(Map.of(projectId, it));
                            }
                            final var all = ((List<Map<String, Object>>) it).stream()
                                    .map(e -> fetch(
                                            gitlab, executor,
                                            URI.create(gitlab.baseUrl()).resolve("/api/v4/projects/" + projectId + "/environments/" + e.getOrDefault("id", "")), false, true, 0,
                                            (a, b) -> a)
                                            .toCompletableFuture())
                                    .toList();
                            return allOf(all.toArray(CompletableFuture<?>[]::new))
                                    .thenApplyAsync(
                                            r -> Map.of(projectId, all.stream()
                                                    .map(e -> e.getNow(null))
                                                    .toList()),
                                            executor);
                        },
                        executor);
    }

    private CompletionStage<Object> fetch(final GitlabConfiguration gitlab,
                                          final Executor executor,
                                          final URI uri,
                                          final boolean ignoreOnFailure,
                                          final boolean followPagination,
                                          final int execution,
                                          final BiFunction<Object, Object, Object> merger) {
        final var builder = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .timeout(Duration.parse(gitlab.timeout()))
                .GET();
        if (gitlab.headers() != null && !gitlab.headers().isEmpty()) {
            gitlab.headers().forEach(builder::header);
        }
        final var req = builder.build();
        logger.info(() -> "GET " + uri);
        try {
            final var promise = http
                    .getOrCreate(executor)
                    .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenComposeAsync(it -> {
                        if (it.statusCode() == 429) {
                            return it
                                    .headers()
                                    .firstValue("ratelimit-reset")
                                    .map(reset -> {
                                        try {
                                            final var l = Long.parseLong(reset);
                                            final var now = Clock.systemUTC().instant().getEpochSecond();
                                            final var wait = l - now;
                                            if (wait > 0 && execution < MAX_RETRIES && executor instanceof ScheduledExecutorService ses) {
                                                logger.warning(() -> it + " will be retried in " + wait + " ms");
                                                return rescheduleAfter(
                                                        ses, wait,
                                                        () -> fetch(gitlab, executor, uri, ignoreOnFailure, followPagination, execution + 1, merger));
                                            }
                                        } catch (final NumberFormatException nfe) {
                                            logger.warning(() -> "Can't parse ratelimit-reset value, got: '" + reset + "'");
                                        }
                                        return null;
                                    })
                                    .orElseThrow(() -> new IllegalStateException("HTTP 429 and no reset header: " + it));
                        }

                        if (it.statusCode() != 200) {
                            if (ignoreOnFailure) {
                                logger.warning(() -> "Ignoring " + req + " (" + it + ")");
                                return completedFuture(List.of());
                            }
                            throw new IllegalStateException("Invalid response: " + it);
                        }

                        final var page = json.fromString(Object.class, it.body());
                        if (!followPagination) {
                            return completedFuture(page);
                        }
                        return it
                                .headers()
                                .firstValue("link")
                                .flatMap(l -> Stream.of(l.split(", "))
                                        .map(String::strip)
                                        .filter(link -> link.contains("rel=\"next\""))
                                        .findFirst()
                                        .map(link -> {
                                            final var from = link.indexOf('<');
                                            final var end = link.indexOf('>', from + 1);
                                            if (end < 0) {
                                                return null;
                                            }
                                            return link.substring(from + 1, end).strip();
                                        }))
                                .map(next -> fetch(gitlab, executor, URI.create(next), ignoreOnFailure, followPagination, 0, merger)
                                        .thenApply(newPage -> merger.apply(page, newPage)))
                                .orElseGet(() -> completedFuture(page));
                    }, executor);
            if (ignoreOnFailure) {
                return promise.exceptionally(e -> {
                    logger.log(Level.WARNING, e, () -> "Ignoring " + req);
                    return List.of();
                });
            }
            return promise;
        } catch (final RuntimeException re) {
            if (ignoreOnFailure) {
                logger.log(Level.WARNING, re, () -> "Ignoring " + req);
                return completedFuture(List.of());
            }
            throw new IllegalStateException("Failure calling " + req, re);
        }
    }

    private CompletionStage<Object> rescheduleAfter(final ScheduledExecutorService ses, final long wait,
                                                    final Supplier<CompletionStage<Object>> provider) {
        final var res = new CompletableFuture<>();
        try {
            ses.schedule(() -> {
                try {
                    provider
                            .get()
                            .thenApplyAsync(res::complete)
                            .exceptionally(res::completeExceptionally);
                } catch (final RuntimeException | Error e) {
                    res.completeExceptionally(e);
                }
            }, wait, MILLISECONDS);
        } catch (final RuntimeException | Error e) {
            res.completeExceptionally(e);
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> flatten(final Object p1, final Object p2) {
        return Stream.concat(((Collection<Map<String, Object>>) p1).stream(), ((Collection<Map<String, Object>>) p2).stream()).toList();
    }

    private record ProjectKey(GitlabConfiguration gitlab, List<String> topics) {
    }
}
