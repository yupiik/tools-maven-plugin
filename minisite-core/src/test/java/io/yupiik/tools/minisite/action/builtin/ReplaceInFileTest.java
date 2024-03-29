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
package io.yupiik.tools.minisite.action.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplaceInFileTest {
    @Test
    void simple(@TempDir final Path work) throws IOException {
        final var file = work.resolve("file.txt");
        Files.writeString(file, "test\nof a text file\nwith some text to replace\n\n");
        new ReplaceInFile(Map.of("source", file.toString(), "token", "text", "replacement", "content"))
                .run();
        assertEquals("test\nof a content file\nwith some content to replace\n\n", Files.readString(file));
    }

    @Test
    void regex(@TempDir final Path work) throws IOException {
        final var file = work.resolve("file.txt");
        Files.writeString(file, "test\nof a text   file\nwith some text   to replace\n\n");
        new ReplaceInFile(Map.of("source", file.toString(), "token", "regex{text *}", "replacement", "content "))
                .run();
        assertEquals("test\nof a content file\nwith some content to replace\n\n", Files.readString(file));
    }
}
