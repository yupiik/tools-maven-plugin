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

import io.yupiik.maven.configuration.PreAction;
import io.yupiik.maven.service.AsciidoctorInstance;
import io.yupiik.maven.service.action.ActionExecutor;
import io.yupiik.maven.service.ftp.configuration.Ftp;
import io.yupiik.maven.service.ftp.configuration.FtpService;
import io.yupiik.maven.service.git.Git;
import io.yupiik.maven.service.git.GitService;
import io.yupiik.maven.service.search.IndexService;
import lombok.RequiredArgsConstructor;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.SafeMode;
import org.asciidoctor.ast.DocumentHeader;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.lang.ClassLoader.getSystemClassLoader;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
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
     * Generate search json.
     */
    @Parameter(property = "yupiik.minisite.searchIndexName", defaultValue = "search.json")
    private String searchIndexName;

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
    @Parameter(defaultValue = "footer.html")
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
        executePreActions();
        if (skipRendering) {
            getLog().info("Rendering (and upload) skipped");
            return;
        }
        final Options options = createOptions();
        asciidoctor.withAsciidoc(this, a -> {
            doRender(a, options, createTemplate(options, a));
            return null;
        });
        if (ftp != null && !ftp.isIgnore()) {
            doFtpUpload();
        }
        if (git != null && !git.isIgnore()) {
            doGitUpdate();
        }
    }

    protected void executePreActions() {
        if (preActions == null || preActions.isEmpty()) {
            return;
        }
        final Thread thread = Thread.currentThread();
        final ClassLoader parentLoader = thread.getContextClassLoader();
        try (final URLClassLoader loader = createProjectLoader(getSystemClassLoader())) {
            thread.setContextClassLoader(loader);
            preActions.forEach(it -> actionExecutor.execute(it, source.toPath(), target.toPath()));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } finally {
            thread.setContextClassLoader(parentLoader);
        }
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
                    project.getVersion());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void doFtpUpload() {
        final Server server = session.getSettings().getServer(ofNullable(ftp.getServerId()).orElse(siteBase));
        final SettingsDecryptionResult decrypted = settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
        final Server clearServer = decrypted.getServer();
        if (clearServer != null && clearServer.getPassword() != null) {
            ftp.setUsername(clearServer.getUsername());
            ftp.setPassword(clearServer.getPassword());
        }
        ftpService.upload(ftp, target.toPath(), getLog()::info);
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

    private void render(final Page page, final Path html,
                        final Asciidoctor asciidoctor, final Options options,
                        final Function<Page, String> template) {
        try {
            Files.write(html, template.apply(new Page(
                    ofNullable(page.title).orElseGet(this::getTitle),
                    singletonMap("minisite-passthrough", true), "" +
                    "        <div class=\"container page-content\">\n" +
                    ofNullable(page.title).map(t -> "" +
                            "            <div class=\"page-header\">\n" +
                            "                <h1>" + t + "</h1>\n" +
                            "            </div>\n")
                            .orElse("") +
                    "            <div class=\"page-content-body\">\n" +
                    renderAdoc(page, asciidoctor, options).trim() + '\n' +
                    "            </div>\n" +
                    "        </div>\n"
            )).getBytes(StandardCharsets.UTF_8));
            return;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String getIcon(final Page it) {
        return ofNullable(it.attributes.get("minisite-index-icon"))
                .map(String::valueOf)
                .map(i -> i.startsWith("fa") && i.contains(" ") ? i : ("fas fa-" + i))
                .orElse("fas fa-download-alt");
    }

    private String getTitle(final Page it) {
        return ofNullable(it.attributes.get("minisite-index-title")).map(String::valueOf).orElse(it.title);
    }

    private String leftMenu(final Map<Page, Path> files) {
        final Path output = target.toPath();
        return "" +
                "        <div class=\"page-navigation-left\">\n" +
                "            <h3>Menu</h3>\n" +
                "            <ul>\n" +
                findIndexPages(files).map(it -> "                " +
                        "<li><a href=\"" + siteBase + '/' + output.relativize(it.getValue()) + "\">" +
                        "<i class=\"" + getIcon(it.getKey()) + "\"></i> " +
                        getTitle(it.getKey()) + "</a></li>\n")
                        .collect(joining()) +
                "            </ul>\n" +
                "        </div>";
    }

    /**
     * Logic there is to use attributes to find {@code minisite-index} attributes.
     * The value is an integer to sort the pages.
     * Then all these pages (others are ignored) are added on index page.
     *
     * @param htmls    pages.
     * @param template
     * @return the index.html content.
     */
    private String generateIndex(final Map<Page, Path> htmls, final Function<Page, String> template) {
        final Path output = target.toPath();
        String content = findIndexPages(htmls)
                .map(p -> "" +
                        "                    <div class=\"col-12 col-lg-4 py-3\">\n" +
                        "                        <div class=\"card shadow-sm\">\n" +
                        "                            <div class=\"card-body\">\n" +
                        "                                <h5 class=\"card-title mb-3\">\n" +
                        "                                    <span class=\"theme-icon-holder card-icon-holder mr-2\">\n" +
                        "                                        <i class=\"" +
                        getIcon(p.getKey()) + "\"></i>\n" +
                        "                                    </span>\n" +
                        "                                    <span class=\"card-title-text\">                         " +
                        getTitle(p.getKey()) + "</span>\n" +
                        "                                </h5>\n" +
                        "                                <div class=\"card-text\">\n                                  " +
                        ofNullable(p.getKey().attributes.get("minisite-index-description")).map(String::valueOf).orElse(p.getKey().title) +
                        "\n                                </div>\n" +
                        "                                <a class=\"card-link-mask\" href=\"" + siteBase + '/' + output.relativize(p.getValue()) + "\"></a>\n" +
                        "                            </div>\n" +
                        "                        </div>\n" +
                        "                    </div>\n" +
                        "")
                .collect(joining("",
                        "    <div class=\"page-content\">\n" +
                                "        <div class=\"container\">\n" +
                                "            <h1 class=\"page-heading mx-auto\">" + getIndexText() + " Documentation</h1>\n" +
                                "            <div class=\"page-intro mx-auto\">" + getIndexSubTitle() + "</div>\n" +
                                "            <div class=\"docs-overview py-5\">\n" +
                                "                <div class=\"row justify-content-center\">\n",
                        "                </div>\n" +
                                "            </div>\n" +
                                "        </div>\n" +
                                "    </div>\n"));

        content = template
                .apply(new Page(ofNullable(title).orElse("Index"), singletonMap("minisite-passthrough", true), content))
                .replace("<minisite-menu-placeholder/>\n", "");

        // now we must drop the navigation-left/right since we reused the global template - easier than rebuilding a dedicated layout
        final int navRight = content.indexOf("<div class=\"page-navigation-right\">");
        if (navRight > 0) {
            content = content.substring(0, navRight) + content.substring(content.indexOf("</div>", navRight) + "</div>".length());
        }

        return content;
    }

    private Stream<Map.Entry<Page, Path>> findIndexPages(final Map<Page, Path> htmls) {
        return htmls.entrySet().stream()
                .filter(p -> p.getKey().attributes.containsKey("minisite-index"))
                .sorted(comparing(p -> Integer.parseInt(String.valueOf(p.getKey().attributes.get("minisite-index")).trim())));
    }

    private String generateSiteMap(final Map<Page, Path> pages) {
        final String now = LocalDate.now().toString();
        final Path output = target.toPath();
        return pages.entrySet().stream()
                .sorted(comparing(p -> p.getKey().title))
                .map(it -> "" +
                        "    <url>\n" +
                        "        <loc>" + siteBase + '/' + output.relativize(it.getValue()) + "</loc>\n" +
                        "        <lastmod>" + ofNullable(it.getKey().attributes.get("minisite-lastmod")).map(String::valueOf).orElse(now) + "</lastmod>\n" +
                        "    </url>\n")
                .collect(joining("",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" " +
                                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                                "xsi:schemaLocation=\"http://www.sitemaps.org/schemas/sitemap/0.9 http://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd\">\n",
                        "</urlset>\n"));
    }

    protected void doRender(final Asciidoctor asciidoctor, final Options options, final Function<Page, String> template) {
        final Path content = source.toPath().resolve("content");
        final Map<Page, Path> files = new HashMap<>();
        final Path output = target.toPath();
        if (!Files.exists(output)) {
            try {
                Files.createDirectories(output);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (Files.exists(content)) {
            try {
                Files.walkFileTree(content, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        final String filename = file.getFileName().toString();
                        if (!filename.endsWith(".adoc")) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (content.relativize(file).toString().startsWith("_partials")) {
                            return FileVisitResult.CONTINUE;
                        }
                        final String contentString = String.join("\n", Files.readAllLines(file));
                        final DocumentHeader header = asciidoctor.readDocumentHeader(contentString);
                        final Page page = new Page(
                                header.getPageTitle(),
                                header.getAttributes(),
                                contentString);
                        if (page.attributes.containsKey("minisite-skip")) {
                            return FileVisitResult.CONTINUE;
                        }
                        final Path out = ofNullable(header.getAttributes().get("minisite-path"))
                                .map(it -> output.resolve(it.toString()))
                                .orElseGet(() -> output.resolve(content.relativize(file)).getParent().resolve(
                                        filename.substring(0, filename.length() - ".adoc".length()) + ".html"));
                        if (out.getParent() != null) {
                            Files.createDirectories(out.getParent());
                        }
                        render(page, out, asciidoctor, options, template);
                        files.put(page, out);
                        getLog().debug("Rendering " + file + " to " + out);
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (generateIndex) {
            try {
                Files.write(output.resolve("index.html"), generateIndex(files, template).getBytes(StandardCharsets.UTF_8));
                getLog().debug("Generated index.html");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (generateSiteMap) {
            try {
                Files.write(output.resolve("sitemap.xml"), generateSiteMap(files).getBytes(StandardCharsets.UTF_8));
                getLog().debug("Generated sitemap.xml");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (templateAddLeftMenu) {
            final String leftMenu = leftMenu(files);
            try {
                Files.walkFileTree(output, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().endsWith(".html")) {
                            final List<String> lines = Files.readAllLines(file);
                            final int toReplace = lines.indexOf("<minisite-menu-placeholder/>");
                            if (toReplace >= 0) {
                                getLog().debug("Replacing left menu in " + file);
                                lines.set(toReplace, leftMenu);
                                Files.write(file, lines);
                            }
                        }
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (useDefaultAssets) {
            Stream.of(
                    "yupiik-tools-maven-plugin/minisite/assets/css/theme.css",
                    "yupiik-tools-maven-plugin/minisite/assets/js/minisite.js")
                    .forEach(resource -> {
                        final Path out = output.resolve(resource.substring("yupiik-tools-maven-plugin/minisite/assets/".length()));
                        try (final BufferedReader buffer = new BufferedReader(new InputStreamReader(Thread.currentThread().getContextClassLoader()
                                .getResourceAsStream(resource), StandardCharsets.UTF_8))) {
                            Files.createDirectories(out.getParent());
                            Files.write(out, buffer.lines().collect(joining("\n")).replace("{{base}}", siteBase).getBytes(StandardCharsets.UTF_8));
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }

        final Path assets = source.toPath().resolve("assets");
        if (Files.exists(assets)) {
            try {
                Files.walkFileTree(assets, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        final Path out = output.resolve(assets.relativize(file));
                        if (out.getParent() != null) {
                            Files.createDirectories(out.getParent());
                        }
                        Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
                        getLog().debug("Copying " + file + " to " + out);
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        if (hasSearch()) {
            indexService.write(indexService.index(output, siteBase), output.resolve(searchIndexName));
        }

        getLog().info("Rendered minisite '" + source.getName() + "'");
    }

    private boolean hasSearch() {
        return !"none".equals(searchIndexName) && searchIndexName != null;
    }

    protected Function<Page, String> createTemplate(final Options options, final Asciidoctor asciidoctor) {
        final Path layout = source.toPath().resolve("templates");
        String prefix = readTemplates(layout, templatePrefixes)
                .replace("{{search}}", hasSearch() ? "" +
                        "<li class=\"list-inline-item\">" +
                        "<a id=\"search-button\" href=\"#\" data-toggle=\"modal\" data-target=\"#searchModal\">" +
                        "<i data-toggle=\"tooltip\" data-placement=\"top\" title=\"Search\" class=\"fas fa-search\"></i>" +
                        "</a>" +
                        "</li>\n" +
                        "" : "")
                .replace("{{customHead}}", ofNullable(customHead).orElse(""))
                .replace("{{projectVersion}}", project.getVersion()) // enables to invalidate browser cache
                .replace("{{logoText}}", getLogoText())
                .replace("{{base}}", siteBase)
                .replace("{{logo}}", ofNullable(logo).orElse("//www.yupiik.com/img/favicon.png"))
                .replace("{{linkedInCompany}}", ofNullable(linkedInCompany)
                        .orElse("yupiik"));
        final String suffix = readTemplates(layout, templateSuffixes)
                .replace("{{searchModal}}", hasSearch() ? "" +
                        "<div class=\"modal fade\" id=\"searchModal\" tabindex=\"-1\" role=\"dialog\" aria-labelledby=\"searchModalLabel\" aria-hidden=\"true\">\n" +
                        "  <div class=\"modal-dialog modal-dialog-centered\" role=\"document\">\n" +
                        "    <div class=\"modal-content\">\n" +
                        "      <div class=\"modal-header\">\n" +
                        "        <h5 class=\"modal-title\" id=\"searchModalLabel\">Search</h5>\n" +
                        "        <button type=\"button\" class=\"close\" data-dismiss=\"modal\" aria-label=\"Close\">\n" +
                        "          <span aria-hidden=\"true\">&times;</span>\n" +
                        "        </button>\n" +
                        "      </div>\n" +
                        "      <div class=\"modal-body\">\n" +
                        "        <form onsubmit=\"return false;\" class=\"form-inline\">\n" +
                        "          <div class=\"form-group\">\n" +
                        "            <label for=\"searchInput\"><b>Search: </b></label>\n" +
                        "            <input class=\"form-control\" id=\"searchInput\" placeholder=\"Enter search text and hit enter...\">\n" +
                        "          </div>\n" +
                        "        </form>\n" +
                        "        <div class=\"search-hits\"></div>\n" +
                        "      </div>\n" +
                        "      <div class=\"modal-footer\">\n" +
                        "        <button type=\"button\" class=\"btn btn-primary\" data-dismiss=\"modal\">Close</button>\n" +
                        "      </div>\n" +
                        "    </div>\n" +
                        "  </div>\n" +
                        "</div>" :
                        "")
                .replace("{{copyright}}", ofNullable(copyright)
                        .orElse("Yupiik &copy;"))
                .replace("{{linkedInCompany}}", ofNullable(linkedInCompany)
                        .orElse("yupiik"))
                .replace("{{customScripts}}",
                        ofNullable(customScripts).orElse("").trim() + (hasSearch() ?
                                "\n<script src=\"https://cdnjs.cloudflare.com/ajax/libs/fuse.js/6.4.3/fuse.min.js\" " +
                                        "integrity=\"sha512-neoBxVNv0UMXjoilAYGxfWrSsW6iAVclx7vQKdPJ9Peet1bM5YQjU0aIB8LtH8iNPa+pAyMZprBBw2ZQ/Q1LjQ==\" " +
                                        "crossorigin=\"anonymous\"></script>\n" :
                                "\n"))
                .replace("{{projectVersion}}", project.getVersion()) // enables to invalidate browser cache
                .replace("{{base}}", siteBase);
        if (templateAddLeftMenu) {
            prefix += "\n<minisite-menu-placeholder/>\n";
        }
        final String prefixRef = prefix;
        return page -> String.join(
                "\n",
                prefixRef
                        .replace("{{title}}", ofNullable(page.title).orElse(title))
                        .replace("{{description}}", ofNullable(page.attributes)
                                .map(a -> a.get("minisite-description"))
                                .map(String::valueOf)
                                .orElseGet(() -> ofNullable(description).orElseGet(this::getIndexSubTitle))),
                page.attributes.containsKey("minisite-passthrough") ? page.content : renderAdoc(page, asciidoctor, options),
                suffix);
    }

    private String getLogoText() {
        return ofNullable(logoText)
                .orElseGet(() -> ofNullable(project.getName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElse(project.getArtifactId()));
    }

    private String getIndexText() {
        return ofNullable(indexText)
                .orElseGet(() -> ofNullable(project.getName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElseGet(() -> getLogoText() + " Documentation"));
    }

    private String getIndexSubTitle() {
        return ofNullable(indexSubTitle)
                .orElseGet(() -> ofNullable(project.getName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElse(project.getArtifactId()));
    }

    private String getTitle() {
        return ofNullable(title)
                .orElseGet(() -> "Yupiik " + getLogoText());
    }

    private String renderAdoc(final Page page, final Asciidoctor asciidoctor, final Options options) {
        final StringWriter writer = new StringWriter();
        try (final StringReader reader = new StringReader(page.content)) {
            asciidoctor.convert(reader, writer, options);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return writer.toString();
    }

    private String readTemplates(final Path layout, final List<String> templatePrefixes) {
        return templatePrefixes.stream()
                .map(it -> {
                    final Path resolved = layout.resolve(it);
                    if (Files.exists(resolved)) {
                        try {
                            return Files.newBufferedReader(resolved);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    final InputStream stream = Thread.currentThread().getContextClassLoader()
                            .getResourceAsStream("yupiik-tools-maven-plugin/minisite/" + it);
                    return stream == null ? null : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                })
                .filter(Objects::nonNull)
                .flatMap(it -> {
                    try (final BufferedReader r = it) { // materialize it to close the stream there
                        return r.lines().collect(toList()).stream();
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(joining("\n"));
    }

    protected Options createOptions() {
        final AttributesBuilder attributes = AttributesBuilder.attributes()
                .linkCss(false)
                .dataUri(true)
                .attribute("stem")
                .attribute("source-highlighter", "highlightjs")
                .attribute("highlightjsdir", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.0.3")
                .attribute("highlightjs-theme", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/styles/vs2015.min.css")
                .attribute("imagesdir", "images")
                .attributes(this.attributes == null ? emptyMap() : this.attributes);
        if (project != null && (this.attributes == null || !this.attributes.containsKey("projectVersion"))) {
            attributes.attribute("projectVersion", project.getVersion());
        }
        final OptionsBuilder options = OptionsBuilder.options()
                .safe(SafeMode.UNSAFE)
                .backend("html5")
                .inPlace(false)
                .headerFooter(false)
                .baseDir(source.toPath().resolve("content").getParent().toAbsolutePath().normalize().toFile())
                .attributes(attributes);
        if (templateDirs != null && !templateDirs.isEmpty()) {
            options.templateDirs(templateDirs.toArray(new File[0]));
        }
        return options.get();
    }

    @RequiredArgsConstructor
    protected static class Page {
        private final String title;
        private final Map<String, Object> attributes;
        private final String content;
    }
}
