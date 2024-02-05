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
package io.yupiik.dev.provider;

import io.yupiik.dev.provider.central.CentralBaseProvider;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.provider.sdkman.SdkManClient;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.Locale.ROOT;
import static java.util.Map.entry;
import static java.util.stream.Collectors.joining;

@ApplicationScoped
public class ProviderRegistry {
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

    public Map.Entry<Provider, Version> findByToolVersionAndProvider(final String tool, final String version, final String provider,
                                                                     final boolean relaxed) {
        return tryFindByToolVersionAndProvider(tool, version, provider, relaxed, new Cache(new IdentityHashMap<>(), new IdentityHashMap<>()))
                .orElseThrow(() -> new IllegalArgumentException("No provider for tool '" + tool + "' in version '" + version + "', available tools:\n" +
                        providers().stream()
                                .flatMap(it -> it.listTools().stream()
                                        .map(Candidate::tool)
                                        .map(t -> "- " + t + "\n" + it.listVersions(t).stream()
                                                .map(v -> "-- " + v.identifier() + " (" + v.version() + ")")
                                                .collect(joining("\n"))))
                                .sorted()
                                .collect(joining("\n"))));
    }

    public Optional<Map.Entry<Provider, Version>> tryFindByToolVersionAndProvider(
            final String tool, final String version, final String provider, final boolean relaxed,
            final Cache cache) {
        return providers().stream()
                .filter(it -> provider == null ||
                        // enable "--install-provider zulu" for example
                        Objects.equals(provider, it.name()) ||
                        it.getClass().getSimpleName().toLowerCase(ROOT).startsWith(provider.toLowerCase(ROOT)))
                .map(it -> {
                    final var candidates = it.listTools();
                    if (candidates.stream().anyMatch(t -> tool.equals(t.tool()))) {
                        return cache.local.computeIfAbsent(it, Provider::listLocal)
                                .entrySet().stream()
                                .filter(e -> Objects.equals(e.getKey().tool(), tool))
                                .flatMap(e -> e.getValue().stream()
                                        .filter(v -> matchVersion(v, version, relaxed))
                                        .findFirst()
                                        .stream())
                                .map(v -> entry(it, v))
                                .findFirst()
                                .map(Optional::of)
                                .orElseGet(() -> {
                                    final var versions = cache.versions
                                            .computeIfAbsent(it, p -> new HashMap<>())
                                            .computeIfAbsent(tool, it::listVersions);
                                    return versions.stream()
                                            .filter(v -> matchVersion(v, version, relaxed))
                                            .findFirst()
                                            .map(v -> entry(it, v));
                                });
                    }
                    return Optional.<Map.Entry<Provider, Version>>empty();
                })
                .flatMap(Optional::stream)
                .findFirst();
    }

    private boolean matchVersion(final Version v, final String version, final boolean relaxed) {
        return version.equals(v.version()) || version.equals(v.identifier()) ||
                (relaxed && v.version().startsWith(version));
    }

    public record Cache(Map<Provider, Map<Candidate, List<Version>>> local,
                        Map<Provider, Map<String, List<Version>>> versions) {
    }
}
