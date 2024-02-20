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

import io.yupiik.maven.service.AsciidoctorInstance;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CucumberReport2AsciidocTest {
    @Test
    void convert(@TempDir final Path dir) throws IOException, MojoExecutionException, MojoFailureException {
        final var input = dir.resolve("input");
        final var output = dir.resolve("output.adoc");
        Files.createDirectories(input);
        Files.writeString(input.resolve("report.json"), "" +
                "[\n" +
                "  {\n" +
                "    \"uri\": \"features/one_passing_one_failing.feature\",\n" +
                "    \"keyword\": \"Feature\",\n" +
                "    \"id\": \"one-passing-scenario,-one-failing-scenario\",\n" +
                "    \"name\": \"One passing scenario, one failing scenario\",\n" +
                "    \"line\": 2,\n" +
                "    \"description\": \"\",\n" +
                "    \"tags\": [\n" +
                "      {\n" +
                "        \"name\": \"@a\",\n" +
                "        \"location\": {\n" +
                "           \"line\": 1,\n" +
                "           \"column\": 1\n" +
                "         }\n" +
                "      }\n" +
                "    ],\n" +
                "    \"elements\": [\n" +
                "      {\n" +
                "        \"keyword\": \"Scenario\",\n" +
                "        \"id\": \"one-passing-scenario,-one-failing-scenario;passing\",\n" +
                "        \"name\": \"Passing\",\n" +
                "        \"line\": 5,\n" +
                "        \"description\": \"this will pass\",\n" +
                "        \"tags\": [\n" +
                "          {\n" +
                "            \"name\": \"@b\",\n" +
                "            \"location\": {\n" +
                "              \"line\": 4,\n" +
                "              \"column\": 1\n" +
                "            }\n" +
                "          }\n" +
                "        ],\n" +
                "        \"type\": \"scenario\",\n" +
                "        \"steps\": [\n" +
                "          {\n" +
                "            \"keyword\": \"Given \",\n" +
                "            \"name\": \"this step passes\",\n" +
                "            \"line\": 6,\n" +
                "            \"match\": {\n" +
                "              \"location\": \"features/step_definitions/steps.rb:1\"\n" +
                "            },\n" +
                "            \"result\": {\n" +
                "              \"status\": \"passed\",\n" +
                "              \"duration\": 1\n" +
                "            }\n" +
                "          }\n" +
                "        ]\n" +
                "      },\n" +
                "      {\n" +
                "        \"keyword\": \"Scenario\",\n" +
                "        \"id\": \"one-passing-scenario,-one-failing-scenario;failing\",\n" +
                "        \"name\": \"Failing\",\n" +
                "        \"line\": 9,\n" +
                "        \"description\": \"this fails\",\n" +
                "        \"tags\": [\n" +
                "          {\n" +
                "            \"name\": \"@c\",\n" +
                "            \"location\": {\n" +
                "              \"line\": 8,\n" +
                "              \"column\": 1\n" +
                "            }\n" +
                "          }\n" +
                "        ],\n" +
                "        \"type\": \"scenario\",\n" +
                "        \"steps\": [\n" +
                "          {\n" +
                "            \"keyword\": \"Given \",\n" +
                "            \"name\": \"this step fails\",\n" +
                "            \"line\": 10,\n" +
                "            \"match\": {\n" +
                "              \"location\": \"features/step_definitions/steps.rb:4\"\n" +
                "            },\n" +
                "            \"result\": {\n" +
                "              \"status\": \"failed\",\n" +
                "              \"error_message\": \" (RuntimeError)\\n./features/step_definitions/steps.rb:4:in /^this step fails$/'\\nfeatures/one_passing_one_failing.feature:10:in Given this step fails'\",\n" +
                "              \"duration\": 1\n" +
                "            }\n" +
                "          }\n" +
                "        ]\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "]" +
                "");
        new CucumberReport2Asciidoc() {{
            source = input.toFile();
            target = output.toFile();
            prefix = "= Report\n" +
                    ":toc:\n" +
                    "\n" +
                    "<<<\n" +
                    "== Project description\n" +
                    "\n" +
                    "...\n" +
                    "\n" +
                    "<<<\n";
            suffix = "<<<\nDone!";
        }}.execute();

        if (Boolean.getBoolean("yupiik.test.mojo.cucumberreport.pdf")) { // easier to test pdf rendering from IDE
            renderDebugPDF(output);
        }

        assertEquals("" +
                        "= Report\n" +
                        ":toc:\n" +
                        "\n" +
                        "<<<\n" +
                        "== Project description\n" +
                        "\n" +
                        "...\n" +
                        "\n" +
                        "<<<\n" +
                        "\n" +
                        "[grid=none,frame=none,cols=\"1,1\"]\n" +
                        "|===\n" +
                        "|{set:cellbgcolor:#2CB14A} 1 passed |{set:cellbgcolor:#BB0000} 1 failed\n" +
                        "|===\n" +
                        "\n" +
                        "[cols=\"^,^,^\",options=\"header\"]\n" +
                        "|====\n" +
                        "|{set:cellbgcolor:inherit} *0.50 % passed*|*-*|*0.0 seconds*\n" +
                        "\n" +
                        "|{set:cellbgcolor:inherit}2 executed|Execution time|Duration\n" +
                        "|====\n" +
                        "\n" +
                        "== Test Runs\n" +
                        "\n" +
                        "[options=\"header\",cols=\"1,3,1\"]\n" +
                        "|===\n" +
                        "|Name|Description|Status\n" +
                        "|{set:cellbgcolor:inherit} <<one-passing-scenario_-one-failing-scenario_passing,Passing>> |{set:cellbgcolor:inherit} this will pass |{set:cellbgcolor:#2CB14A} PASSED\n" +
                        "|{set:cellbgcolor:inherit} <<one-passing-scenario_-one-failing-scenario_failing,Failing>> |{set:cellbgcolor:inherit} this fails |{set:cellbgcolor:#BB0000} FAILED\n" +
                        "|===\n" +
                        "\n" +
                        "== Tests details\n" +
                        "\n" +
                        "[#one-passing-scenario_-one-failing-scenario_passing]\n" +
                        "=== Passing\n" +
                        "\n" +
                        "==== Description\n" +
                        "\n" +
                        "this will pass\n" +
                        "\n" +
                        "==== Tags\n" +
                        "\n" +
                        "* b\n" +
                        "\n" +
                        "==== Steps\n" +
                        "\n" +
                        "===== Passing\n" +
                        "\n" +
                        "Status:: passed\n" +
                        "Duration:: 0 second\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "[#one-passing-scenario_-one-failing-scenario_failing]\n" +
                        "=== Failing\n" +
                        "\n" +
                        "==== Description\n" +
                        "\n" +
                        "this fails\n" +
                        "\n" +
                        "==== Tags\n" +
                        "\n" +
                        "* c\n" +
                        "\n" +
                        "==== Steps\n" +
                        "\n" +
                        "===== Failing\n" +
                        "\n" +
                        "Status:: failed\n" +
                        "Duration:: 0 second\n" +
                        "\n" +
                        "[source]\n" +
                        "----\n" +
                        "(RuntimeError)\n" +
                        "./features/step_definitions/steps.rb:4:in /^this step fails$/'\n" +
                        "features/one_passing_one_failing.feature:10:in Given this step fails'\n" +
                        "----\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "<<<\n" +
                        "Done!" +
                        "",
                Files.readString(output, StandardCharsets.UTF_8));
    }

    private void renderDebugPDF(final Path output) throws MojoExecutionException {
        final PDFMojo mojo = new PDFMojo();
        final AsciidoctorInstance asciidoctor = new AsciidoctorInstance();
        mojo.setSourceDirectory(output.getParent().toFile());
        mojo.setTargetDirectory(new File("/tmp/pdf"));
        mojo.setRequires(List.of());
        mojo.setWorkDir(new File("target/classes/yupiik-tools-maven-plugin"));
        mojo.setAsciidoctor(asciidoctor);
        mojo.setLog(new SystemStreamLog());
        mojo.execute();
    }
}
