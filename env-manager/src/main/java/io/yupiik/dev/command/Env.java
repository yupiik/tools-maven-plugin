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

import io.yupiik.dev.shared.MessageHelper;
import io.yupiik.dev.shared.Os;
import io.yupiik.dev.shared.RcService;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.io.File.pathSeparator;
import static java.util.Optional.ofNullable;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Command(name = "env", description = "Creates a script you can eval in a shell to prepare the environment from a file. Often used as `eval $(yem env--env-rc .yemrc)`")
public class Env implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Conf conf;
    private final RcService rc;
    private final Os os;
    private final MessageHelper messageHelper;
    private final Args args;

    public Env(final Conf conf, final Os os, final RcService rc, final MessageHelper messageHelper, final Args args) {
        this.conf = conf;
        this.os = os;
        this.rc = rc;
        this.messageHelper = messageHelper;
        this.args = args;
    }

    @Override
    public void run() {
        final var hasTerm = os.isUnixLikeTerm();
        final var windows = os.isWindows() && hasTerm /* if not null behave as bash */;
        final var export = windows ? "set " : "export ";
        final var comment = windows ? "%% " : "# ";
        final var pathName = windows ? "Path" : "PATH";
        final var pathVar = windows ? "%" + pathName + "%" : ("$" + pathName);
        final var quote = windows ? "" : "\"";

        if (!conf.skipReset()) {
            resetOriginalPath(export, pathName, windows);
        }

        final var tools = rc.loadPropertiesFrom(conf.rc(), conf.defaultRc());
        final var inlineProps = new Properties();
        // check if we have any --xxxx-version arg and if so we inject all args in props
        // there is no real chance of conflict with default props so we can do it
        if (args.args().size() > 1 /* "env" */ &&
                args.args().stream().anyMatch(a -> a.startsWith("--") && !Objects.equals("--version", a) && a.endsWith("-version"))) {
            final var params = args.args().subList(1, args.args().size());
            if (!params.isEmpty()) {
                inlineProps.putAll(IntStream.range(0, params.size() / 2)
                        .boxed()
                        .filter(f -> {
                            final var key = params.get(2 * f);
                            return key.startsWith("--env-") && key.lastIndexOf('-') > "--env-".length() /* default options */;
                        })
                        .collect(toMap(d -> {
                            final var key = params.get(2 * d);
                            return key.substring("--env-".length()).replace('-', '.');
                        }, i -> params.get(2 * i + 1))));
            }
        }
        if (conf.inlineRc() != null && !conf.inlineRc().isBlank()) {
            try (final var reader = new StringReader(conf.inlineRc().replace("\\n", "\n"))) {
                inlineProps.load(reader);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (conf.enableAutoDetection() && (tools == null || (tools.local().isEmpty()) && inlineProps.isEmpty())) {
            logger.finest(() -> "Try auto-detecting environment");
            inlineProps.putAll(rc.autoDetectProperties(tools == null ? null : tools.global()));
        } else {
            logger.finest(() -> "No auto-detection");
        }

        if ((tools == null || (tools.global().isEmpty() && tools.local().isEmpty())) && inlineProps.isEmpty()) { // nothing to do
            return;
        }

        final var logger = Logger.getLogger("io.yupiik.dev");
        final var useParentHandlers = logger.getUseParentHandlers();
        final var messages = new ArrayList<String>();
        final var tempHandler = captureLogHandler(logger, messages, useParentHandlers);
        logger.setUseParentHandlers(false);
        logger.addHandler(tempHandler);
        try {
            (tools == null ? rc.match(inlineProps) : rc.match(inlineProps, tools.local(), tools.global()))
                    .thenAccept(resolved -> createScript(resolved, export, quote, pathName, hasTerm, pathVar, tools, messages, comment))
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

    private Handler captureLogHandler(final Logger logger, final ArrayList<String> messages, final boolean useParentHandlers) {
        return new Handler() { // forward all standard messages to stderr and at debug level to avoid to break default behavior
            @Override
            public void publish(final LogRecord record) {
                // capture to forward messages in the shell when init is done (thanks eval call)
                if (logger.isLoggable(record.getLevel())) {
                    messages.add(messageHelper.formatLog(record.getLevel(), record.getMessage()));
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
    }

    private void createScript(final List<RcService.MatchedPath> rawResolved,
                              final String export,
                              final String quote, final String pathName, final boolean hasTerm, final String pathVar,
                              final RcService.Props tools, final List<String> messages, final String comment) {
        final var resolved = rawResolved.stream()
                // ignore manually overriden vars
                .filter(it -> {
                    final var overridenEnvVar = rc.toOverridenEnvVar(it.properties());
                    if (System.getenv(overridenEnvVar) != null) {
                        logger.finest(() -> "Ignoring '" + it.properties().envPathVarName() + "' because '" + overridenEnvVar + "' is defined");
                        return false;
                    }
                    return true;
                })
                .toList();
        final var toolVars = resolved.stream()
                .flatMap(e -> Stream.concat(
                        Stream.of(
                                export + e.properties().envPathVarName() + "=" + quote + quoted(e.path()) + quote + ";",
                                export + e.properties().envVersionVarName() + "=" + quote + e.properties().version() + quote + ";"),
                        e.properties().index() == 0 /* custom override */ ?
                                Stream.of(export + rc.toOverridenEnvVar(e.properties()) + "=" + quote + e.properties().version() + quote + ";") :
                                Stream.empty()))
                .sorted()
                .collect(joining("\n", "", "\n"));

        final var pathBase = ofNullable(System.getenv("YEM_ORIGINAL_PATH"))
                .or(() -> ofNullable(System.getenv(pathName)))
                .orElse("");
        final var pathsSeparator = hasTerm ? ":" : pathSeparator;
        final var pathVars = resolved.stream().map(RcService.MatchedPath::properties).anyMatch(RcService.ToolProperties::addToPath) ?
                export + "YEM_ORIGINAL_PATH=" + quote + pathBase + quote + ";\n" +
                        export + pathName + "=" + quote + resolved.stream()
                        .filter(r -> r.properties().addToPath())
                        .map(r -> quoted(rc.toBin(r.path())))
                        .collect(joining(pathsSeparator, "", pathsSeparator)) + pathVar + quote + ";\n" :
                "";
        final var home = System.getProperty("user.home", "");
        final var echos = tools == null || Boolean.parseBoolean(tools.global().getProperty("echo", tools.local().getProperty("echo", "true"))) ?
                resolved.stream()
                        // don't log too much, if it does not change, don't re-log it
                        .filter(Predicate.not(it -> Objects.equals(it.path().toString(), System.getenv(it.properties().envPathVarName()))))
                        .map(e -> "echo \"[yem] " + messageHelper.formatLog(INFO, "Resolved " + messageHelper.formatToolNameAndVersion(
                                e.candidate(), e.properties().toolName(), e.properties().version()) + " to '" +
                                e.path().toString().replace(home, "~") + "'") + "\";")
                        .collect(joining("\n", "", "\n")) :
                "";

        final var script = messages.stream().map(m -> "echo \"[yem] " + m.replace("\"", "\"\\\"\"") + "\";").collect(joining("\n", "", "\n\n")) +
                pathVars + toolVars + echos + "\n" +
                comment + "To load a .yemrc configuration run:\n" +
                comment + "[ -f .yemrc ] && eval $(yem env --env-file .yemrc)\n" +
                comment + "\n" +
                comment + "See https://www.yupiik.io/tools-maven-plugin/yem.html#autopath for details\n" +
                "\n";
        System.out.println(script);
    }

    private String quoted(final Path path) {
        return path
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\"", "\\\"");
    }

    private void resetOriginalPath(final String export, final String pathName, final boolean windows) {
        // just check we have YEM_ORIGINAL_PATH and reset PATH if needed
        ofNullable(System.getenv("YEM_ORIGINAL_PATH"))
                .filter(it -> !"skip".equals(it))
                .ifPresent(value -> {
                    if (windows) {
                        System.out.println("set YEM_ORIGINAL_PATH=;");
                    } else {
                        System.out.println("unset YEM_ORIGINAL_PATH;");
                    }
                    System.out.println(export + " " + pathName + "=\"" + value + "\";");
                });
    }

    @RootConfiguration("env")
    public record Conf(
            @Property(documentation = "EXPERIMENTAL. If enabled and no `.yemrc` nor `.sdkmanrc` setup any tool (ie empty does not count) then try to detect some well known tools like Java (there is a `pom.xml` and the compiler version can be extracted for example) and Maven (`pom.xml` presence). Note that it is done using `./` folder and bubbles up with a max of 10 levels.", defaultValue = "false") boolean enableAutoDetection,
            @Property(documentation = "By default if `YEM_ORIGINAL_PATH` exists in the environment variables it is used as `PATH` base to not keep appending path to the `PATH` indefinively. This can be disabled setting this property to `false`", defaultValue = "false") boolean skipReset,
            @Property(documentation = "Should `~/.yupiik/yem/rc` be ignored or not. If present it defines default versions and uses the same syntax than `yemrc`.", defaultValue = "System.getProperty(\"user.home\") + \"/.yupiik/yem/rc\"") String defaultRc,
            @Property(documentation = "Enables to set inline a rc file, ex: `eval $(yem env --inlineRc 'java.version=17.0.9')`, you can use EOL too: `eval $(yem env --inlineRc 'java.version=17.\\njava.relaxed = true')`. Note that to persist the change even if you automatically switch from the global `yemrc` file the context, we set `YEM_$TOOLPATHVARNAME_OVERRIDEN` environment variable. To reset the value to the global configuration just `unset` this variable (ex: `unset YEM_JAVA_PATH_OVERRIDEN`). Note that you can also just set the values inline as args without that option: `eval $(yem env --java-version 17. --java-relaxed true ...)`.") String inlineRc,
            @Property(documentation = "Env file location to read to generate the script. Note that `auto` will try to pick `.yemrc` and if not there will use `.sdkmanrc` if present.", defaultValue = "\"auto\"") String rc) {
    }
}
