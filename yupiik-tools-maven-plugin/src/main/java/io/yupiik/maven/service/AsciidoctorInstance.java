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
package io.yupiik.maven.service;

import io.yupiik.maven.mojo.BaseMojo;
import io.yupiik.maven.service.extension.CodeEvalMacro;
import io.yupiik.maven.service.extension.DependenciesMacro;
import io.yupiik.maven.service.extension.ExcelTableMacro;
import io.yupiik.maven.service.extension.JLatexMath;
import io.yupiik.maven.service.extension.XsltMacro;
import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import lombok.Data;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.extension.BlockMacroProcessor;
import org.asciidoctor.extension.BlockProcessor;
import org.asciidoctor.extension.DocinfoProcessor;
import org.asciidoctor.extension.IncludeProcessor;
import org.asciidoctor.extension.InlineMacroProcessor;
import org.asciidoctor.extension.JavaExtensionRegistry;
import org.asciidoctor.extension.Postprocessor;
import org.asciidoctor.extension.Preprocessor;
import org.asciidoctor.extension.Treeprocessor;
import org.asciidoctor.jruby.internal.JRubyAsciidoctor;

import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.logging.Logger;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

@Named
@Singleton
public class AsciidoctorInstance {
    // for concurrent builds
    private final Map<List<AsciidoctorExtension>, Queue<Asciidoctor>> instances = new ConcurrentHashMap<>();
    private final ThreadLocal<AsciidoctorConfiguration> mojo = new ThreadLocal<>();

    public <T> T withAsciidoc(final AsciidoctorConfiguration base, final Function<Asciidoctor, T> task) {
        return withAsciidoc(base, task, List.of());
    }

    public <T> T withAsciidoc(final AsciidoctorConfiguration base, final Function<Asciidoctor, T> task,
                              final List<AsciidoctorExtension> extensions) {
        final var map = instances.computeIfAbsent(extensions == null ? List.of() : extensions, k -> new ConcurrentLinkedQueue<>());
        var poll = map.poll();
        if (poll == null) {
            poll = newInstance(base, base.gems(), base.customGems(), base.requires(), extensions);
        }
        mojo.set(base);
        try {
            return task.apply(poll);
        } finally {
            mojo.remove();
            map.add(poll);
        }
    }

    private Asciidoctor newInstance(final AsciidoctorConfiguration log, final Path path, final String customGems,
                                    final List<String> requires, final List<AsciidoctorExtension> extensions) {
        final var thread = Thread.currentThread();
        final var oldLoader = thread.getContextClassLoader();
        final Asciidoctor asciidoctor;
        try {
            thread.setContextClassLoader(AsciidoctorInstance.class.getClassLoader());
            asciidoctor = JRubyAsciidoctor.create(ofNullable(customGems).orElseGet(path::toString));
        } finally {
            thread.setContextClassLoader(oldLoader);
        }
        Logger.getLogger("asciidoctor").setUseParentHandlers(false);

        final var registry = asciidoctor.javaExtensionRegistry();
        registerExtensions(registry);
        if (extensions != null && !extensions.isEmpty()) {
            extensions.stream()
                    .map(this::instantiate)
                    .forEach(ext -> {
                        if (BlockProcessor.class.isInstance(ext)) {
                            registry.block(BlockProcessor.class.cast(ext));
                        }
                        if (BlockMacroProcessor.class.isInstance(ext)) {
                            registry.blockMacro(BlockMacroProcessor.class.cast(ext));
                        }
                        if (IncludeProcessor.class.isInstance(ext)) {
                            registry.includeProcessor(IncludeProcessor.class.cast(ext));
                        }
                        if (InlineMacroProcessor.class.isInstance(ext)) {
                            registry.inlineMacro(InlineMacroProcessor.class.cast(ext));
                        }
                        if (DocinfoProcessor.class.isInstance(ext)) {
                            registry.docinfoProcessor(DocinfoProcessor.class.cast(ext));
                        }
                        if (Postprocessor.class.isInstance(ext)) {
                            registry.postprocessor(Postprocessor.class.cast(ext));
                        }
                        if (Preprocessor.class.isInstance(ext)) {
                            registry.preprocessor(Preprocessor.class.cast(ext));
                        }
                        if (Treeprocessor.class.isInstance(ext)) {
                            registry.treeprocessor(Treeprocessor.class.cast(ext));
                        }
                    });
        }

        asciidoctor.registerLogHandler(logRecord -> {
            switch (logRecord.getSeverity()) {
                case UNKNOWN:
                case INFO:
                    log.info().accept(logRecord.getMessage());
                    break;
                case ERROR:
                case FATAL:
                    log.error().accept(logRecord.getMessage());
                    break;
                case WARN:
                    log.warn().accept(logRecord.getMessage());
                    break;
                case DEBUG:
                default:
                    log.debug().accept(logRecord.getMessage());
            }
        });
        if (requires != null) {
            asciidoctor.requireLibraries(requires.stream()
                    .filter(it -> { // enable to import automatically default requires by aliases
                        switch (it) {
                            case "global":
                                globalRequires(asciidoctor);
                                return false;
                            case "pdf":
                                pdfRequires(asciidoctor);
                                return false;
                            case "slides":
                                slideRequires(path, asciidoctor);
                                return false;
                            default:
                                return true;
                        }
                    }).collect(toList()));
        } else {
            globalRequires(asciidoctor);
            pdfRequires(asciidoctor);
            slideRequires(path, asciidoctor);
        }
        return asciidoctor;
    }

