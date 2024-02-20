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
package io.yupiik.tools.cli.command;

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.MiniSite;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import io.yupiik.tools.minisite.language.AsciidoctorAsciidoc;
import lombok.extern.java.Log;
import org.asciidoctor.Asciidoctor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static io.yupiik.tools.cli.launcher.Main.main;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@Log
@TestInstance(PER_CLASS)
class VersionInjectorCommandTest {
    private static final Asciidoctor ASCIIDOCTOR = Asciidoctor.Factory.create();

    @AfterAll
    static void close() {
        ASCIIDOCTOR.close();
    }

    @Test
    void run(@TempDir final Path workDir) throws IOException {
        final var source = workDir.resolve("source");
        final var output = workDir.resolve("output");

        // create some versions
        Files.createDirectories(workDir.resolve("1.0.0"));
        Files.createDirectories(workDir.resolve("1.0.1"));
        Files.createDirectories(workDir.resolve("1.121.1"));
        Files.createDirectories(workDir.resolve("1.12.1"));
        Files.createDirectories(workDir.resolve("1.3.1"));
        Files.createDirectories(workDir.resolve("v1.2.1"));

        // render some content
        Files.writeString(
                Files.createDirectories(source.resolve("content")).resolve("page.adoc"),
                "= Page 1\n\nContent.\n");
        generateSite(workDir, source, output);

        // call the command
        main(
                "inject-version",
                "--source=" + output,
                "--inplace=true",
                "--version-folder-parent=" + workDir,
                "--select-properties=id=version-selector\nonchange=console.log(document.getElementById('version-selector').value)",
                "--replaced-string=regex:(?s)<div class=\"page-navigation-left\">(.*)            </ul>\n        </div>",
                "--replacing-content=" +
                        "<div class=\"page-navigation-left\">\n" +
                        "\n" +
                        "<div class=\"version-selector\">\n" +
                        "<!-- generated_versions_select -->\n" +
                        "</div>\n" +
                        "$1\n" +
                        "    </ul>\n" +
                        "  </div>\n");
        assertEquals(
                "<div class=\"page\">\n" +
                        "        <div class=\"page-navigation-left\">\n" +
                        "\n" +
                        "<div class=\"version-selector\">\n" +
                        "<!-- START generated_versions_select -->" +
                        "<select id=\"version-selector\" onchange=\"console.log(document.getElementById('version-selector').value)\"" +
                        " data-versioninjector-generation-iteration=\"1\">" +
                        "<option>1.0.0</option>" +
                        "<option>1.0.1</option>" +
                        "<option>v1.2.1</option>" +
                        "<option>1.3.1</option>" +
                        "<option>1.12.1</option>" +
                        "<option>1.121.1</option>" +
                        "</select>" +
                        "<!-- END generated_versions_select -->/div>\n" +
                        "\n" +
                        "            <h3>Menu</h3>\n" +
                        "            <ul>\n" +
                        "\n" +
                        "\n" +
                        "    </ul>\n" +
                        "  </div>\n" +
                        "\n" +
                        "\n" +
                        "        <div id=\"page-html-container\" class=\"container page-content page-html\">\n" +
                        "                        <div class=\"page-header\">\n" +
                        "                <h1>Page 1</h1>\n" +
                        "            </div>\n" +
                        "            <div class=\"page-content-body\">\n" +
                        "                <div class=\"paragraph\">\n" +
                        "<p>Content.</p>\n" +
                        "</div>\n" +
                        "                \n" +
                        "                \n" +
                        "            </div>\n" +
                        "        </div>",
                extractContent(Files.readString(output.resolve("page.html"))).strip());
    }

    private String extractContent(final String readString) {
        return readString.substring(
                readString.indexOf("<div class=\"page\">"),
                readString.indexOf("<div class=\"page-navigation-right\">"));
    }

    private void generateSite(final Path workDir, final Path source, final Path output) {
        new MiniSite(
                MiniSiteConfiguration.builder()
                        .source(source)
                        .target(output)
                        .actionClassLoader(null)
                        .skipRendering(false)
                        .useDefaultAssets(true)
                        .generateIndex(true)
                        .generateSiteMap(true)
                        .generateBlog(true)
                        .templateAddLeftMenu(true)
                        .blogPageSize(2)
                        .title("Test Site")
                        .description("The test rendering.")
                        .logoText("The logo")
                        .logo("foo logo")
                        .indexText("Index text")
                        .indexSubTitle("Index sub title")
                        .copyright("Test Copyright")
                        .linkedInCompany("test linkedin")
                        .siteBase("")
                        .searchIndexName("search.json")
                        .templatePrefixes(List.of("header.html", "menu.html"))
                        .templateSuffixes(List.of("footer-top.html", "footer-end.html"))
                        .projectVersion("1.0.0")
                        .projectName("test project")
                        .projectArtifactId("test-artifact")
                        .asciidoc(new AsciidoctorAsciidoc((conf, fn) -> fn.apply(ASCIIDOCTOR)))
                        .asciidoctorConfiguration(new AsciidoctorConfiguration() {
                            @Override
                            public Path gems() {
                                return workDir.resolve("gems");
                            }

                            @Override
                            public String customGems() {
                                return null;
                            }

                            @Override
                            public List<String> requires() {
                                return null;
                            }

                            @Override
                            public Consumer<String> info() {
                                return log::info;
                            }

                            @Override
                            public Consumer<String> debug() {
                                return log::finest;
                            }

                            @Override
                            public Consumer<String> warn() {
                                return log::warning;
                            }

                            @Override
                            public Consumer<String> error() {
                                return log::severe;
                            }
                        })
                        .build())
                .run();
    }
}
