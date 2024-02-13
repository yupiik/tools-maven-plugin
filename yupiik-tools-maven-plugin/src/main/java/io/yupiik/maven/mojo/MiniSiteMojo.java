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
import io.yupiik.maven.service.confluence.Confluence;
import io.yupiik.maven.service.confluence.ConfluenceService;
import io.yupiik.maven.service.ftp.configuration.Ftp;
import io.yupiik.maven.service.ftp.configuration.FtpService;
import io.yupiik.maven.service.git.Git;
import io.yupiik.maven.service.git.GitService;
import io.yupiik.tools.minisite.ActionExecutor;
import io.yupiik.tools.minisite.IndexService;
import io.yupiik.tools.minisite.MiniSite;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import io.yupiik.tools.minisite.PreAction;
import io.yupiik.tools.minisite.language.Asciidoc;
import io.yupiik.tools.minisite.language.AsciidoctorAsciidoc;
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
import java.io.IOException;
import java.io.InputStream;
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
 */
@Mojo(name = "minisite", requiresDependencyResolution = COMPILE_PLUS_RUNTIME, threadSafe = true)
public class MiniSiteMojo extends BaseMojo {
    /**
     * Should Yupiik asciidoctor-java be used instead of asciidoctorj (over jruby).
     * While this is likely faster to bootstrap and render it is not as complete as asciidoctor as of today
     * and requires to run at least on Java 17.
     */
    @Parameter(property = "yupiik.minisite.preferYupiikAsciidoc", defaultValue = "false")
    protected boolean preferYupiikAsciidoc;

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
     * RSS feed location (relative to the output) if set.
     */
    @Parameter(property = "yupiik.minisite.rssFeedFile")
    private String rssFeedFile;

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
    @Parameter(property = "yupiik.minisite.logo", defaultValue = "//www.yupiik.io/images/logo.svg")
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
     * Adds a copy button to code snippets (highlightjs).
     */
    @Parameter(property = "yupiik.minisite.addCodeCopyButton", defaultValue = "true")
    private boolean addCodeCopyButton;

    /**
     * Use yupiik theme.
     */
    @Parameter(property = "yupiik.minisite.injectYupiikTemplateExtensionPoints", defaultValue = "true")
    private boolean injectYupiikTemplateExtensionPoints;

    /**
     * Custom default theme extension points.
     * An extension point is a placeholder in a template surrounded by 3 braces: {@code {{{xyz}}} }.
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
     * Max date until when posts are published.
     * {@code today} and {@code infinite} are supported as dynamic alias.
     * Using {@code default} the minisite rendering uses {@code today} and the serve mode uses {@code infinite} to get local previews.
     */
    @Parameter(property = "yupiik.minisite.blogPublicationDate", defaultValue = "default")
    private String blogPublicationDate;

    /**
     * Add reading time on blog pages.
     */
    @Parameter(property = "yupiik.minisite.injectBlogMeta", defaultValue = "true")
    private boolean injectBlogMeta;

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
     * Asciidoctor extensions to register in Asciidoctor instance.
     * Can be in target/classes.
     */
    @Parameter
    protected List<AsciidoctorInstance.AsciidoctorExtension> asciidoctorExtensions;

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
     * Should asciidoctor extensions and preactions be loaded with the provided dependencies in the classloader or not.
     */
    @Parameter(property = "yupiik.minisite.includeProvidedScope", defaultValue = "true")
    private boolean includeProvidedScope;

    /**
     * Artifacts to ignore in the classloader loading asciidoctor extensions and preactions.
     */
    @Parameter(property = "yupiik.minisite.excludedArtifacts", defaultValue = "org.asciidoctor:asciidoctorj")
    private List<String> excludedArtifacts;

    /**
     * A boolean or auto (= if asciidoctorExtensions is not empty it behaves as true) to use the plugin classloader as parent
     * for preaction and asciidoctor extension or not.
     */
    @Parameter(property = "yupiik.minisite.usePluginAsParentClassLoader", defaultValue = "auto")
    private String usePluginAsParentClassLoader;

    /**
     * Text next to the logo text.
     */
    @Parameter(property = "yupiik.minisite.logoSideText", defaultValue = "Docs")
    private String logoSideText;

    /**
     * Gravatar configuration.
     */
    @Parameter
    private MiniSiteConfiguration.GravatarConfiguration gravatar;

    /**
     * Enables to customize how categories are shown on home page.
     */
    @Parameter
    private Map<String, MiniSiteConfiguration.BlogCategoryConfiguration> blogCategoriesCustomizations;

    /**
     * List of pages to ignore in search.json when search is enabled, typically useful if you create an all in one page.
     */
    @Parameter
    private List<String> notIndexedPages;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter
    private Ftp ftp;

    @Parameter
    private Confluence confluence;

    @Parameter
    private Git git;

    @Inject
    protected AsciidoctorInstance asciidoctor;

    @Inject
    private FtpService ftpService;

    @Inject
    private ConfluenceService confluenceService;

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
        try (final var loader = createProjectLoader()) {
            new MiniSite(createMiniSiteConfiguration(loader)).run();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        if (ftp != null && !ftp.isIgnore()) {
            doFtpUpload();
        }
        if (git != null && !git.isIgnore()) {
            doGitUpdate();
        }
        if (confluence != null && !confluence.isIgnore()) {
            doConfluenceUpdate();
        }
    }

    private ClassLoader getParentClassLoader() {
        return "true".equalsIgnoreCase(usePluginAsParentClassLoader) ||
                ("auto".equalsIgnoreCase(usePluginAsParentClassLoader) && asciidoctorExtensions != null && !asciidoctorExtensions.isEmpty()) ?
                Thread.currentThread().getContextClassLoader() :
                getSystemClassLoader();
    }

