/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.maven.service.ftp.configuration.Ftp;
import io.yupiik.maven.service.ftp.configuration.FtpService;
import io.yupiik.maven.service.git.Git;
import io.yupiik.maven.service.git.GitService;
import io.yupiik.tools.minisite.ActionExecutor;
import io.yupiik.tools.minisite.IndexService;
import io.yupiik.tools.minisite.MiniSite;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import io.yupiik.tools.minisite.PreAction;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;

import javax.inject.Inject;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static java.lang.ClassLoader.getSystemClassLoader;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.apache.maven.plugins.annotations.ResolutionScope.COMPILE_PLUS_RUNTIME;

/**
 * Minisite goal. Enables to generate a small website.
 * <p>
 * It needs a "documentation" module, it uses the following default layout:
 * <ul>
 *     <li>src/main/doc/content: contains a set of .adoc (converted to .html)</li>
 *     <li>src/main/doc/assets: contains a set of files moved "as this" to site output directory (css, js)</li>
 *     <li>src/main/doc/templates: contains the templates for the layout and pages of the website</li>
 * </ul>
 * <p>
 * TODO: add a way to execute a main before the rendering.
 */
@Mojo(name = "minisite", requiresDependencyResolution = COMPILE_PLUS_RUNTIME)
public class MiniSiteMojo extends BaseMojo {
    /**
     * Where to read content (layout root) from.
     */
    @Parameter(property = "yupiik.minisite.source", defaultValue = "${project.basedir}/src/main/minisite")
    protected File source;

    /**
     * Where to generate the site.
     */
    @Parameter(property = "yupiik.minisite.target", defaultValue = "${project.build.directory}/${project.build.finalName}")
    protected File target;

    /**
     * target/classes for pre-actions if needed.
     */
    @Parameter(property = "yupiik.minisite.classes", defaultValue = "${project.build.outputDirectory}")
    protected File classes;

    /**
     * Template directory if set.
     */
    @Parameter(property = "yupiik.slides.templateDirs")
    private List<File> templateDirs;

    /**
     * Asciidoctor attributes.
     */
    @Parameter
    private Map<String, Object> attributes;

    /**
     * Default HTML page title.
     */
    @Parameter(property = "yupiik.minisite.title")
    private String title;

    /**
     * Default HTML page description.
     */
    @Parameter(property = "yupiik.minisite.description")
    private String description;

    /**
     * Default logo text for default template.
     */
    @Parameter(property = "yupiik.minisite.logoText")
    private String logoText;

    /**
     * Logo url.
     */
    @Parameter(property = "yupiik.minisite.logo", defaultValue = "//www.yupiik.com/img/favicon.png")
    private String logo;

    /**
     * When index is generated the content page title.
     */
    @Parameter(property = "yupiik.minisite.indexText")
    private String indexText;

    /**
     * When index is generated the content page title.
     */
    @Parameter(property = "yupiik.minisite.indexSubTitle")
    private String indexSubTitle;

    /**
     * copyright value in the footer when using default theme.
     */
    @Parameter(property = "yupiik.minisite.copyright")
    private String copyright;

    /**
     * linkedInCompany name (in linkedin url).
     */
    @Parameter(property = "yupiik.minisite.linkedInCompany", defaultValue = "yupiik")
    private String linkedInCompany;

    /**
     * Custom head links (to add custom css easily).
     */
    @Parameter(property = "yupiik.minisite.customHead")
    protected String customHead;

    /**
     * Custom scripts links (to add custom js easily).
     */
    @Parameter(property = "yupiik.minisite.customScripts")
    protected String customScripts;

    /**
     * Custom html template for menus.
     */
    @Parameter(property = "yupiik.minisite.customMenu")
    protected String customMenu;

    /**
     * Default HTML page description.
     */
    @Parameter(property = "yupiik.minisite.siteBase", defaultValue = "http://localhost:4200")
    protected String siteBase;

    /**
     * Use default assets.
     */
    @Parameter(property = "yupiik.minisite.useDefaultAssets", defaultValue = "true")
    private boolean useDefaultAssets;

