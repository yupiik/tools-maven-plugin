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

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleDependenciesMojoTest {
    @Test
    void run(@TempDir final Path work) throws MojoExecutionException, MojoFailureException, IOException {
        final var file = work.resolve("deps.json");
        final var mojo = new SimpleDependenciesMojo() {{
            output = file.toString();
            format = Format.JSON_PRETTY;
            scope = "all";
            project = new MavenProject();
            project.setGroupId("test.yupiik");
            project.setArtifactId("test");
            project.setVersion("1.2.3-SNAPSHOT");
            project.setArtifacts(Set.of(new DefaultArtifact(
                    "the.group", "the.art", "the.version",
                    "the.scope", "the.type", "the.classifier", null)));
        }};
        mojo.execute();
        assertEquals("" +
                "{\n" +
                "  \"artifactId\":\"test\",\n" +
                "  \"groupId\":\"test.yupiik\",\n" +
                "  \"items\":[\n" +
                "    {\n" +
                "      \"artifactId\":\"the.art\",\n" +
                "      \"classifier\":\"the.classifier\",\n" +
                "      \"groupId\":\"the.group\",\n" +
                "      \"scope\":\"the.scope\",\n" +
                "      \"type\":\"the.type\",\n" +
                "      \"version\":\"the.version\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"packaging\":\"jar\",\n" +
                "  \"version\":\"1.2.3-SNAPSHOT\"\n" +
                "}" +
                "", Files.readString(file));
    }
}
