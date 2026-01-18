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

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.tools.generator.generic.contributor.ContextContributor;
import io.yupiik.tools.generator.generic.contributor.ContributorDocumentation;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

@DefaultScoped
@ContributorDocumentation(
        configuration = JavaContributor.JavaContributorConfiguration.class,
        documentation = """
                WARNING: requires a JDK and not a simple JRE.
                
                Enable to inject plain Java code to implement dynamically a contributor.
                
                This is quite neat when dealing with complex logic and default contributors are not sufficient.
                """
)
public class JavaContributor implements ContextContributor {
    private final RuntimeContainer container;
    private final AtomicInteger counter = new AtomicInteger();

    public JavaContributor(final RuntimeContainer container) {
        this.container = container;
    }

    @Override
    public String name() {
        return "java";
    }

    @Override
    public CompletionStage<Map<String, Object>> contribute(final Map<String, String> configuration,
                                                           final Executor executor) {
        final var conf = new JavaContributor$JavaContributorConfiguration$FusionConfigurationFactory(configuration(configuration))
                .get();
        final var result = new CompletableFuture<Map<String, Object>>();
        executor.execute(() -> {
            try {
                final var pck = "generated.yupiik.generator.generic.java";
                final var className = "DynamicJavaContributor" + counter.getAndIncrement();
                final var code =
                        String.format("""
                                package %s;
                                
                                import java.util.*;
                                import java.util.concurrent.CompletionStage;
                                import java.util.concurrent.Executor;
                                import io.yupiik.fusion.framework.api.RuntimeContainer;
                                import io.yupiik.tools.generator.generic.contributor.ContextContributor;
                                
                                import static java.util.concurrent.CompletableFuture.completedFuture;
                                
                                public class %s implements ContextContributor {
                                    private final RuntimeContainer container;
                                
                                    public %s(final RuntimeContainer container) {
                                        this.container = container;
                                    }
                                
                                    @Override
                                    public String name() {
                                        return "";
                                    }
                                
                                    @Override
                                    public CompletionStage<Map<String, Object>> contribute(
                                            final Map<String, String> configuration,
                                            final Executor executor) {
                                %s
                                    }
                                }
                                """, pck, className, className, conf.code().indent(8));

                final var compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    throw new IllegalStateException("No compiler found. Are you running a JRE instead of a JDK?");
                }

                final var diagnostics = new DiagnosticCollector<JavaFileObject>();
                final var fileManager = new InMemoryFileManager(compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8));
                final var source = new InMemorySource(className, code);
                final var success = compiler.getTask(null, fileManager, diagnostics, null, null, List.of(source)).call();
                if (!success) {
                    result.completeExceptionally(new IllegalStateException("Invalid code:\n" + diagnostics.getDiagnostics().stream()
                            .map(it -> "- " + it)
                            .collect(joining("\n"))));
                    return;
                }

                final var loader = new ClassLoader(
                        ofNullable(JavaContributor.class.getClassLoader())
                                .orElseGet(() -> Thread.currentThread().getContextClassLoader())) {
                    // we don't make it a concurrent classloader
                    // since we'll not load a lot from this one
                    // maybe 3-4 classes max
                    @Override
                    protected synchronized Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                        final var bytecode = fileManager.classes.get(name);
                        if (bytecode != null) {
                            var clazz = findLoadedClass(name);
                            if (clazz == null) {
                                final var bytes = bytecode.bytecode.toByteArray();
                                clazz = super.defineClass(name, bytes, 0, bytes.length, null);
                            }
                            if (resolve) {
                                resolveClass(clazz);
                            }
                            return clazz;
                        }
                        return super.loadClass(name, resolve);
                    }
                };
                final var clazz = loader.loadClass(pck + '.' + className).asSubclass(ContextContributor.class);
                final var constructor = clazz.getDeclaredConstructor(RuntimeContainer.class);
                constructor.setAccessible(true);
                final var contributor = constructor.newInstance(container);
                contributor
                        .contribute(configuration, executor)
                        .whenComplete((ok, ko) -> {
                            if (ko != null) {
                                result.completeExceptionally(ko);
                            } else {
                                result.complete(ok);
                            }
                        });
            } catch (final RuntimeException | Error | ClassNotFoundException | NoSuchMethodException |
                           InstantiationException | IllegalAccessException | InvocationTargetException re) {
                result.completeExceptionally(re);
            }
        });
        return result;
    }

    @RootConfiguration("contributors.java")
    public record JavaContributorConfiguration(
            @Property(documentation = "Code of the contributor `contribute` method. " +
                    "There are three implicit variables:" +
                    " `configuration` which is a `Map<String, String>` with the contributor configuration," +
                    " `executor` which is the thread pool to use if needed and" +
                    " `container` which is Yupiik Fusion IoC if some other beans are needed. " +
                    "It must end with a `return xxx` with `xxx` a `CompletionStage<Map<String, Object>>`. " +
                    "For convenience, `completedFuture` is implicitly available as well as `java.util`.")
            String code
    ) {
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        private InMemorySource(final String className, final String code) {
            super(URI.create("memory-input:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class InMemoryBytecode extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytecode = new ByteArrayOutputStream();

        private InMemoryBytecode(final String className, final Kind kind) {
            super(URI.create("memory-output:///" + className.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return bytecode;
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, InMemoryBytecode> classes = new HashMap<>();

        private InMemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                final Location location,
                final String className,
                final JavaFileObject.Kind kind,
                final FileObject sibling
        ) {
            final var file = new InMemoryBytecode(className, kind);
            classes.put(className, file);
            return file;
        }
    }
}
