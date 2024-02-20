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

import lombok.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonSchemaGeneratorTest {
    @Test
    void json(@TempDir final Path dir) throws IOException {
        Files.createDirectories(dir);
        final Path output = dir.resolve("in.txt");
        final Map<String, String> configuration = new HashMap<>();
        configuration.put("class", Foo.class.getName());
        configuration.put("to", output.toString());
        new JsonSchemaGenerator(configuration).run();
        assertEquals("" +
                        "{\n" +
                        "  \"$id\":\"io_yupiik_maven_service_action_builtin_JsonSchemaGeneratorTest_Foo\",\n" +
                        "  \"type\":\"object\",\n" +
                        "  \"definitions\":{\n" +
                        "\n" +
                        "  },\n" +
                        "  \"properties\":{\n" +
                        "    \"age\":{\n" +
                        "      \"type\":\"integer\",\n" +
                        "      \"title\":\"the age\",\n" +
                        "      \"description\":\"the age\"\n" +
                        "    },\n" +
                        "    \"foo2\":{\n" +
                        "      \"$id\":\"io_yupiik_maven_service_action_builtin_JsonSchemaGeneratorTest_Foo2\",\n" +
                        "      \"type\":\"object\",\n" +
                        "      \"title\":\"nested\",\n" +
                        "      \"description\":\"nested\",\n" +
                        "      \"properties\":{\n" +
                        "        \"number\":{\n" +
                        "          \"type\":\"integer\",\n" +
                        "          \"title\":\"the number\",\n" +
                        "          \"description\":\"the number\"\n" +
                        "        },\n" +
                        "        \"street\":{\n" +
                        "          \"type\":\"string\",\n" +
                        "          \"title\":\"the street\",\n" +
                        "          \"description\":\"the street\"\n" +
                        "        }\n" +
                        "      }\n" +
                        "    },\n" +
                        "    \"name\":{\n" +
                        "      \"type\":\"string\",\n" +
                        "      \"title\":\"Foo.name\",\n" +
                        "      \"description\":\"the name\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}",
                new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
    }

    @Test
    void adoc(@TempDir final Path dir) throws IOException {
        Files.createDirectories(dir);
        final Path output = dir.resolve("in.txt");
        final Map<String, String> configuration = new HashMap<>();
        configuration.put("class", Foo.class.getName());
        configuration.put("to", output.toString());
        configuration.put("type", "ADOC");
        configuration.put("title", "The Title");
        configuration.put("description", "The Description");
        new JsonSchemaGenerator(configuration).run();
        assertEquals("" +
                        "= The Title\n" +
                        "\n" +
                        "The Description\n" +
                        "\n" +
                        "[cols=\"2,2m,1,5\", options=\"header\"]\n" +
                        ".The Title\n" +
                        "|===\n" +
                        "|Name|JSON Name|Type|Description\n" +
                        "|age|age|integer|the age\n" +
                        "|name|name|string|the name\n" +
                        "|<<io_yupiik_maven_service_action_builtin_JsonSchemaGeneratorTest_Foo2>>|foo2|object|nested\n" +
                        "|===\n" +
                        "\n" +
                        "[#io_yupiik_maven_service_action_builtin_JsonSchemaGeneratorTest_Foo2]\n" +
                        "== nested\n" +
                        "\n" +
                        "nested\n" +
                        "\n" +
                        "[cols=\"2,2m,1,5\", options=\"header\"]\n" +
                        ".nested\n" +
                        "|===\n" +
                        "|Name|JSON Name|Type|Description\n" +
                        "|number|number|integer|the number\n" +
                        "|street|street|string|the street\n" +
                        "|===\n" +
                        "\n" +
                        "\n",
                new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
    }

    @Retention(RUNTIME)
    public @interface Desc {
        String value();
    }

    @Data
    @Desc("The foo")
    public static class Foo {
        @Desc("the name")
        private String name;

        @Desc("the age")
        private int age;

        @Desc("nested")
        private Foo2 foo2;
    }

    @Data
    @Desc("The foo2")
    public static class Foo2 {
        @Desc("the street")
        private String street;

        @Desc("the number")
        private int number;
    }
}
