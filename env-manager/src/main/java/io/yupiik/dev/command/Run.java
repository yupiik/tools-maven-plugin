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
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.io.File.pathSeparator;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

@Command(name = "run", description = "Similar to `env` spirit but for aliases or execution in the shell context of the `.yemrc` file." +
        " Important: it is recommended to use `--` to separate yem args from the command: `yem run --rc .yemrc -- mvn clean package`." +
        " Note that the first arg after `--` will be tested against an alias in the `.yemrc` (or global one) so you can pre-define complex commands there." +
        " Ex: `build.alias = mvn package` in `.yemrc` will enable to run `yem run -- build` which will actually execute `mvn package` and if you configure maven version (`maven.version = 3.9.6` for example) the execution environment will use the right distribution.")
public class Run implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final Conf conf;
    private final RcService rc;
    private final Os os;
    private final Args args;

    public Run(final Conf conf, final RcService rc, final Os os, final Args args) {
        this.conf = conf;
        this.rc = rc;
        this.os = os;
        this.args = args;
    }

    @Override
    public void run() {
        final var tools = rc.loadPropertiesFrom(conf.rc(), conf.defaultRc());
        try {
            rc.match(tools.local(), tools.global()).thenAccept(resolved -> {
                final int idx = args.args().indexOf("--");
                final var command = new ArrayList<String>(8);
                if (idx > 0) {
                    command.addAll(args.args().subList(idx + 1, args.args().size()));
                } else {
                    command.addAll(args.args().subList(1, args.args().size()));
                }

                if (!command.isEmpty()) { // handle aliasing
                    final var aliasKey = command.get(0) + ".alias";
                    final var alias = tools.local().getProperty(aliasKey, tools.global().getProperty(aliasKey));
                    if (alias != null) {
                        command.remove(0);
                        command.addAll(0, parseArgs(alias));
                    }
                }

                final var process = new ProcessBuilder(command);
                process.inheritIO();

                final var environment = process.environment();
                setEnv(resolved, environment);

                final var path = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
                if (!command.isEmpty() && !path.isBlank()) {
                    final var exec = command.get(0);
                    if (Files.notExists(Path.of(exec))) {
                        final var paths = path.split(pathSeparator);
                        final var exts = os.isWindows() ?
                                Stream.concat(
                                                Stream.of(""),
                                                Stream.ofNullable(ofNullable(System.getenv("PathExt"))
                                                                .orElseGet(() -> System.getenv("PATHEXT")))
                                                        .flatMap(i -> Stream.of(i.split(";"))
                                                                .map(String::strip)
                                                                .filter(Predicate.not(String::isBlank))))
                                        .toList() :
                                List.of("");
                        Stream.of(paths)
                                .map(Path::of)
                                .filter(Files::exists)
                                .flatMap(d -> exts.stream()
                                        .map(e -> d.resolve(exec + e)))
                                .filter(Files::exists)
                                .findFirst()
                                .ifPresent(bin -> command.set(0, bin.toString()));
                    }
                }

                logger.finest(() -> "Resolved command: " + command);

                try {
                    final var processExec = process.start();
                    final int exitCode = processExec.waitFor();
                    logger.finest(() -> "Process exited with status=" + exitCode);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }).toCompletableFuture().get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private void setEnv(final List<RcService.MatchedPath> resolved, final Map<String, String> environment) {
        resolved.forEach(it -> {
            final var homeStr = it.path().toString();
            logger.finest(() -> "Setting '" + it.properties().envPathVarName() + "' to '" + homeStr + "'");
            environment.put(it.properties().envPathVarName(), homeStr);
        });
        if (resolved.stream().map(RcService.MatchedPath::properties).anyMatch(RcService.ToolProperties::addToPath)) {
            final var pathName = os.isWindows() ? "Path" : "PATH";
            final var path = resolved.stream()
                    .filter(r -> r.properties().addToPath())
                    .map(r -> rc.toBin(r.path()).toString())
                    .collect(joining(pathSeparator, "", pathSeparator)) +
                    ofNullable(System.getenv(pathName)).orElse("");
            logger.finest(() -> "Setting 'PATH' to '" + path + "'");
            environment.put(pathName, path);
        }
    }

    private List<String> parseArgs(final String alias) {
        final var out = new ArrayList<String>(4);
        final var builder = new StringBuilder(alias.length());
        char await = 0;
        boolean escaped = false;
        for (final char c : alias.toCharArray()) {
            if (escaped) {
                if (!(c == '"' || c == '\'')) {
                    builder.append('\\');
                }
                builder.append(c);
                escaped = false;
            } else if (await == 0 && c == ' ') {
                if (!builder.isEmpty()) {
                    out.add(builder.toString().strip());
                    builder.setLength(0);
                }
            } else if (c == await) {
                out.add(builder.toString().strip());
                builder.setLength(0);
                await = 0;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"' && builder.isEmpty()) {
                await = '"';
            } else if (c == '\'' && builder.isEmpty()) {
                await = '\'';
            } else {
                builder.append(c);
            }
        }
        if (!builder.isEmpty()) {
            out.add(builder.toString().strip());
        }
        return out;
    }

    @RootConfiguration("run")
    public record Conf(
            @Property(documentation = "Should `~/.yupiik/yem/rc` be ignored or not. If present it defines default versions and uses the same syntax than `yemrc`.", defaultValue = "System.getProperty(\"user.home\") + \"/.yupiik/yem/rc\"") String defaultRc,
            @Property(documentation = "Env file location to read to generate the script. Note that `auto` will try to pick `.yemrc` and if not there will use `.sdkmanrc` if present.", defaultValue = "\".yemrc\"") String rc) {
    }
}
