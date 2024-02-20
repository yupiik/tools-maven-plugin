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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.dev.provider.Provider;
import io.yupiik.dev.provider.ProviderRegistry;
import io.yupiik.dev.provider.central.CentralBaseProvider;
import io.yupiik.dev.provider.central.CentralConfiguration;
import io.yupiik.dev.provider.central.Gav;
import io.yupiik.dev.provider.github.GithubConfiguration;
import io.yupiik.dev.provider.github.MinikubeConfiguration;
import io.yupiik.dev.provider.github.SingletonGithubConfiguration;
import io.yupiik.dev.provider.model.Candidate;
import io.yupiik.dev.provider.model.Version;
import io.yupiik.dev.provider.sdkman.SdkManConfiguration;
import io.yupiik.dev.provider.zulu.ZuluCdnConfiguration;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import static io.yupiik.dev.provider.Provider.ProgressListener.NOOP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.function.Function.identity;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Command(name = "http", description = "Show configuration in a web browser.")
public class Http implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final Conf conf;

    private final CentralConfiguration central;
    private final SdkManConfiguration sdkman;
    private final SingletonGithubConfiguration github;
    private final ZuluCdnConfiguration zulu;
    private final MinikubeConfiguration minikube;
    private final Configuration configuration;

    private final JsonMapper jsonMapper;
    private final ProviderRegistry providerRegistry;

    public Http(final Conf conf,
                final Configuration configuration,
                final CentralConfiguration central,
                final SdkManConfiguration sdkman,
                final SingletonGithubConfiguration github,
                final ZuluCdnConfiguration zulu,
                final MinikubeConfiguration minikube,
                final JsonMapper jsonMapper,
                final ProviderRegistry providerRegistry) {
        this.conf = conf;
        this.configuration = configuration;
        this.central = central;
        this.sdkman = sdkman;
        this.github = github;
        this.zulu = zulu;
        this.minikube = minikube;
        this.jsonMapper = jsonMapper;
        this.providerRegistry = providerRegistry;
    }

    @Override
    public void run() {
        try {
            final var confDoc = loadDescriptions();

            // note: we can indeed use fusion-httpserver but
            //       for a dev server the JVM embedded one is sufficient
            //       and does not make the binary as heavier so all good for this need
            final var server = HttpServer.create(new InetSocketAddress(conf.address(), conf.port()), 32);
            server.createContext("/").setHandler(ex -> onExchange(ex, confDoc));
            server.start();

            final var base = "http://localhost:" + server.getAddress().getPort() + "/yem/";
            try {
                logger.info(() -> "Launched '" + base + "', hit Ctrl+C when you want to stop");

                if (conf.open()) {
                    open(base);
                }

                new CountDownLatch(1).await();
            } finally {
                server.stop(0);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, List<DocItem>> loadDescriptions() {
        try (final var in = new BufferedReader(new InputStreamReader(
                requireNonNull(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/fusion/configuration/documentation.json"),
                        "no documentation.json"),
                UTF_8))) {
            return jsonMapper.read(ConfDoc.class, in).classes();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void open(final String base) {
        final var uri = URI.create(base);
        try {
            if (!java.awt.Desktop.isDesktopSupported()) {
                logger.warning(() -> "Desktop is not supported on this JVM, go to '" + uri + "' in your browser");
                return;
            }
            java.awt.Desktop.getDesktop().browse(uri);
        } catch (final UnsatisfiedLinkError | // native-image
                       IOException |
                       RuntimeException e) { // seems broken on recent linux version and java is a bit late to fix it
            try {
                final var xdgOpen = Path.of("/usr/bin/xdg-open");
                if (Files.exists(xdgOpen)) {
                    new ProcessBuilder(xdgOpen.toString(), uri.toString()).start().waitFor();
                } else {
                    logger.info(() -> "No /usr/bin/xdg-open so will not be able to open the browser automatically");
                }
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (final RuntimeException | IOException re) {
                logger.log(SEVERE, e, () -> "Desktop is not supported on this JVM, go to '" + uri + "' in your browser (" + e.getMessage() + ")");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void onExchange(final HttpExchange exchange, final Map<String, List<DocItem>> confDoc) throws IOException {
        try {
            final var rawpath = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/yem/jsonrpc".equals(rawpath)) { // fake light jsonrpc
                final Map<String, Object> request;
                try (final var in = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), UTF_8))) {
                    request = (Map<String, Object>) jsonMapper.read(Object.class, in);
                }
                onJsonRpc(exchange, request.getOrDefault("method", "").toString(), confDoc, request);
                return;
            }

            if (!"GET".equals(exchange.getRequestMethod()) || rawpath.endsWith(".map")) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }

            final var path = switch (rawpath) {
                case "/yem/js/app.js", "/yem/js/app.css" -> rawpath;
                default -> "/yem/index.html";
            };
            final var contentType = rawpath.endsWith(".js") ?
                    "application/javascript" :
                    (rawpath.endsWith(".css") ?
                            "text/css" :
                            "text/html");
            try (final var index = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources" + path)) {
                sendPayload(
                        exchange,
                        requireNonNull(index, "frontend not built").readAllBytes(),
                        contentType);
            }
        } catch (final RuntimeException re) { // shouldn't occur, just a bug protection to not leak an exchange
            exchange.close();
            throw re;
        }
    }

    private void onJsonRpc(final HttpExchange exchange, final String method,
                           final Map<String, List<DocItem>> confDoc,
                           final Map<String, Object> request) {
        try {
            switch (method) {
                case "yem.providers" -> sendPayload(exchange, jsonMapper.toBytes(Map.of(
                        "jsonrpc", "2.0",
                        "result", Map.of(
                                "items", providerRegistry.providers().stream()
                                        .map(p -> Map.of(
                                                "name", p.name(),
                                                "className", p.getClass().getSimpleName(),
                                                "configuration", p instanceof CentralBaseProvider centralProvider ?
                                                        findConfiguration(p, centralProvider.gav(), confDoc) :
                                                        findConfiguration(p, null, confDoc)))
                                        .collect(toList()),
                                "total", providerRegistry.providers().size()))), "application/json");
                case "yem.local" -> serveVersions(
                        exchange, providerRegistry.providers().stream()
                                .map(p -> p.listLocal()
                                        .thenApply(candidates -> candidates.entrySet().stream()
                                                .filter(it -> !it.getValue().isEmpty())
                                                .map(e -> toToolModel(p, e))
                                                .toList())
                                        .toCompletableFuture())
                                .toList());
                case "yem.remote" -> serveVersions(
                        exchange, providerRegistry.providers().stream()
                                .map(p -> p.listTools()
                                        .thenCompose(tools -> {
                                            final var all = tools.stream()
                                                    .collect(toMap(identity(), tool -> p.listVersions(tool.tool()).toCompletableFuture()));
                                            return allOf(all.values().toArray(new CompletableFuture<?>[0]))
                                                    .thenApply(ready -> all.entrySet().stream()
                                                            .map(e -> entry(e.getKey(), e.getValue().getNow(List.of())))
                                                            .map(e -> toToolModel(p, e))
                                                            .toList());
                                        })
                                        .toCompletableFuture())
                                .toList());
                case "yem.delete" -> {
                    @SuppressWarnings("unchecked") final var params = (Map<String, Object>) request.getOrDefault("params", Map.of());
                    final var provider = params.getOrDefault("provider", "").toString();
                    final var tool = params.getOrDefault("tool", "").toString();
                    final var version = params.getOrDefault("version", "").toString();
                    sendPayload(
                            exchange, jsonMapper.toBytes(providerRegistry.providers().stream()
                                    .filter(p -> Objects.equals(provider, p.name()))
                                    .findFirst()
                                    .map(matchedProvider -> {
                                        matchedProvider.delete(tool, version);
                                        return Map.of("jsonrpc", "2.0", "result", Map.of("success", true));
                                    })
                                    .orElseGet(() -> Map.of(
                                            "jsonrpc", "2.0",
                                            "error", Map.of("code", 1800, "message", "no provider '" + provider + "'")))),
                            "application/json");
                }
                case "yem.install" -> {
                    @SuppressWarnings("unchecked") final var params = (Map<String, Object>) request.getOrDefault("params", Map.of());
                    final var provider = params.getOrDefault("provider", "").toString();
                    final var tool = params.getOrDefault("tool", "").toString();
                    final var version = params.getOrDefault("version", "").toString();
                    sendPayload(
                            exchange, jsonMapper.toBytes(providerRegistry.providers().stream()
                                    .filter(p -> Objects.equals(provider, p.name()))
                                    .findFirst()
                                    .map(matchedProvider -> {
                                        matchedProvider.install(tool, version, NOOP); // todo: move to SSE
                                        return Map.of("jsonrpc", "2.0", "result", Map.of("success", true));
                                    })
                                    .orElseGet(() -> Map.of(
                                            "jsonrpc", "2.0",
                                            "error", Map.of("code", 1900, "message", "no provider '" + provider + "'")))),
                            "application/json");
                }
                default -> sendPayload(
                        exchange, jsonMapper.toBytes(Map.of(
                                "jsonrpc", "2.0",
                                "error", Map.of("code", 1500, "message", "unknown method: '" + method + "'"))),
                        "application/json");
            }
        } catch (final RuntimeException re) {
            onJsonRpcError(exchange, re);
        }
    }

    private void serveVersions(final HttpExchange exchange, final List<CompletableFuture<List<Map<String, Object>>>> promises) {
        allOf(promises.toArray(new CompletableFuture<?>[0]))
                .thenAccept(ok -> {
                    final var collected = promises.stream()
                            .flatMap(p -> p.getNow(List.of()).stream())
                            .sorted(Comparator.<Map<String, Object>, String>comparing(m -> m.get("tool").toString())
                                    .thenComparing(m -> m.get("provider").toString()))
                            .toList();
                    sendPayload(exchange, jsonMapper.toBytes(
                                    Map.of(
                                            "jsonrpc", "2.0",
                                            "result", Map.of("items", collected, "total", collected.size()))),
                            "application/json");
                })
                .exceptionally(e -> onJsonRpcError(exchange, e));
    }

    private Map<String, Object> toToolModel(final Provider p, final Map.Entry<Candidate, List<Version>> candidate) {
        return Map.of(
                "provider", p.name(),
                "tool", candidate.getKey().tool(),
                "description", candidate.getKey().description(),
                "versions", candidate.getValue().stream()
                        .sorted()
                        .map(v -> Map.of("version", v.version(), "identifier", v.identifier()))
                        .toList());
    }

    private Void onJsonRpcError(final HttpExchange exchange, final Throwable e) {
        logger.log(SEVERE, e, e::getMessage);
        sendPayload(
                exchange, jsonMapper.toBytes(Map.of(
                        "jsonrpc", "2.0",
                        "error", Map.of("code", 1600, "message", e.getMessage()))),
                "application/json");
        return null;
    }

    // todo: define an api for that?
    private Map<String, ConfigurationProperty> findConfiguration(final Provider provider, final Gav gav, final Map<String, List<DocItem>> confDoc) {
        return switch (provider.name()) {
            case "minikube-github" -> {
                final var minikubeConf = index(confDoc.get(MinikubeConfiguration.class.getName()));
                final var githubConf = index(confDoc.get(GithubConfiguration.class.getName()));
                yield Map.of(
                        "enabled", new ConfigurationProperty(minikubeConf.get("enabled").documentation(), Boolean.toString(minikube.enabled())),
                        "base", new ConfigurationProperty(githubConf.get("base").documentation(), github.configuration().base()),
                        "local", new ConfigurationProperty(githubConf.get("local").documentation(), github.configuration().local()),
                        "header", new ConfigurationProperty(githubConf.get("header").documentation(), github.configuration().header()));
            }
            case "sdkman" -> {
                final var sdkmanConf = index(confDoc.get(SdkManConfiguration.class.getName()));
                yield Map.of(
                        "enabled", new ConfigurationProperty(sdkmanConf.get("enabled").documentation(), Boolean.toString(sdkman.enabled())),
                        "base", new ConfigurationProperty(sdkmanConf.get("base").documentation(), sdkman.base()),
                        "local", new ConfigurationProperty(sdkmanConf.get("local").documentation(), sdkman.local()),
                        "platform", new ConfigurationProperty(sdkmanConf.get("platform").documentation(), sdkman.platform()));
            }
            case "zulu" -> {
                final var conf = index(confDoc.get(ZuluCdnConfiguration.class.getName()));
                yield Map.of(
                        "enabled", new ConfigurationProperty(conf.get("enabled").documentation(), Boolean.toString(zulu.enabled())),
                        "apiBase", new ConfigurationProperty(conf.get("apiBase").documentation(), zulu.apiBase()),
                        "base", new ConfigurationProperty(conf.get("base").documentation(), zulu.base()),
                        "local", new ConfigurationProperty(conf.get("local").documentation(), zulu.local()),
                        "platform", new ConfigurationProperty(conf.get("platform").documentation(), zulu.platform()),
                        "preferApi", new ConfigurationProperty(conf.get("preferApi").documentation(), Boolean.toString(zulu.preferApi())),
                        "preferJre", new ConfigurationProperty(conf.get("preferJre").documentation(), Boolean.toString(zulu.preferJre())));
            }
            default -> {
                final var pck = provider.getClass().getPackage();
                if (pck != null && pck.getName().endsWith(".central")) {
                    final var conf = index(confDoc.get(CentralConfiguration.class.getName()));
                    yield Map.of(
                            "enabled", new ConfigurationProperty("Is the provider enabled", configuration.get(gav.artifactId() + ".enabled").orElse("true")),
                            "gav", new ConfigurationProperty("Artifact coordinates", gav.groupId() + ':' + gav.artifactId() + ':' + gav.type() + (gav.classifier() != null ? ':' + gav.classifier() : "")),
                            "base", new ConfigurationProperty(conf.get("base").documentation(), central.base()),
                            "local", new ConfigurationProperty(conf.get("local").documentation(), central.local()),
                            "header", new ConfigurationProperty(conf.get("header").documentation(), central.header()));
                }
                yield Map.of("enabled", new ConfigurationProperty("-", "true"));
            }
        };
    }

    private Map<String, DocItem> index(final List<DocItem> docItems) {
        return docItems.stream().collect(toMap(i -> i.name().substring(i.name().lastIndexOf('.') + 1), identity()));
    }

    private void sendPayload(final HttpExchange exchange, final byte[] payload, final String type) {
        try {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseHeaders().set("content-type", type);
            exchange.getResponseBody().write(payload);
        } catch (final IOException ioe) {
            logger.log(FINEST, ioe, () -> "Message already closed: " + ioe.getMessage() + "\n" + exchange.getRequestURI());
        } finally {
            try {
                exchange.close();
            } catch (final RuntimeException re) {
                // not important
                logger.log(FINEST, re, () -> "Message already closed so can't close twice the exchange: " + re.getMessage() + "\n" + exchange.getRequestURI());
            }
        }
    }

    @RootConfiguration("http")
    public record Conf(
            @Property(documentation = "Binding address.", defaultValue = "\"localhost\"") String address,
            @Property(documentation = "Port to open the server to, by default it is random.", defaultValue = "0") int port,
            @Property(documentation = "Should the server be opened ASAP if possible on your system or just the server be launched.", defaultValue = "true") boolean open) {
    }

    @JsonModel
    public record ConfigurationProperty(String documentation, String value) {
    }

    @JsonModel
    public record ConfDoc(Map<String, List<DocItem>> classes) {
    }

    @JsonModel
    public record DocItem(String name, String documentation, Object defaultValue, boolean required) {
    }
}
