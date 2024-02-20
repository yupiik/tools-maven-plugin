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
package io.yupiik.maven.mojo;

import lombok.Setter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.asciidoctor.Options;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Thread.sleep;

@Setter
@Mojo(name = "watch-pdf", requiresProject = false, threadSafe = true)
public class WatchPDFMojo extends PDFMojo {
    /**
     * How long to pause between two check to see if sources must be re-rendered.
     */
    @Parameter(property = "yupiik.pdf.pauseBetweenChecks", defaultValue = "1000")
    private long pauseBetweenChecks;

    @Override
    public void doExecute() throws MojoExecutionException {
        final Path theme = super.prepare();
        final Path src = sourceDirectory.toPath();
        final Options options = super.createOptions(theme, Files.isDirectory(src) ? src : src.getParent());
        Instant lastUpdate = findLastUpdate(src, theme);
        try {
            super.doRender(src, options);
            getLog().info("Rendered " + src);
        } catch (final RuntimeException re) {
            getLog().error(re);
        }
        do {
            final var update = findLastUpdate(src, theme);
            if (update.equals(lastUpdate)) {
                try {
                    sleep(pauseBetweenChecks);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                getLog().debug("No change since last check time, will pause for 500ms");
                continue;
            }
            lastUpdate = update;
            getLog().info("Re-rendering " + src);
            try {
                super.doRender(src, options);
            } catch (final RuntimeException re) {
                getLog().error(re);
            }
        } while (true);
    }

    private Instant findLastUpdate(final Path src, final Path theme) {
        try {
            final var last = new AtomicReference<>(Instant.ofEpochMilli(0));
            findLastUpdate(theme, last, false);
            if (Files.isDirectory(src)) {
                findLastUpdate(src, last, true);
                return last.get();
            }
            final var source = Files.getLastModifiedTime(src).toInstant();
            if (source.isAfter(last.get())) {
                last.set(source);
            }
            return source;
        } catch (final IOException ie) {
            getLog().debug(ie.getMessage());
            return Instant.ofEpochMilli(0);
        }
    }

    private void findLastUpdate(final Path src, final AtomicReference<Instant> last, final boolean adocOnly) {
        if (!Files.exists(src)) {
            return;
        }
        visit(src, f -> {
            try {
                final var ref = last.get();
                final var current = Files.getLastModifiedTime(f).toInstant();
                synchronized (last) {
                    if (current.isAfter(ref)) {
                        last.set(current);
                    }
                }
            } catch (final IOException ie) {
                getLog().debug(ie.getMessage());
            }
        }, adocOnly);
    }
}
