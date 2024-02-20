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
import io.yupiik.dev.shared.MessageHelper;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

@Command(name = "resolve", description = "Resolve a distribution.")
public class Resolve implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Conf conf;
    private final ProviderRegistry registry;
    private final MessageHelper messageHelper;

    public Resolve(final Conf conf,
                   final ProviderRegistry registry,
                   final MessageHelper messageHelper) {
        this.conf = conf;
        this.registry = registry;
        this.messageHelper = messageHelper;
    }

    @Override
    public void run() {
        try {
            registry.findByToolVersionAndProvider(conf.tool(), conf.version(), conf.provider(), false, false)
                    .thenAccept(matched -> {
                        final var resolved = matched.provider().resolve(conf.tool(), matched.version().identifier())
                                .orElseThrow(() -> new IllegalArgumentException("No matching instance for " + conf.tool() + "@" + conf.version() + ", ensure to install it before resolving it."));
                        logger.info(() -> "Resolved " + messageHelper.formatToolNameAndVersion(matched.candidate(), conf.tool(), matched.version().version()) + ": '" + resolved + "'");
                    })
                    .toCompletableFuture()
                    .get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }

    }

    @RootConfiguration("resolve")
    public record Conf(
            @Property(documentation = "Tool to resolve.", required = true) String tool,
            @Property(documentation = "Version of `tool` to resolve.", required = true) String version,
            @Property(documentation = "Provider to use to resolve the version (if not t is deduced from the tool/version parameters).") String provider,
            @Property(documentation = "Should version be matched with a `startsWith` logic (ex: `resolve --resolve-tool java --resolve-relaxed true --resolve-version 21.`).", defaultValue = "false") boolean relaxed
    ) {
    }
}
