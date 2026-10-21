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
package io.yupiik.tools.cli.service;

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.common.jar.Extractor;
import io.yupiik.tools.slides.Slides;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.Optional.ofNullable;
import static org.tomitribe.util.JarLocation.jarLocation;

/**
 * Provides an asciidoctorj instance on demand. The asciidoctorj classes are only referenced by the
 * {@link LazyInstance} inner class so that merely instantiating {@link AsciidoctorProvider} (which the
 * CLI does for every command) does not require asciidoctorj to be on the classpath. Only the forced
 * JRuby path actually creates an asciidoctorj instance.
 */
public class AsciidoctorProvider {
    private final LazyInstance lazy = new LazyInstance();

    public Object get(final AsciidoctorConfiguration conf, final PrintStream stdout, final PrintStream stderr,
                           final Path workDir, final boolean requiresSlideResources) {
        if (requiresSlideResources) {
            prepareSlideResources(workDir);
        }
        return lazy.get(conf, stdout, stderr, workDir, requiresSlideResources);
    }

    /**
     * Extract the slides static resources (css/js/svg/favicon) bundled in the slides-core jar into the working
     * directory, so the slides rendering can stage/copy them. Renderer-agnostic and does not require asciidoctorj.
     */
    public static void prepareSlideResources(final Path workDir) {
        try {
            if (!Files.exists(workDir.resolve("slides"))) {
                new Extractor().extract(Files.createDirectories(workDir), jarLocation(Slides.class), "slides-core/");
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Holds the asciidoctorj instance and the actual JRuby wiring; only loaded when the asciidoctorj
     * path is really used. No asciidoctorj type appears in a field or method/class signature (only in method
     * bodies via {@code var}), so loading this class does not require asciidoctorj to be on the classpath.
     */
    private static class LazyInstance {
        private Object asciidoctor;

        private Object get(final AsciidoctorConfiguration conf, final PrintStream stdout, final PrintStream stderr,
                           final Path workDir, final boolean requiresSlideResources) {
            if (requiresSlideResources) {
                prepareSlideResources(workDir);
            }
            if (asciidoctor != null) {
                return asciidoctor;
            }
            final var created = org.asciidoctor.jruby.internal.JRubyAsciidoctor.create(ofNullable(conf.customGems()).orElseGet(conf.gems()::toString));
            if (conf.requires() != null) {
                created.requireLibraries(conf.requires());
            } else {
                created.requireLibrary("asciidoctor-diagram", "asciidoctor-revealjs");
                boolean doIt;
                try (final var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("ruby/rouge_themes/yupiik.rb")) {
                    doIt = stream != null;
                } catch (final IOException e) {
                    doIt = false;
                }
                if (doIt) {
                    created.requireLibrary("uri:classloader:/ruby/rouge_themes/yupiik.rb");
                }
                try {
                    created.requireLibrary(Files.list(conf.gems().resolve("gems"))
                            .filter(it -> it.getFileName().toString().startsWith("asciidoctor-bespoke-"))
                            .findFirst()
                            .map(it -> it.resolve("lib/asciidoctor-bespoke.rb"))
                            .orElseThrow(() -> new IllegalStateException("bespoke was not bundled at build time"))
                            .toAbsolutePath().normalize().toString());
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            asciidoctor = created;
            return created;
        }
    }
}