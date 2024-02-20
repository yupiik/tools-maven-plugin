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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerateBashScriptCLISkeletonMojoTest {
    @Test
    void run(@TempDir final Path out) throws MojoExecutionException, MojoFailureException, IOException {
        try (final var list = Files.list(out)) {
            assertEquals(0, list.count());
        }

        new GenerateBashScriptCLISkeletonMojo() {{
            directory = out.toFile();
            generateHelloWorldCommand = true;
        }}.execute();

        try (final var list = Files.list(out)) {
            assertEquals(Set.of("main.sh", "commands"), list.map(Path::getFileName).map(Objects::toString).collect(toSet()));
        }
        try (final var list = Files.list(out.resolve("commands"))) {
            assertEquals(Set.of("help", "hello_world"), list.map(Path::getFileName).map(Objects::toString).collect(toSet()));
        }
        for (final var cmd : List.of("help", "hello_world")) {
            final var cmdFolder = out.resolve("commands").resolve(cmd);
            try (final var list = Files.list(cmdFolder)) {
                assertEquals(Set.of("index.sh", "_cli"), list.map(Path::getFileName).map(Objects::toString).collect(toSet()));
            }
            try (final var list = Files.list(cmdFolder.resolve("_cli"))) {
                assertEquals(Set.of("help.txt"), list.map(Path::getFileName).map(Objects::toString).collect(toSet()));
            }
        }

        // todo: some exec? assume we have bash?
    }
}
