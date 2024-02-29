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
package io.yupiik.dev.shared.http;

import io.yupiik.dev.provider.Provider;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import io.yupiik.fusion.httpclient.core.listener.impl.DefaultTimeout;
import io.yupiik.fusion.httpclient.core.listener.impl.ExchangeLogger;
import io.yupiik.fusion.httpclient.core.request.UnlockedHttpRequest;
import io.yupiik.fusion.httpclient.core.response.StaticHttpResponse;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Clock.systemDefaultZone;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class YemHttpClient implements AutoCloseable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final ExtendedHttpClient client;
    private final Cache cache;
    private final Map<String, Boolean> state;
    private final Map<AuthKey, Auth> authentications;
    private final int offlineTimeout;
    private final int interfaces;

    private volatile boolean offline;

    protected YemHttpClient() { // for subclassing proxy
        this.client = null;
        this.cache = null;
        this.interfaces = 0;
        this.offlineTimeout = 0;
        this.offline = false;
        this.state = null;
        this.authentications = null;
    }

    public YemHttpClient(final HttpConfiguration configuration, final Cache cache) {
        List<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.networkInterfaces().filter(n -> {
                try {
                    return !n.isLoopback() && !n.isVirtual();
                } catch (final SocketException e) {
                    return false;
                }
            }).toList();
        } catch (SocketException e) {
            interfaces = List.of();
        }

        this.interfaces = interfaces.size();
        this.offline = configuration.offlineMode() || detectIsOffline(interfaces);
        this.offlineTimeout = configuration.offlineTimeout();
        this.cache = cache;
        this.state = new ConcurrentHashMap<>();
        this.authentications = new ConcurrentHashMap<>();

        if (configuration.ignoreSSLErrors()) {
            // can be late but generally first one will enable it - FOR TESTING ONLY anyway
            final var currentValue = System.getProperty("jdk.internal.httpclient.disableHostnameVerification");
            if (!Boolean.parseBoolean(currentValue)) {
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            }
        }

        final var listeners = new ArrayList<RequestListener<?>>();
        if (configuration.log()) {
            listeners.add((new ExchangeLogger(
                    Logger.getLogger("io.yupiik.dev.shared.http.HttpClient"),
                    systemDefaultZone(),
                    true)));
        }
        if (configuration.requestTimeout() > 0) {
            listeners.add(new DefaultTimeout(Duration.ofMillis(configuration.requestTimeout())));
        }
        listeners.add(new RequestListener<Void>() { // prefer gzip if enabled
            @Override
            public RequestListener.State<Void> before(final long count, final HttpRequest request) {
                final var headers = request.headers();
                return new RequestListener.State<>(new UnlockedHttpRequest(
                        request.bodyPublisher(), request.method(),
                        request.timeout(), request.expectContinue(),
                        request.uri(), request.version(),
                        headers.firstValue("accept-encoding").isEmpty() ?
                                HttpHeaders.of(
                                        Stream.concat(
                                                        headers.map().entrySet().stream(),
                                                        Map.of("accept-encoding", List.of("gzip")).entrySet().stream())
                                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)),
                                        (k, v) -> true) :
                                headers),
                        null);
            }
        });

        final var httpClientBuilder = HttpClient.newBuilder()
                .followRedirects(ALWAYS)
                .version(HTTP_1_1)
                .executor(Executors.newFixedThreadPool(configuration.threads(), new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(1);

                    @Override
                    public Thread newThread(final Runnable r) {
                        final var thread = new Thread(r, YemHttpClient.class.getName() + "-" + counter.getAndIncrement());
                        thread.setContextClassLoader(YemHttpClient.class.getClassLoader());
                        return thread;
                    }
                }))
                .connectTimeout(Duration.ofMillis(configuration.connectTimeout()));
        if (configuration.ignoreSSLErrors()) {
            httpClientBuilder.sslContext(unsafeSSLContext());
        }

        final var proxy = configuration.proxy();
        if (proxy != null && proxy.host() != null && !"none".equals(proxy.host()) && !proxy.host().isBlank()) {
            if (proxy.username() != null && !proxy.username().isBlank() && !"none".equals(proxy.username())) {
                logger.finest(() -> "Enabling proxy authentication");
                final var pa = new PasswordAuthentication(
                        proxy.username(),
                        requireNonNull(proxy.password(), "Missing http proxy password.").toCharArray());
                httpClientBuilder.authenticator(new Authenticator() {
                    @Override
                    public PasswordAuthentication requestPasswordAuthenticationInstance(
                            final String host, final InetAddress addr, final int port,
                            final String protocol, final String prompt, final String scheme,
                            final URL url, final RequestorType reqType) {
                        if (reqType == RequestorType.PROXY && (proxy.ignoredHosts() == null || !proxy.ignoredHosts().contains(host))) {
                            logger.finest(() -> "Using proxy for '" + url + "'");
                            return super.requestPasswordAuthenticationInstance(host, addr, port, protocol, prompt, scheme, url, reqType);
                        } else if (reqType == RequestorType.PROXY) {
                            logger.finest(() -> "Skipping proxy for '" + url + "'");
                        }

                        if (reqType == RequestorType.SERVER) {
                            final var auth = authentications.get(new AuthKey(host, port));
                            if (auth != null && "authorization".equalsIgnoreCase(auth.header())) {
                                final var decoded = new String(Base64.getDecoder().decode(auth.value()), UTF_8);
                                if (decoded.length() > "basic ".length() && decoded.substring("basic ".length()).equalsIgnoreCase("basic ")) {
                                    final int sep = decoded.indexOf(':');
                                    if (sep > 0) {
                                        final var user = decoded.substring("basic ".length(), sep);
                                        logger.finest(() -> "Using user '" + user + "' for '" + url + "'");
                                        return new PasswordAuthentication(user, decoded.substring(sep + 1).toCharArray());
                                    } // else can it work?
                                }
                            }

                            logger.finest(() -> "No authentication for '" + url + "'");
                        }

                        return null;
                    }

                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return pa;
                    }
                });
            } else {
                logger.finest(() -> "No proxy authentication");
            }

            logger.finest(() -> "Enabling proxy usage");
            httpClientBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
        }

        final var conf = new ExtendedHttpClientConfiguration()
                .setDelegate(httpClientBuilder.build())
                .setRequestListeners(listeners);

        this.client = new ExtendedHttpClient(conf).onClose(c -> {
            final var executorService = (ExecutorService) conf.getDelegate().executor().orElseThrow();
            executorService.shutdown();
            while (!executorService.isTerminated()) {
                try {
                    if (executorService.awaitTermination(1L, TimeUnit.DAYS)) {
                        break;
                    }
                } catch (final InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private boolean detectIsOffline(final List<NetworkInterface> networkInterfaces) {
        return networkInterfaces.stream().noneMatch(n -> {
            try {
                return n.isUp();
            } catch (final SocketException e) {
                return false;
            }
        });
    }

    @Destroy
    @Override
    public void close() {
        this.client.close();
    }

    public YemHttpClient registerAuthentication(final String host, final int port, final String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return this;
        }

        final int nameEnd = rawHeader.indexOf(':');
        if (nameEnd < 0) {
            logger.warning(() -> "Header for " + host + ":" + port + " not using the syntax 'name: value', ignoring");
            return this;
        }

        // we can computeIfAbsent() since it is not supposed to change when registered on global configuration
        authentications.computeIfAbsent(new AuthKey(host, port), k -> new Auth(
                rawHeader.substring(0, nameEnd).strip(), rawHeader.substring(nameEnd + 1).stripLeading()));
        return this;
    }

    public CompletionStage<HttpResponse<Path>> getFile(final HttpRequest request, final Path target, final Provider.ProgressListener listener) {
        logger.finest(() -> "Calling " + request);
        checkOffline(request.uri());
        return sendWithProgress(wrapRequest(request), listener, HttpResponse.BodyHandlers.ofFile(target))
                .thenApply(response -> {
                    if (isGzip(response) && Files.exists(response.body())) {
                        final var tmp = response.body().getParent().resolve(response.body().getFileName() + ".degzip.tmp");
                        try (final var in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(response.body())))) {
                            Files.copy(in, tmp);
                        } catch (final IOException ioe) {
                            return response;
                        } finally {
                            if (Files.exists(tmp)) {
                                try {
                                    Files.delete(tmp);
                                } catch (final IOException e) {
                                    // no-op
                                }
                            }
                        }
                        try {
                            Files.move(tmp, response.body());
                        } catch (final IOException e) {
                            return response;
                        }
                        return new SimpleHttpResponse<>(
                                response.request(), response.uri(), response.version(), response.statusCode(), response.headers(),
                                response.body());
                    }
                    return response;
                });
    }

    public CompletionStage<HttpResponse<String>> sendAsync(final HttpRequest request) {
        final var entry = cache.lookup(request);
        if (entry != null && entry.hit() != null && !entry.expired()) {
            return fromCache(request, entry);
        }

        logger.finest(() -> "Calling " + request);
        try {
            checkOffline(request.uri());
        } catch (final RuntimeException re) {
            if (entry != null && entry.hit() != null) { // expired but use it instead of failling
                return fromCache(request, entry);
            }
            throw re;
        }
        return client.sendAsync(wrapRequest(request), ofByteArray())
                .thenApply(response -> {
                    HttpResponse<String> result = null;
                    if (isGzip(response) && response.body() != null) {
                        try (final var in = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                            result = new SimpleHttpResponse<>(
                                    response.request(), response.uri(), response.version(), response.statusCode(), response.headers(),
                                    new String(in.readAllBytes(), UTF_8));
                        } catch (final IOException ioe) {
                            // no-op, use original response
                        }
                    }
                    result = result != null ? result : new SimpleHttpResponse<>(
                            response.request(), response.uri(), response.version(), response.statusCode(), response.headers(),
                            new String(response.body(), UTF_8));
                    if (entry != null && result.statusCode() == 200) {
                        logger.info(() -> "Updated '" + request.uri() + "' metadata");
                        cache.save(entry.key(), result);
                    }
                    return result;
                })
                .exceptionally(e -> {
                    if (e instanceof CompletionException ce && ce.getCause() instanceof HttpConnectTimeoutException) {
                        offline = true;
                        if (entry != null && entry.hit() != null && entry.hit().headers() != null && entry.hit().payload() != null) {
                            // refresh the cached entry for next runs
                            logger.info(() -> "Keeping cached entry for '" + request.uri() + "' metadata since system is offline");
                            cache.save(entry.key(), entry.hit().headers(), entry.hit().payload());
                        }
                        throw ce;
                    }
                    throw new IllegalStateException(e);
                });
    }

    private HttpRequest wrapRequest(final HttpRequest request) {
        final var auth = authentications.get(new AuthKey(request.uri().getHost(), request.uri().getPort()));
        if (auth != null && request.headers().firstValue(auth.header()).isEmpty()) {
            logger.finest(() -> "Using authentication for '" + request.uri() + "'");
            return new UnlockedHttpRequest(
                    request.bodyPublisher(),
                    request.method(), request.timeout(), request.expectContinue(), request.uri(),
                    request.version(), HttpHeaders.of(Stream.concat(
                            request.headers().map().entrySet().stream(),
                            Stream.of(entry(auth.header(), List.of(auth.value()))))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b)), (a, b) -> true));
        }

        logger.finest(() -> "No authentication for '" + request.uri() + "'");
        return request;
    }

    private CompletableFuture<HttpResponse<String>> fromCache(final HttpRequest request, final Cache.CachedEntry entry) {
        return completedFuture(new SimpleHttpResponse<>(
                request, request.uri(), HTTP_1_1, 200,
                HttpHeaders.of(
                        entry.hit().headers().entrySet().stream()
                                .collect(toMap(Map.Entry::getKey, e -> List.of(e.getValue()))),
                        (a, b) -> true),
                entry.hit().payload()));
    }

    private void checkOffline(final URI uri) {
        if (offline) {
            throw OfflineException.INSTANCE;
        }
        if (state.computeIfAbsent(uri.getHost() + ":" + uri.getPort(), k -> {
            if (interfaces == 1 && !state.isEmpty()) {
                return state.values().iterator().next();
            }

            try (final var socket = new Socket()) {
                final var address = new InetSocketAddress(uri.getHost(), uri.getPort() >= 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80));
                socket.connect(address, offlineTimeout);
                socket.getInputStream().close();
                return false;
            } catch (final IOException e) {
                return true;
            }
        })) {
            throw OfflineException.INSTANCE;
        }
    }

    private boolean isGzip(final HttpResponse<?> response) {
        return response.headers().allValues("content-encoding").stream().anyMatch(it -> it.contains("gzip"));
    }

    private SSLContext unsafeSSLContext() {
        try {
            final var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    null,
                    new TrustManager[]{
                            new X509TrustManager() {
                                @Override
                                public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
                                    // no-op
                                }

                                @Override
                                public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
                                    // no-op
                                }

                                @Override
                                public X509Certificate[] getAcceptedIssuers() {
                                    return null;
                                }
                            }
                    },
                    new SecureRandom());

            return sslContext;
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private <A> CompletionStage<HttpResponse<A>> sendWithProgress(final HttpRequest request, final Provider.ProgressListener listener,
                                                                  final HttpResponse.BodyHandler<A> delegateHandler) {
        return client.sendAsync(request, listener == Provider.ProgressListener.NOOP ? delegateHandler : responseInfo -> {
            final long contentLength = Long.parseLong(responseInfo.headers().firstValue("content-length").orElse("-1"));
            final var delegate = delegateHandler.apply(responseInfo);
            if (contentLength > 0) {
                final var name = request.uri().getPath();
                return HttpResponse.BodySubscribers.fromSubscriber(new Flow.Subscriber<List<ByteBuffer>>() {
                    private long current = 0;

                    @Override
                    public void onSubscribe(final Flow.Subscription subscription) {
                        delegate.onSubscribe(subscription);
                    }

                    @Override
                    public void onNext(final List<ByteBuffer> item) {
                        current += item.stream().mapToLong(ByteBuffer::remaining).sum();
                        listener.onProcess(name, current * 1. / contentLength);
                        delegate.onNext(item);
                    }

                    @Override
                    public void onError(final Throwable throwable) {
                        delegate.onError(throwable);
                    }

                    @Override
                    public void onComplete() {
                        delegate.onComplete();
                    }
                }, subscriber -> delegate.getBody().toCompletableFuture().getNow(null));
            }
            return delegate;
        });
    }

    private static class SimpleHttpResponse<T> extends StaticHttpResponse<T> {
        private SimpleHttpResponse(final HttpRequest request, final URI uri, final HttpClient.Version version,
                                   final int status, final HttpHeaders headers, final T body) {
            super(request, uri, version, status, headers, body);
        }

        @Override
        public String toString() {
            final var uri = request().uri();
            return '(' + request().method() + ' ' + (uri == null ? "" : uri) + ") " + statusCode();
        }
    }

    public static class OfflineException extends RuntimeException {
        public static final OfflineException INSTANCE = new OfflineException();

        static {
            INSTANCE.setStackTrace(new StackTraceElement[0]);
        }

        public OfflineException() {
            super("Network is offline");
        }
    }

    private record AuthKey(String host, int port) {
    }

    private record Auth(String header, String value) {
    }
}
