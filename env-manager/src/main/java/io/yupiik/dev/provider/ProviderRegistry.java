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
package io.yupiik.dev.provider;

import io.yupiik.dev.provider.central.CentralBaseProvider;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.provider.sdkman.SdkManClient;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.Optional.empty;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.FINEST;
import static java.util.stream.Collectors.joining;

@ApplicationScoped
public class ProviderRegistry {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final List<Provider> providers;

    public ProviderRegistry(final List<Provider> providers) {
        this.providers = providers == null ? null : providers.stream()
                .sorted((a, b) -> { // mainly push sdkman last since it does more remoting than othes
                    if (a == b) {
                        return 0;
                    }
                    if (a instanceof CentralBaseProvider p1 && b instanceof CentralBaseProvider p2) {
                        return p1.gav().compareTo(p2.gav());
                    }
                    if (a instanceof SdkManClient && !(b instanceof SdkManClient)) {
                        return 1;
                    }
                    if (b instanceof SdkManClient && !(a instanceof SdkManClient)) {
                        return -1;
                    }
                    return a.getClass().getName().compareTo(b.getClass().getName());
                })
                .toList();
    }

    public List<Provider> providers() {
        return providers;
    }

    public CompletionStage<MatchedVersion> findByToolVersionAndProvider(final String tool, final String version, final String provider,
                                                                        final boolean relaxed, final boolean canBeRemote) {
        return tryFindByToolVersionAndProvider(tool, version, provider, relaxed, canBeRemote, new Cache(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()))
                .thenApply(found -> found.orElseThrow(() -> new IllegalArgumentException(
                        "No provider for tool " + tool + "@" + version + "', available tools:\n" +
                                providers().stream()
                                        .flatMap(it -> { // here we accept to block since normally we cached everything
                                            try {
                                                return it.listTools().toCompletableFuture().get().stream()
                                                        .map(Candidate::tool)
                                                        .map(t -> {
                                                            try {
                                                                return "- " + t + "\n" + it.listVersions(t)
                                                                        .toCompletableFuture().get().stream()
                                                                        .map(v -> "-- " + v.identifier() + " (" + v.version() + ")")
                                                                        .collect(joining("\n"));
                                                            } catch (final InterruptedException e) {
                                                                Thread.currentThread().interrupt();
                                                                throw new IllegalStateException(e);
                                                            } catch (final ExecutionException e) {
                                                                throw new IllegalStateException(e.getCause());
                                                            }
                                                        });
                                            } catch (final InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                throw new IllegalStateException(e);
                                            } catch (final ExecutionException e) {
                                                throw new IllegalStateException(e.getCause());
                                            }
                                        })
                                        .sorted()
                                        .collect(joining("\n")))));
    }

    public CompletionStage<Optional<MatchedVersion>> tryFindByToolVersionAndProvider(
            final String tool, final String version, final String provider, final boolean relaxed,
            final boolean testRemote, final Cache cache) {
        final var result = new CompletableFuture<Optional<MatchedVersion>>();
        final var promises = providers().stream()
                .filter(it -> provider == null ||
                        // enable "--install-provider zulu" for example
                        Objects.equals(provider, it.name()) ||
                        it.getClass().getSimpleName().toLowerCase(ROOT).startsWith(provider.toLowerCase(ROOT)))
                .map(it -> it.listTools().thenCompose(candidates -> {
                    if (candidates.stream().anyMatch(t -> tool.equals(t.tool()))) {
                        final var candidateListMap = cache.local.get(it);
                        return (candidateListMap != null ?
                                completedFuture(candidateListMap) :
                                it.listLocal().thenApply(res -> {
                                    cache.local.putIfAbsent(it, res);
                                    return res;
                                })).thenCompose(list -> findMatchingVersion(tool, version, relaxed, it, list)
                                .findFirst()
                                .map(Optional::of)
                                .map(CompletableFuture::completedFuture)
                                .orElseGet(() -> testRemote ?
                                        findRemoteVersions(tool, cache, it)
                                                .thenApply(all -> all.stream()
                                                        .filter(v -> matchVersion(v, version, relaxed))
                                                        .findFirst()
                                                        .map(v -> new MatchedVersion(
                                                                it,
                                                                candidates.stream()
                                                                        .filter(c -> Objects.equals(c.tool(), tool))
                                                                        .findFirst()
                                                                        .orElse(null),
                                                                v)))
                                                .toCompletableFuture() :
                                        completedFuture(empty())));
                    }
                    return completedFuture(Optional.empty());
                }))
                .toList();

        // don't leak a promise when exiting
        var guard = completedFuture(null);
        for (final var it : promises) {
            guard = guard.thenCompose(ok -> it
                    .thenAccept(opt -> {
                        if (opt.isPresent() && !result.isDone()) {
                            result.complete(opt);
                        }
                    })
                    .exceptionally(e -> {
                        logger.log(FINEST, e, e::getMessage);
                        return null;
                    }).thenApply(ignored -> null));
        }

        return guard.thenCompose(allDone -> {
            if (!result.isDone()) {
                result.complete(empty());
            }
            return result;
        });
    }

    private CompletionStage<List<Version>> findRemoteVersions(final String tool, final Cache cache, final Provider provider) {
        final var providerCache = cache.versions
                .computeIfAbsent(provider, p -> new ConcurrentHashMap<>());
        final var cached = providerCache.get(tool);
        return (cached != null ?
                completedFuture(cached) :
                provider.listVersions(tool).thenApply(res -> {
                    providerCache.putIfAbsent(tool, res);
                    return res;
                }));
    }

    private Stream<MatchedVersion> findMatchingVersion(final String tool, final String version,
                                                       final boolean relaxed, final Provider provider,
                                                       final Map<Candidate, List<Version>> versions) {
        return versions.entrySet().stream()
                .filter(e -> Objects.equals(e.getKey().tool(), tool))
                .flatMap(e -> e.getValue().stream()
                        .filter(v -> matchVersion(v, version, relaxed))
                        .findFirst()
                        .stream()
                        .map(v -> new MatchedVersion(provider, e.getKey(), v)));
    }

    private boolean matchVersion(final Version v, final String version, final boolean relaxed) {
        return version.equals(v.version()) || version.equals(v.identifier()) ||
                (relaxed && v.version().startsWith(version));
    }

    public record Cache(Map<Provider, Map<Candidate, List<Version>>> local,
                        Map<Provider, Map<String, List<Version>>> versions) {
    }

    public record MatchedVersion(Provider provider, Candidate candidate, Version version) {
    }
}
