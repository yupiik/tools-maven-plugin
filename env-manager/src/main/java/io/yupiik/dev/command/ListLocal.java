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
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.Objects;
import java.util.logging.Logger;

import static java.util.stream.Collectors.joining;

@Command(name = "list-local", description = "List local available distributions.")
public class ListLocal implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final ProviderRegistry registry;
    private final Conf conf;

    public ListLocal(final Conf conf,
                     final ProviderRegistry registry) {
        this.conf = conf;
        this.registry = registry;
    }

    @Override
    public void run() {
        final var collect = registry.providers().stream()
                .flatMap(p -> p.listLocal().entrySet().stream()
                        .filter(it -> (conf.tool() == null || Objects.equals(conf.tool(), it.getKey().tool())) &&
                                !it.getValue().isEmpty())
                        .map(e -> "- [" + p.name() + "] " + e.getKey().tool() + ":" + (e.getValue().isEmpty() ?
                                " no version" :
                                e.getValue().stream()
                                        .sorted((a, b) -> -a.compareTo(b)) // more recent first
                                        .map(v -> "-- " + v.version())
                                        .collect(joining("\n", "\n", "\n")))))
                .sorted()
                .collect(joining("\n"));
        logger.info(() -> collect.isBlank() ? "No distribution available." : collect);
    }

    @RootConfiguration("list-local")
    public record Conf(@Property(documentation = "Tool to filter.") String tool) {
    }
}
