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
import io.yupiik.dev.shared.Os;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.io.File.pathSeparator;
import static java.util.Locale.ROOT;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.logging.Level.FINE;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Command(name = "env", description = "Creates a script you can eval in a shell to prepare the environment from a file. Often used as `eval $(yem env--env-rc .yemrc)`")
public class Env implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Conf conf;
    private final ProviderRegistry registry;
    private final Os os;

    public Env(final Conf conf,
               final Os os,
               final ProviderRegistry registry) {
        this.conf = conf;
        this.os = os;
        this.registry = registry;
    }

    @Override
    public void run() {
        final var windows = "windows".equals(os.findOs());
        final var export = windows ? "set " : "export ";
        final var comment = windows ? "%% " : "# ";
        final var pathName = windows ? "Path" : "PATH";
        final var pathVar = windows ? "%" + pathName + "%" : ("$" + pathName);

        var rcLocation = Path.of(conf.rc());
        if (Files.notExists(rcLocation)) { // enable to navigate in the project without loosing the env
            while (Files.notExists(rcLocation)) {
                var parent = rcLocation.toAbsolutePath().getParent();
                if (parent == null || !Files.isReadable(parent)) {
                    break;
                }
                parent = parent.getParent();
                if (parent == null || !Files.isReadable(parent)) {
                    break;
                }
                rcLocation = parent.resolve(conf.rc());
            }
        }

        if (Files.notExists(rcLocation) || !Files.isReadable(rcLocation)) {
            // just check we have YEM_ORIGINAL_PATH and reset PATH if needed
            ofNullable(System.getenv("YEM_ORIGINAL_PATH"))
                    .ifPresent(value -> {
                        if (windows) {
                            System.out.println("set YEM_ORIGINAL_PATH=");
                        } else {
                            System.out.println("unset YEM_ORIGINAL_PATH");
                        }
                        System.out.println(export + " " + pathName + "=\"" + value + '"');
                    });
            return;
        }

        final var props = new Properties();
        try (final var reader = Files.newBufferedReader(rcLocation)) {
            props.load(reader);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        final var logger = this.logger.getParent().getParent();
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
            final var resolved = props.stringPropertyNames().stream()
                    .filter(it -> it.endsWith(".version"))
                    .map(versionKey -> {
                        final var name = versionKey.substring(0, versionKey.lastIndexOf('.'));
                        return new ToolProperties(
                                props.getProperty(name + ".toolName", name),
                                props.getProperty(versionKey),
                                props.getProperty(name + ".provider"),
                                Boolean.parseBoolean(props.getProperty(name + ".relaxed", props.getProperty("relaxed"))),
                                props.getProperty(name + ".envVarName", name.toUpperCase(ROOT).replace('.', '_') + "_HOME"),
                                Boolean.parseBoolean(props.getProperty(name + ".addToPath", props.getProperty("addToPath", "true"))),
                                Boolean.parseBoolean(props.getProperty(name + ".failOnMissing", props.getProperty("failOnMissing"))),
                                Boolean.parseBoolean(props.getProperty(name + ".installIfMissing", props.getProperty("installIfMissing"))));
                    })
                    .flatMap(tool -> registry.tryFindByToolVersionAndProvider(
                                    tool.toolName(), tool.version(), tool.provider() == null || tool.provider().isBlank() ? null : tool.provider(), tool.relaxed())
                            .or(() -> {
                                if (tool.failOnMissing()) {
                                    throw new IllegalStateException("Missing home for " + tool.toolName() + "@" + tool.version());
                                }
                                return Optional.empty();
                            })
                            .flatMap(providerAndVersion -> {
                                final var provider = providerAndVersion.getKey();
                                final var version = providerAndVersion.getValue().identifier();
                                return provider.resolve(tool.toolName(), tool.version())
                                        .or(() -> {
                                            if (tool.installIfMissing()) {
                                                logger.info(() -> "Installing " + tool.toolName() + '@' + version);
                                                provider.install(tool.toolName(), version, Provider.ProgressListener.NOOP);
                                            } else if (tool.failOnMissing()) {
                                                throw new IllegalStateException("Missing home for " + tool.toolName() + "@" + version);
                                            }
                                            return provider.resolve(tool.toolName(), version);
                                        });
                            })
                            .stream()
                            .map(home -> entry(tool, home)))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            final var toolVars = resolved.entrySet().stream()
                    .map(e -> export + e.getKey().envVarName() + "=\"" + quoted(e.getValue()) + "\"")
                    .sorted()
                    .collect(joining("\n", "", "\n"));

            final var pathBase = ofNullable(System.getenv("YEM_ORIGINAL_PATH"))
                    .or(() -> ofNullable(System.getenv(pathName)))
                    .orElse("");
            final var pathVars = resolved.keySet().stream().anyMatch(ToolProperties::addToPath) ?
                    export + "YEM_ORIGINAL_PATH=\"" + pathBase + "\"\n" +
                            export + pathName + "=\"" + resolved.entrySet().stream()
                            .filter(r -> r.getKey().addToPath())
                            .map(r -> quoted(toBin(r.getValue())))
                            .collect(joining(pathSeparator, "", pathSeparator)) + pathVar + "\"\n" :
                    "";
            final var echos = Boolean.parseBoolean(props.getProperty("echo", "true")) ?
                    resolved.entrySet().stream()
                            .map(e -> "echo \"[yem] Resolved " + e.getKey().toolName() + "@" + e.getKey().version() + " to '" + e.getValue() + "'\"")
                            .collect(joining("\n", "", "\n")) :
                    "";

            final var script = messages.stream().map(m -> "echo \"[yem] " + m + "\"").collect(joining("\n", "", "\n\n")) +
                    pathVars + toolVars + echos + "\n" +
                    comment + "To load a .yemrc configuration run:\n" +
                    comment + "[ -f .yemrc ] && eval $(yem env--env-file .yemrc)\n" +
                    comment + "\n" +
                    comment + "yemrc format is based on properties\n" +
                    comment + "(only version one is required, version being either a plain version or version identifier, see versions command)\n" +
                    comment + "$toolName is just a marker or if .toolName is not set it is the actual tool name:\n" +
                    comment + "$toolName.toolName = xxxx\n" +
                    comment + "\n" +
                    comment + "$toolName.version = 1.2.3\n" +
                    comment + "$toolName.provider = xxxx\n" +
                    comment + "$toolName.relaxed = [true|false]\n" +
                    comment + "$toolName.envVarName = xxxx\n" +
                    comment + "$toolName.addToPath = [true|false]\n" +
                    comment + "$toolName.failOnMissing = [true|false]\n" +
                    comment + "$toolName.installIfMissing = [true|false]\n" +
                    "\n";
            System.out.println(script);
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

    private Path toBin(final Path value) {
        return Stream.of("bin" /* add other potential folders */)
                .map(value::resolve)
                .filter(Files::exists)
                .findFirst()
                .orElse(value);
    }

    @RootConfiguration("env")
    public record Conf(
            @Property(documentation = "Env file location to read to generate the script.", required = true) String rc) {
    }

    private record ToolProperties(
            String toolName,
            String version,
            String provider,
            boolean relaxed,
            String envVarName,
            boolean addToPath,
            boolean failOnMissing,
            boolean installIfMissing) {
    }
}
