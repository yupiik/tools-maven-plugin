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
package io.yupiik.tools.common.http;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class StaticHttpServer implements Runnable {
    private final Consumer<String> logInfo;
    private final BiConsumer<String, Throwable> logError;
    private final int port;
    private final Path docBase;
    private final String indexName;
    private final Runnable afterStart;

    @Override
    public void run() {
        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final HttpContext ctx = server.createContext("/");
        ctx.setHandler(new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                Path file = resolveFile(exchange.getRequestURI());
                if (Files.isDirectory(file)) {
                    file = file.resolve(indexName);
                }
                if (!Files.exists(file)) {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                }
                final byte[] bytes = Files.readAllBytes(file);
                exchange.getResponseHeaders().add("Content-Type", findType(file.getFileName().toString()));
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }

            private String findType(final String name) {
                if (name.endsWith(".html")) {
                    return "text/html";
                }
                if (name.endsWith(".css")) {
                    return "text/css";
                }
                if (name.endsWith(".js")) {
                    return "application/javascript";
                }
                if (name.endsWith(".svg")) {
                    return "image/svg+xml";
                }
                if (name.endsWith(".png")) {
                    return "image/png";
                }
                if (name.endsWith(".jpg")) {
                    return "image/jpg";
                }
                return "application/octect-stream";
            }

            private Path resolveFile(final URI requestURI) {
                final String path = requestURI.getPath().substring(1);
                if (path.isEmpty() || "/".equals(path)) {
                    return docBase.resolve(indexName);
                }
                return docBase.resolve(path);
            }
        });
        server.start();
        logInfo.accept("Started server at 'http://localhost:" + port + "'");
        try {
            afterStart.run();
        } finally {
            server.stop(0);
        }
    }

    public void open(final boolean openBrowser) {
        if (!openBrowser) {
            return;
        }
        final URI uri = URI.create("http://localhost:" + port);
        if (!java.awt.Desktop.isDesktopSupported()) {
            logInfo.accept("Desktop is not supported on this JVM, go to " + uri + " in your browser");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(uri);
        } catch (final IOException | RuntimeException e) { // seems broken on recent linux version and java is a bit late to fix it
            try {
                final var xdgOpen = Path.of("/usr/bin/xdg-open");
                if (Files.exists(xdgOpen)) {
                    new ProcessBuilder(xdgOpen.toString(), uri.toString()).start().waitFor();
                } // else todo
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (final RuntimeException | IOException re) {
                logError.accept("Desktop is not supported on this JVM, go to " + uri + " in your browser (" + e.getMessage() + ")", e);
            }
        }
    }
}
