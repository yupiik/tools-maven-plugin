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
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.jruby.internal.JRubyAsciidoctor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.Optional.ofNullable;
import static org.tomitribe.util.JarLocation.jarLocation;

public class AsciidoctorProvider {
    private Asciidoctor asciidoctor;

    public Asciidoctor get(final AsciidoctorConfiguration conf, final PrintStream stdout, final PrintStream stderr,
                           final Path workDir, final boolean requiresSlideResources) {
        if (requiresSlideResources) {
            try {
                prepare(workDir);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        if (asciidoctor != null) {
            return asciidoctor;
        }

        asciidoctor = JRubyAsciidoctor.create(ofNullable(conf.customGems()).orElseGet(conf.gems()::toString));
        asciidoctor.registerLogHandler(logRecord -> {
            switch (logRecord.getSeverity()) {
                case UNKNOWN:
                case INFO:
                    stdout.println("[INFO] " + logRecord.getMessage());
                    break;
                case ERROR:
                case FATAL:
                    stderr.println("[ERROR] " + logRecord.getMessage());
                    break;
                case WARN:
                    stdout.println("[WARNING] " + logRecord.getMessage());
                    break;
                case DEBUG:
                default:
            }
        });
        if (conf.requires() != null) {
            asciidoctor.requireLibraries(conf.requires());
        } else {
            asciidoctor.requireLibrary("asciidoctor-diagram", "asciidoctor-revealjs");
            requireRougeThemeIfPresent();
            try {
                asciidoctor.requireLibrary(Files.list(conf.gems().resolve("gems"))
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

    private void requireRougeThemeIfPresent() {
        boolean doIt;
        try (final var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("ruby/rouge_themes/yupiik.rb")) {
            doIt = stream != null;
        } catch (IOException e) {
            doIt = false;
        }
        if (doIt) {
            asciidoctor.requireLibrary("uri:classloader:/ruby/rouge_themes/yupiik.rb");
        }
    }

    private void prepare(final Path workDir) throws IOException {
        if (!Files.exists(workDir.resolve("gem"))) {
            new Extractor().extract(Files.createDirectories(workDir), jarLocation(Slides.class), "slides-core/");
        }
    }
}
