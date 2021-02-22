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
package io.yupiik.tools.minisite;

import lombok.RequiredArgsConstructor;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.SafeMode;
import org.asciidoctor.ast.DocumentHeader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
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

import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@RequiredArgsConstructor
public class MiniSite implements Runnable {
    private final MiniSiteConfiguration configuration;

    @Override
    public void run() {
        configuration.fixConfig();
        executePreActions();
        if (configuration.isSkipRendering()) {
            configuration.getAsciidoctorConfiguration().info().accept("Rendering (and upload) skipped");
            return;
        }
        final Options options = createOptions();
        configuration.getAsciidoctorPool().apply(configuration.getAsciidoctorConfiguration(), a -> {
            doRender(a, options, createTemplate(options, a));
            return null;
        });
    }

    public void executePreActions() {
        if (configuration.getPreActions() == null || configuration.getPreActions().isEmpty()) {
            return;
        }
        final var executor = new ActionExecutor();
        final Thread thread = Thread.currentThread();
        final ClassLoader parentLoader = thread.getContextClassLoader();
        final var classLoader = ofNullable(configuration.getActionClassLoader().get())
                .orElseGet(Thread.currentThread()::getContextClassLoader);
        try {
            thread.setContextClassLoader(classLoader);
            configuration.getPreActions().forEach(it -> executor.execute(it, configuration.getSource(), configuration.getTarget()));
        } finally {
            if (URLClassLoader.class.isInstance(classLoader) && configuration.getActionClassLoader() != null) {
                try {
                    URLClassLoader.class.cast(classLoader).close();
                } catch (final IOException e) {
                    configuration.getAsciidoctorConfiguration().error().accept(e.getMessage());
                }
            }
            thread.setContextClassLoader(parentLoader);
        }
    }

