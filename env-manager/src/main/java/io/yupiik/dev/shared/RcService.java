package io.yupiik.dev.shared;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.ProviderRegistry;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class RcService {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Os os;
    private final ProviderRegistry registry;

    public RcService(final Os os, final ProviderRegistry registry) {
        this.os = os;
        this.registry = registry;
    }

    public Map<ToolProperties, Path> toToolProperties(final Properties props) {
        return props.stringPropertyNames().stream()
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
                                tool.toolName(), tool.version(),
                                tool.provider() == null || tool.provider().isBlank() ? null : tool.provider(), tool.relaxed(),
                                new ProviderRegistry.Cache(new IdentityHashMap<>(), new IdentityHashMap<>()))
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
    }

    public Properties loadPropertiesFrom(final String rcPath, final String defaultRcPath) {
        final var defaultRc = Path.of(defaultRcPath);
        final var defaultProps = new Properties();
        if (Files.exists(defaultRc)) {
            readRc(defaultRc, defaultProps);
        }

        final var isAuto = "auto".equals(rcPath);
        var rcLocation = isAuto ? auto() : Path.of(rcPath);
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
                rcLocation = parent.resolve(isAuto ? rcLocation.getFileName().toString() : rcPath);
            }
        }

        final var props = new Properties();
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

    private Path auto() {
        return Stream.of(".yemrc", ".sdkmanrc")
                .map(Path::of)
                .filter(Files::exists)
                .findFirst()
                .orElseGet(() -> Path.of(".yemrc"));
    }

    public record ToolProperties(
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
