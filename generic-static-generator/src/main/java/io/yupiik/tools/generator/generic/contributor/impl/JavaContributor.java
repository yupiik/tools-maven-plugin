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
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.yupiik.tools.generator.generic.io.IO.loadFromFileOrIdentity;
import static java.util.Collections.list;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

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
                final var pck = ofNullable(conf.packageName()).orElse("generated.yupiik.generator.generic.java");
                final var className = "DynamicJavaContributor" + counter.getAndIncrement();
                final var code =
                        String.format("""
                                        package %s;
                                        
                                        import java.util.*;
                                        import java.util.concurrent.CompletionStage;
                                        import java.util.concurrent.Executor;
                                        import io.yupiik.fusion.framework.api.RuntimeContainer;
                                        import io.yupiik.tools.generator.generic.contributor.ContextContributor;%s
                                        
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
                                        """,
                                pck,
                                ofNullable(conf.imports())
                                        .map(it -> "\n" + it.strip() + "\n")
                                        .orElse(""),
                                className, className, loadFromFileOrIdentity(requireNonNull(conf.code(), "No code set for java contributor")).indent(8));

                final var compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    throw new IllegalStateException("No compiler found. Are you running a JRE instead of a JDK?");
                }

                final var diagnostics = new DiagnosticCollector<JavaFileObject>();
                final var classpath = ofNullable(conf.compilationClasspath())
                        .map(this::parseCp)
                        .orElseGet(this::findClasspath);
                final var fileManager = new InMemoryFileManager(compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8));
                final var source = new InMemorySource(className, code);
                final var success = compiler.getTask(
                        null, fileManager, diagnostics,
                        List.of("-classpath", classpath.stream().map(File::getAbsolutePath).collect(joining(File.pathSeparator))),
                        null, List.of(source)).call();
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

    private Collection<File> findClasspath() {
        try {
            return new ClassPath().find(Thread.currentThread().getContextClassLoader());
        } catch (final IOException e) {
            return List.of();
        }
    }

    private Collection<File> parseCp(final String cp) {
        return Stream.of(cp.replace(';', ':').split(":"))
                .map(File::new)
                .filter(File::exists)
                .toList();
    }

    @RootConfiguration("contributors.java")
    public record JavaContributorConfiguration(
            @Property(documentation = "Code of the contributor `contribute` method. " +
                    "There are three implicit variables:" +
                    " `configuration` which is a `Map<String, String>` with the contributor configuration," +
                    " `executor` which is the thread pool to use if needed and" +
                    " `container` which is Yupiik Fusion IoC if some other beans are needed. " +
                    "It must end with a `return xxx` with `xxx` a `CompletionStage<Map<String, Object>>`. " +
                    "For convenience, `completedFuture` is implicitly available as well as `java.util`. " +
                    "If it starts with `file:` the end of the string is a file path.")
            String code,

            @Property(documentation = "Package name override - else an internal package is used. Mainly useful to access package scope classes.", defaultValue = "null")
            String packageName,

            @Property(documentation = "Enable to set global imports to the generated class.", defaultValue = "null")
            String imports,

            @Property(documentation = "Enable to set the compilation classpath (for 3rd parties). If not set we try to inherit from current classloader one.", defaultValue = "null")
            String compilationClasspath
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

        private InMemoryFileManager(final StandardJavaFileManager delegate) {
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

    // from geronimo-xbean
    private static final class ClassPath {
        private static final ClassLoader SYSTEM = ClassLoader.getSystemClassLoader();
        private static final Pattern MJAR_PATTERN = Pattern.compile(".*/META-INF/versions/[0-9]+/$");
        private static final boolean UNIX = !System.getProperty("os.name").toLowerCase().contains("win");

        private Collection<File> find(final ClassLoader classLoader) throws IOException {
            if (classLoader == null || (SYSTEM.getParent() != null && classLoader == SYSTEM.getParent())) {
                return Set.of();
            }

            final var urls = new HashSet<File>();

            if (classLoader instanceof URLClassLoader l) {
                if (!isSurefire(classLoader)) {
                    Stream.concat(Stream.of(l.getURLs()).map(this::toFile), find(classLoader.getParent()).stream())
                            .forEach(it -> addIfNotSo(urls, it));
                } else {
                    urls.addAll(fromClassPath());
                }
            }

            if (urls.size() <= 1) {
                urls.addAll(findUrlFromResources(classLoader));
            }

            return urls.stream().distinct().toList();
        }

        private void addIfNotSo(final Set<File> urls, final File url) {
            if (!UNIX || !isNative(url)) {
                urls.add(url);
            }
        }

        private boolean isNative(final File file) {
            if (file != null) {
                final String name = file.getName();
                return !name.endsWith(".jar") && !file.isDirectory()
                        && name.contains(".so") && file.getAbsolutePath().startsWith("/usr/lib");
            }
            return false;
        }


        private boolean isSurefire(final ClassLoader classLoader) {
            return System.getProperty("surefire.real.class.path") != null && classLoader == SYSTEM;
        }

        private Collection<File> fromClassPath() {
            return Stream.of(System.getProperty("java.class.path", "").replace(';', ':').split(":"))
                    .map(File::new)
                    .filter(File::exists)
                    .collect(toSet());
        }

        private Set<File> findUrlFromResources(final ClassLoader classLoader) throws IOException {
            return Stream.concat(
                            list(classLoader.getResources("META-INF")).stream()
                                    .map(it -> {
                                        final var externalForm = it.toExternalForm();
                                        try {
                                            return new URL(externalForm.substring(0, externalForm.lastIndexOf("META-INF")));
                                        } catch (final MalformedURLException e) {
                                            throw new IllegalStateException(e);
                                        }
                                    }),
                            list(classLoader.getResources("")).stream()
                                    .map(it -> {
                                        final var externalForm = it.toExternalForm();
                                        if (MJAR_PATTERN.matcher(externalForm).matches()) {
                                            try {
                                                return new URL(externalForm.substring(0, externalForm.lastIndexOf("META-INF")));
                                            } catch (final MalformedURLException e) {
                                                throw new IllegalStateException(e);
                                            }
                                        }
                                        return it;
                                    }))
                    .map(this::toFile)
                    .collect(toSet());
        }

        private File toFile(final URL url) {
            if ("jar".equals(url.getProtocol())) {
                try {
                    final String spec = url.getFile();
                    final int separator = spec.indexOf('!');
                    if (separator == -1) {
                        return null;
                    }
                    return toFile(new URL(spec.substring(0, separator + 1)));
                } catch (final MalformedURLException e) {
                    return null;
                }
            }
            if ("file".equals(url.getProtocol())) {
                var path = decode(url.getFile());
                if (path.endsWith("!")) {
                    path = path.substring(0, path.length() - 1);
                }
                return new File(path);
            }
            return null;
        }

        private String decode(final String fileName) {
            if (fileName.indexOf('%') == -1) {
                return fileName;
            }

            final var result = new StringBuilder(fileName.length());
            final var out = new ByteArrayOutputStream();

            for (int i = 0; i < fileName.length(); ) {
                final char c = fileName.charAt(i);

                if (c == '%') {
                    out.reset();
                    do {
                        if (i + 2 >= fileName.length()) {
                            throw new IllegalArgumentException("Incomplete % sequence at: " + i);
                        }

                        final int d1 = Character.digit(fileName.charAt(i + 1), 16);
                        final int d2 = Character.digit(fileName.charAt(i + 2), 16);
                        if (d1 == -1 || d2 == -1) {
                            throw new IllegalArgumentException("Invalid % sequence (" + fileName.substring(i, i + 3) + ") at: " + String.valueOf(i));
                        }

                        out.write((byte) ((d1 << 4) + d2));
                        i += 3;
                    } while (i < fileName.length() && fileName.charAt(i) == '%');
                    result.append(out);
                    continue;
                } else {
                    result.append(c);
                }

                i++;
            }
            return result.toString();
        }

        private ClassPath() {
            // no-op
        }
    }
}
