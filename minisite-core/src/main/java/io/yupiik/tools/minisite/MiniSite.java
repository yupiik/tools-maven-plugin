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

import io.yupiik.tools.minisite.language.Asciidoc;
import lombok.RequiredArgsConstructor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static java.util.Locale.ROOT;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class MiniSite implements Runnable {
    private final MiniSiteConfiguration configuration;
    private final ReadingTimeComputer readingTimeComputer = new ReadingTimeComputer();
    private final Gravatar gravatar = new Gravatar();
    private final Pattern linkTitleReplacement = Pattern.compile("[\"\n]");
    private final Urlifier urlifier = new Urlifier();

    public MiniSite(final MiniSiteConfiguration configuration) {
        this.configuration = configuration;
        this.configuration.fixConfig();
    }

    @Override
    public void run() {
        executePreActions();
        if (configuration.isSkipRendering()) {
            configuration.getAsciidoctorConfiguration().info().accept("Rendering (and upload) skipped");
            return;
        }

        final Object options = createOptions();
        configuration.getAsciidoc().withInstance(configuration.getAsciidoctorConfiguration(), a -> {
            executeInMinisiteClassLoader(() -> doRender(a, options));
            return null;
        });
    }

    public void executeInMinisiteClassLoader(final Runnable task) {
        final Thread thread = Thread.currentThread();
        final ClassLoader old = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(MiniSite.class.getClassLoader());
            task.run();
        } finally {
            thread.setContextClassLoader(old);
        }
    }

    public void executePreActions() {
        if (configuration.getPreActions() == null || configuration.getPreActions().isEmpty()) {
            return;
        }
        final ActionExecutor executor = new ActionExecutor();
        final Thread thread = Thread.currentThread();
        final ClassLoader parentLoader = thread.getContextClassLoader();
        final Supplier<ClassLoader> actionClassLoader = configuration.getActionClassLoader();
        final ClassLoader classLoader = ofNullable(actionClassLoader.get())
                .orElseGet(Thread.currentThread()::getContextClassLoader);
        try {
            thread.setContextClassLoader(classLoader);
            configuration.getPreActions().forEach(it -> executor.execute(it, configuration.getSource(), configuration.getTarget()));
        } finally {
            if (URLClassLoader.class.isInstance(classLoader) && actionClassLoader != null) {
                try {
                    URLClassLoader.class.cast(classLoader).close();
                } catch (final IOException e) {
                    configuration.getAsciidoctorConfiguration().error().accept(e.getMessage());
                }
            }
            thread.setContextClassLoader(parentLoader);
        }
    }

    protected Consumer<Function<Page, String>> render(final Page page, final Path html,
                                                      final Asciidoc.AsciidocInstance asciidoctor, final Object options,
                                                      final boolean withLeftMenuIfConfigured,
                                                      final Function<String, String> postProcessor,
                                                      final Function<String, String> customInterpolations,
                                                      final Function<Page, String> footerNavTemplate) {
        final Path templates = getTemplatesDir();
        final String titleTemplate = findPageTemplate(templates, "page-title");
        final String contentTemplate = findPageTemplate(templates, "page-content");
        return template -> {
            try {
                final Map<String, String> attrs = new HashMap<>(Map.of("minisite-passthrough", "true"));
                attrs.putAll(page.attributes);
                final String title = ofNullable(page.title)
                        .map(t -> new TemplateSubstitutor(key -> {
                            if ("title".equals(key)) {
                                return t;
                            }
                            return getDefaultInterpolation(key, page, asciidoctor, options, null);
                        }).replace(titleTemplate))
                        .orElse("");
                final String body = new TemplateSubstitutor(key -> {
                    if ("title".equals(key)) {
                        return title;
                    }
                    return getDefaultInterpolation(key, page, asciidoctor, options, k -> {
                        switch (k) {
                            case "pageFooterNav":
                                return footerNavTemplate.apply(page);
                            default:
                                return getDefaultInterpolation(k, page, asciidoctor, options, customInterpolations);
                        }
                    });
                }).replace(contentTemplate);
                final String content = template.apply(new Page(
                        '/' + configuration.getTarget().relativize(html).toString().replace(File.separatorChar, '/'),
                        ofNullable(page.title).orElseGet(() -> getTitle(options)),
                        attrs, body));
                Files.writeString(html, postProcessor.apply(withLeftMenuIfConfigured || !configuration.isTemplateAddLeftMenu() ? content : dropLeftMenu(content)));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    protected String findPageTemplate(final Path templates, final String name) {
        return requireNonNull(findTemplate(templates, name + (name.endsWith(".adoc") ? "" : ".html")), "can't find " + name + " template")
                .collect(joining("\n"));
    }

    protected Path getTemplatesDir() {
        return configuration.getSource().resolve("templates");
    }

    protected String getIcon(final Page it) {
        return of(it)
                .flatMap(p -> isBlogPage(p) ?
                        findFirstBlogCategoryIcon(p) :
                        ofNullable(p.attributes.get("minisite-index-icon"))
                                .map(String::valueOf))
                .map(this::toIcon)
                .orElse("fas fa-download");
    }

    protected Optional<String> findFirstBlogCategoryIcon(final Page p) {
        return ofNullable(getPageCategories(p))
                .map(String::valueOf)
                .map(this::parseCsv)
                .flatMap(cats -> cats
                        .map(c -> {
                            final Map<String, MiniSiteConfiguration.BlogCategoryConfiguration> customizations = configuration.getBlogCategoriesCustomizations();
                            return ofNullable(customizations.get(c))
                                    // for complex names (with slashes or so) we normalize it to make it conf friendly)
                                    .orElseGet(() -> customizations.get(toClassName(c)));
                        })
                        .filter(Objects::nonNull)
                        .map(MiniSiteConfiguration.BlogCategoryConfiguration::getIcon)
                        .filter(Objects::nonNull)
                        .findFirst());
    }

    protected Object getPageCategories(final Page p) {
        return p.attributes.get("minisite-blog-categories");
    }

    protected String toIcon(final String i) {
        return i.startsWith("fa") && i.contains(" ") ? i : ("fas fa-" + i);
    }

    protected String getTitle(final Page it) {
        return ofNullable(it.attributes.get("minisite-index-title")).map(String::valueOf).orElse(it.title);
    }

    protected String leftMenu(final Map<Page, Path> files) {
        final Path templatesDir = getTemplatesDir();
        final String template = findPageTemplate(templatesDir, "left-menu");
        return new TemplateSubstitutor(key -> {
            if ("listItems".equals(key)) {
                final String itemTemplate = findPageTemplate(templatesDir, "left-menu-item");
                final Path output = configuration.getTarget();
                return findIndexPages(files).map(it -> toMenuItem(output, it, itemTemplate)).collect(joining());
            }
            try {
                return findPageTemplate(templatesDir, key);
            } catch (final RuntimeException re) {
                throw new IllegalArgumentException("Unknown key '" + key + "'");
            }
        }).replace(template);
    }

    protected String toMenuItem(final Path output, final Map.Entry<Page, Path> it, final String template) {
        return new TemplateSubstitutor(key -> {
            switch (key) {
                case "title":
                    return toLinkTitle(it.getKey());
                case "text":
                    return getTitle(it.getKey());
                case "href":
                    return configuration.getSiteBase() + '/' + output.relativize(it.getValue());
                default:
                    return getDefaultInterpolation(key, it.getKey(), null, null, null);
            }
        }).replace(template);
    }

    protected String toLinkTitle(final Page page) {
        if (page.title == null) {
            return "";
        }
        return linkTitleReplacement.matcher(page.title).replaceAll(" ");
    }

    /**
     * Logic there is to use attributes to find {@code minisite-index} attributes.
     * The value is an integer to sort the pages.
     * Then all these pages (others are ignored) are added on index page.
     *
     * @param htmls          pages.
     * @param template       mapper from a page meta to html.
     * @param blogCategories available categories for blog part.
     * @param options
     * @return the index.html content.
     */
    protected String generateIndex(final Map<Page, Path> htmls, final Function<Page, String> template,
                                   final boolean hasBlog, final List<String> blogCategories, final Object options) {
        final Path output = configuration.getTarget();
        final Path templatesDir = getTemplatesDir();
        final String itemTemplate = findPageTemplate(templatesDir, "index-item");
        final String contentTemplate = findPageTemplate(templatesDir, "index-content");
        final String indexText = getIndexText(options);
        final String indexContent = (hasBlog ?
                Stream.concat(
                        findIndexPages(htmls),
                        Stream.concat(
                                configuration.isAddIndexRegistrationPerCategory() ? blogCategories.stream()
                                        .sorted(Comparator.<String, Integer>comparing(c -> {
                                            final MiniSiteConfiguration.BlogCategoryConfiguration configuration = this.configuration.getBlogCategoriesCustomizations().get(toClassName(c));
                                            return configuration == null ? -1 : configuration.getOrder();
                                        }).thenComparing(identity()))
                                        .map(category -> {
                                            final MiniSiteConfiguration.BlogCategoryConfiguration categoryConf = this.configuration.getBlogCategoriesCustomizations().get(toClassName(category));
                                            final String link = "blog/category/" + toUrlName(category) + "/page-1.html";
                                            return Map.entry(
                                                    new Page(
                                                            "/" + link,
                                                            "Blog",
                                                            Map.of(
                                                                    "minisite-keywords", "Blog, " + indexText + ", " + category,
                                                                    "minisite-blog-categories", category,
                                                                    "minisite-index-icon", toIcon(ofNullable(categoryConf).map(MiniSiteConfiguration.BlogCategoryConfiguration::getIcon).orElse("fa fa-blog")),
                                                                    "minisite-index-title", ofNullable(categoryConf).map(MiniSiteConfiguration.BlogCategoryConfiguration::getHomePageName).orElse(category),
                                                                    "minisite-index-description", ofNullable(categoryConf).map(MiniSiteConfiguration.BlogCategoryConfiguration::getDescription).orElseGet(() -> category + " category.")),
                                                            null),
                                                    output.resolve(link));
                                        }) :
                                        Stream.empty(),
                                // add blog last for now
                                Stream.of(
                                        Map.entry(
                                                new Page(
                                                        "/blog/index.html",
                                                        "Blog",
                                                        Map.of(
                                                                "minisite-keywords", "Blog, " + indexText,
                                                                "minisite-index-icon", "fa fa-blog",
                                                                "minisite-index-title", "Blog",
                                                                "minisite-index-description", configuration.isAddIndexRegistrationPerCategory() ?
                                                                        "All posts." : "Blogging area."),
                                                        null),
                                                output.resolve("blog/index.html"))))) :
                findIndexPages(htmls))
                .map(p -> new TemplateSubstitutor(key -> {
                    if ("href".equals(key)) {
                        return configuration.getSiteBase() + '/' + output.relativize(p.getValue());
                    }
                    return getDefaultInterpolation(key, p.getKey(), null, null, null);
                }).replace(itemTemplate))
                .collect(joining(""));

        final String content = dropLeftMenu(
                template.apply(new Page(
                        "/index.html",
                        ofNullable(configuration.getTitle()).orElse("Index"), Map.of(
                        "minisite-keywords", indexText,
                        "minisite-passthrough", "true"),
                        new TemplateSubstitutor(key -> {
                            switch (key) {
                                case "title":
                                    return indexText +
                                            (!indexText.toLowerCase(Locale.ROOT).endsWith("documentation") && !configuration.isSkipIndexTitleDocumentationText() ?
                                                    " Documentation" : "");
                                case "subTitle":
                                    return getIndexSubTitle(options);
                                case "content":
                                    return indexContent;
                                default:
                                    try {
                                        return findPageTemplate(templatesDir, key);
                                    } catch (final RuntimeException re) {
                                        throw new IllegalArgumentException("Unknown key '" + key + "'");
                                    }
                            }
                        }).replace(contentTemplate))));

        // now we must drop the navigation-left/right since we reused the global template - easier than rebuilding a dedicated layout
        return dropRightColumn(content);
    }

    protected String getDefaultInterpolation(final String key, final Page page,
                                             final Asciidoc.AsciidocInstance asciidoctor, final Object options,
                                             final Function<String, String> customInterpolations) {
        final int idx = key.lastIndexOf('?');
        if (idx < 0) {
            return getDefaultInterpolation(key, page, asciidoctor, options, false, customInterpolations);
        }
        final Map<String, String> params = Stream.of(key.substring(idx + 1).split("&"))
                .map(it -> it.split("="))
                .collect(toMap(it -> it[0], it -> it[1]));
        final boolean ignoreErrors = Boolean.parseBoolean(params.getOrDefault("ignoreErrors", "false"));
        try {
            return getDefaultInterpolation(
                    key.substring(0, idx), page, asciidoctor, options,
                    Boolean.parseBoolean(params.getOrDefault("emptyIfMissing", "false")),
                    customInterpolations);
        } catch (final RuntimeException re) {
            if (ignoreErrors) {
                return "";
            }
            throw re;
        }
    }

    protected String getDefaultInterpolation(final String key, final Page page,
                                             final Asciidoc.AsciidocInstance asciidoctor, final Object options,
                                             final boolean emptyIfMissing,
                                             final Function<String, String> customInterpolations) {
        if (customInterpolations != null) {
            final String value = customInterpolations.apply(key);
            if (value != null) {
                return value;
            }
        }
        switch (key) {
            case "metaAuthor":
                return ofNullable(page.attributes.get("minisite-blog-authors"))
                        .map(String::valueOf)
                        .map(String::strip)
                        .orElse("Yupiik Minisite Generator");
            case "metaKeywords":
                return ofNullable(page.attributes.get("minisite-keywords"))
                        .map(String::valueOf)
                        .map(String::strip)
                        .map(it -> "<meta name=\"keywords\" content=\"" + it + "\">\n")
                        .orElse("");
            case "isBlogClass":
                return isBlogPage(page) ? "is-blog" : "";
            case "categoryClass":
                return getCategoryClass(getPageCategories(page));
            case "icon":
                return getIcon(page);
            case "title":
                return getTitle(page);
            case "description":
                return ofNullable(page.attributes.get("minisite-index-description")).map(String::valueOf).orElse(page.title);
            case "hrefTitle":
                return toLinkTitle(page);
            case "pageClass":
                return toUrlName(page.relativePath).replaceFirst("^/", "").replace('/', '-');
            case "body":
                if (asciidoctor == null) {
                    throw new IllegalArgumentException("'body' no available");
                }
                return renderAdoc(page, asciidoctor, options).strip();
            case "defaultEndOfContent": // used for blog OOTB so ensure it does not fail for standard pages
                return "";
            case "href":
                return page.relativePath;
            case "author":
                return ofNullable(page.attributes.get("minisite-blog-authors"))
                        .map(String::valueOf)
                        .flatMap(a -> Stream.of(a.split(",")).findFirst())
                        .orElse("");
            case "authors":
                return ofNullable(page.attributes.get("minisite-blog-authors"))
                        .map(String::valueOf)
                        .map(a -> "by " + String.join(" and ", a.split(",")))
                        .orElse("");
            case "authors-list":
                return ofNullable(page.attributes.get("minisite-blog-authors"))
                        .map(String::valueOf)
                        .map(a -> String.join(" and ", a.split(",")))
                        .orElse("");
            case "gravatar":
                return ofNullable(page.attributes.get("minisite-blog-gravatar"))
                        .map(String::valueOf)
                        .map(String::strip)
                        .or(() -> ofNullable(page.attributes.get("minisite-blog-authors"))
                                .map(String::valueOf)
                                .map(it -> it.split(","))
                                .flatMap(it -> Stream.of(it).map(String::strip).filter(n -> !n.isBlank()).findFirst()))
                        .map(a -> gravatar.toUrl(configuration.getGravatar(), a))
                        .orElse("");
            case "publishedDate":
                return readPublishedDate(page).toLocalDate().toString();
            case "summary":
                return ofNullable(page.attributes.get("minisite-blog-summary"))
                        .map(String::valueOf)
                        .orElse("");
            case "readingTime":
                return readingTimeComputer.toReadingTime(page.content);
            case "xyz": // passthrough
                return "{{xyz}}";
            case "breadcrumb":
                String def = page.attributes.get("minisite-breadcrumb");
                if (def == null || def.isBlank()) {
                    return "";
                }
                // parse: Home[#] > Another Page[link-to-another-page.html] > This page
                final StringBuilder out = new StringBuilder("<nav aria-label=\"breadcrumb\" style=\"padding-left: 0;\">\n" +
                        "  <ol class=\"breadcrumb\" style=\"margin-left: 0;background-color: unset;padding-left: 0;\">\n");
                int from = 0;
                def = def.replace("&gt;", ">");
                while (from < def.length()) {
                    final int end = def.indexOf('>', from);
                    final String segment;
                    if (end < 0) {
                        segment = def.substring(from).strip();
                        from = def.length();
                    } else {
                        segment = def.substring(from, end).strip();
                        from = end + 1;
                    }

                    out.append("    <li class=\"breadcrumb-item")
                            .append(from == def.length() ? " active" : "").append("\"")
                            .append(from == def.length() ? " aria-current=\"page\"" : "")
                            .append(">");
                    final int link = segment.indexOf('[');
                    if (link < 0) {
                        out.append(segment);
                    } else {
                        final int endLink = segment.indexOf(']', link);
                        if (endLink < 0) {
                            out.append(segment);
                        } else {
                            final String text = segment.substring(0, link).strip();
                            out.append("<a href=\"").append(segment.substring(link + 1, endLink).strip()).append("\">")
                                    .append("Home".equalsIgnoreCase(text) ? // home is worth an icon cause it is particular
                                            "<svg viewBox=\"0 0 24 24\" " +
                                                    "style=\"height: 1.4rem;position: relative;top: 1px;vertical-align: top;width: 1.1rem;\">" +
                                                    "<path d=\"M10 19v-5h4v5c0 .55.45 1 1 1h3c.55 0 1-.45 1-1v-7h1.7c.46 0 .68-.57.33-.87L12.67 3.6c-.38-.34-.96-.34-1.34 0l-8.36 7.53c-.34.3-.13.87.33.87H5v7c0 .55.45 1 1 1h3c.55 0 1-.45 1-1z\"" +
                                                    " fill=\"currentColor\"></path>" +
                                                    "</svg>" :
                                            text)
                                    .append("</a>");
                        }
                    }
                    out.append("</li>\n");
                }
                return out.append("  </ol>\n</nav>").toString();
            default:
                try {
                    return findPageTemplate(getTemplatesDir(), key);
                } catch (final RuntimeException re) {
                    if (emptyIfMissing) {
                        return "";
                    }
                    throw new IllegalArgumentException("Unknown template key '" + key + "'");
                }
        }
    }

    protected String getCategoryClass(final Object categories) {
        return "category-" + ofNullable(categories)
                .map(String::valueOf)
                .map(this::parseCsv)
                .flatMap(Stream::findFirst)
                .map(this::toClassName)
                .map(it -> it.toLowerCase(Locale.ROOT))
                .orElse("default");
    }

    private String toClassName(final String value) {
        return value.replace(" ", "").replace("/", "");
    }

    protected String dropRightColumn(final String content) {
        final int navRight = content.indexOf("<div class=\"page-navigation-right\">");
        if (navRight > 0) {
            return content.substring(0, navRight) + content.substring(content.indexOf("</div>", navRight) + "</div>".length());
        }
        return content;
    }

    protected String dropLeftMenu(final String content) {
        return content.replace("<minisite-menu-placeholder/>\n", "");
    }

    protected Stream<Map.Entry<Page, Path>> findIndexPages(final Map<Page, Path> htmls) {
        return htmls.entrySet().stream()
                .filter(p -> p.getKey().attributes.containsKey("minisite-index"))
                .sorted(comparing(p -> Integer.parseInt(String.valueOf(p.getKey().attributes.get("minisite-index")).trim())));
    }

    protected String generateSiteMap(final Map<Page, Path> pages) {
        final String now = LocalDate.now().toString();
        final Path output = configuration.getTarget();
        return pages.entrySet().stream()
                .sorted(comparing(p -> p.getKey().title == null ? "" : p.getKey().title))
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

    public void doRender(final Asciidoc.AsciidocInstance asciidoctor, final Object options) {
        final Path content = configuration.getSource().resolve("content");
        final Map<Page, Path> files = new HashMap<>();
        final Path output = configuration.getTarget();
        final OffsetDateTime now = configuration.getRuntimeBlogPublicationDate() == null ?
                OffsetDateTime.now() : configuration.getRuntimeBlogPublicationDate();
        if (!Files.exists(output)) {
            try {
                Files.createDirectories(output);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        final Collection<Page> pages = findPages(asciidoctor, options);
        Function<Page, String> template = null;
        boolean hasBlog = false;
        List<String> categories = emptyList();
        if (Files.exists(content)) {
            final List<BlogPage> blog = new ArrayList<>();
            final List<Consumer<Function<Page, String>>> pageToRender = new ArrayList<Consumer<Function<Page, String>>>();
            final Function<Page, String> footerNavTemplate = loadNavTemplates();
            pages.forEach(page -> pageToRender.add(onVisitedFile(page, asciidoctor, options, files, now, blog, footerNavTemplate)));
            hasBlog = (!blog.isEmpty() && configuration.isGenerateBlog());
            template = createTemplate(options, asciidoctor, hasBlog);
            final Function<Page, String> tpl = template;
            pageToRender.forEach(it -> it.accept(tpl));
            if (hasBlog) {
                categories = generateBlog(blog, asciidoctor, options, template);
            }
        }
        if (configuration.isGenerateIndex()) {
            if (template == null) {
                template = createTemplate(options, asciidoctor, false);
            }
            try {
                Files.write(output.resolve("index.html"), generateIndex(files, template, hasBlog, categories, options).getBytes(StandardCharsets.UTF_8));
                configuration.getAsciidoctorConfiguration().debug().accept("Generated index.html");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (configuration.isGenerateSiteMap()) { // ignore blog virtual pages since we want to index the content
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
                Files.walkFileTree(output, new SimpleFileVisitor<Path>() {
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
                            "yupiik-tools-maven-plugin/minisite/assets/css/theme-old.css",
                            "yupiik-tools-maven-plugin/minisite/assets/js/minisite.js",
                            "yupiik-tools-maven-plugin/minisite/assets/images/logo.svg")
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
            final Predicate<String> ignoredPages = configuration.getNotIndexedPages() == null || configuration.getNotIndexedPages().isEmpty() ?
                    s -> false :
                    configuration.getNotIndexedPages().stream()
                            .map(it -> {
                                if (it.startsWith("regex:")) {
                                    return Pattern.compile(it.substring("regex:".length())).asMatchPredicate();
                                }
                                if (it.startsWith("prefix:")) {
                                    final String value = it.substring("prefix:".length());
                                    return (Predicate<String>) s -> s.startsWith(value);
                                }
                                return (Predicate<String>) it::equals;
                            })
                            .reduce(s -> false, Predicate::or);
            final IndexService indexer = new IndexService();
            indexer.write(indexer.index(output, configuration.getSiteBase(), path -> {
                final String location = configuration.getTarget().relativize(path).toString().replace(File.separatorChar, '/');
                final String name = path.getFileName().toString();
                if ((location.startsWith("blog/") && (name.startsWith("page-") || name.equals("index.html"))) || ignoredPages.test(name)) {
                    return false;
                }
                return true;
            }), output.resolve(configuration.getSearchIndexName()));
        }
        if (configuration.getRssFeedFile() != null) {
            final Path out = output.resolve(configuration.getRssFeedFile());
            try {
                Files.createDirectories(out.getParent());
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            try (final BufferedWriter rss = Files.newBufferedWriter(out, UTF_8)) {
                final String rssContent = generateRssFeed(files, options);
                rss.write(rssContent);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        configuration.getAsciidoctorConfiguration().info().accept("Rendered minisite '" + configuration.getSource().getFileName() + "'");
    }

    private String generateRssFeed(final Map<Page, Path> files, final Object options) {
        final List<Map.Entry<Page, OffsetDateTime>> all = files.keySet().stream()
                .filter(it -> {
                    final String name = files.get(it).getFileName().toString();
                    return !(name.startsWith("page-") || name.equals("index.html")) && name.endsWith(".html");
                })
                .map(it -> entry(it, readPublishedDate(it)))
                .sorted(Map.Entry.<Page, OffsetDateTime>comparingByValue().reversed())
                .collect(toList());

        final Function<String, String> escaper = findXmlEscaper();
        final DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
        final String baseSite = configuration.getSiteBase() + (!configuration.getSiteBase().endsWith("/") ? "/" : "");
        final String items = all.stream()
                .map(Map.Entry::getKey)
                .map(it -> {
                    final String title = getTitle(it);
                    return "" +
                            "   <item>\n" +
                            "    <title>" + escaper.apply(title) + "</title>\n" +
                            "    <description>" + escaper.apply(ofNullable(it.attributes.get("minisite-blog-summary")).map(String::valueOf).orElse(title)) + "</description>\n" +
                            "    <link>" + baseSite + it.relativePath.substring(1) + "</link>\n" +
                            "    <guid isPermaLink=\"false\">" + it.relativePath + "</guid>\n" +
                            "    <pubDate>" + readPublishedDate(it).format(formatter) + "</pubDate>\n" +
                            "   </item>";
                })
                .collect(joining("\n", "", "\n"));
        final String lastDate = all.stream().limit(1).findFirst().map(Map.Entry::getValue).orElseGet(OffsetDateTime::now).format(formatter);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                "<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\">\n" +
                "  <channel>\n" +
                "   <atom:link href=\"" + baseSite + configuration.getRssFeedFile() + "\" rel=\"self\" type=\"application/rss+xml\" />\n" +
                "   <title>" + escaper.apply(getTitle(options)) + "</title>\n" +
                "   <description>" + escaper.apply(getIndexSubTitle(false)) + "</description>\n" +
                "   <link>" + baseSite + configuration.getRssFeedFile() + "</link>\n" +
                "   <lastBuildDate>" + lastDate + "</lastBuildDate>\n" +
                "   <pubDate>" + lastDate + "</pubDate>\n" +
                "   <ttl>1800</ttl>\n" +
                items +
                "  </channel>\n" +
                "</rss>\n";
    }

    private Function<String, String> findXmlEscaper() {
        try { // todo: absorb it there to avoid the need of the dep
            final Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass("org.apache.commons.lang3.StringEscapeUtils");
            final Method escapeXml11 = clazz.getMethod("escapeXml11", String.class);
            if (!escapeXml11.isAccessible()) {
                escapeXml11.setAccessible(true);
            }
            return s -> {
                try {
                    return String.valueOf(escapeXml11.invoke(null, s));
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException(e);
                } catch (final InvocationTargetException e) {
                    throw new IllegalStateException(e.getTargetException());
                }
            };
        } catch (final Exception cnfe) {
            configuration.getAsciidoctorConfiguration().info()
                    .accept("Can't load StringEscapeUtils " +
                            "(ensure you added commons-lang3 if you use rss feature with unsafe characters in titles/descriptions).");
            return this::xmlEscape;
        }
    }

    protected String xmlEscape(final String text) {
        return text
                // https://www.htmlhelp.com/reference/html40/entities/special.html
                .replace("&", "&#x26;")
                .replace("<", "&#x3C;")
                .replace(">", "&#x3E;");
    }

    protected Consumer<Function<Page, String>> onVisitedFile(final Page page, final Asciidoc.AsciidocInstance asciidoctor, final Object options,
                                                             final Map<Page, Path> files, final OffsetDateTime now, final List<BlogPage> blog,
                                                             final Function<Page, String> footerNavTemplate) {
        if (page.attributes.containsKey("minisite-skip")) {
            return t -> {
            };
        }
        final Path out = configuration.getTarget().resolve(page.relativePath.substring(1));
        if (out.getParent() != null && !Files.exists(out.getParent())) {
            try {
                Files.createDirectories(out.getParent());
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        final boolean isBlog = isBlogPage(page);
        files.put(page, out);
        if (isBlog) {
            final OffsetDateTime publishedDate = readPublishedDate(page);
            if (now.isAfter(publishedDate)) {
                blog.add(new BlogPage(page, publishedDate));
            }
            return t -> {
            };
        } else {
            return template -> {
                render(page, out, asciidoctor, options, true, identity(), null, footerNavTemplate).accept(template);
                configuration.getAsciidoctorConfiguration().debug().accept("Rendered " + page.relativePath + " to " + out);
            };
        }
    }

    protected boolean isBlogPage(final Page page) {
        return page.attributes.keySet().stream().anyMatch(k -> k.startsWith("minisite-blog"));
    }

    protected OffsetDateTime readPublishedDate(final Page page) {
        return ofNullable(page.attributes.get("minisite-blog-published-date"))
                .map(String::valueOf)
                .map(String::trim)
                .map(value -> {
                    switch (value.length()) {
                        case 10: // yyyy-MM-dd HH:mm
                            return LocalDate.parse(value).atTime(LocalTime.MIN).atOffset(ZoneOffset.UTC);
                        default:
                            if (value.length() > 19) { // with offset
                                return OffsetDateTime.parse(value.replace(' ', 'T'));
                            }
                            // yyyy-MM-dd HH:mm[:ss]
                            return LocalDateTime.parse(value.replace(' ', 'T')).atOffset(ZoneOffset.UTC);
                    }
                })
                .orElseGet(OffsetDateTime::now);
    }

    protected List<String> generateBlog(final List<BlogPage> blog, final Asciidoc.AsciidocInstance asciidoctor, final Object options,
                                        final Function<Page, String> template) {
        Comparator<BlogPage> pageComparator = comparing(p -> p.publishedDate);
        if (configuration.isReverseBlogOrder()) {
            pageComparator = pageComparator.reversed();
        }
        blog.sort(pageComparator);
        final Path baseBlog = configuration.getTarget().resolve("blog");
        try {
            Files.createDirectories(baseBlog);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }

        final Path templateDir = getTemplatesDir();
        final String blogItemTemplate = findPageTemplate(templateDir, "blog-list-item.adoc");
        final String blogTemplate = findPageTemplate(templateDir, "blog-list.adoc");

        paginateBlogPages((number, total) -> "Blog Page " + number + "/" + total, "", blog, asciidoctor, options, template, baseBlog, blogItemTemplate, blogTemplate);
        final List<String> allCategories = new ArrayList<>(paginatePer("category", "categories", blog, asciidoctor, options, template, baseBlog, blogItemTemplate, blogTemplate));
        paginatePer("author", "authors", blog, asciidoctor, options, template, baseBlog, blogItemTemplate, blogTemplate);

        // render all blog pages
        blog.forEach(bp -> {
            final Path out = configuration.getTarget().resolve(bp.page.relativePath.substring(1));
            final int idx = blog.indexOf(bp);
            render(new Page( // add links to other posts
                            bp.page.relativePath,
                            bp.page.title,
                            bp.page.attributes,
                            (configuration.isInjectBlogMeta() ? injectBlogMeta(bp) : bp.page.content) + "\n"),
                    out, asciidoctor, options, false, this::markPageAsBlog, key -> {
                        switch (key) {
                            case "hasPreviousLinkClass":
                                return "has-previous-link-" + (idx > 0);
                            case "hasNextLinkClass":
                                return "has-next-link-" + (idx < (blog.size() - 1));
                            case "previousLinkHref":
                                return configuration.getSiteBase() + blog.get(idx - 1).page.relativePath;
                            case "nextLinkHref":
                                return configuration.getSiteBase() + blog.get(idx + 1).page.relativePath;
                            case "blogHomeLinkHref":
                                return configuration.getSiteBase() + "/blog/index.html";
                            case "categoriesList":
                                return getCategoriesList(bp);
                            case "authorsList":
                                return getAuthorsList(bp);
                            case "navigationLinks":
                                return getNavigationLinks(blog, idx);
                            case "defaultEndOfContent":
                                return "\n" +
                                        getAuthorsList(bp) + '\n' +
                                        getCategoriesList(bp) + '\n' +
                                        getNavigationLinks(blog, idx);
                            default:
                                return null;
                        }
                    }, p -> "").accept(template);
            configuration.getAsciidoctorConfiguration().debug().accept("Rendered " + bp.page.relativePath + " to " + out);
        });
        return allCategories;
    }

    private String getHtmlList(final String urlMarker, final Function<String, String> prefixText,
                               final Object value) {
        return ofNullable(value)
                .map(String::valueOf)
                .map(categories -> parseCsv(categories)
                        .map(category -> "<li><p><a href=\"" + configuration.getSiteBase() + "/blog/" + urlMarker + "/" + toUrlName(category) + "/page-1.html\">" + toHumanName(category) + "</a></p></li>")
                        .collect(joining("\n",
                                "\n\n<div class=\"paragraph blog-categories\">\n" +
                                        prefixText.apply(categories) +
                                        "</div>\n" +
                                        "<div class=\"ulist blog-links blog-categories\">\n" +
                                        "<ul>\n", "</ul>\n</div>\n\n\n")))
                .orElse("");
    }

    private String getCategoriesList(final BlogPage bp) {
        return getHtmlList("category", categories -> "<p>In the same categor" + (categories.contains(",") ? "ies" : "y") + ":</p>\n", getPageCategories(bp.page));
    }

    private String getAuthorsList(final BlogPage bp) {
        return getHtmlList("author", authors -> "<p>From the same author" + (authors.contains(",") ? "s" : "") + ":<p>\n", bp.page.attributes.get("minisite-blog-authors"));
    }

    private String getNavigationLinks(final List<BlogPage> blog, final int idx) {
        return "\n\n<div class=\"ulist blog-links\">\n" +
                "<ul>\n" +
                (idx > 0 ? "<li class=\"blog-link-previous\"><p><a href=\"" + configuration.getSiteBase() + blog.get(idx - 1).page.relativePath + "\">Previous</a></p></li>\n" : "") +
                "<li class=\"blog-link-all\"><p><a href=\"" + configuration.getSiteBase() + "/blog/index.html\">All posts</a></p></li>\n" +
                (idx < (blog.size() - 1) ? "<li class=\"blog-link-next\"><p><a href=\"" + configuration.getSiteBase() + blog.get(idx + 1).page.relativePath + "\">Next</a></p></li>\n" : "") +
                "</ul>\n" +
                "</div>\n\n";
    }

    private Stream<String> parseCsv(final String categories) {
        return Stream.of(categories.split(","))
                .map(String::trim)
                .filter(c -> !c.isBlank());
    }

    private String injectBlogMeta(final BlogPage bp) {
        final List<String> lines;
        try (final BufferedReader reader = new BufferedReader(new StringReader(bp.page.content))) {
            lines = reader.lines().collect(toList());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        int idx = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                idx = i;
                break;
            }
        }
        lines.addAll(idx, List.of(
                "",
                Stream.of(
                                findFirstBlogCategoryIcon(bp.page)
                                        .map(this::toIcon)
                                        .map(icon -> "[.mr-2." + getCategoryClass(getPageCategories(bp.page)) + "]#icon:" + icon + "[]#")
                                        .orElse(""),
                                ofNullable(bp.page.attributes.get("minisite-blog-authors"))
                                        .map(value -> Stream.of(value.split(","))
                                                .map(String::strip)
                                                .filter(it -> !it.isBlank())
                                                .map(it -> "link:" + configuration.getSiteBase() + "/blog/author/" + toUrlName(it) + "/page-1.html[" + toHumanName(it) + "]")
                                                .collect(joining(" ")))
                                        .map(it -> "[.metadata-authors]#" + it + "#")
                                        .orElse(""),
                                "[.metadata-published]#" + bp.publishedDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + "#",
                                "[.metadata-readingtime]#" + readingTimeComputer.toReadingTime(bp.page.content) + "#")
                        .filter(it -> !it.isBlank())
                        .collect(joining(", ", "[.metadata]\n", "")),
                ""));
        return String.join("\n", lines);
    }

    protected Collection<String> paginatePer(final String singular, final String plural,
                                             final List<BlogPage> blog,
                                             final Asciidoc.AsciidocInstance asciidoctor, final Object options,
                                             final Function<Page, String> template,
                                             final Path baseBlog, final String itemTemplate, final String contentTemplate) {
        // per category pagination /category/<name>/page-<x>.html
        final Map<String, List<BlogPage>> perCriteria = blog.stream()
                .filter(it -> it.page.attributes.containsKey("minisite-blog-" + plural))
                .flatMap(it -> parseCsv(String.valueOf(it.page.attributes.get("minisite-blog-" + plural)))
                        .map(c -> new AbstractMap.SimpleImmutableEntry<>(c, it)))
                .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));
        perCriteria.forEach((key, posts) -> {
            final String humanName = toHumanName(key);
            final String urlName = toUrlName(key);
            paginateBlogPages(
                    (current, total) -> "Blog " + humanName + " (page " + current + "/" + total + ")",
                    singular + "/" + urlName + '/',
                    posts, asciidoctor, options, template, baseBlog, itemTemplate, contentTemplate);
        });
        if (perCriteria.values().stream().mapToLong(Collection::size).sum() == 0) {
            return perCriteria.keySet();
        }
        // /category/index.html
        render(
                new Page(
                        '/' + configuration.getTarget().relativize(baseBlog).toString().replace(File.separatorChar, '/') +
                                "/" + singular + "/index.html",
                        "Blog " + plural,
                        Map.of(),
                        "= Blog " + plural + "\n" +
                                perCriteria.keySet().stream()
                                        .map(it -> "* link:" + toUrlName(it) + "/page-1.html[" + toHumanName(it) + ']')
                                        .collect(joining("\n"))),
                baseBlog.resolve(singular + "/index.html"), asciidoctor, options,
                false,
                this::markPageAsBlog, null, p -> "").accept(template);
        return perCriteria.keySet();
    }

    protected String toHumanName(final String string) {
        char previousChar = string.charAt(0);
        final StringBuilder out = new StringBuilder()
                .append(Character.toUpperCase(previousChar));
        for (int i = 1; i < string.length(); i++) {
            final char c = string.charAt(i);
            if (Character.isUpperCase(c) && Character.isJavaIdentifierPart(previousChar) && !Character.isUpperCase(previousChar)) {
                out.append(' ').append(c);
            } else {
                out.append(c);
            }
            previousChar = c;
        }
        return out.toString();
    }

    protected String toUrlName(final String string) {
        return urlifier.toUrlName(string);
    }

    protected void paginateBlogPages(final BiFunction<Integer, Integer, String> prefix,
                                     final String pageRelativeFolder,
                                     final List<BlogPage> blogPages,
                                     final Asciidoc.AsciidocInstance asciidoctor,
                                     final Object options,
                                     final Function<Page, String> template,
                                     final Path baseBlog,
                                     final String itemTemplate, final String contentTemplate) {
        if (blogPages.isEmpty()) {
            return;
        }
        if (!pageRelativeFolder.isBlank()) {
            try {
                Files.createDirectories(baseBlog.resolve(pageRelativeFolder));
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
        final int pageSize = configuration.getBlogPageSize() <= 0 ? 10 : configuration.getBlogPageSize();
        final List<List<BlogPage>> pages = splitByPage(blogPages, pageSize);
        IntStream.rangeClosed(1, pages.size()).forEach(page -> {
            final Path output = baseBlog.resolve(pageRelativeFolder + "page-" + page + ".html");
            render(
                    new Page(
                            '/' + configuration.getTarget().relativize(output).toString().replace(File.separatorChar, '/'),
                            prefix.apply(page, pages.size()),
                            Map.of(),
                            new TemplateSubstitutor(key -> {
                                switch (key) {
                                    case "title":
                                        return prefix.apply(page, pages.size());
                                    case "items":
                                        return pages.get(page - 1).stream()
                                                .map(it -> new TemplateSubstitutor(itemKey -> {
                                                    if ("title".equals(itemKey)) {
                                                        return it.page.title;
                                                    }
                                                    return getDefaultInterpolation(itemKey, it.page, asciidoctor, options, null);
                                                }).replace(itemTemplate))
                                                .collect(joining("\n", "\n", "\n"));
                                    case "links":
                                        return "\n" +
                                                "[role=\"blog-links blog-links-page\"]\n" +
                                                (page > 1 ? "* link:" + configuration.getSiteBase() + "/blog/page-" + (page - 1) + ".html[Previous,role=\"blog-link-previous\"]\n" : "") +
                                                "* link:" + configuration.getSiteBase() + "/blog/index.html[All posts,role=\"blog-link-all\"]\n" +
                                                (page < pages.size() ? "* link:" + configuration.getSiteBase() + "/blog/page-" + (page + 1) + ".html[Next,role=\"blog-link-next\"]\n" : "");
                                    default:
                                        throw new IllegalArgumentException("Unknown key '" + key + "'");
                                }
                            }).replace(contentTemplate)),
                    output, asciidoctor, options, false,
                    this::markPageAsBlog, null, p -> "").accept(template);
        });

        final Path indexRedirect = baseBlog.resolve(pageRelativeFolder + "index.html");
        if (!Files.exists(indexRedirect)) {
            try {
                Files.writeString(
                        indexRedirect,
                        "<html><head><meta http-equiv=\"refresh\" content=\"0; URL=page-1.html\" /></head><body></body></html>",
                        StandardOpenOption.CREATE);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    protected String markPageAsBlog(final String html) {
        return html.replace("<body>", "<body class=\"blog\">");
    }

    protected List<List<BlogPage>> splitByPage(final List<BlogPage> sortedPages, final int pageSize) {
        final int totalPages = (int) Math.ceil(sortedPages.size() * 1. / pageSize);
        return IntStream.rangeClosed(1, totalPages)
                .mapToObj(page -> sortedPages.subList(configuration.getBlogPageSize() * (page - 1), Math.min(configuration.getBlogPageSize() * page, sortedPages.size())))
                .collect(toList());
    }

    protected boolean hasSearch() {
        return !"none".equals(configuration.getSearchIndexName()) && configuration.getSearchIndexName() != null;
    }

    protected Function<Page, String> loadNavTemplates() {
        final Path templatesDir = getTemplatesDir();
        final String globalTemplate = readTemplates(templatesDir, List.of("page-footer-nav.html"));
        final String linkTemplate = readTemplates(templatesDir, List.of("page-footer-nav-link.html"));
        return page -> {
            if (page.attributes == null) {
                return "";
            }

            final NavLink prev = new NavLink(page.attributes, "minisite-nav-prev-");
            final NavLink next = new NavLink(page.attributes, "minisite-nav-next-");
            if (prev.link == null && next.link == null) {
                return "";
            }

            return globalTemplate
                    .replace("{{previousLink}}", prev.render(linkTemplate, "prev", "Previous"))
                    .replace("{{nextLink}}", next.render(linkTemplate, "next", "Next"));
        };
    }

    public Function<Page, String> createTemplate(final Object options,
                                                 final Asciidoc.AsciidocInstance asciidoctor,
                                                 final boolean hasBlog) {
        final Path layout = getTemplatesDir();
        String prefix = readTemplates(layout, configuration.getTemplatePrefixes())
                .replace("{{blogLink}}", !hasBlog ? "" : "<li class=\"list-inline-item\">" +
                        "<a title=\"Blog\" href=\"" + configuration.getSiteBase() + "/blog/\">" +
                        "<i class=\"fa fa-blog fa-fw\"></i></a></li>")
                .replace("{{search}}", hasSearch() ? "" +
                        "<li class=\"list-inline-item\">" +
                        "<a title=\"Search\" id=\"search-button\" href=\"#\" data-toggle=\"modal\" data-target=\"#searchModal\">" +
                        "<i data-toggle=\"tooltip\" data-placement=\"top\" title=\"Search\" class=\"fas fa-search\"></i>" +
                        "</a>" +
                        "</li>\n" +
                        "" : "")
                .replace("{{customHead}}", ofNullable(configuration.getCustomHead()).orElse(""))
                .replace("{{customMenu}}", ofNullable(configuration.getCustomMenu()).orElse(""))
                .replace("{{projectVersion}}", configuration.getProjectVersion()) // enables to invalidate browser cache
                .replace("{{logoText}}", getLogoText())
                .replace("{{logoSideText}}", getLogoSideText())
                .replace("{{base}}", configuration.getSiteBase())
                .replace("{{logo}}", ofNullable(configuration.getLogo()).orElse("//www.yupiik.io/images/logo.svg"))
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
                        .replace("{{title}}", getDefaultInterpolation("title", page, null, null, null))
                        .replace("{{metaAuthor}}", getDefaultInterpolation("metaAuthor", page, null, null, null))
                        .replace("{{metaKeywords}}", getDefaultInterpolation("metaKeywords", page, null, null, null))
                        .replace("{{highlightJsCss}}", page.attributes == null || !page.attributes.containsKey("minisite-highlightjs-skip") ?
                                "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/styles/vs2015.min.css\" integrity=\"sha512-w8aclkBlN3Ha08SMwFKXFJqhSUx2qlvTBFLLelF8sm4xQnlg64qmGB/A6pBIKy0W8Bo51yDMDtQiPLNRq1WMcQ==\" crossorigin=\"anonymous\" />" :
                                "")
                        .replace("{{description}}", ofNullable(page.attributes)
                                .map(a -> a.get("minisite-description"))
                                .map(String::valueOf)
                                .orElseGet(() -> ofNullable(configuration.getDescription()).orElseGet(() -> {
                                    final String content = getIndexSubTitle(false, options).replace('\n', ' ');
                                    if (content.startsWith("adoc:")) {
                                        return content.substring("adoc:".length());
                                    }
                                    return content;
                                }))),
                page.attributes != null && page.attributes.containsKey("minisite-passthrough") ? page.content : renderAdoc(page, asciidoctor, options),
                suffix.replace("{{highlightJs}}", page.attributes != null && page.attributes.containsKey("minisite-highlightjs-skip") ? "" : ("" +
                        "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/highlight.min.js\" integrity=\"sha512-d00ajEME7cZhepRqSIVsQVGDJBdZlfHyQLNC6tZXYKTG7iwcF8nhlFuppanz8hYgXr8VvlfKh4gLC25ud3c90A==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/languages/bash.min.js\" integrity=\"sha512-Hg0ufGEvn0AuzKMU0psJ1iH238iUN6THh7EI0CfA0n1sd3yu6PYet4SaDMpgzN9L1yQHxfB3yc5ezw3PwolIfA==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/languages/bash.min.js\" integrity=\"sha512-Hg0ufGEvn0AuzKMU0psJ1iH238iUN6THh7EI0CfA0n1sd3yu6PYet4SaDMpgzN9L1yQHxfB3yc5ezw3PwolIfA==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/languages/json.min.js\" integrity=\"sha512-37sW1XqaJmseHAGNg4N4Y01u6g2do6LZL8tsziiL5CMXGy04Th65OXROw2jeDeXLo5+4Fsx7pmhEJJw77htBFg==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.1/languages/dockerfile.min.js\" integrity=\"sha512-eRNl3ty7GOJPBN53nxLgtSSj2rkYj5/W0Vg0MFQBw8xAoILeT6byOogENHHCRRvHil4pKQ/HbgeJ5DOwQK3SJA==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script>if (!(window.minisite || {}).skipHighlightJs) { " +
                        "hljs.highlightAll();\n" +
                        (configuration.isAddCodeCopyButton() ?
                                "function addCopyButtons(clipboard) {\n" +
                                        "  document.querySelectorAll('pre > code').forEach(function (codeBlock) {\n" +
                                        "  var button = document.createElement('button');" +
                                        "  button.className = 'copy-code-button';" +
                                        "  button.type = 'button';" +
                                        "  button.innerText = 'Copy';" +
                                        "  button.addEventListener('click', function () {" +
                                        "   clipboard.writeText(codeBlock.innerText).then(function () {" +
                                        "   button.blur();" +
                                        "   button.innerText = 'Copied!';" +
                                        "   setTimeout(function () { button.innerText = 'Copy'; }, 2000); }, function (error) { button.innerText = 'Error'; });" +
                                        "  });" +
                                        "  var pre = codeBlock.parentNode;" +
                                        "  if (pre.parentNode.classList.contains('highlight')) { " +
                                        "  var highlight = pre.parentNode; highlight.parentNode.insertBefore(button, highlight);" +
                                        "  } else { pre.parentNode.insertBefore(button, pre); }" +
                                        " });" +
                                        "}\n" +
                                        "if (navigator && navigator.clipboard) {" +
                                        " addCopyButtons(navigator.clipboard);" +
                                        "} else {" +
                                        " var script = document.createElement('script');" +
                                        " script.src = '//cdnjs.cloudflare.com/ajax/libs/clipboard-polyfill/2.7.0/clipboard-polyfill.promise.js';" +
                                        " script.integrity = 'sha256-waClS2re9NUbXRsryKoof+F9qc1gjjIhc2eT7ZbIv94=';" +
                                        " script.crossOrigin = 'anonymous';" +
                                        " script.onload = function() {addCopyButtons(clipboard);};" +
                                        " document.body.appendChild(script);" +
                                        "}" :
                                "") +
                        " }</script>")));
    }

    protected Collection<Page> findPages(final Asciidoc.AsciidocInstance asciidoctor, final Object options) {
        try {
            final Path content = configuration.getSource().resolve("content");
            final Collection<Page> pages = new ArrayList<>();
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
                    final String contentString = Files.readString(file);
                    final Asciidoc.AsciidocInstance.Header header = asciidoctor.header(contentString, options);
                    final Path out = ofNullable(header.getAttributes().get("minisite-path"))
                            .map(it -> configuration.getTarget().resolve(it))
                            .orElseGet(() -> configuration.getTarget().resolve(content.relativize(file)).getParent().resolve(
                                    filename.substring(0, filename.length() - ".adoc".length()) + ".html"));
                    final Page page = new Page(
                            '/' + configuration.getTarget().relativize(out).toString().replace(File.separatorChar, '/'),
                            header.getTitle(),
                            header.getAttributes(),
                            contentString);
                    pages.add(page);
                    return super.visitFile(file, attrs);
                }
            });
            return pages;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String renderText(final String text, final Object options) {
        if (text.startsWith("adoc:")) {
            return configuration.getAsciidoc().withInstance(
                    configuration.getAsciidoctorConfiguration(),
                    a -> a.convert(text.substring("adoc:".length()), options));
        }
        return text;
    }

    protected String getLogoText() {
        return ofNullable(configuration.getLogoText())
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElse(configuration.getProjectArtifactId()));
    }

    protected String getLogoSideText() {
        return ofNullable(configuration.getLogoSideText())
                .orElse("Docs");
    }

    protected String getIndexText(final Object options) {
        return ofNullable(configuration.getIndexText())
                .map(it -> renderText(it, options))
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElseGet(() -> getLogoText() + " Documentation"));
    }

    protected String getIndexSubTitle(final Object options) {
        return getIndexSubTitle(true, options);
    }

    protected String getIndexSubTitle(final boolean render, final Object options) {
        return ofNullable(configuration.getIndexSubTitle())
                .map(it -> render ? renderText(it, options) : it)
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElse(configuration.getProjectArtifactId()));
    }

    protected String getTitle(final Object options) {
        return ofNullable(configuration.getTitle())
                .map(it -> renderText(it, options))
                .orElseGet(() -> "Yupiik " + getLogoText());
    }

    protected String renderAdoc(final Page page, final Asciidoc.AsciidocInstance asciidoctor, final Object options) {
        return asciidoctor.convert(page.content, options);
    }

    protected String readTemplates(final Path layout, final List<String> templatePrefixes) {
        return newTemplateSubstitutor(layout).replace(templatePrefixes.stream()
                .flatMap(it -> findTemplate(layout, it))
                .filter(Objects::nonNull)
                .collect(joining("\n")));
    }

    private Stream<String> findTemplate(final Path layout, final String it) {
        final Path resolved = layout.resolve(it);
        if (Files.exists(resolved)) {
            try (final BufferedReader r = Files.newBufferedReader(resolved, StandardCharsets.UTF_8)) {
                // materialize it to close the stream there
                return r.lines().collect(toList()).stream();
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        final InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("yupiik-tools-maven-plugin/minisite/" + it);
        if (stream == null) {
            configuration.getAsciidoctorConfiguration().info().accept("No '" + it + "' template found");
            return null;
        }
        try (final BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            // materialize it to close the stream there
            return r.lines().collect(toList()).stream();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private TemplateSubstitutor newTemplateSubstitutor(final Path layout) {
        return new TemplateSubstitutor(key -> ofNullable(configuration.getTemplateExtensionPoints().get(key))
                .or(() -> ofNullable(findTemplate(layout.resolve("extension-points"), key + ".html")).map(s -> s.collect(joining("\n"))))
                .orElse(null));
    }

    public Object createOptions() {
        return configuration.getAsciidoc().createOptions(configuration);
    }

    @RequiredArgsConstructor
    public static class BlogPage {
        private final Page page;
        private final OffsetDateTime publishedDate;
    }

    @RequiredArgsConstructor
    public static class Page {
        private final String relativePath;
        private final String title;
        private final Map<String, String> attributes;
        private final String content;
    }

    private static class NavLink {
        private final String label;
        private final String link;

        private NavLink(final Map<String, String> props, final String prefix) {
            this.label = props.get(prefix + "label");
            this.link = ofNullable(props.get(prefix + "link"))
                    .filter(Predicate.not(String::isBlank))
                    .orElseGet(() -> label == null || label.isBlank() ? null : label.toLowerCase(ROOT).replace(' ', '-') + ".html");
        }

        public String render(final String template, final String clazz, final String subLabel) {
            return link == null || link.isBlank() ? "" : template
                    .replace("{{class}}", "page-footer-nav-link-" + clazz)
                    .replace("{{link}}", link)
                    .replace("{{subLabel}}", subLabel)
                    .replace("{{label}}", label);
        }
    }
}
