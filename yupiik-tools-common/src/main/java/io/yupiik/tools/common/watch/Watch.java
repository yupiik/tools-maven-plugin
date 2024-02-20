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
package io.yupiik.tools.common.watch;

import lombok.RequiredArgsConstructor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;

@RequiredArgsConstructor
public class Watch<O, A> implements Runnable {
    private final Consumer<String> logInfo;
    private final Consumer<String> logDebug;
    private final BiConsumer<String, Throwable> logDebugWithException;
    private final Consumer<String> logError;
    private final List<Path> sources;
    private final O options;
    private final A asciidoctor;
    private final long watchDelay;
    private final BiConsumer<O, A> renderer;
    private final Runnable onFirstRender;

    @Override
    public void run() {
        watch(options, asciidoctor);
    }

    private void watch(final O options, final A adoc) {
        final ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable worker) {
                final Thread thread = new Thread(worker, getClass().getName() + "-watch");
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        });
        final AtomicLong lastModified = new AtomicLong(findLastUpdated(-1, sources));
        final AtomicLong checksCount = new AtomicLong(0);
        service.scheduleWithFixedDelay(() -> {
            final long currentLM = findLastUpdated(-1, sources);
            final long lastModifiedValue = lastModified.get();
            if (lastModifiedValue < currentLM) {
                lastModified.set(currentLM);
                if (checksCount.getAndIncrement() > 0) {
                    logDebug.accept("Change detected, re-rendering");
                    renderer.accept(options, adoc);
                    checksCount.set(0);
                } else {
                    logDebug.accept("Change detected, waiting another iteration to ensure it is fully refreshed");
                }
            } else if (checksCount.get() > 0) {
                logDebug.accept("Change detected, re-rendering");
                renderer.accept(options, adoc);
                checksCount.set(0);
            } else {
                logDebug.accept("No change");
            }
        }, watchDelay, watchDelay, TimeUnit.MILLISECONDS);
        launchCli(options, adoc);
        try {
            service.shutdownNow();
            service.awaitTermination(2, SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void launchCli(final O options, final A adoc) {
        renderer.accept(options, adoc);
        onFirstRender.run();

        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            logInfo.accept("Type '[refresh|exit]' to either force a rendering or exit");
            while ((line = reader.readLine()) != null)
                switch (line) {
                    case "":
                    case "r":
                    case "refresh":
                        renderer.accept(options, adoc);
                        break;
                    case "exit":
                    case "quit":
                    case "q":
                        return;
                    default:
                        logError.accept("Unknown command: '" + line + "', type: '[refresh|exit]'");
                }
        } catch (final IOException e) {
            logDebugWithException.accept("Exiting waiting loop", e);
        }
    }

    private long findLastUpdated(final long value, final List<Path> dirs) {
        return dirs.stream().mapToLong(it -> findLastUpdated(value, it)).max().orElse(value);
    }

    private long findLastUpdated(final long value, final Path dir) {
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try {
                final var max = new AtomicLong(0);
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                        if (isIgnored(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return super.preVisitDirectory(dir, attrs);
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        try {
                            final var current = Files.getLastModifiedTime(file).toMillis();
                            if (current > max.get()) {
                                max.set(current);
                            }
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                        return super.visitFile(file, attrs);
                    }
                });
                final var result = max.get();
                if (result == 0) {
                    return value;
                }
                return result;
            } catch (final IOException e) {
                // no-op, default to value for this iteration
            }
        }
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isIgnored(final Path dir) {
        final var name = dir.getFileName().toString();
        return ".idea".equals(name) || "target".equals(name) || "node_modules".equals(name);
    }
}
