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

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.ProviderRegistry;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.logging.Logger;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;

@Command(name = "install", description = "Install a distribution.")
public class Install implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Conf conf;
    private final ProviderRegistry registry;

    public Install(final Conf conf,
                   final ProviderRegistry registry) {
        this.conf = conf;
        this.registry = registry;
    }

    @Override
    public void run() {
        final var providerAndVersion = registry.findByToolVersionAndProvider(conf.tool(), conf.version(), conf.provider(), conf.relaxed());
        final var result = providerAndVersion.getKey().install(conf.tool(), providerAndVersion.getValue().identifier(), new Provider.ProgressListener() {
            @Override
            public void onProcess(final String name, final double percent) {
                final int plain = (int) (10 * percent);
                System.out.printf("%s [%s]\r",
                        name,
                        (plain == 0 ? "" : IntStream.range(0, plain).mapToObj(i -> "X").collect(joining(""))) +
                                (plain == 10 ? "" : IntStream.range(0, 10 - plain).mapToObj(i -> "_").collect(joining(""))));
            }
        });
        logger.info(() -> "Installed " + conf.tool() + "@" + providerAndVersion.getValue().version() + " at '" + result + "'");
    }

    @RootConfiguration("install")
    public record Conf(
            @Property(documentation = "Should progress bar be skipped (can be useful on CI for example).", defaultValue = "System.getenv(\"CI\") != null") boolean skipProgress,
            @Property(documentation = "Tool to install.", required = true) String tool,
            @Property(documentation = "Version of `tool` to install.", required = true) String version,
            @Property(documentation = "Provider to use to install the version (if not t is deduced from the tool/version parameters).") String provider,
            @Property(documentation = "Should version be matched with a `startsWith` logic (ex: `install --install-tool java --install-relaxed true --install-version 21.`).", defaultValue = "false") boolean relaxed
    ) {
    }
}
