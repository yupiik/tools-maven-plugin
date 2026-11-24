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
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojoDocumentationGenerationTest {
    @Test
    void rejectsInternalEntities(@TempDir final Path dir) throws IOException {
        assertRejectedXml(dir, "<!DOCTYPE plugin [<!ENTITY value 'expanded'>]>\n<plugin>&value;</plugin>");
    }

    @Test
    void rejectsExternalEntities(@TempDir final Path dir) throws IOException {
        final var secret = Files.writeString(dir.resolve("secret.txt"), "private data");
        assertRejectedXml(dir, "<!DOCTYPE plugin [<!ENTITY value SYSTEM '" + secret.toUri() +
                "'>]>\n<plugin>&value;</plugin>");
    }

    @Test
    void rejectsExternalDtd(@TempDir final Path dir) throws IOException {
        final var dtd = Files.writeString(dir.resolve("external.dtd"), "<!ENTITY value 'expanded'>");
        assertRejectedXml(dir, "<!DOCTYPE plugin SYSTEM '" + dtd.toUri() + "'>\n<plugin>&value;</plugin>");
    }

    @Test
    void rejectsExternalParameterEntities(@TempDir final Path dir) throws IOException {
        final var dtd = Files.writeString(dir.resolve("external.dtd"), "<!ENTITY value 'expanded'>");
        assertRejectedXml(dir, "<!DOCTYPE plugin [<!ENTITY % external SYSTEM '" + dtd.toUri() +
                "'>%external;]>\n<plugin>&value;</plugin>");
    }

    private void assertRejectedXml(final Path dir, final String xml) throws IOException {
        final var plugin = Files.writeString(dir.resolve("plugin.xml"), xml);
        final var output = dir.resolve("output");
        final var error = assertThrows(IllegalStateException.class, () -> new MojoDocumentationGeneration(
                dir, Map.of("pluginXml", plugin.toString(), "toBase", output.toString())).run());
        final var cause = assertInstanceOf(SAXException.class, error.getCause());
        assertTrue(cause.getMessage().contains("DOCTYPE"), cause::getMessage);
        try (final var files = Files.list(output)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void generate(@TempDir final Path dir) throws IOException {
        final Path output = dir.resolve("output");
        final Map<String, String> configuration = new HashMap<>();
        configuration.put("pluginXml", "test/maven/plugin.xml");
        configuration.put("toBase", output.toString());
        new MojoDocumentationGeneration(
                dir.resolve("base"),
                configuration)
                .run();

        final Map<String, String> generated;
        try (final var list = Files.list(output)) {
            generated = list.collect(toMap(it -> it.getFileName().toString(), it -> {
                try {
                    return String.join("\n", Files.readAllLines(it));
                } catch (final IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }));
        }
        assertEquals(2, generated.size());
        assertEquals("" +
                        "= super:foo\n" +
                        "\n" +
                        "Open a bar.\n" +
                        "\n" +
                        "== Coordinates\n" +
                        "\n" +
                        "[source,xml]\n" +
                        "----\n" +
                        "<plugin>\n" +
                        "  <groupId>org.superbiz</groupId>\n" +
                        "  <artifactId>super-maven-plugin</artifactId>\n" +
                        "  <version>1.0.0-SNAPSHOT</version>\n" +
                        "</plugin>\n" +
                        "----\n" +
                        "\n" +
                        "To call this goal from the command line execute: `mvn super:foo`.\n" +
                        "\n" +
                        "To bind this goal in the build you can use:\n" +
                        "\n" +
                        "[source,xml]\n" +
                        "----\n" +
                        "<plugin>\n" +
                        "  <groupId>org.superbiz</groupId>\n" +
                        "  <artifactId>super-maven-plugin</artifactId>\n" +
                        "  <version>1.0.0-SNAPSHOT</version>\n" +
                        "  <executions>\n" +
                        "    <execution>\n" +
                        "      <id>my-execution</id>\n" +
                        "      <goals>\n" +
                        "        <goal>foo</goal>\n" +
                        "      </goals>\n" +
                        "      <configuration>\n" +
                        "        <!-- execution specific configuration come there -->\n" +
                        "      </configuration>\n" +
                        "    </execution>\n" +
                        "  </executions>\n" +
                        "</plugin>\n" +
                        "----\n" +
                        "\n" +
                        "You can execute this goal particularly with `mvn super:foo@my-execution` command.\n" +
                        "\n" +
                        "== Configuration\n" +
                        "\n" +
                        "dummy (`boolean`)::\n" +
                        "Some param. Default value: `false`. Property: `${super.dummy}`.",
                Files.readString(output.resolve("foo.adoc")).trim());
        assertEquals("" +
                        "= Super Maven Plugin\n" +
                        "\n" +
                        "== Goals\n" +
                        "\n" +
                        "* xref:foo.adoc[foo]: open a bar.",
                Files.readString(output.resolve("super-maven-plugin.adoc")).trim());
    }
}
