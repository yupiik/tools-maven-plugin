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
package io.yupiik.dev.command;

import io.yupiik.dev.provider.ProviderRegistry;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.Map;
import java.util.logging.Logger;

import static java.util.function.Function.identity;
import static java.util.logging.Level.FINEST;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Command(name = "list", description = "List remote (available) distributions.")
public class List implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final ProviderRegistry registry;

    public List(final Conf conf,
                final ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run() {
        final var collect = registry.providers().stream()
                .map(p -> {
                    try {
                        return p.listTools().stream()
                                .collect(toMap(identity(), tool -> p.listVersions(tool.tool())));
                    } catch (final RuntimeException re) {
                        logger.log(FINEST, re, re::getMessage);
                        return Map.<Candidate, java.util.List<Version>>of();
                    }
                })
                .flatMap(m -> m.entrySet().stream())
                .map(e -> "- " + e.getKey().tool() + ":" + (e.getValue().isEmpty() ?
                        " no version available" :
                        e.getValue().stream()
                                .sorted((a, b) -> -a.compareTo(b))
                                .map(v -> "-- " + v.version())
                                .collect(joining("\n", "\n", "\n"))))
                .sorted()
                .collect(joining("\n"));
        logger.info(() -> collect.isBlank() ? "No distribution available." : collect);
    }

    @RootConfiguration("list")
    public record Conf(/* no option yet */) {
    }
}