    /**
     * Use yupiik theme.
     */
    @Parameter(property = "yupiik.minisite.injectYupiikTemplateExtensionPoints", defaultValue = "true")
    private boolean injectYupiikTemplateExtensionPoints;

    /**
     * Custom default theme extension points.
     * An extension point is a placeholder in a template surrounded by 3 braces: {@code {{{point}}} }.
     */
    @Parameter(property = "yupiik.minisite.templateExtensionPoints")
    private Map<String, String> templateExtensionPoints;

    /**
     * Generate search json.
     */
    @Parameter(property = "yupiik.minisite.searchIndexName", defaultValue = "search.json")
    private String searchIndexName;

    /**
     * Generate blog pages if it has some blog entries - skipped otherwise.
     */
    @Parameter(property = "yupiik.minisite.generateBlog", defaultValue = "true")
    private boolean generateBlog;

    /**
     * For blog generated pages, the number of items to keep per page.
     */
    @Parameter(property = "yupiik.minisite.blogPageSize", defaultValue = "10")
    private int blogPageSize;

    /**
     * Generate index page.
     */
    @Parameter(property = "yupiik.minisite.generateIndex", defaultValue = "true")
    private boolean generateIndex;

    /**
     * Generate sitemap page (xml).
     */
    @Parameter(property = "yupiik.minisite.generateSiteMap", defaultValue = "true")
    private boolean generateSiteMap;

    /**
     * Template file name added before the content.
     */
    @Parameter(defaultValue = "header.html,menu.html")
    private List<String> templatePrefixes;

    /**
     * Should left menu (global navigation) be added from index links.
     */
    @Parameter(property = "yupiik.minisite.addLeftMenu", defaultValue = "true")
    private boolean templateAddLeftMenu;

    /**
     * Template file name added after the content.
     */
    @Parameter(defaultValue = "footer-top.html,footer-end.html")
    private List<String> templateSuffixes;

    /**
     * Actions to execute before any rendering.
     * Typically used to generate some content.
     */
    @Parameter
    private List<PreAction> preActions;

