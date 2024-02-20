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
package io.yupiik.maven.service.action.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

@Log
@RequiredArgsConstructor
public class CopyFile implements Runnable {
    private final Map<String, String> configuration;

    @Override
    public void run() {
        final Path from = Paths.get(configuration.get("from"));
        if (!Files.exists(from)) {
            throw new IllegalArgumentException(from + " does not exist");
        }

        final Path to = Paths.get(configuration.get("to"));

        try {
            if (Files.isDirectory(from)) {
                Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        final Path target = to.resolve(from.relativize(file));
                        doCopy(file, target);
                        return super.visitFile(file, attrs);
                    }
                });
            } else {
                doCopy(from, to);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void doCopy(final Path file, final Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        log.info(() -> "Copied '" + file + "' to '" + target + "'");
    }
}
