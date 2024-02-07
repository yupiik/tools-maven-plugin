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
package io.yupiik.dev.shared;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.ProviderRegistry;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class RcService {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final ProviderRegistry registry;

    public RcService(final ProviderRegistry registry) {
        this.registry = registry;
    }

    public Properties loadPropertiesFrom(final String rcPath, final String defaultRcPath) {
        final var defaultRc = Path.of(defaultRcPath);
        final var defaultProps = new Properties();
        if (Files.exists(defaultRc)) {
            readRc(defaultRc, defaultProps);
        }

        final var isAuto = "auto".equals(rcPath);
        var rcLocation = isAuto ? auto(Path.of(".")) : Path.of(rcPath);
        final boolean isAbsolute = rcLocation.isAbsolute();
        if (Files.notExists(rcLocation)) { // enable to navigate in the project without loosing the env
            while (!isAbsolute && Files.notExists(rcLocation)) {
                var parent = rcLocation.toAbsolutePath().getParent();
                if (parent == null || !Files.isReadable(parent)) {
                    break;
                }
                parent = parent.getParent();
                if (parent == null || !Files.isReadable(parent)) {
                    break;
                }
                rcLocation = isAuto ? auto(parent) : parent.resolve(rcPath);
            }
        }

        final var props = new Properties();
        props.putAll(defaultProps);
        if (Files.exists(rcLocation)) {
            readRc(rcLocation, props);
            if (".sdkmanrc".equals(rcLocation.getFileName().toString())) {
                rewritePropertiesFromSdkManRc(props);
            }
        } else if (Files.notExists(defaultRc)) {
            return null; // no config at all
        }

        return props;
    }

    public Path toBin(final Path value) {
        return Stream.of("bin" /* add other potential folders */)
                .map(value::resolve)
                .filter(Files::exists)
                .findFirst()
                .orElse(value);
    }

    public CompletionStage<List<MatchedPath>> toToolProperties(final Properties props) {
        final var promises = props.stringPropertyNames().stream()
                .filter(it -> it.endsWith(".version"))
                .map(versionKey -> toToolProperties(props, versionKey))
                .map(tool -> {
                    final var promise = registry.tryFindByToolVersionAndProvider(
                            tool.toolName(), tool.version(),
                            tool.provider() == null || tool.provider().isBlank() ? null : tool.provider(), tool.relaxed(),
                            new ProviderRegistry.Cache(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
                    return promise.thenCompose(providerAndVersionOpt -> providerAndVersionOpt
                                    .map(providerVersion -> doResolveVersion(tool, providerVersion))
                                    .orElseGet(() -> {
                                        if (tool.failOnMissing()) {
                                            throw new IllegalStateException("Missing home for " + tool.toolName() + "@" + tool.version());
                                        }
                                        logger.finest(() -> tool.toolName() + "@" + tool.version() + " not available");
                                        return completedFuture(Optional.empty());
                                    }))
                            .toCompletableFuture();
                })
                .toList();
        return allOf(promises.toArray(new CompletableFuture<?>[0]))
                .thenApply(ok -> promises.stream()
                        .flatMap(p -> p.getNow(Optional.empty()).stream())
                        .toList());
    }

    private CompletableFuture<Optional<MatchedPath>> doResolveVersion(final ToolProperties tool,
                                                                      final ProviderRegistry.MatchedVersion matchedVersion) {
        final var version = matchedVersion.version().identifier();
        return matchedVersion.provider().resolve(tool.toolName(), version)
                .map(path -> completedFuture(Optional.of(path)))
                .or(() -> {
                    if (!tool.installIfMissing()) {
                        if (tool.failOnMissing()) {
                            throw new IllegalStateException("Missing home for " + tool.toolName() + "@" + version);
                        }
                        return Optional.empty();
                    }

                    logger.info(() -> "Installing " + tool.toolName() + '@' + version);
                    return Optional.of(matchedVersion.provider().install(tool.toolName(), version, Provider.ProgressListener.NOOP)
                            .exceptionally(this::onInstallException)
                            .thenApply(Optional::ofNullable)
                            .toCompletableFuture());
                })
                .orElseGet(() -> completedFuture(Optional.empty()))
                .thenApply(path -> path.map(p -> new MatchedPath(
                        p, adjustToolVersion(tool, matchedVersion.version()), matchedVersion.provider(), matchedVersion.candidate())));
    }

    private Path onInstallException(final Throwable e) {
        final var unwrapped = e instanceof CompletionException ce ? ce.getCause() : e;
        if (unwrapped instanceof YemHttpClient.OfflineException) {
            return null;
        }
        if (unwrapped instanceof RuntimeException ex) {
            throw ex;
        }
        throw new IllegalStateException(unwrapped);
    }

    private ToolProperties adjustToolVersion(final ToolProperties tool, final Version version) {
        return Objects.equals(tool.version(), version.version()) ?
                tool :
                new ToolProperties(
                        tool.toolName(),
                        version.version(),
                        tool.provider(),
                        true,
                        tool.envPathVarName(),
                        tool.envVersionVarName(),
                        tool.addToPath(),
                        tool.failOnMissing(),
                        tool.installIfMissing());
    }

    private ToolProperties toToolProperties(final Properties props, final String versionKey) {
        final var name = versionKey.substring(0, versionKey.lastIndexOf('.'));
        final var baseEnvVar = name.toUpperCase(ROOT).replace('.', '_');
        return new ToolProperties(
                props.getProperty(name + ".toolName", name),
                props.getProperty(versionKey),
                props.getProperty(name + ".provider"),
                Boolean.parseBoolean(props.getProperty(name + ".relaxed", props.getProperty("relaxed"))),
                props.getProperty(name + ".envVarName", baseEnvVar + "_HOME"),
                props.getProperty(name + ".envVarVersionName", baseEnvVar + "_VERSION"),
                Boolean.parseBoolean(props.getProperty(name + ".addToPath", props.getProperty("addToPath", "true"))),
                Boolean.parseBoolean(props.getProperty(name + ".failOnMissing", props.getProperty("failOnMissing"))),
                Boolean.parseBoolean(props.getProperty(name + ".installIfMissing", props.getProperty("installIfMissing"))));
    }

    private void readRc(final Path rcLocation, final Properties props) {
        try (final var reader = Files.newBufferedReader(rcLocation)) {
            props.load(reader);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void rewritePropertiesFromSdkManRc(final Properties props) {
        final var original = new Properties();
        original.putAll(props);

        props.clear();
        props.setProperty("addToPath", "true");
        props.putAll(original.stringPropertyNames().stream()
                .collect(toMap(p -> p + ".version", original::getProperty)));
    }

    private Path auto(final Path from) {
        return Stream.of(".yemrc", ".sdkmanrc")
                .map(from::resolve)
                .filter(Files::exists)
                .findFirst()
                .orElseGet(() -> from.resolve(".yemrc"));
    }

    public record ToolProperties(
            String toolName,
            String version,
            String provider,
            boolean relaxed,
            String envPathVarName,
            String envVersionVarName,
            boolean addToPath,
            boolean failOnMissing,
            boolean installIfMissing) {
    }

    public record MatchedPath(Path path, ToolProperties properties, Provider provider, Candidate candidate) {
    }
}