    protected MiniSiteConfiguration createMiniSiteConfiguration(final ClassLoader loader) {
        return MiniSiteConfiguration.builder()
                .actionClassLoader(() -> new ClassLoader(loader) {
                    // just to avoid minisite to close it, we do it in the enclosing scope
                })
                .addCodeCopyButton(addCodeCopyButton)
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
                .rssFeedFile(rssFeedFile)
                .blogPublicationDate("default".equals(blogPublicationDate) ? getDefaultPublicationDate() : blogPublicationDate)
                .injectBlogMeta(injectBlogMeta)
                .generateBlog(generateBlog)
                .linkedInCompany(linkedInCompany)
                .customHead(customHead)
                .customScripts(customScripts)
                .customMenu(customMenu)
                .siteBase(getNormalizedSiteBase())
                .useDefaultAssets(useDefaultAssets)
                .searchIndexName(searchIndexName)
                .notIndexedPages(notIndexedPages)
                .generateIndex(generateIndex)
                .generateSiteMap(generateSiteMap)
                .templatePrefixes(templatePrefixes)
                .templateAddLeftMenu(templateAddLeftMenu)
                .templateSuffixes(templateSuffixes)
                .preActions(preActions)
                .skipRendering(skipRendering)
                .customGems(customGems)
                .requires(requires)
                .projectVersion(ofNullable(attributes).map(a -> a.get("projectVersion")).map(Object::toString).orElseGet(() -> project.getVersion()))
                .projectName(ofNullable(attributes).map(a -> a.get("projectName")).map(Object::toString).orElseGet(() -> project.getName()))
                .projectArtifactId(ofNullable(attributes).map(a -> a.get("projectArtifactId")).map(Object::toString).orElseGet(() -> project.getArtifactId()))
                .asciidoctorConfiguration(this)
                .asciidoc(createAsciidoc(preferYupiikAsciidoc))
                .reverseBlogOrder(reverseBlogOrder)
                .addIndexRegistrationPerCategory(addIndexRegistrationPerCategory)
                .blogCategoriesCustomizations(blogCategoriesCustomizations)
                .skipIndexTitleDocumentationText(skipIndexTitleDocumentationText)
                .logoSideText(logoSideText)
                .injectYupiikTemplateExtensionPoints(injectYupiikTemplateExtensionPoints)
                .templateExtensionPoints(templateExtensionPoints)
                .gravatar(gravatar == null ? new MiniSiteConfiguration.GravatarConfiguration() : gravatar)
                .build();
    }

    protected Asciidoc createAsciidoc(final boolean tryYupiikImpl) {
        if (tryYupiikImpl) {
            try {
                return MiniSiteMojo.class.getClassLoader()
                        .loadClass("io.yupiik.tools.minisite.language.YupiikAsciidoc")
                        .asSubclass(Asciidoc.class)
                        .getConstructor()
                        .newInstance();
            } catch (final Error | Exception cnfe) {
                getLog().warn("Can't use asciidoctor-java, switching to asciidoctorj over JRuby,\n" +
                        "if you want this behavior add as yupiik-tools-maven-plugin the following dependency and ensure to run on java >= 17:\n" +
                        "\n" +
                        " <dependencies>\n" +
                        "   <dependency>\n" +
                        "     <groupId>io.yupiik.maven</groupId>\n" +
                        "     <artifactId>asciidoc-java</artifactId>\n" +
                        "     <version>${yupiik-tools.version}</version>\n" +
                        "   </dependency>\n" +
                        " </dependencies>", cnfe);
            }
        }
        return new AsciidoctorAsciidoc((conf, fn) -> asciidoctor.withAsciidoc(conf, fn, asciidoctorExtensions));
    }

    protected String getDefaultPublicationDate() {
        return "today";
    }

    protected URLClassLoader createProjectLoader() {
        final List<URL> urls = project.getArtifacts().stream()
                .filter(artifact -> artifact.getFile() != null)
                .filter(artifact -> !"test".equals(artifact.getScope()))
                .filter(artifact -> includeProvidedScope || !"provided".equals(artifact.getScope()))
                .filter(artifact -> excludedArtifacts == null || excludedArtifacts.isEmpty() ||
                        !excludedArtifacts.contains(artifact.getGroupId() + ':' + artifact.getArtifactId()))
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
        final var thread = Thread.currentThread();
        final var originalLoader = thread.getContextClassLoader();
        final var urlClassLoader = new URLClassLoader(urls.toArray(new URL[0]), getParentClassLoader()) {
            @Override
            public InputStream getResourceAsStream(final String name) {
                // if parent is not real one then we wouldn't find our own resources otherwise
                final var res = super.getResourceAsStream(name);
                if (res != null) {
                    return res;
                }
                return originalLoader.getResourceAsStream(name);
            }

            @Override
            public void close() throws IOException {
                super.close();
                thread.setContextClassLoader(originalLoader);
            }
        };
        thread.setContextClassLoader(urlClassLoader);
        return urlClassLoader;
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

    private void doConfluenceUpdate() {
        final Server clearServer = decryptServer(confluence.getServerId());
        if (clearServer != null && clearServer.getPassword() != null) {
            confluence.setAuthorization(clearServer.getPassword());
        }
        confluenceService.upload(confluence, target.toPath(), getLog()::info, getNormalizedSiteBase(), getLog());
    }

    private String getNormalizedSiteBase() {
        return "/".equals(siteBase) ? "" : siteBase;
    }

    private Server decryptServer(final String serverId) {
        final Server server = session.getSettings().getServer(ofNullable(serverId).orElse(siteBase));
        if (server == null) {
            return null;
        }
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
