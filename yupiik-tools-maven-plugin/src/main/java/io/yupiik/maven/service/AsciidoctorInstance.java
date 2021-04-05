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
package io.yupiik.maven.service;

import io.yupiik.maven.mojo.BaseMojo;
import io.yupiik.maven.service.extension.DependenciesMacro;
import io.yupiik.maven.service.extension.ExcelTableMacro;
import io.yupiik.maven.service.extension.JLatexMath;
import io.yupiik.maven.service.extension.XsltMacro;
import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.extension.JavaExtensionRegistry;
import org.asciidoctor.jruby.internal.JRubyAsciidoctor;

import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.logging.Logger;

import static java.util.Optional.ofNullable;

@Named
@Singleton
public class AsciidoctorInstance {
    // for concurrent builds
    private final Queue<Asciidoctor> instances = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<AsciidoctorConfiguration> mojo = new ThreadLocal<>();

    public <T> T withAsciidoc(final AsciidoctorConfiguration base, final Function<Asciidoctor, T> task) {
        Asciidoctor poll = instances.poll();
        if (poll == null) {
            poll = newInstance(base, base.gems(), base.customGems(), base.requires());
        }
        mojo.set(base);
        try {
            return task.apply(poll);
        } finally {
            mojo.remove();
            instances.add(poll);
        }
    }

    private Asciidoctor newInstance(final AsciidoctorConfiguration log, final Path path, final String customGems, final List<String> requires) {
        final Asciidoctor asciidoctor = JRubyAsciidoctor.create(ofNullable(customGems).orElseGet(path::toString));
        Logger.getLogger("asciidoctor").setUseParentHandlers(false);
        registerExtensions(asciidoctor.javaExtensionRegistry());
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
            asciidoctor.requireLibraries(requires);
        } else {
            asciidoctor.requireLibrary("asciidoctor-diagram", "asciidoctor-revealjs");
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
        return asciidoctor;
    }

    private void registerExtensions(final JavaExtensionRegistry registry) {
        registry.block(new DependenciesMacro(() -> BaseMojo.class.cast(mojo.get())));
        registry.block(new XsltMacro(() -> BaseMojo.class.cast(mojo.get())));
        registry.block(new ExcelTableMacro());
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
        instances.forEach(Asciidoctor::shutdown);
    }
}
