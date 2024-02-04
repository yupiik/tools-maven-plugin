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
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Flow;

import static java.net.http.HttpResponse.BodyHandlers.ofString;

public class YemHttpClient extends ExtendedHttpClient {
    public YemHttpClient(final ExtendedHttpClientConfiguration clientConfiguration) {
        super(clientConfiguration);
    }

    public HttpResponse<Path> getFile(final HttpRequest request, final Path target, final Provider.ProgressListener listener) {
        return sendWithProgress(request, listener, HttpResponse.BodyHandlers.ofFile(target));
    }

    public HttpResponse<String> send(final HttpRequest request, final Provider.ProgressListener listener) {
        return sendWithProgress(request, listener, ofString());
    }

    private <A> HttpResponse<A> sendWithProgress(final HttpRequest request, final Provider.ProgressListener listener,
                                                 final HttpResponse.BodyHandler<A> delegateHandler) {
        try {
            return this.send(request, listener == Provider.ProgressListener.NOOP ? delegateHandler : responseInfo -> {
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
}