    /**
     * Skip mojo execution.
     */
    @Parameter(property = "yupiik.minisite.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Skip site rendering.
     */
    @Parameter(property = "yupiik.minisite.skipRendering", defaultValue = "false")
    private boolean skipRendering;

    /**
     * Should page sorting be reversed (by published date).
     */
    @Parameter(property = "yupiik.minisite.reverseBlogOrder", defaultValue = "true")
    private boolean reverseBlogOrder;

    /**
     * Should blog categories pages be added to home page.
     */
    @Parameter(property = "yupiik.minisite.addIndexRegistrationPerCategory", defaultValue = "true")
    private boolean addIndexRegistrationPerCategory;

    /**
     * Should Documentation title suffix be skipped.
     */
    @Parameter(property = "yupiik.minisite.skipIndexTitleDocumentationText", defaultValue = "false")
    private boolean skipIndexTitleDocumentationText;

    /**
     * Text next to the logo text.
     */
    @Parameter(property = "yupiik.minisite.logoSideText", defaultValue = "Docs")
    private String logoSideText;

    /**
     * Enables to customize how categories are shown on home page.
     */
    @Parameter
    private Map<String, MiniSiteConfiguration.BlogCategoryConfiguration> blogCategoriesCustomizations;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter
    private Ftp ftp;

    @Parameter
    private Git git;

    @Inject
    protected AsciidoctorInstance asciidoctor;

    @Inject
    private FtpService ftpService;

    @Inject
    private GitService gitService;

    @Inject
    private IndexService indexService;

    @Inject
    private ActionExecutor actionExecutor;

    @Component
    private SettingsDecrypter settingsDecrypter;

    @Override
    public void doExecute() {
        if (skip) {
            getLog().info("Mojo skipped");
            return;
        }
        fixConfig();
        new MiniSite(createMiniSiteConfiguration()).run();
        if (ftp != null && !ftp.isIgnore()) {
            doFtpUpload();
        }
        if (git != null && !git.isIgnore()) {
            doGitUpdate();
        }
    }

    protected MiniSiteConfiguration createMiniSiteConfiguration() {
        return MiniSiteConfiguration.builder()
                .actionClassLoader(() -> createProjectLoader(getSystemClassLoader()))
                .source(source.toPath())
                .target(target.toPath())
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
                .generateIndex(generateIndex)
                .generateSiteMap(generateSiteMap)
                .templatePrefixes(templatePrefixes)
                .templateAddLeftMenu(templateAddLeftMenu)
                .templateSuffixes(templateSuffixes)
                .preActions(preActions)
                .skipRendering(skipRendering)
                .customGems(customGems)
                .requires(requires)
                .projectVersion(project.getVersion())
                .projectName(project.getName())
                .projectArtifactId(project.getArtifactId())
                .asciidoctorConfiguration(this)
                .asciidoctorPool((conf, fn) -> asciidoctor.withAsciidoc(conf, fn))
                .reverseBlogOrder(reverseBlogOrder)
                .addIndexRegistrationPerCategory(addIndexRegistrationPerCategory)
                .blogCategoriesCustomizations(blogCategoriesCustomizations)
                .skipIndexTitleDocumentationText(skipIndexTitleDocumentationText)
                .logoSideText(logoSideText)
                .injectYupiikTemplateExtensionPoints(injectYupiikTemplateExtensionPoints)
                .templateExtensionPoints(templateExtensionPoints)
                .build();
    }

    private URLClassLoader createProjectLoader(final ClassLoader parent) {
        final List<URL> urls = project.getArtifacts().stream()
                .filter(artifact -> artifact.getFile() != null)
                .filter(artifact -> !"test".equals(artifact.getScope()))
                .filter(artifact -> "jar".equals(artifact.getType()) || "zip".equals(artifact.getType()))
                .map(artifact -> {
                    try {
                        return artifact.getFile().toURI().toURL();
                    } catch (final MalformedURLException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(toList());
        if (classes != null && classes.exists()) {
            try {
                urls.add(classes.toURI().toURL());
            } catch (final MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    private void doGitUpdate() {
        Server server = session.getSettings().getServer(ofNullable(git.getServerId())
                .orElseGet(() -> project.getScm().getUrl()));
        if (server == null) { // try host only
            try {
                final String host = new URL(project.getScm().getUrl()).getHost();
                server = session.getSettings().getServer(host);
            } catch (final MalformedURLException ignored) {
                // no-op
            }
        }
        getLog().info("Using serverId=" + server.getId() + " for git synchronization");
        final SettingsDecryptionResult decrypted = settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
        final Server clearServer = decrypted.getServer();
        if (clearServer != null && clearServer.getPassword() != null) {
            git.setUsername(clearServer.getUsername());
            git.setPassword(clearServer.getPassword());
        }
        if (git.getUrl() == null) {
            git.setUrl(project.getScm().getUrl());
        }
        if (git.getBranch() == null) {
            git.setBranch("refs/heads/gh-pages");
        }
        try {
            gitService.update(
                    git, target.toPath(), getLog()::info,
                    Paths.get(project.getBuild().getDirectory()).resolve("minisite_git_work"),
                    project.getVersion(),
                    this::decryptServer);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void doFtpUpload() {
        final Server clearServer = decryptServer(ftp.getServerId());
        if (clearServer != null && clearServer.getPassword() != null) {
            ftp.setUsername(clearServer.getUsername());
            ftp.setPassword(clearServer.getPassword());
        }
        ftpService.upload(ftp, target.toPath(), getLog()::info);
    }

    private Server decryptServer(final String serverId) {
        final Server server = session.getSettings().getServer(ofNullable(serverId).orElse(siteBase));
        final SettingsDecryptionResult decrypted = settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
        return decrypted.getServer() == null ? server : decrypted.getServer();
    }

    protected void fixConfig() {
        if (requires == null) { // ensure we don't load reveal.js by default since we disabled extraction of gems
            requires = emptyList();
        }
    }

    @Override // not needed in this mojo
    protected Path extract(final Path output) {
        return null;
    }
}
