/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.tools.minisite.action.builtin;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public class DownloadAndUnzip implements Runnable {
    private final String url;
    private final String subpath;
    private final String target;
    private final String workdir;
    private final Properties headers;

    public DownloadAndUnzip(final Map<String, String> configuration) {
        this.url = requireNonNull(configuration.get("url"), "No 'url'.");
        this.subpath = configuration.getOrDefault("subpath", "");
        this.target = requireNonNull(configuration.get("target"), "No 'target'");
        this.workdir = configuration.get("workdir");
        this.headers = ofNullable(configuration.get("headers"))
                .map(h -> {
                    final Properties props = new Properties();
                    try (final StringReader reader = new StringReader(h)) {
                        props.load(reader);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return props;
                })
                .orElseGet(Properties::new);
    }

    @Override
    public void run() {
        final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(url));
        headers.stringPropertyNames().forEach(key -> builder.header(key, headers.getProperty(key)));

        final boolean deleteWorkDir = workdir == null || !Files.exists(Paths.get(workdir));
        final Path work = ofNullable(workdir)
                .map(Paths::get)
                .orElseGet(() -> {
                    try {
                        return Files.createTempDirectory("ypkmst");
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        try {
            final Path zip = Files.createDirectories(work).resolve("download-" + url.hashCode() + ".zip");
            Files.deleteIfExists(zip);
            try {
                final HttpResponse<Path> response = http.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofFile(zip));
                if (response.statusCode() < 200 || response.statusCode() > 399) {
                    throw new IllegalStateException(response.toString());
                }

                final Path baseOutput = Paths.get(target);
                try (final ZipInputStream jar = new ZipInputStream(Files.newInputStream(zip))) {
                    ZipEntry entry;
                    while ((entry = jar.getNextEntry()) != null) {
                        if (!entry.getName().startsWith(subpath) || entry.isDirectory()) {
                            continue;
                        }
                        final Path out = baseOutput.resolve(entry.getName().substring(subpath.length()));
                        if (out.getParent() != null) {
                            Files.createDirectories(out.getParent());
                        }
                        Files.copy(jar, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                Files.deleteIfExists(zip);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            if (deleteWorkDir) {
                delete(work);
            }
        }
    }

    private void delete(final Path work) {
        try {
            Files.walkFileTree(work, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return super.visitFile(file, attrs);
                }

                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                    Files.delete(dir);
                    return super.postVisitDirectory(dir, exc);
                }
            });
        } catch (final IOException e) {
            // no-op
        }
    }
}
