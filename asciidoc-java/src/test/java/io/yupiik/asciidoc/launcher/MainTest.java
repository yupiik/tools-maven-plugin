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
package io.yupiik.asciidoc.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {
    @Test
    void simpleRender(@TempDir final Path work) throws IOException {
        final var src = Files.writeString(work.resolve("src.adoc"), "= Test");
        final var out = work.resolve("src.html");
        Main.main("-i", src.toString(), "-o", out.toString());
        assertEquals("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                 <meta charset="UTF-8">
                 <meta http-equiv="X-UA-Compatible" content="IE=edge">
                </head>
                <body>
                 <div id="content">
                 <h1>Test</h1>
                 </div>
                </body>
                </html>
                """, Files.readString(out));
    }
}
