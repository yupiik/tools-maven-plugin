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
package io.yupiik.dev.test;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.dev.shared.http.Cache;
import io.yupiik.dev.shared.http.HttpConfiguration;
import io.yupiik.dev.shared.http.ProxyConfiguration;
import io.yupiik.dev.shared.http.YemHttpClient;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.ofNullable;
import static org.junit.platform.commons.support.AnnotationSupport.findRepeatableAnnotations;

public class HttpMockExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    public static final HttpConfiguration DEFAULT_HTTP_CONFIGURATION = new HttpConfiguration(
            false, false, 10_000, 1, false, 30_000L, 30_000L, 0, "none",
            new ProxyConfiguration("none", 3128, "none", "none", List.of()));

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(HttpMockExtension.class);

    @Override
    public void beforeEach(final ExtensionContext context) throws Exception {
        final var annotations = Stream.concat(
                        findRepeatableAnnotations(context.getTestMethod(), Mock.class).stream(), // first to override class ones if needed
                        findRepeatableAnnotations(context.getTestClass(), Mock.class).stream())
                .toList();
        if (annotations.isEmpty()) {
            return;
        }

        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 16);
        server.createContext("/").setHandler(ex -> {
            try (ex) {
                final var method = ex.getRequestMethod();
                final var uri = ex.getRequestURI().toASCIIString();
                final var resp = annotations.stream()
                        .filter(m -> Objects.equals(method, m.method()) && Objects.equals(uri, m.uri()))
                        .findFirst()
                        .orElse(null);
                if (resp == null) {
                    ex.sendResponseHeaders(404, 0);
                } else {
                    final var bytes = process(resp.payload().getBytes(UTF_8), resp.format());
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                }
            }
        });
        server.start();
        context.getStore(NAMESPACE).put(HttpServer.class, server);
    }

    @Override
    public void afterEach(final ExtensionContext context) {
        ofNullable(context.getStore(NAMESPACE).get(HttpServer.class, HttpServer.class)).ifPresent(s -> s.stop(0));
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
        return URI.class == parameterContext.getParameter().getType() || YemHttpClient.class == parameterContext.getParameter().getType();
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext context) throws ParameterResolutionException {
        if (URI.class == parameterContext.getParameter().getType()) {
            return URI.create("http://localhost:" + context.getStore(NAMESPACE).get(HttpServer.class, HttpServer.class).getAddress().getPort() + "/2/");
        }
        if (YemHttpClient.class == parameterContext.getParameter().getType()) {
            return new YemHttpClient(DEFAULT_HTTP_CONFIGURATION, new Cache(DEFAULT_HTTP_CONFIGURATION, null));
        }
        throw new ParameterResolutionException("Can't resolve " + parameterContext.getParameter().getType());
    }

    private byte[] process(final byte[] bytes, final String format) throws IOException {
        return switch (format) {
            case "tar.gz" -> {
                final var out = new ByteArrayOutputStream();
                try (final var archive = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
                    archive.putArchiveEntry(new TarArchiveEntry("root-1.2.3/"));
                    archive.closeArchiveEntry();

                    final var archiveEntry = new TarArchiveEntry("root-1.2.3/entry.txt");
                    archiveEntry.setSize(bytes.length);
                    archive.putArchiveEntry(archiveEntry);
                    archive.write(bytes);
                    archive.closeArchiveEntry();

                    archive.finish();
                }
                yield out.toByteArray();
            }
            case "zip" -> {
                final var out = new ByteArrayOutputStream();
                try (final var archive = new ZipOutputStream(out)) {
                    archive.putNextEntry(new ZipEntry("root-1.2.3/"));
                    archive.closeEntry();
                    archive.putNextEntry(new ZipEntry("root-1.2.3/entry.txt"));
                    archive.write(bytes);
                    archive.closeEntry();
                    archive.finish();
                }
                yield out.toByteArray();
            }
            default -> bytes;
        };
    }
}
