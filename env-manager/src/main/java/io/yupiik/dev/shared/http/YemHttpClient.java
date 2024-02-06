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
package io.yupiik.dev.shared.http;

import io.yupiik.dev.provider.Provider;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import io.yupiik.fusion.httpclient.core.listener.impl.DefaultTimeout;
import io.yupiik.fusion.httpclient.core.listener.impl.ExchangeLogger;
import io.yupiik.fusion.httpclient.core.request.UnlockedHttpRequest;
import io.yupiik.fusion.httpclient.core.response.StaticHttpResponse;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class YemHttpClient implements AutoCloseable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final ExtendedHttpClient client;
    private final Cache cache;
    private final Map<String, Boolean> state = new ConcurrentHashMap<>();
    private final int offlineTimeout;
    private final boolean offline;

    protected YemHttpClient() { // for subclassing proxy
        this.client = null;
        this.cache = null;
        this.offlineTimeout = 0;
        this.offline = false;
    }

    public YemHttpClient(final HttpConfiguration configuration, final Cache cache) {
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

        final var conf = new ExtendedHttpClientConfiguration()
                .setDelegate(HttpClient.newBuilder()
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
                        .connectTimeout(Duration.ofMillis(configuration.connectTimeout()))
                        .build())
                .setRequestListeners(listeners);

        this.offline = configuration.offlineMode();
        this.offlineTimeout = configuration.offlineTimeout();
        this.cache = cache;
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

    @Override
    public void close() {
        this.client.close();
    }

    public CompletionStage<HttpResponse<Path>> getFile(final HttpRequest request, final Path target, final Provider.ProgressListener listener) {
        logger.finest(() -> "Calling " + request);
        checkOffline(request.uri());
        return sendWithProgress(request, listener, HttpResponse.BodyHandlers.ofFile(target))
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
        return client.sendAsync(request, ofByteArray())
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
                });
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
}
