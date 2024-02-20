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
package io.yupiik.dev.command;

import io.yupiik.dev.provider.ProviderRegistry;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.FINEST;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Command(name = "list", description = "List remote (available) distributions.")
public class List implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final Conf conf;
    private final ProviderRegistry registry;

    public List(final Conf conf,
                final ProviderRegistry registry) {
        this.conf = conf;
        this.registry = registry;
    }

    @Override
    public void run() {
        final var toolFilter = toFilter(conf.tools());
        final var providerFilter = toFilter(conf.providers());
        final var promises = registry.providers().stream()
                .filter(p -> providerFilter.test(p.name()) || providerFilter.test(p.getClass().getSimpleName().toLowerCase(ROOT)))
                .map(p -> {
                    try {
                        return p.listTools()
                                .thenCompose(tools -> {
                                    final var filteredTools = tools.stream()
                                            .filter(t -> toolFilter.test(t.tool()) || toolFilter.test(t.name()))
                                            .toList();

                                    final var versions = filteredTools.stream()
                                            .map(v -> p.listVersions(v.tool()).toCompletableFuture())
                                            .toList();

                                    final var versionsIt = versions.iterator();
                                    return allOf(versions.toArray(new CompletableFuture<?>[0]))
                                            .thenApply(ready -> filteredTools.stream()
                                                    .collect(toMap(c -> "[" + p.name() + "] " + c.tool(), tool -> versionsIt.next().getNow(java.util.List.of())))
                                                    .entrySet().stream()
                                                    .filter(Predicate.not(m -> m.getValue().isEmpty()))
                                                    .map(e -> "- " + e.getKey() + ":" + e.getValue().stream()
                                                            .sorted((a, b) -> -a.compareTo(b))
                                                            .map(v -> "-- " + v.version() + (!Objects.equals(v.version(), v.identifier()) ? " (" + v.identifier() + ")" : ""))
                                                            .distinct()
                                                            .collect(joining("\n", "\n", "\n")))
                                                    .toList());
                                })
                                .exceptionally(e -> {
                                    logger.log(FINEST, e, e::getMessage);
                                    return java.util.List.of();
                                })
                                .toCompletableFuture();
                    } catch (final RuntimeException re) {
                        logger.log(FINEST, re, re::getMessage);
                        return completedFuture(java.util.List.<String>of());
                    }
                })
                .toList();
        try {
            allOf(promises.toArray(new CompletableFuture<?>[0]))
                    .thenApply(ok -> promises.stream()
                            .flatMap(p -> p.getNow(java.util.List.of()).stream())
                            .sorted()
                            .collect(joining("\n")))
                    .thenAccept(collect -> logger.info(() -> collect.isBlank() ? "No distribution available." : collect))
                    .get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private Predicate<String> toFilter(final String values) {
        return values == null || values.isBlank() ?
                t -> true :
                Stream.of(values.split(","))
                        .map(String::strip)
                        .filter(Predicate.not(String::isBlank))
                        .map(t -> (Predicate<String>) t::equals)
                        .reduce(t -> false, Predicate::or);
    }

    @RootConfiguration("list")
    public record Conf(@Property(documentation = "List of tools to list (comma separated).") String tools,
                       @Property(documentation = "List of providers to use (comma separated).") String providers) {
    }
}
