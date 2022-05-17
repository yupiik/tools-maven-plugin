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
package io.yupiik.maven;

import io.yupiik.maven.service.action.builtin.MojoDocumentationGeneration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class RegenerateDoc {
    private RegenerateDoc() {
        // no-op
    }

    public static void main(final String... args) {
        var doc = Path.of("../_documentation");
        if (!Files.exists(doc)) {
            doc = Path.of("_documentation");
            if (!Files.exists(doc)) {
                throw new IllegalArgumentException(
                        "Ensure working directory is the right one, can't find _documentation folder:" + doc.toAbsolutePath().normalize());
            }
        }

        final var configuration = Map.of(
                "toBase", doc.toString(),
                "pluginXml", doc.resolve("../yupiik-tools-maven-plugin/target/classes/META-INF/maven/plugin.xml").toString());
        new MojoDocumentationGeneration(Path.of("ignored"), configuration).run();
    }
}
