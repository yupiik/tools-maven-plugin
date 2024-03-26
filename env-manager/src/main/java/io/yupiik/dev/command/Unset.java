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

import io.yupiik.dev.shared.Os;
import io.yupiik.dev.shared.RcService;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.util.List;
import java.util.Properties;
import java.util.stream.Collector;

import static java.util.Optional.ofNullable;

@Command(name = "unset", description = "Enable to unset manually forced environment/versions with `env` command. " +
        "As `env`, it is used with `eval` (`eval $(yem unset --tools java)` for example). " +
        "It also uses `YEM_ORIGINAL_PATH` environment variable to restore original path if `--all` is set.")
public class Unset implements Runnable {
    private final Conf conf;
    private final RcService rc;
    private final Os os;

    public Unset(final Conf conf, final Os os, final RcService rc) {
        this.conf = conf;
        this.os = os;
        this.rc = rc;
    }

    @Override
    public void run() {
        final var hasTerm = os.isUnixLikeTerm();
        final var windows = os.isWindows() && hasTerm /* if not null behave as bash */;
        final var export = windows ? "set " : "export ";
        final var unsetPrefix = windows ? "set " : "unset ";
        final var unsetSuffix = windows ? "=" : "";
        final var pathName = windows ? "Path" : "PATH";
        final var quote = windows ? "" : "\"";

        if (conf.all()) { // reset path
            ofNullable(System.getenv("YEM_ORIGINAL_PATH"))
                    .filter(it -> !"skip".equals(it))
                    .ifPresent(value -> {
                        System.out.println(unsetPrefix + "YEM_ORIGINAL_PATH" + unsetSuffix + ";");
                        System.out.println(export + pathName + "=" + quote + value + quote + ";");
                    });
        }

        final List<String> variables;
        if (conf.all()) {
            variables = System.getenv().keySet().stream()
                    .filter(rc::isOverriddenEnvVar)
                    .toList();
        } else if (conf.tools() != null && !conf.tools().isEmpty()) {
            final var props = conf.tools().stream()
                    .collect(Collector.of(
                            Properties::new,
                            (p, i) -> p.setProperty(i + ".version", "" /* not used there */),
                            (a, b) -> {
                                a.putAll(b);
                                return a;
                            }));
            final var toolProperties = rc.toToolProperties(props, 0);
            variables = toolProperties.stream()
                    .map(rc::toOverriddenEnvVar)
                    .filter(p -> System.getenv(p) != null)
                    .toList();
        } else {
            return;
        }

        variables.forEach(name -> System.out.println(unsetPrefix + name + unsetSuffix + ";"));
    }

    @RootConfiguration("unset")
    public record Conf(
            @Property(documentation = "Will unset all overridden versions.", defaultValue = "false") boolean all,
            @Property(documentation = "List of tools to reset overridden environment.", defaultValue = "java.util.List.of()") List<String> tools) {
    }
}
