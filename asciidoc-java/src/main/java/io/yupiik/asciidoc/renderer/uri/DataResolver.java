package io.yupiik.asciidoc.renderer.uri;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static java.net.http.HttpClient.newHttpClient;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
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
        }, findMimeType(path));
    }

    private DataUri resolveHttp(final String url) {
        if (httpClient == null) {
            httpClient = newHttpClient();
        }
        try {
            final var res = httpClient.send(
                    HttpRequest.newBuilder()
                            .build(),
                    ofByteArray());
            if (res.statusCode() >= 400) {
                throw new IllegalArgumentException("Invalid url: '" + url + "': " + res);
            }
            return new DataUri(() -> new ByteArrayInputStream(res.body()), findMimeType(url));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String findMimeType(final String url) {
        final var ext = url.substring(url.lastIndexOf('.') + 1);
        if (ext.length() < url.length()) {
            return "image/" + ext.toLowerCase(ROOT);
        }
        return "application/octet-stream";
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
