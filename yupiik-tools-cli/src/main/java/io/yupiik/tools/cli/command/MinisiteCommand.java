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

import io.yupiik.tools.cli.service.AsciidoctorConfigurationImpl;
import io.yupiik.tools.cli.service.AsciidoctorProvider;
import io.yupiik.tools.minisite.MiniSite;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import io.yupiik.tools.minisite.PreAction;
import io.yupiik.tools.minisite.language.AsciidoctorAsciidoc;
import io.yupiik.tools.minisite.language.YupiikAsciidoc;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Err;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;
import org.tomitribe.crest.api.Required;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class MinisiteCommand {
    @Command(usage = "Builds a minisite.")
    public static void minisite(@Option(value = "source", description = "Where the minisite sits in (input folder).") @Required final Path source,
                                @Option(value = "target", description = "Where the minisite will be generated (output folder).") @Required final Path target,
                                @Option(value = "workdir", description = "Where the minisite can generate temporary files.") @Default("${java.io.tmpdir}/yupiik/minisite") final Path workdir,
                                @Option(value = "title", description = "Site title.") final String title,
                                @Option(value = "description", description = "Site description.") final String description,
                                @Option(value = "logoText", description = "Site text next the logo.") final String logoText,
                                @Option(value = "logoSideText", description = "Site text next the logo.") final String logoSideText,
                                @Option(value = "logo", description = "Site logo.") @Default("//www.yupiik.io/images/logo.svg") final String logo,
                                @Option(value = "indexText", description = "Site index content text.") final String indexText,
                                @Option(value = "indexSubTitle", description = "Site index subtitle text.") final String indexSubTitle,
                                @Option(value = "copyright", description = "Site copyright text.") final String copyright,
                                @Option(value = "linkedInCompany", description = "LinkedIn name (to link it).") @Default("yupiik") final String linkedInCompany,
                                @Option(value = "searchIndexName", description = "Name for the main search index file.") @Default("search.json") final String searchIndexName,
                                @Option(value = "customHead", description = "Custom head content.") final String customHead,
                                @Option(value = "customScripts", description = "Custom scripts (injected at the end before closing body tag).") final String customScripts,
                                @Option(value = "customMenu", description = "Custom menu entries.") final String customMenu,
                                @Option(value = "siteBase", description = "Base URL for generated links.") @Default("/") final String siteBase,
                                @Option(value = "useDefaultAssets", description = "Should default assets be used for the site rendering.") @Default("true") final boolean useDefaultAssets,
                                @Option(value = "generateIndex", description = "Should index be generated (or is it written manually).") @Default("true") final boolean generateIndex,
                                @Option(value = "generateSiteMap", description = "Should sitemap be generated.") @Default("true") final boolean generateSiteMap,
                                @Option(value = "skipRendering", description = "Should rendering be done.") @Default("false") final boolean skipRendering,
                                @Option(value = "skipIndexTitleDocumentationText", description = "Should Documentation title suffix be skipped.") @Default("false") final boolean skipIndexTitleDocumentationText,
                                @Option(value = "addIndexRegistrationPerCategory", description = "Should blog categories pages be added to home page.") @Default("true") final boolean addIndexRegistrationPerCategory,
                                @Option(value = "blogCategoriesCustomizations", description = "Enables to customize how categories are shown on home page - set attributes in properties format.") @Default("true") final Map<String, MiniSiteConfiguration.BlogCategoryConfiguration> blogCategoriesCustomizations,
                                @Option(value = "templateAddLeftMenu", description = "Should left menu (with index links) be added on pages.") @Default("true") final boolean templateAddLeftMenu,
                                @Option(value = "templatePrefixes", description = "Template names for page 'prefixes' (top part of the page).") @Default("header.html,menu.html") final List<String> templatePrefixes,
                                @Option(value = "templateSuffixes", description = "Template names for page 'suffixes' (bottom part of the page).") @Default("footer-top.html,footer-end.html") final List<String> templateSuffixes,
                                @Option(value = "injectYupiikTemplateExtensionPoints", description = "Autoconfigure extension point to have yupiik default template values.") @Default("false") final boolean injectYupiikTemplateExtensionPoints,
                                @Option(value = "generateBlog", description = "Should blog be generated.") @Default("true") final boolean generateBlog,
                                @Option(value = "reverseBlogOrder", description = "Should blog index pages be sorted by reversed publication date.") @Default("true") final boolean reverseBlogOrder,
                                @Option(value = "blogPageSize", description = "Site page size when blog is enabled.") @Default("10") final int blogPageSize,
                                @Option(value = "templateDirs", description = "Asciidoctor template directory.") final List<File> templateDirs,
                                @Option(value = "notIndexedPages", description = "When search is enabled the files to not index.") final List<String> notIndexedPages,
                                @Option(value = "attributes", description = "Asciidoctor attributes (using properties syntax).") final Map<String, Object> attributes,
                                @Option(value = "templateExtensionPoints", description = "Extension point content.") final Map<String, String> templateExtensionPoints,
                                @Option(value = "version", description = "Version (for projectVersion template).") @Default("latest") final String version,
                                @Option(value = "name", description = "Name (for projectName template).") @Default("doc") final String name,
                                @Option(value = "artifactId", description = "Artifact ID (use to default some values).") @Default("doc") final String artifactId,
                                @Option(value = "customGems", description = "Custom JRuby gems path.") final String customGems,
                                @Option(value = "requires", description = "Custom ruby requires (asciidoctor dependencies).") @Default("auto") final List<String> requires,
                                @Option(value = "preActions", description = "PreAction to execute in properties format (type and configuration as keys, configuration value being properties again).") final List<PreAction> preActions,
                                @Option(value = "useYupiikAsciidoc", description = "Should Yupiik Asciidoc renderer be used instead of JRuby Asciidoctor one.") final boolean useYupiikAsciidoc,
                                @Out final PrintStream stdout,
                                @Err final PrintStream stderr,
                                final AsciidoctorProvider asciidoctorProvider) {
        new MiniSite(MiniSiteConfiguration.builder()
                .preActions(preActions)
                .customGems(customGems)
                .requires(List.of("auto").equals(requires) ? null : requires)
                .projectVersion(version)
                .projectName(name == null ? artifactId : name)
                .projectArtifactId(artifactId == null ? name : artifactId)
                .asciidoc(useYupiikAsciidoc ? new YupiikAsciidoc() : new AsciidoctorAsciidoc((conf, fn) -> {
                    final var asciidoctor = asciidoctorProvider.get(conf, stdout, stderr, workdir, false);
                    fn.apply(asciidoctor);
                    return asciidoctor;
                }))
                .actionClassLoader(() -> Thread.currentThread().getContextClassLoader())
                .source(source)
                .target(target)
                .templateDirs(templateDirs)
                .attributes(attributes)
                .title(title)
                .description(description)
                .logoText(logoText)
                .logo(logo)
                .indexText(indexText)
                .indexSubTitle(indexSubTitle)
                .copyright(copyright)
                .blogPageSize(blogPageSize)
                .generateBlog(generateBlog)
                .linkedInCompany(linkedInCompany)
                .customHead(customHead)
                .customScripts(customScripts)
                .customMenu(customMenu)
                .siteBase("/".equals(siteBase) ? "" : siteBase)
                .useDefaultAssets(useDefaultAssets)
                .searchIndexName(searchIndexName)
                .notIndexedPages(notIndexedPages)
                .generateIndex(generateIndex)
                .generateSiteMap(generateSiteMap)
                .templatePrefixes(templatePrefixes)
                .templateAddLeftMenu(templateAddLeftMenu)
                .templateSuffixes(templateSuffixes)
                .skipRendering(skipRendering)
                .asciidoctorConfiguration(new AsciidoctorConfigurationImpl(workdir.resolve("gem"), customGems, requires, stdout, stderr))
                .reverseBlogOrder(reverseBlogOrder)
                .addIndexRegistrationPerCategory(addIndexRegistrationPerCategory)
                .blogCategoriesCustomizations(blogCategoriesCustomizations)
                .skipIndexTitleDocumentationText(skipIndexTitleDocumentationText)
                .logoSideText(logoSideText)
                .injectYupiikTemplateExtensionPoints(injectYupiikTemplateExtensionPoints)
                .templateExtensionPoints(templateExtensionPoints)
                .build())
                .run();
    }
}
