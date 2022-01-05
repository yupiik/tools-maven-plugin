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
package io.yupiik.maven.mojo;

import lombok.Setter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.asciidoctor.Options;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Thread.sleep;

@Setter
@Mojo(name = "watch-pdf", requiresProject = false, threadSafe = true)
public class WatchPDFMojo extends PDFMojo {
    @Override
    public void doExecute() throws MojoExecutionException {
        final Path theme = super.prepare();
        final Path src = sourceDirectory.toPath();
        final Options options = super.createOptions(theme, Files.isDirectory(src) ? src : src.getParent());
        final Instant lastUpdate = findLastUpdate(src, theme);
        try {
            super.doRender(src, options);
            getLog().info("Rendered " + src);
        } catch (final RuntimeException re) {
            getLog().error(re);
        }
        do {
            if (findLastUpdate(src, theme).equals(lastUpdate)) {
                try {
                    sleep(500);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                getLog().debug("No change since last check time, will pause for 500ms");
                continue;
            }
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
            if (Files.isDirectory(src)) {
                final var last = new AtomicReference<>(Instant.ofEpochMilli(0));
                findLastUpdate(src, last);
                findLastUpdate(theme, last);
                return last.get();
            }
            return Files.getLastModifiedTime(src).toInstant();
        } catch (final IOException ie) {
            getLog().debug(ie.getMessage());
            return Instant.ofEpochMilli(0);
        }
    }

    private void findLastUpdate(final Path src, final AtomicReference<Instant> last) {
        if (!Files.exists(src)) {
            return;
        }
        visit(src, f -> {
            try {
                final var ref = last.get();
                final var current = Files.getLastModifiedTime(src).toInstant();
                synchronized (last) {
                    if (current.isAfter(ref)) {
                        last.set(current);
                    }
                }
            } catch (final IOException ie) {
                getLog().debug(ie.getMessage());
            }
        });
    }
}
