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
package io.yupiik.tools.common.jar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.jar.JarFile;

import static java.util.Collections.list;

public class Extractor {
    public void extract(final Path output, final File self, final String prefix) {
        final var path = self.toPath();
        if (Files.isDirectory(path)) {
            final var sub = path.resolve(prefix);
            if (!Files.exists(sub)) {
                throw new IllegalArgumentException("Missing '" + sub + "'");
            }
            try {
                Files.walkFileTree(sub, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        final Path target = output.resolve(sub.relativize(file));
                        try {
                            Files.createDirectories(target.getParent());
                        } catch (final IOException mojoExecutionException) {
                            throw new IllegalStateException(mojoExecutionException);
                        }
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            return;
        }

        try (final JarFile file = new JarFile(self)) {
            list(file.entries()).stream()
                    .filter(it -> it.getName().startsWith(prefix) && !it.isDirectory())
                    .forEach(e -> {
                        final Path target = output.resolve(e.getName().substring(prefix.length()));
                        try {
                            Files.createDirectories(target.getParent());
                        } catch (final IOException mojoExecutionException) {
                            throw new IllegalStateException(mojoExecutionException);
                        }
                        try (final InputStream in = file.getInputStream(e)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (final IOException ex) {
                            throw new IllegalStateException(ex.getMessage(), ex);
                        }
                    });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
