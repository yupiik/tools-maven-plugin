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
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import io.yupiik.fusion.httpclient.core.listener.impl.DefaultTimeout;
import io.yupiik.fusion.httpclient.core.listener.impl.ExchangeLogger;
import io.yupiik.fusion.httpclient.core.request.UnlockedHttpRequest;
import io.yupiik.fusion.httpclient.core.response.StaticHttpResponse;
import io.yupiik.fusion.json.JsonMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Clock.systemDefaultZone;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class YemHttpClient implements AutoCloseable {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final ExtendedHttpClient client;
    private final Path cache;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final long cacheValidity;

    protected YemHttpClient() { // for subclassing proxy
        this.client = null;
        this.cache = null;
        this.jsonMapper = null;
        this.clock = null;
        this.cacheValidity = 0L;
    }

    public YemHttpClient(final HttpConfiguration configuration, final JsonMapper jsonMapper) {
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
                        .connectTimeout(Duration.ofMillis(configuration.connectTimeout()))
                        .build())
                .setRequestListeners(listeners);

        try {
            this.cache = "none".equals(configuration.cache()) || configuration.cacheValidity() <= 0 ?
                    null :
                    Files.createDirectories(Path.of(configuration.cache()));
        } catch (final IOException e) {
            throw new IllegalArgumentException("Can't create HTTP cache directory : '" + configuration.cache() + "', adjust --http-cache parameter");
        }
        this.cacheValidity = configuration.cacheValidity();
        this.client = new ExtendedHttpClient(conf);
        this.jsonMapper = jsonMapper;
        this.clock = systemDefaultZone();
    }

    @Override
    public void close() {
        this.client.close();
    }

    public HttpResponse<Path> getFile(final HttpRequest request, final Path target, final Provider.ProgressListener listener) {
        logger.finest(() -> "Calling " + request);
        final var response = sendWithProgress(request, listener, HttpResponse.BodyHandlers.ofFile(target));
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
            return new StaticHttpResponse<>(
                    response.request(), response.uri(), response.version(), response.statusCode(), response.headers(),
                    response.body());
        }
        return response;
    }

    public HttpResponse<String> send(final HttpRequest request) {
        final Path cacheLocation;
        if (cache != null) {
            cacheLocation = cache.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(request.uri().toASCIIString().getBytes(UTF_8)));
            if (Files.exists(cacheLocation)) {
                try {
                    final var cached = jsonMapper.fromString(Response.class, Files.readString(cacheLocation));
                    if (cached.validUntil() > clock.instant().toEpochMilli()) {
                        return new StaticHttpResponse<>(
                                request, request.uri(), HTTP_1_1, 200,
                                HttpHeaders.of(
                                        cached.headers().entrySet().stream()
                                                .collect(toMap(Map.Entry::getKey, e -> List.of(e.getValue()))),
                                        (a, b) -> true),
                                cached.payload());
                    }
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        } else {
            cacheLocation = null;
        }

        logger.finest(() -> "Calling " + request);
        try {
            final var response = client.send(request, ofByteArray());
            HttpResponse<String> result = null;
            if (isGzip(response) && response.body() != null) {
                try (final var in = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                    result = new StaticHttpResponse<>(
                            response.request(), response.uri(), response.version(), response.statusCode(), response.headers(),
                            new String(in.readAllBytes(), UTF_8));
                } catch (final IOException ioe) {
                    // no-op, use original response
                }
            }
            result = result != null ? result : new StaticHttpResponse<>(
                    response.request(), response.uri(), response.version(), response.statusCode(), response.headers(),
                    new String(response.body(), UTF_8));
            if (cacheLocation != null && result.statusCode() == 200) {
                final var cachedData = jsonMapper.toString(new Response(
                        result.headers().map().entrySet().stream()
                                .filter(it -> !"content-encoding".equalsIgnoreCase(it.getKey()))
                                .collect(toMap(Map.Entry::getKey, l -> String.join(",", l.getValue()))),
                        result.body(),
                        clock.instant().plusMillis(cacheValidity).toEpochMilli()));
                try {
                    Files.writeString(cacheLocation, cachedData);
                } catch (final IOException e) {
                    try {
                        Files.deleteIfExists(cacheLocation);
                    } catch (final IOException ex) {
                        // no-op
                    }
                }
            }
            return result;
        } catch (final InterruptedException var4) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(var4);
        } catch (final RuntimeException run) {
            throw run;
        } catch (final Exception others) {
            throw new IllegalStateException(others);
        }
    }

    private boolean isGzip(final HttpResponse<?> response) {
        return response.headers().allValues("content-encoding").stream().anyMatch(it -> it.contains("gzip"));
    }

    /* not needed yet
    public HttpResponse<String> send(final HttpRequest request, final Provider.ProgressListener listener) {
        return sendWithProgress(request, listener, ofString());
    }
     */

    private <A> HttpResponse<A> sendWithProgress(final HttpRequest request, final Provider.ProgressListener listener,
                                                 final HttpResponse.BodyHandler<A> delegateHandler) {
        try {
            return client.send(request, listener == Provider.ProgressListener.NOOP ? delegateHandler : responseInfo -> {
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
        } catch (final InterruptedException var3) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(var3);
        } catch (final RuntimeException var4) {
            throw var4;
        } catch (final Exception var5) {
            throw new IllegalStateException(var5);
        }
    }

    @JsonModel
    public record Response(Map<String, String> headers, String payload, long validUntil) {
    }
}