    private void render(final Page page, final Path html,
                        final Asciidoctor asciidoctor, final Options options,
                        final Function<Page, String> template) {
        try {
            final Map<String, Object> attrs = new HashMap<>(Map.of("minisite-passthrough", true));
            attrs.putAll(page.attributes);
            Files.write(html, template.apply(new Page(
                    ofNullable(page.title).orElseGet(this::getTitle),
                    attrs, "" +
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
        final Path output = configuration.getTarget();
        return "" +
                "        <div class=\"page-navigation-left\">\n" +
                "            <h3>Menu</h3>\n" +
                "            <ul>\n" +
                findIndexPages(files).map(it -> "                " +
                        "<li><a href=\"" + configuration.getSiteBase() + '/' + output.relativize(it.getValue()) + "\">" +
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
        final Path output = configuration.getTarget();
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
                        "                                <a class=\"card-link-mask\" href=\"" + configuration.getSiteBase() + '/' + output.relativize(p.getValue()) + "\"></a>\n" +
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
                .apply(new Page(ofNullable(configuration.getTitle()).orElse("Index"), Map.of("minisite-passthrough", true), content))
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
        final Path output = configuration.getTarget();
        return pages.entrySet().stream()
                .sorted(comparing(p -> p.getKey().title))
                .map(it -> "" +
                        "    <url>\n" +
                        "        <loc>" + configuration.getSiteBase() + '/' + output.relativize(it.getValue()) + "</loc>\n" +
                        "        <lastmod>" + ofNullable(it.getKey().attributes.get("minisite-lastmod")).map(String::valueOf).orElse(now) + "</lastmod>\n" +
                        "    </url>\n")
                .collect(joining("",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" " +
                                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                                "xsi:schemaLocation=\"http://www.sitemaps.org/schemas/sitemap/0.9 http://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd\">\n",
                        "</urlset>\n"));
    }

    public void doRender(final Asciidoctor asciidoctor, final Options options, final Function<Page, String> template) {
        final Path content = configuration.getSource().resolve("content");
        final Map<Page, Path> files = new HashMap<>();
        final Path output = configuration.getTarget();
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
                        configuration.getAsciidoctorConfiguration().debug().accept("Rendering " + file + " to " + out);
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (configuration.isGenerateIndex()) {
            try {
                Files.write(output.resolve("index.html"), generateIndex(files, template).getBytes(StandardCharsets.UTF_8));
                configuration.getAsciidoctorConfiguration().debug().accept("Generated index.html");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (configuration.isGenerateSiteMap()) {
            try {
                Files.write(output.resolve("sitemap.xml"), generateSiteMap(files).getBytes(StandardCharsets.UTF_8));
                configuration.getAsciidoctorConfiguration().debug().accept("Generated sitemap.xml");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (configuration.isTemplateAddLeftMenu()) {
            final String leftMenu = leftMenu(files);
            try {
                Files.walkFileTree(output, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().endsWith(".html")) {
                            final List<String> lines = Files.readAllLines(file);
                            final int toReplace = lines.indexOf("<minisite-menu-placeholder/>");
                            if (toReplace >= 0) {
                                configuration.getAsciidoctorConfiguration().debug().accept("Replacing left menu in " + file);
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
        if (configuration.isUseDefaultAssets()) {
            Stream.of(
                    "yupiik-tools-maven-plugin/minisite/assets/css/theme.css",
                    "yupiik-tools-maven-plugin/minisite/assets/js/minisite.js")
                    .forEach(resource -> {
                        final Path out = output.resolve(resource.substring("yupiik-tools-maven-plugin/minisite/assets/".length()));
                        try (final BufferedReader buffer = new BufferedReader(new InputStreamReader(Thread.currentThread().getContextClassLoader()
                                .getResourceAsStream(resource), StandardCharsets.UTF_8))) {
                            Files.createDirectories(out.getParent());
                            Files.write(out, buffer.lines().collect(joining("\n")).replace("{{base}}", configuration.getSiteBase())
                                    .getBytes(StandardCharsets.UTF_8));
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }

        final Path assets = configuration.getSource().resolve("assets");
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
                        configuration.getAsciidoctorConfiguration().debug().accept("Copying " + file + " to " + out);
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        if (hasSearch()) {
            final var indexer = new IndexService();
            indexer.write(indexer.index(output, configuration.getSiteBase()), output.resolve(configuration.getSearchIndexName()));
        }

        configuration.getAsciidoctorConfiguration().info().accept("Rendered minisite '" + configuration.getSource().getFileName() + "'");
    }

    private boolean hasSearch() {
        return !"none".equals(configuration.getSearchIndexName()) && configuration.getSearchIndexName() != null;
    }

    public Function<Page, String> createTemplate(final Options options, final Asciidoctor asciidoctor) {
        final Path layout = configuration.getSource().resolve("templates");
        String prefix = readTemplates(layout, configuration.getTemplatePrefixes())
                .replace("{{search}}", hasSearch() ? "" +
                        "<li class=\"list-inline-item\">" +
                        "<a id=\"search-button\" href=\"#\" data-toggle=\"modal\" data-target=\"#searchModal\">" +
                        "<i data-toggle=\"tooltip\" data-placement=\"top\" title=\"Search\" class=\"fas fa-search\"></i>" +
                        "</a>" +
                        "</li>\n" +
                        "" : "")
                .replace("{{customHead}}", ofNullable(configuration.getCustomHead()).orElse(""))
                .replace("{{customMenu}}", ofNullable(configuration.getCustomMenu()).orElse(""))
                .replace("{{projectVersion}}", configuration.getProjectVersion()) // enables to invalidate browser cache
                .replace("{{logoText}}", getLogoText())
                .replace("{{base}}", configuration.getSiteBase())
                .replace("{{logo}}", ofNullable(configuration.getLogo()).orElse("//www.yupiik.com/img/favicon.png"))
                .replace("{{linkedInCompany}}", ofNullable(configuration.getLinkedInCompany())
                        .orElse("yupiik"));
        final String suffix = readTemplates(layout, configuration.getTemplateSuffixes())
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
                .replace("{{copyright}}", ofNullable(configuration.getCopyright())
                        .orElse("Yupiik &copy;"))
                .replace("{{linkedInCompany}}", ofNullable(configuration.getLinkedInCompany())
                        .orElse("yupiik"))
                .replace("{{customScripts}}",
                        ofNullable(configuration.getCustomScripts()).orElse("").trim() + (hasSearch() ?
                                "\n    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/fuse.js/6.4.3/fuse.min.js\" " +
                                        "integrity=\"sha512-neoBxVNv0UMXjoilAYGxfWrSsW6iAVclx7vQKdPJ9Peet1bM5YQjU0aIB8LtH8iNPa+pAyMZprBBw2ZQ/Q1LjQ==\" " +
                                        "crossorigin=\"anonymous\"></script>\n" :
                                "\n"))
                .replace("{{projectVersion}}", configuration.getProjectVersion()) // enables to invalidate browser cache
                .replace("{{base}}", configuration.getSiteBase());
        if (configuration.isTemplateAddLeftMenu()) {
            prefix += "\n<minisite-menu-placeholder/>\n";
        }
        final String prefixRef = prefix;
        return page -> String.join(
                "\n",
                prefixRef
                        .replace("{{title}}", ofNullable(page.title).orElse(configuration.getTitle()))
                        .replace("{{highlightJsCss}}", page.attributes == null || !page.attributes.containsKey("minisite-highlightjs-skip") ?
                                "<link rel=\"stylesheet\" href=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/styles/vs2015.min.css\" integrity=\"sha512-w8aclkBlN3Ha08SMwFKXFJqhSUx2qlvTBFLLelF8sm4xQnlg64qmGB/A6pBIKy0W8Bo51yDMDtQiPLNRq1WMcQ==\" crossorigin=\"anonymous\" />" :
                                "")
                        .replace("{{description}}", ofNullable(page.attributes)
                                .map(a -> a.get("minisite-description"))
                                .map(String::valueOf)
                                .orElseGet(() -> ofNullable(configuration.getDescription()).orElseGet(this::getIndexSubTitle))),
                page.attributes != null && page.attributes.containsKey("minisite-passthrough") ? page.content : renderAdoc(page, asciidoctor, options),
                suffix.replace("{{highlightJs}}", page.attributes != null && page.attributes.containsKey("minisite-highlightjs-skip") ? "" : ("" +
                        "<script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/highlight.min.js\" integrity=\"sha512-DrpaExP2d7RJqNhXB41Q/zzzQrtb6J0zfnXD5XeVEWE8d9Hj54irCLj6dRS3eNepPja7DvKcV+9PnHC7A/g83A==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/languages/java.min.js\" integrity=\"sha512-ku64EPM6PjpseXTZbyRs0ZfXSbVsmLe+XcXxO3tZf1zW2GxZ+aKH9LZWgl/CRI9AFkU3IL7Pc1mzcZiUuvk7FQ==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/languages/bash.min.js\" integrity=\"sha512-Hg0ufGEvn0AuzKMU0psJ1iH238iUN6THh7EI0CfA0n1sd3yu6PYet4SaDMpgzN9L1yQHxfB3yc5ezw3PwolIfA==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/languages/json.min.js\" integrity=\"sha512-37sW1XqaJmseHAGNg4N4Y01u6g2do6LZL8tsziiL5CMXGy04Th65OXROw2jeDeXLo5+4Fsx7pmhEJJw77htBFg==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/languages/dockerfile.min.js\" integrity=\"sha512-eRNl3ty7GOJPBN53nxLgtSSj2rkYj5/W0Vg0MFQBw8xAoILeT6byOogENHHCRRvHil4pKQ/HbgeJ5DOwQK3SJA==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script>if (!(window.minisite || {}).skipHighlightJs) { hljs.initHighlightingOnLoad(); }</script>")));
    }

    private String getLogoText() {
        return ofNullable(configuration.getLogoText())
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElse(configuration.getProjectArtifactId()));
    }

    private String getIndexText() {
        return ofNullable(configuration.getIndexText())
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElseGet(() -> getLogoText() + " Documentation"));
    }

    private String getIndexSubTitle() {
        return ofNullable(configuration.getIndexSubTitle())
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElse(configuration.getProjectArtifactId()));
    }

    private String getTitle() {
        return ofNullable(configuration.getTitle())
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

    public Options createOptions() {
        final AttributesBuilder attributes = AttributesBuilder.attributes()
                .linkCss(false)
                .dataUri(true)
                .attribute("stem")
                .attribute("source-highlighter", "highlightjs")
                .attribute("highlightjsdir", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.0.3")
                .attribute("highlightjs-theme", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/styles/vs2015.min.css")
                .attribute("imagesdir", "images")
                .attributes(configuration.getAttributes() == null ? Map.of() : configuration.getAttributes());
        if (configuration.getProjectVersion() != null && (configuration.getAttributes() == null || !configuration.getAttributes().containsKey("projectVersion"))) {
            attributes.attribute("projectVersion", configuration.getProjectVersion());
        }
        final OptionsBuilder options = OptionsBuilder.options()
                .safe(SafeMode.UNSAFE)
                .backend("html5")
                .inPlace(false)
                .headerFooter(false)
                .baseDir(configuration.getSource().resolve("content").getParent().toAbsolutePath().normalize().toFile())
                .attributes(attributes);
        if (configuration.getTemplateDirs() != null && !configuration.getTemplateDirs().isEmpty()) {
            options.templateDirs(configuration.getTemplateDirs().toArray(new File[0]));
        }
        return options.get();
    }

    @RequiredArgsConstructor
    public static class Page {
        private final String title;
        private final Map<String, Object> attributes;
        private final String content;
    }
}
