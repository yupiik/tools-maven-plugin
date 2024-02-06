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

import io.yupiik.dev.shared.Os;
import io.yupiik.dev.shared.RcService;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static java.io.File.pathSeparator;
import static java.util.Optional.ofNullable;
import static java.util.logging.Level.FINE;
import static java.util.stream.Collectors.joining;

@Command(name = "env", description = "Creates a script you can eval in a shell to prepare the environment from a file. Often used as `eval $(yem env--env-rc .yemrc)`")
public class Env implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Conf conf;
    private final RcService rc;
    private final Os os;

    public Env(final Conf conf, final Os os, final RcService rc) {
        this.conf = conf;
        this.os = os;
        this.rc = rc;
    }

    @Override
    public void run() {
        final var windows = os.isWindows();
        final var export = windows ? "set " : "export ";
        final var comment = windows ? "%% " : "# ";
        final var pathName = windows ? "Path" : "PATH";
        final var pathVar = windows ? "%" + pathName + "%" : ("$" + pathName);

        resetOriginalPath(export, pathName);

        final var tools = rc.loadPropertiesFrom(conf.rc(), conf.defaultRc());
        if (tools == null || tools.isEmpty()) { // nothing to do
            return;
        }

        final var logger = Logger.getLogger("io.yupiik.dev");
        final var useParentHandlers = logger.getUseParentHandlers();
        final var messages = new ArrayList<String>();
        final var tempHandler = new Handler() { // forward all standard messages to stderr and at debug level to avoid to break default behavior
            @Override
            public void publish(final LogRecord record) {
                // capture to forward messages in the shell when init is done (thanks eval call)
                if (logger.isLoggable(record.getLevel())) {
                    messages.add(record.getMessage());
                }

                // enable to log at fine level for debug purposes
                record.setLevel(FINE);
                if (useParentHandlers) {
                    logger.getParent().log(record);
                }
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() throws SecurityException {
                flush();
            }
        };
        logger.setUseParentHandlers(false);
        logger.addHandler(tempHandler);

        try {
            rc.toToolProperties(tools).thenAccept(resolved -> {
                        final var toolVars = resolved.entrySet().stream()
                                .map(e -> export + e.getKey().envVarName() + "=\"" + quoted(e.getValue()) + "\";")
                                .sorted()
                                .collect(joining("\n", "", "\n"));

                        final var pathBase = ofNullable(System.getenv("YEM_ORIGINAL_PATH"))
                                .or(() -> ofNullable(System.getenv(pathName)))
                                .orElse("");
                        final var pathVars = resolved.keySet().stream().anyMatch(RcService.ToolProperties::addToPath) ?
                                export + "YEM_ORIGINAL_PATH=\"" + pathBase + "\";\n" +
                                        export + pathName + "=\"" + resolved.entrySet().stream()
                                        .filter(r -> r.getKey().addToPath())
                                        .map(r -> quoted(rc.toBin(r.getValue())))
                                        .collect(joining(pathSeparator, "", pathSeparator)) + pathVar + "\";\n" :
                                "";
                        final var echos = Boolean.parseBoolean(tools.getProperty("echo", "true")) ?
                                resolved.entrySet().stream()
                                        .map(e -> "echo \"[yem] Resolved " + e.getKey().toolName() + "@" + e.getKey().version() + " to '" + e.getValue() + "'\";")
                                        .collect(joining("\n", "", "\n")) :
                                "";

                        final var script = messages.stream().map(m -> "echo \"[yem] " + m.replace("\"", "\"\\\"\"") + "\";").collect(joining("\n", "", "\n\n")) +
                                pathVars + toolVars + echos + "\n" +
                                comment + "To load a .yemrc configuration run:\n" +
                                comment + "[ -f .yemrc ] && eval $(yem env--env-file .yemrc)\n" +
                                comment + "\n" +
                                comment + "See https://www.yupiik.io/tools-maven-plugin/yem.html#autopath for details\n" +
                                "\n";
                        System.out.println(script);
                    })
                    .toCompletableFuture()
                    .get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            logger.setUseParentHandlers(useParentHandlers);
            logger.removeHandler(tempHandler);
        }
    }

    private String quoted(final Path path) {
        return path
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\"", "\\\"");
    }

    private void resetOriginalPath(final String export, final String pathName) {
        // just check we have YEM_ORIGINAL_PATH and reset PATH if needed
        ofNullable(System.getenv("YEM_ORIGINAL_PATH"))
                .ifPresent(value -> {
                    if (os.isWindows()) {
                        System.out.println("set YEM_ORIGINAL_PATH=;");
                    } else {
                        System.out.println("unset YEM_ORIGINAL_PATH;");
                    }
                    System.out.println(export + " " + pathName + "=\"" + value + "\";");
                });
    }

    @RootConfiguration("env")
    public record Conf(
            @Property(documentation = "Should `~/.yupiik/yem/rc` be ignored or not. If present it defines default versions and uses the same syntax than `yemrc`.", defaultValue = "System.getProperty(\"user.home\") + \"/.yupiik/yem/rc\"") String defaultRc,
            @Property(documentation = "Env file location to read to generate the script. Note that `auto` will try to pick `.yemrc` and if not there will use `.sdkmanrc` if present.", defaultValue = "\"auto\"") String rc) {
    }
}
