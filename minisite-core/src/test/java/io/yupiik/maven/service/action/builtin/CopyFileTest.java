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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CopyFileTest {
    @Test
    void copy(@TempDir final Path dir) throws IOException {
        Files.createDirectories(dir);
        final Path input = dir.resolve("in.txt");
        Files.write(input, "foo".getBytes(StandardCharsets.UTF_8));
        final Path output = dir.resolve("in.txt");
        final Map<String, String> configuration = new HashMap<>();
        configuration.put("from", input.toString());
        configuration.put("to", output.toString());
        new CopyFile(configuration).run();
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
    }

    @Test
    void copyDir(@TempDir final Path dir) throws IOException {
        Files.createDirectories(dir);
        final Path input = dir.resolve("in.txt");
        Files.write(input, "foo".getBytes(StandardCharsets.UTF_8));
        final Path output = dir.resolve("out");
        final Map<String, String> configuration = new HashMap<>();
        configuration.put("from", dir.toString());
        configuration.put("to", output.toString());
        new CopyFile(configuration).run();
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output.resolve("in.txt")));
    }
}
