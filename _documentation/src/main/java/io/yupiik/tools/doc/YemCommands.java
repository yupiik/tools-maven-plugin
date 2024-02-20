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
package io.yupiik.tools.doc;

import io.yupiik.dev.YemModule;
import io.yupiik.fusion.cli.CliAwaiter;
import io.yupiik.fusion.cli.internal.CliModule;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.internal.framework.JsonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class YemCommands implements Runnable {
    private final Path sourceBase;

    public YemCommands(final Path sourceBase) {
        this.sourceBase = sourceBase;
    }

    @Override
    public void run() { // cheap way to generate the help, todo: make it sexier and contribute it to fusion-documentation?
        try (final var container = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, Args.class, () -> new Args(List.of())))
                .register(new JsonModule(), new YemModule(), new CliModule())
                .start();
             final var awaiter = container.lookup(CliAwaiter.class)) {
            awaiter.instance().await();
            throw new IllegalStateException("Should have failed since CliAwaiter didn't find any command");
        } catch (final IllegalArgumentException iae) {
            try {
                Files.writeString(
                        Files.createDirectories(sourceBase.resolve("content/_partials/generated")).resolve("commands.yem.adoc"),
                        iae.getMessage().substring(iae.getMessage().indexOf(':') + 1).strip()
                                // structure next lines
                                .replace("  Parameters:", "** Parameters:")
                                .replace("  --", "*** --"));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
