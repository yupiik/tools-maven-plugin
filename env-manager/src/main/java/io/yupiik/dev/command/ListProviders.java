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
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.logging.Logger;

import static java.util.stream.Collectors.joining;

@Command(name = "list-providers", description = "List available providers.")
public class ListProviders implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final ProviderRegistry registry;

    public ListProviders(final Conf conf,
                         final ProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run() {
        final var collect = registry.providers().stream()
                .map(p -> "- " + p.name())
                .sorted()
                .collect(joining("\n"));
        logger.info(() -> collect.isBlank() ? "No provider available." : collect);
    }

    @RootConfiguration("list-providers")
    public record Conf(/* no option yet */) {
    }
}
