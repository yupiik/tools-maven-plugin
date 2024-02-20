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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class GenerateBlogReleaseFromGithubMojoTest {
    @Disabled // for local test usage
    @Test
    void map() throws MojoExecutionException, IOException {
        Files.deleteIfExists(Path.of("target/releases-blog-post-*.adoc"));

        new GenerateBlogReleaseFromGithubMojo() {{
            from = "01/10/2022";
            to = "01/12/2022";
            useMavenCredentials = false;
            githubBaseApi = "https://api.github.com/";
            githubServerId = "github.com";
            githubRepositories = List.of("yupiik/bundlebee", "yupiik/uship");
            threads = 16;
            workdir = "target";
        }}.execute();
    }
}
