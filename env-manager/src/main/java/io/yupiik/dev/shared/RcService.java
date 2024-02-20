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
package io.yupiik.dev.shared;

import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.ProviderRegistry;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.shared.http.YemHttpClient;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
import static java.util.logging.Level.FINEST;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class RcService {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final ProviderRegistry registry;

    public RcService(final ProviderRegistry registry) {
        this.registry = registry;
    }

    public Props loadPropertiesFrom(final String rcPath, final String defaultRcPath) {
        final var defaultRc = Path.of(defaultRcPath);
        final var defaultProps = new Properties();
        if (Files.exists(defaultRc)) {
            readRc(defaultRc, defaultProps);
            if (defaultRcPath.endsWith(".sdkmanrc")) {
                rewritePropertiesFromSdkManRc(defaultProps);
            }
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
        if (Files.exists(rcLocation)) {
            readRc(rcLocation, props);
            if (".sdkmanrc".equals(rcLocation.getFileName().toString())) {
                rewritePropertiesFromSdkManRc(props);
            }
        } else if (Files.notExists(defaultRc)) {
            return null; // no config at all
        }

        return new Props(defaultProps, props);
    }

    public Path toBin(final Path value) {
        return Stream.of("bin" /* add other potential folders */)
                .map(value::resolve)
                .filter(Files::exists)
                .findFirst()
                .orElse(value);
    }

    /**
     * @param props input properties sorted in overriding order (first overriding second, second overriding the third etc)
     * @return matched paths for the aggregated set of incoming properties - env path var name being the identifier.
     */
    public CompletionStage<List<MatchedPath>> match(final Properties... props) {
        if (props.length == 0 || Stream.of(props).allMatch(Properties::isEmpty)) {
            return completedFuture(List.of());
        }

        final var toolProps = new ArrayList<ToolProperties>();
        int index = 0;
        for (final var it : props) {
            if (it.isEmpty()) {
                index++;
                continue;
            }
            toolProps.addAll(toToolProperties(it, index++).stream()
                    .filter(p -> toolProps.stream().noneMatch(existing -> Objects.equals(existing.envPathVarName(), p.envPathVarName())))
                    .toList());
        }

        if (toolProps.isEmpty()) {
            return completedFuture(List.of());
        }
        return doToolProperties(toolProps);
    }

    public String toOverridenEnvVar(final ToolProperties toolProperties) {
        return "YEM_" + toolProperties.envPathVarName() + "_OVERRIDEN";
    }

    private ToolProperties toSingleToolProperties(final Properties props, final String versionKey, final int index) {
        final var name = versionKey.substring(0, versionKey.lastIndexOf('.'));
        final var baseEnvVar = name.toUpperCase(ROOT).replace('.', '_');
        return new ToolProperties(
                index,
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

    private CompletableFuture<List<MatchedPath>> doToolProperties(final List<ToolProperties> props) {
        if (props.isEmpty()) {
            return completedFuture(List.of());
        }

        final var promises = props.stream()
                .map(tool -> {
                    final var promise = registry.tryFindByToolVersionAndProvider(
                            tool.toolName(), tool.version(),
                            tool.provider() == null || tool.provider().isBlank() ? null : tool.provider(), tool.relaxed(),
                            tool.installIfMissing(), new ProviderRegistry.Cache(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
                    return promise.thenCompose(providerAndVersionOpt -> providerAndVersionOpt
                                    .map(providerVersion -> doResolveVersion(tool, providerVersion))
                                    .orElseGet(() -> {
                                        if (tool.failOnMissing()) {
                                            throw new IllegalStateException("Missing home for " + tool.toolName() + "@" + tool.version());
                                        }
                                        logger.warning(() -> tool.toolName() + "@" + tool.version() + " not available");
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

    public List<ToolProperties> toToolProperties(final Properties props, final int index) {
        return props.stringPropertyNames().stream()
                .filter(it -> it.endsWith(".version"))
                .map(versionKey -> toSingleToolProperties(props, versionKey, index))
                .toList();
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
                        tool.index(),
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

    public Map<String, String> autoDetectProperties(final Properties globalConf) {
        final var props = new HashMap<String, String>();
        autoDetectMvn(props, Path.of("."), globalConf, false, false, null);
        return props;
    }

    // todo: refine before enabling automatically
    private void autoDetectMvn(final Map<String, String> props, final Path folder, final Properties globalConf,
                               final boolean ignoreChildren, final boolean ignoreParent,
                               final SAXParserFactory providedFactory) {
        if (props.containsKey("maven.version")) { // already setup
            return;
        }

        try {
            SAXParserFactory saxParserFactory = providedFactory;

            final var pom = folder.resolve("pom.xml");
            if (!Files.exists(pom)) {
                return;
            }

            if (saxParserFactory == null) {
                saxParserFactory = SAXParserFactory.newInstance();
                saxParserFactory.setNamespaceAware(false);
                saxParserFactory.setValidating(false);
            }

            QuickMvnParser meta;
            try (final var in = new BufferedInputStream(Files.newInputStream(pom))) {
                meta = parsePom(saxParserFactory, in);
            }

            if (!ignoreParent && meta.parentRelativePath != null) {
                autoDetectMvn(props, folder.resolve(meta.parentRelativePath), globalConf, true, false, saxParserFactory);
            }

            if (meta.requireMavenVersion != null) {
                var version = meta.requireMavenVersion;
                if (version.endsWith(",)") && version.startsWith("[")) { // only a minimum, no max
                    version = version.substring(1, version.length() - ",)".length());
                    props.put("maven.relaxed", "true");
                    if (version.startsWith("2.")) {
                        props.put("maven.version", "3."); // no joke, 2 is dead today
                    } else {
                        props.put("maven.version", version.length() > 2 ? version.substring(0, 2) : version);
                    }
                } else if (!version.contains("(") && !version.contains(")") && !version.contains("[") && !version.contains("]")) {
                    props.put("maven.relaxed", "true");
                    props.put("maven.version", version.length() > 2 ? version.substring(0, 2) : version);
                } else if (globalConf == null || !globalConf.containsKey("maven.version")) {
                    // todo: parse range? https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html
                    props.put("maven.version", "3.");
                    props.put("maven.relaxed", "true");
                }
            }
            if (meta.requireJavaVersion >= 8) {
                final var existing = props.get("java.version");
                props.put("java.version", Integer.toString(existing == null ?
                        meta.requireJavaVersion :
                        Math.max(meta.requireJavaVersion, Integer.parseInt(meta.requireMavenVersion))));
                props.put("java.relaxed", "true");
            }

            if (!ignoreChildren && !meta.children.isEmpty()) {
                for (final var child : meta.children) {
                    autoDetectMvn(props, folder.resolve(child), globalConf, false, true, saxParserFactory);
                }
            }

            if (!ignoreChildren && !ignoreParent) { // root
                if (globalConf == null || !globalConf.containsKey("maven.version")) {
                    props.putIfAbsent("maven.version", "3.");
                    props.putIfAbsent("maven.relaxed", "true");
                }
                if (globalConf == null || !globalConf.containsKey("java.version")) {
                    props.putIfAbsent("java.version", "");
                    props.putIfAbsent("java.relaxed", "true");
                }
            }
        } catch (final IOException ioe) {
            logger.log(FINEST, ioe, ioe::getMessage);
        }
    }

    private QuickMvnParser parsePom(final SAXParserFactory factory, final InputStream stream) {
        final var handler = new QuickMvnParser();
        try {
            factory.newSAXParser().parse(stream, handler);
        } catch (final Exception e) {
            // no-op: not parseable so ignoring
        }
        return handler;
    }

    public record ToolProperties(
            int index,
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

    public record Props(Properties global, Properties local) {
    }

    private static class QuickMvnParser extends DefaultHandler {
        private final LinkedList<String> tags = new LinkedList<>();
        private final Map<String, String> properties = new HashMap<>();
        private String currentPlugin;
        private StringBuilder text;

        private int requireJavaVersion = 0;
        private String requireMavenVersion;
        private String parentRelativePath = "..";
        private final List<String> children = new ArrayList<>();

        @Override
        public void startElement(final String uri, final String localName,
                                 final String qName, final Attributes attributes) {
            if ("relativePath".equals(qName) ||
                    "module".equals(qName) ||
                    ("artifactId".equals(qName) && tags.size() >= 3) ||
                    ("maven-compiler-plugin".equals(currentPlugin) && (
                            "release".equals(qName) || "target".equals(qName) || "source".equals(qName))) ||
                    (tags.size() == 2 && "properties".equals(tags.getLast())) ||
                    (tags.size() > 4 && "requireMavenVersion".equals(tags.getLast()) && "version".equals(qName))) {
                text = new StringBuilder();
            }
            tags.add(qName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            if (text != null) {
                text.append(new String(ch, start, length));
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            tags.removeLast();
            if ("relativePath".equals(qName) && "parent".equals(tags.getLast()) && tags.size() == 2) {
                parentRelativePath = text.isEmpty() ? ".." : text.toString().strip();
            } else if ("module".equals(qName) && "modules".equals(tags.getLast()) && tags.size() == 2 && !text.isEmpty()) {
                children.add(text.toString().strip());
            } else if ("artifactId".equals(qName) && "plugin".equals(tags.getLast()) && (tags.size() == 4 || tags.size() == 5 /* mgt */)) {
                currentPlugin = text.toString().strip();
            } else if ("plugin".equals(qName)) {
                currentPlugin = null;
            } else if (tags.size() == 2 && "properties".equals(tags.getLast())) {
                properties.put(qName, text.toString().strip());
            } else if ("requireMavenVersion".equals(tags.getLast()) && "version".equals(qName)) {
                requireMavenVersion = text.toString().strip();
            } else if ("maven-compiler-plugin".equals(currentPlugin) && (
                    "release".equals(qName) || "target".equals(qName) || "source".equals(qName))) {
                var version = text.toString();
                if (!version.isBlank()) {
                    int iterations = 10;
                    while (iterations-- > 0 && version.startsWith("${") && version.endsWith("}")) {
                        version = properties.get(version.substring("${".length(), version.length() - 1));
                    }
                    if (version != null) {
                        try {
                            final var end = version.indexOf('.');
                            requireJavaVersion = Math.max(
                                    requireJavaVersion,
                                    version.startsWith("1.8") ?
                                            8 :
                                            Integer.parseInt(version.substring(0, end < 0 ? version.length() : end)));
                        } catch (final NumberFormatException nfe) {
                            // no-op
                        }
                    }
                }
            }
            text = null;
        }
    }
}
