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
package io.yupiik.tools.doc;

import io.yupiik.fusion.documentation.DocumentationGenerator;
import io.yupiik.maven.service.git.Git;
import io.yupiik.maven.service.git.GitService;
import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.MiniSite;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import io.yupiik.tools.minisite.PreAction;
import io.yupiik.tools.minisite.language.YupiikAsciidoc;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static lombok.AccessLevel.PRIVATE;

@Log
@NoArgsConstructor(access = PRIVATE)
public final class Generate {
    public static void main(final String... args) throws Exception {
        final var doc = Path.of(args.length == 0 ? "src/main/minisite" : args[0]);
        final var out = Path.of(args.length == 0 ? "target/minisite" : args[1]);
        final var projectName = args.length < 3 ? "Yupiik Tools" : args[2];
        final var projectArtifact = args.length < 4 ? "yupiik-tools" : args[3];
        final var projectVersion = args.length < 5 ? "dev" : args[4];
        final var deploy = args.length >= 6 && Boolean.parseBoolean(args[5]);
        final var username = args.length >= 7 ? args[6] : System.getProperty("user.name", "user");
        final var password = args.length >= 8 && Files.exists(Path.of(args[7])) ? Files.readString(Path.of(args[7])).strip() : null;

        final var mojoAction = new PreAction();
        mojoAction.setType("maven-plugin");
        mojoAction.setConfiguration(Map.of(
                "toBase", doc.resolve("content/mojo").toString(),
                "pluginXml", doc.resolve("../../../../yupiik-tools-maven-plugin/target/classes/META-INF/maven/plugin.xml").toString()));

        final var generateEnvManagerConfiguration = new PreAction();
        generateEnvManagerConfiguration.setType(DocumentationGenerator.class.getName());
        generateEnvManagerConfiguration.setConfiguration(Map.of(
                "includeEnvironmentNames", "true",
                "module", "yem",
                "urls", doc
                        .resolve("../../../../env-manager/target/classes/META-INF/fusion/configuration/documentation.json")
                        .normalize()
                        .toUri().toURL().toExternalForm()));

        final var generateEnvManagerCommands = new PreAction();
        generateEnvManagerCommands.setType(YemCommands.class.getName());

        final var configuration = new MiniSiteConfiguration();
        configuration.setIndexText("Yupiik Tools");
        configuration.setIndexSubTitle("adoc:" +
                "A set of build utilities.\n" +
                "\n" +
                "IMPORTANT: 1.0.x use `javax.json` whereas 1.1.x uses `jakarta.json`, " +
                "it can impact your setup and need customization for minisite generation for example.\n" +
                "Last available 1.0.x release is 1.0.26.");
        configuration.setTitle("Yupiik Tools Plugin");
        configuration.setLogoSideText("Docs");
        configuration.setLogo("//www.yupiik.io/images/logo.svg");
        configuration.setLinkedInCompany("yupiik");
        configuration.setUseDefaultAssets(true);
        configuration.setInjectYupiikTemplateExtensionPoints(true);
        configuration.setSearchIndexName("search.json");
        configuration.setGenerateBlog(true);
        configuration.setBlogPageSize(10);
        configuration.setBlogPublicationDate("today");
        configuration.setReverseBlogOrder(true);
        configuration.setAddIndexRegistrationPerCategory(true);
        configuration.setInjectBlogMeta(true);
        configuration.setSource(doc);
        configuration.setTarget(out);
        configuration.setSiteBase("/tools-maven-plugin");
        configuration.setPreActions(List.of(mojoAction, generateEnvManagerConfiguration, generateEnvManagerCommands));
        configuration.setGenerateSiteMap(true);
        configuration.setGenerateIndex(true);
        configuration.setProjectName(projectName);
        configuration.setProjectArtifactId(projectArtifact);
        configuration.setProjectVersion(projectVersion);
        configuration.setTemplateExtensionPoints(Map.of("point", "{{point}}"));
        configuration.setTemplatePrefixes(List.of("header.html", "menu.html"));
        configuration.setTemplateSuffixes(List.of("footer-top.html", "footer-end.html"));
        configuration.setTemplateAddLeftMenu(true);
        configuration.setActionClassLoader(() -> new ClassLoader(Thread.currentThread().getContextClassLoader()) {
            // avoid it to be closed too early by wrapping it in a not URLCLassLoader
        });
        configuration.setAttributes(Map.of("partialsdir", doc + "/content/_partials"));
        configuration.setAsciidoc(new YupiikAsciidoc());
        configuration.setAsciidoctorConfiguration(new AsciidoctorConfiguration() {
            @Override
            public Path gems() {
                return null;
            }

            @Override
            public String customGems() {
                return null;
            }

            @Override
            public List<String> requires() {
                return List.of();
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
        });

        new MiniSite(configuration).run();

        if (deploy) {
            final var git = new Git();
            git.setIgnore(false);
            git.setNoJekyll(true);
            git.setUsername(username);
            git.setPassword(password);
            git.setBranch("refs/heads/gh-pages");
            git.setUrl("https://github.com/yupiik/tools-maven-plugin.git");

            final var work = out.getParent().resolve("minisite_work_" + UUID.randomUUID());
            new GitService().update(git, out, log::info, work, projectVersion, s -> {
                throw new IllegalStateException("shouldn't be called");
            });
        }
    }
}
