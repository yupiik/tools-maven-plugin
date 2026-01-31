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
package io.yupiik.tools.generator.generic;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.compiler.accessor.MapAccessor;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;
import io.yupiik.tools.generator.generic.contributor.ContributorRegistry;
import io.yupiik.tools.generator.generic.helper.HelperRegistry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.yupiik.tools.generator.generic.io.IO.loadFromFileOrIdentity;
import static java.util.Map.entry;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

@DefaultScoped
@Command(
        name = "generic-static-generator",
        description = """
                Simple extensible generic static generator.
                
                Base concept is to create a _context_ based on _ContextContributors_ 
                and pass this context to some handlebars template to render a text file (can be a HTML or not content).
                
                A `ContextContributor` must implement `io.yupiik.tools.generator.generic.contributor.ContextContributor` interface
                and register itself in Yupiik Fusion IoC using a `FusionModule` `ServiceLoader` registration.
                
                Similarly, `io.yupiik.tools.generator.generic.helper.Helper` can be registered as a fusion bean to add helpers to the
                rendering context in addition to the default ones.
                
                TIP: it can also be used to generate a static javascript site dataset - the site being built elsewhere with `rsbuild`, `esbuild` or any friend
                     and the generate just used to aggregate data and dump them as json using `toJson` helper.
                
                == Contributors
                
                include::{partialsdir}/generated/static-generator.contributors.adoc[]
                
                == Helpers
                
                include::{partialsdir}/generated/static-generator.helpers.adoc[]
                """)
public class GenericStaticGenerator implements Runnable {
    private final GenericStaticGeneratorConfiguration configuration;
    private final List<ContextContributor> contributors;
    private final Map<String, Function<Object, String>> helpers;

    public GenericStaticGenerator(final GenericStaticGeneratorConfiguration configuration,
                                  final ContributorRegistry contributorRegistry,
                                  final HelperRegistry helperRegistry) {
        this.configuration = configuration;
        this.contributors = contributorRegistry.contributors();
        this.helpers = helperRegistry.helpers();
    }

