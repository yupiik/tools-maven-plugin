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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonSchema2AdocGeneratorTest {
    @Test
    void run(@TempDir final Path work) throws IOException {
        final var schema = Files.writeString(Files.createDirectories(work).resolve("schema.json"), "{\n" +
                "  \"schemas\": {\n" +
                "    \"io.yupiik.test.MyRootObject\": {\n" +
                "      \"type\": \"object\",\n" +
                "      \"properties\": {\n" +
                "        \"aliases\": {\n" +
                "          \"type\": \"array\",\n" +
                "          \"items\": {\n" +
                "            \"nullable\": true,\n" +
                "            \"$ref\": \"#/schemas/io.yupiik.test.MyObject\"\n" +
                "          }\n" +
                "        }\n" +
                "      },\n" +
                "      \"$id\": \"io.yupiik.test.MyRootObject\"\n" +
                "    },\n" +
                "    \"io.yupiik.test.MyObject\": {\n" +
                "      \"type\": \"object\",\n" +
                "      \"properties\": {\n" +
                "        \"name\": {\n" +
                "          \"nullable\": true,\n" +
                "          \"type\": \"string\"\n" +
                "        },\n" +
                "        \"value\": {\n" +
                "          \"nullable\": true,\n" +
                "          \"type\": \"string\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"$id\": \"io.yupiik.test.MyObject\"\n" +
                "    }\n" +
                "  }\n" +
                "}");
        final var output = work.resolve("output");
        final var conf = Map.of(
                "schema", schema.toString(),
                "output", output.toString(),
                "root", "io.yupiik.test.MyRootObject");
        new JsonSchema2AdocGenerator(conf).run();
        assertEquals("" +
                        "= io.yupiik.test.MyRootObject\n" +
                        "\n" +
                        "[cols=\"2,2m,1,5\", options=\"header\"]\n" +
                        ".io.yupiik.test.MyRootObject\n" +
                        "|===\n" +
                        "|Name|JSON Name|Type|Description\n" +
                        "|<<io.yupiik.test.MyObject>>|aliases|array of object|-\n" +
                        "|===\n" +
                        "\n" +
                        "[#io.yupiik.test.MyObject]\n" +
                        "== io.yupiik.test.MyObject\n" +
                        "\n" +
                        "[cols=\"2,2m,1,5\", options=\"header\"]\n" +
                        ".io.yupiik.test.MyObject\n" +
                        "|===\n" +
                        "|Name|JSON Name|Type|Description\n" +
                        "|name|name|string|-\n" +
                        "|value|value|string|-\n" +
                        "|===\n" +
                        "\n" +
                        "\n" +
                        "",
                Files.readString(output));
    }
}
