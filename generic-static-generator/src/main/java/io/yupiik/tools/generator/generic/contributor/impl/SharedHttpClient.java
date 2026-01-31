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
package io.yupiik.tools.generator.generic.contributor.impl;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.httpclient.core.DelegatingHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.impl.ExchangeLogger;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static java.time.Clock.systemUTC;

@ApplicationScoped
public class SharedHttpClient implements AutoCloseable {
    private volatile ExtendedHttpClient client;

    private final HttpConfiguration configuration;

    public SharedHttpClient(final HttpConfiguration configuration) {
        this.configuration = configuration;
    }

    // we know we have a single executor per execution until reused so we can capture it lazily for now
    public HttpClient getOrCreate(final Executor executor) {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    var delegate = HttpClient.newBuilder()
                            .executor(executor)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .version(configuration.forceHttp11() ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
                            .build();
                    if (configuration.maxHttp2Concurrency() > 0) {
                        delegate = new DelegatingHttpClient(delegate) {
                            private final AsyncSemaphore semaphore = new AsyncSemaphore(configuration.maxHttp2Concurrency());

                            @Override
                            public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                                                    final HttpResponse.BodyHandler<T> responseBodyHandler) {
                                final var isHttp2 = request
                                        .version()
                                        .map(it -> it == HttpClient.Version.HTTP_2)
                                        .orElse(true);

                                if (!isHttp2) {
                                    return super.sendAsync(request, responseBodyHandler);
                                }

                                return semaphore
                                        .acquire()
                                        .thenCompose(v -> super.sendAsync(request, responseBodyHandler))
                                        .whenComplete((ok, ko) -> semaphore.release());
                            }
                        };
                    }
                    client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                            .setRequestListeners(List.of(new ExchangeLogger(
                                    Logger.getLogger(getClass().getName()), systemUTC(), false)))
                            .setDelegate(delegate));
                }
            }
        }
        return client;
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    @RootConfiguration("http")
    public record HttpConfiguration(
            @Property(value = "force-http1.1", documentation = "HTTP/2.0 has several pitfalls when it comes to concurrency (max stream limit) and security to it can be neat to disable it sometimes.", defaultValue = "false")
            boolean forceHttp11,

            @Property(value = "max-http2-concurrency", documentation = "Allowed max concurrency if using HTTP/2.0. This is mainly to align on `MAX_CONCURRENT_STREAMS` value. Negative means unbounded.", defaultValue = "-1")
            int maxHttp2Concurrency
    ) {
    }

    // FIXME: to replace by fusion once upgraded to 1.0.34
    private static final class AsyncSemaphore {
        private int permits;
        private final Queue<CompletableFuture<Permit>> waiters = new ConcurrentLinkedQueue<>();

        private AsyncSemaphore(final int permits) {
            if (permits <= 0) {
                throw new IllegalArgumentException("permits <= 0: " + permits);
            }
            this.permits = permits;
        }

        private CompletableFuture<Permit> acquire() {
            CompletableFuture<Permit> cf;
            synchronized (this) {
                if (permits > 0) {
                    permits--;
                    return CompletableFuture.completedFuture(new Permit(this));
                }

                cf = new CompletableFuture<>();
            }
            waiters.add(cf);
            return cf;
        }

        private void release() {
            CompletableFuture<Permit> next;
            synchronized (this) {
                next = waiters.poll();
                if (next == null) {
                    permits++;
                    return;
                }
            }
            next.complete(new Permit(this));
        }

        private static final class Permit implements AutoCloseable {
            private final AsyncSemaphore semaphore;
            private final AtomicBoolean released = new AtomicBoolean(false);

            private Permit(final AsyncSemaphore semaphore) {
                this.semaphore = semaphore;
            }

            @Override
            public void close() {
                if (released.compareAndSet(false, true)) {
                    semaphore.release();
                }
            }
        }
    }
}