    @Override
    public void run() {
        final var executorServiceRef = new AtomicReference<ExecutorService>();
        final var lazyExecutorService = new Executor() {
            @Override
            public void execute(final Runnable command) {
                if (executorServiceRef.get() == null) {
                    synchronized (executorServiceRef) {
                        if (executorServiceRef.get() == null) {
                            executorServiceRef.set(Executors.newScheduledThreadPool(Math.max(1, configuration.threads()), new ThreadFactory() {
                                private final AtomicInteger counter = new AtomicInteger();

                                @Override
                                public Thread newThread(final Runnable r) {
                                    final var thread = new Thread(r, GenericStaticGenerator.class.getName() + "-" + counter.getAndIncrement());
                                    thread.setContextClassLoader(GenericStaticGenerator.class.getClassLoader());
                                    return thread;
                                }
                            }));
                        }
                    }
                }
                executorServiceRef.get().execute(command);
            }
        };
        try {
            final var contributorIndex = contributors.stream().collect(toMap(ContextContributor::name, identity()));
            final var contributions = configuration.contributors().stream()
                    .map(it -> {
                        final var contributor = contributorIndex.get(it.name());
                        if (contributor == null) {
                            if ("noop".equals(it.name())) { // virtual contributor
                                return Map.entry("noop", completedFuture(Map.of()));
                            }
                            throw new IllegalArgumentException("Unknown contributor '" + it.name() + "', available: " + contributorIndex.keySet());
                        }
                        return Map.entry(
                                it.contextName().isBlank() ? contributor.name() : it.contextName(),
                                contributor.contribute(it.configuration(), lazyExecutorService).toCompletableFuture());
                    })
                    .toList();
            allOf(contributions.stream().map(Map.Entry::getValue).toArray(CompletableFuture<?>[]::new));

            final var context = contributions.stream()
                    .map(it -> {
                        try {
                            return Map.entry(it.getKey(), it.getValue().get());
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return Map.entry("", Map.of());
                        } catch (final ExecutionException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

            final var content = new HandlebarsCompiler(new MapAccessor())
                    .compile(new HandlebarsCompiler.CompilationContext(
                            new HandlebarsCompiler.Settings()
                                    .helpers(helpers)
                                    .partials(configuration.partials().isEmpty() ?
                                            Map.of() :
                                            processPartials(configuration.partials()).entrySet().stream()
                                                    .collect(toMap(Map.Entry::getKey, it -> loadFromFileOrIdentity(it.getValue())))),
                            loadFromFileOrIdentity(configuration.template())))
                    .render(context);

            try (final var out = switch (configuration.output()) {
                case "STDOUT", "STDERR" -> new BufferedWriter(new OutputStreamWriter(
                        "STDOUT".equals(configuration.output()) ? System.out : System.err, StandardCharsets.UTF_8) {
                    @Override
                    public void close() throws IOException {
                        super.flush();
                    }
                });
                default -> {
                    Path path = Path.of(configuration.output());
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    yield Files.newBufferedWriter(path);
                }
            }) {
                out.write(content);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } finally {
            final var executorService = executorServiceRef.get();
            if (executorService != null && !executorService.isShutdown() && !executorService.isTerminated()) {
                // no need to await, we just awaited before the rendering
                executorService.shutdownNow();
            }
        }
    }

    private Map<String, String> processPartials(final Map<String, String> partials) {
        return partials.entrySet().stream()
                .flatMap(it -> {
                    if (it.getKey().endsWith("/*.hb")) {
                        try (final var list = Files.list(Path.of(it.getKey().substring(0, it.getKey().length() - "/*.hb".length())))) {
                            return list
                                    .filter(p -> p.getFileName().toString().endsWith(".hb"))
                                    .map(p -> {
                                        final var name = p.getFileName().toString();
                                        try {
                                            return entry(name.substring(0, name.length() - ".hb".length()), Files.readString(p));
                                        } catch (final IOException e) {
                                            throw new IllegalArgumentException("Can't load '" + p + "'", e);
                                        }
                                    })
                                    .toList() // materialize before closing the list
                                    .stream();
                        } catch (final IOException e) {
                            throw new IllegalArgumentException("Can't load directory (for partials): '" + it.getKey() + "'", e);
                        }
                    }
                    return Stream.of(it);
                })
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @RootConfiguration("-")
    public record GenericStaticGeneratorConfiguration(
            @Property(documentation = "Where to write the output. `STDOUT` and `STDERR` are two specific values for the standard outputs.", defaultValue = "\"STDOUT\"")
            String output,
            @Property(required = true, documentation = "Handlebars template to render once the context is built. If starting with `file:` the end of the path is a file path used as source.")
            String template,
            @Property(documentation = "Partials handlebars. " +
                    "If a value is starting with `file:` the end of the path is a file path used as source. " +
                    "If a key ends with `/*.hb`, all files in the directory are loaded as this - value is ignored.", defaultValue = "java.util.Map.of()")
            Map<String, String> partials,
            @Property(documentation = "Size of the thread pool to run contribution in.", defaultValue = "1")
            int threads,
            @Property(documentation = "List of contributors to generate the output.", defaultValue = "java.util.List.of()")
            List<ContributorConfiguration> contributors
    ) {
    }

    public record ContributorConfiguration(
            @Property(defaultValue = "\"noop\"", documentation = "Name of the contributor to use.")
            String name,
            @Property(value = "context-name", defaultValue = "\"\"", documentation = "Context name where the result will be available, if not set or empty contributor name is reused.")
            String contextName,
            @Property(defaultValue = "java.util.Map.of()", documentation = "Configuration to pass to the contributor.")
            Map<String, String> configuration
    ) {
    }
}
