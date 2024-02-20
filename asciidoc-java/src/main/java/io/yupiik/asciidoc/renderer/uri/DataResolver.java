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
package io.yupiik.asciidoc.renderer.uri;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpClient.newBuilder;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;

public class DataResolver implements Function<String, DataUri>, AutoCloseable {
    private final Path base;
    private final Map<String, DataUri> cache = new ConcurrentHashMap<>();
    private HttpClient httpClient;

    public DataResolver(final Path base) {
        this.base = base;
    }

    public Map<String, DataUri> cache() { // enable to reuse it
        return cache;
    }

    public DataResolver cache(final Map<String, DataUri> cache) {
        this.cache.putAll(cache);
        return this;
    }

    @Override
    public DataUri apply(final String path) {
        return cache.computeIfAbsent(path, k -> {
            if (k.startsWith("http://") || k.startsWith("https://")) {
                return resolveHttp(k);
            }
            return resolveLocal(path);
        });
    }

    private DataUri resolveLocal(final String path) { // todo: log
        var local = Path.of(path);
        if (!local.isAbsolute()) {
            local = base.resolve(local);
        }
        final var ref = local;
        return new DataUri(() -> {
            try {
                return Files.newInputStream(ref);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }, findMimeType(ref, () -> {
            try { // just read a few for the detection
                final var buffer = new byte[256]; // "big" mainly for svg, other cases only need 2-4 bytes
                try (final var in = Files.newInputStream(ref)) {
                    in.read(buffer);
                }
                return buffer;
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    private DataUri resolveHttp(final String url) {
        if (httpClient == null) {
            httpClient = newBuilder().followRedirects(ALWAYS).build();
        }
        try {
            final var res = httpClient.send(
                    HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create(url))
                            .build(),
                    ofByteArray());
            if (res.statusCode() >= 400) {
                throw new IllegalArgumentException("Invalid url: '" + url + "': " + res);
            }
            return new DataUri(
                    () -> new ByteArrayInputStream(res.body()),
                    findMimeType(url, () -> Arrays.copyOf(res.body(), Math.min(256, res.body().length))));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String findMimeType(final Path ref, final Supplier<byte[]> content) {
        try {
            final var found = Files.probeContentType(ref);
            if (found != null) {
                return found;
            }
        } catch (final IOException e) {
            // no-op
        }
        return findMimeType(ref.getFileName().toString(), content);
    }

    private String findMimeType(final String url, final Supplier<byte[]> content) {
        final var ext = url.substring(url.lastIndexOf('.') + 1);
        if (!ext.isBlank() && ext.length() < 25 /* arbitrary */) {
            return "image/" + ext.toLowerCase(ROOT);
        }

        final var bytes = content.get();
        if (bytes.length > 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpg";
        }
        if (bytes.length > 2 && bytes[0] == (byte) 137 && bytes[1] == (byte) 80) {
            return "image/png";
        }

        if (new String(bytes, UTF_8).contains("<svg")) {
            return "image/svg+xml";
        }
        return "";
    }

    @Override
    public void close() {
        if (httpClient instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (final RuntimeException e) {
                throw e;
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }
        httpClient = null;
    }
}