    private Object instantiate(final AsciidoctorExtension extension) {
        try {
            final var name = extension.getType().trim();
            final var clazz = Thread.currentThread().getContextClassLoader()
                    .loadClass(name);
            final var constructor = clazz.getDeclaredConstructor();
            if (!constructor.isAccessible()) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance();
        } catch (final Exception e) {
            throw new IllegalArgumentException("can't create extension " + extension, e);
        }
    }

    private void slideRequires(final Path path, final Asciidoctor asciidoctor) {
        try {
            asciidoctor.requireLibrary(Files.list(path.resolve("gems"))
                    .filter(it -> it.getFileName().toString().startsWith("asciidoctor-bespoke-"))
                    .findFirst()
                    .map(it -> it.resolve("lib/asciidoctor-bespoke.rb"))
                    .orElseThrow(() -> new IllegalStateException("bespoke was not bundled at build time"))
                    .toAbsolutePath().normalize().toString());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void pdfRequires(final Asciidoctor asciidoctor) {
        asciidoctor.requireLibrary("uri:classloader:/ruby/rouge_themes/yupiik.rb");
    }

    private void globalRequires(final Asciidoctor asciidoctor) {
        asciidoctor.requireLibrary("asciidoctor-diagram", "asciidoctor-revealjs");
    }

    private void registerExtensions(final JavaExtensionRegistry registry) {
        registry.block(new DependenciesMacro(() -> BaseMojo.class.cast(mojo.get())));
        registry.block(new XsltMacro(() -> BaseMojo.class.cast(mojo.get())));
        registry.block(new ExcelTableMacro());
        registry.block(new CodeEvalMacro());
        try {
            Thread.currentThread().getContextClassLoader().loadClass("org.scilab.forge.jlatexmath.TeXFormula");
            registry.inlineMacro(new JLatexMath.Inline());
            registry.block(new JLatexMath.Block());
        } catch (final ClassNotFoundException cnfe) {
            // no-op
        }
    }

    @PreDestroy
    public void destroy() {
        instances.values().stream().flatMap(Collection::stream).forEach(a -> {
            try {
                a.shutdown();
            } catch (final NoClassDefFoundError e) {
                // no-op
            }
        });
    }

    @Data
    public static class AsciidoctorExtension {
        private String type;
        private Map<String, String> configuration;
    }
}
