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
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.yupiik.maven.mojo.GitReportMojo.Renderer.DEFAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GitReportMojoTest {
    @Test
    void map(@TempDir final Path tmp) throws MojoExecutionException, MojoFailureException, IOException, GitAPIException {
        final var repository = tmp.resolve("fake-git");
        try (final var git = Git.init()
                .setDirectory(repository.toFile())
                .call()) {
            Files.writeString(repository.resolve("file"), "whatever");
            git.add()
                    .addFilepattern("file")
                    .call();
            git.commit()
                    .setCommitter("Test", "test@yupiik.com")
                    .setMessage("initial import")
                    .call();
        }
        final var output = tmp.resolve("output.adoc");
        assertFalse(Files.exists(output));
        new GitReportMojo() {{
            dotGit = repository.resolve(".git").toFile();
            target = output.toFile();
            // defaults but since we don't use maven harnessing toolkit we have to propagate it ourselves
            renderers = new Renderer[]{DEFAULT};
            title = "Report";
        }}.execute();
        assertEquals(("" +
                        "= Report\n" +
                        "Yupiik <contact@yupiik.com>\n" +
                        "\n" +
                        "\n" +
                        "\n" +
                        "== Commits Per Author\n" +
                        "\n" +
                        "=== Test (#1)\n" +
                        "\n" +
                        "* [<sha>][<year>-<month>-<day>T<hour>:<minutes>:<seconds>] initial import\n" +
                        ""),
                Files.readString(output, StandardCharsets.UTF_8)
                        .replaceFirst("\\* \\[`[^`]+`]\\[\\p{Digit}{4}-\\p{Digit}{2}-\\p{Digit}{2}T\\p{Digit}{2}:\\p{Digit}{2}(:\\p{Digit}{2})?] initial import$",
                                "* [<sha>][<year>-<month>-<day>T<hour>:<minutes>:<seconds>] initial import"));
    }
}
