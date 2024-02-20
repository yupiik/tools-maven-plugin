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
package io.yupiik.maven.service.confluence;

import io.yupiik.tools.minisite.Urlifier;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.annotation.JsonbProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.maven.plugin.logging.Log;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Named
@Singleton
public class ConfluenceService {
    private final Urlifier urlifier = new Urlifier();

    public void upload(final Confluence confluence, final Path path,
                       final Consumer<String> info, final String base,
                       final Log log) {
        final var httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        final var uri = URI.create(confluence.getUrl());
        final var uriSep = confluence.getUrl().endsWith("/") ? "" : "/";
        final var createContent = uri.resolve(uriSep + "rest/api/content");
        final var space = new Space(confluence.getSpace());
        final var auth = confluence.getAuthorization();
        final var baseWikiLink = uri.getPath().endsWith("/") ? uri.getPath().substring(0, uri.getPath().length() - 1) : uri.getPath();
        final var state = new State();
        try (final var jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            final var contents = findAllPages(httpClient, createContent, auth, space, jsonb);
            final var hrefReplacements = toHrefReplacements(contents);
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final var name = file.getFileName().toString();
                    if (!name.endsWith(".html")) { // todo: better asset handling, for now consider them embedded
                        return FileVisitResult.CONTINUE;
                    }
                    if (confluence.isSkipIndex() && "index.html".equals(name)) {
                        return FileVisitResult.CONTINUE;
                    }

                    final var relative = path.relativize(file).toString();
                    final var relativeLink = createOrUpdate(
                            httpClient, createContent, auth, space, jsonb, file, base,
                            baseWikiLink, contents, hrefReplacements, state);
                    info.accept("Saved '" + relative + "' on " + uri.resolve(uriSep + (uriSep.isBlank() ? relativeLink.substring(1) : relativeLink)));

                    return super.visitFile(file, attrs);
                }
            });

            // recompute links after having created all files - so new files can be linked too
            if (!state.needsRecomputation.isEmpty()) {
                final var updatedContent = findAllPages(httpClient, createContent, auth, space, jsonb);
                final var updatedHrefReplacements = toHrefReplacements(updatedContent);
                final var newState = new State();
                state.needsRecomputation.forEach(file -> {
                    final var relative = path.relativize(file).toString();
                    final var relativeLink = createOrUpdate(
                            httpClient, createContent, auth, space, jsonb, file, base,
                            baseWikiLink, updatedContent, updatedHrefReplacements, newState);
                    info.accept("Saved '" + relative + "' on " + uri.resolve(uriSep + (uriSep.isBlank() ? relativeLink.substring(1) : relativeLink)));
                });

                // if the set is not empty it means some links were not rewritten properly so log it
                if (!newState.needsRecomputation.isEmpty()) {
                    log.warn("Some links will not be valid:\n" +
                            newState.badLinks.entrySet().stream()
                                    .map(e -> "- " + e.getKey() + ": " + e.getValue())
                                    .collect(joining("\n")) + "\n" +
                            "Available contents: " + updatedContent.keySet());
                }
            }
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<Pattern, String> toHrefReplacements(final Map<String, Content> contents) {
        return contents.entrySet().stream()
                .collect(toMap( // replace - by . since the reverse process converts foo.html in foo-html so we must use "any char" matching since we don't know if it is a dot or not
                        it -> Pattern.compile("href=\"" + urlifier.toUrlName(it.getKey()).replace('-', '.') + "\""),
                        e -> e.getValue().getLinks().get("webui")));
    }

    private Map<String, Content> findAllPages(final HttpClient httpClient, final URI getContent, final String auth, final Space space, final Jsonb jsonb) {
        try {
            final var fetch = httpClient.send(
                    HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create(getContent.toASCIIString() + "/search?cql=space=" + space.getKey() + "%20AND%20type=page&expand=body.storage,version.number"))
                            .header("Authorization", auth)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (fetch.statusCode() != 200) {
                throw new IllegalStateException("Invalid content fetch: " + fetch + "\n" + fetch.body());
            }
            final var contents = jsonb.fromJson(fetch.body(), Contents.class);
            return (contents.getLinks().containsKey("next") ?
                    Stream.concat(
                            contents.getResults().stream(),
                            fetchContents(httpClient, contents.getLinks().get("base") + contents.getLinks().get("next"), auth, jsonb).stream()) :
                    contents.getResults().stream())
                    .filter(it -> {
                        if (it.getBody() != null && it.getBody().getStorage() != null && it.getBody().getStorage().getValue() != null) {
                            final var content = it.getBody().getStorage().getValue().stripLeading();
                            return content.contains("<div class=\"container page-content ") || content.contains("<h1 class=\"page-heading mx-auto\">");
                        }
                        return false;
                    })
                    .collect(toMap(c -> extractId(c.getBody().getStorage().getValue()).orElseGet(c::getId), identity()));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private List<Content> fetchContents(final HttpClient httpClient, final String uri, final String auth, final Jsonb jsonb) {
        try {
            final var fetch = httpClient.send(
                    HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create(uri))
                            .header("Authorization", auth)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (fetch.statusCode() != 200) {
                throw new IllegalStateException("Invalid content fetch: " + fetch + "\n" + fetch.body());
            }
            final var current = jsonb.fromJson(fetch.body(), Contents.class);
            return (current.getLinks().containsKey("next") ?
                    Stream.concat(
                                    current.getResults().stream(),
                                    fetchContents(httpClient, current.getLinks().get("base") + current.getLinks().get("next"), auth, jsonb).stream())
                            .collect(toList()) :
                    current.getResults());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String createOrUpdate(final HttpClient httpClient, final URI createContent, final String auth, final Space space,
                                  final Jsonb jsonb, final Path path, final String base, final String baseWikiLink,
                                  final Map<String, Content> contents, final Map<Pattern, String> hrefReplacements, final State state) {
        try {
            final var content = toContent(Files.readString(path, StandardCharsets.UTF_8), base, baseWikiLink, contents, hrefReplacements, state, path);
            final var title = findTitle(content).orElseGet(() -> path.getFileName().toString());
            final var id = extractId(content).orElseThrow(() -> new IllegalArgumentException("No id in " + path));
            final var existing = contents.get(id);
            if (existing != null && ( // only update if needed
                    existing.getBody() == null ||
                            existing.getBody().getStorage() == null ||
                            existing.getBody().getStorage().getValue() == null ||
                            !Objects.equals(content, existing.getBody().getStorage().getValue()))) {
                return doUpdate(httpClient, createContent, auth, space, existing, jsonb, title, content);
            }
            return doCreate(httpClient, createContent, auth, space, jsonb, content, title);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String doCreate(final HttpClient httpClient, final URI createContent, final String auth, final Space space,
                            final Jsonb jsonb, final String content, final String title) throws IOException, InterruptedException {
        final var createResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .POST(HttpRequest.BodyPublishers.ofString(jsonb.toJson(
                                new Content(
                                        "page",
                                        null,
                                        title,
                                        space,
                                        new Body(new Storage("storage", content)),
                                        null,
                                        null
                                )
                        ), StandardCharsets.UTF_8))
                        .uri(createContent)
                        .header("Authorization", auth)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (createResponse.statusCode() > 299) {
            throw new IllegalStateException("Invalid content creation for '" + title + "': " + createResponse + "\n" + createResponse.body());
        }
        final var created = jsonb.fromJson(createResponse.body(), Content.class);
        return created.getLinks().get("webui");
    }

    private String doUpdate(final HttpClient httpClient, final URI createContent, final String auth, final Space space, final Content existing,
                            final Jsonb jsonb, final String title, final String content) {
        try {
            final var response = httpClient.send(
                    HttpRequest.newBuilder()
                            .PUT(HttpRequest.BodyPublishers.ofString(jsonb.toJson(
                                    new Content(
                                            "page",
                                            existing.getId(),
                                            title,
                                            space,
                                            new Body(new Storage("storage", content)),
                                            new Version(existing.getVersion().getNumber() + 1),
                                            null
                                    )
                            ), StandardCharsets.UTF_8))
                            .uri(URI.create(createContent.toASCIIString() + '/' + existing.getId()))
                            .header("Authorization", auth)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() > 299) {
                throw new IllegalStateException("Invalid content update: " + response + "\n" + response.body());
            }
            return existing.getLinks().get("webui");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * We extract it from the template which uses:
     * <p>
     * {@code <div class="container page-content {{{pageClass}}}">}.
     * <p>
     * IMPORTANT: we don't use the {@code id} attribute cause confluence strips it.
     */
    private Optional<String> extractId(final String content) {
        final var start = content.indexOf(" class=\"container page-content ");
        if (start < 0) {
            if (content.contains("<h1 class=\"page-heading mx-auto\">")) {
                return Optional.of("index-html-container"); // not in the dom but by convention on the default template we deduce it
            }
            return Optional.empty();
        }
        final var end = content.indexOf("\"", start + " class=\"container page-content ".length() + 1);
        if (end < 0) {
            throw new IllegalArgumentException("Invalid content, malformed '<div id>':\n" + content);
        }
        return Optional.of(content.substring(start + " class=\"container page-content ".length(), end).strip());
    }

    // todo: disable template upfront, this way no post processing needed? IMPORTANT: only works with default template
    private String toContent(final String html, final String base, final String baseWikiLink,
                             final Map<String, Content> existingPages, final Map<Pattern, String> hrefReplacements,
                             final State state, final Path path) {
        final int start = html.indexOf("<div class=\"page");
        if (start < 0) {
            throw new IllegalArgumentException("No '<div class=\"page' found in\n" + html);
        }
        final int endStart = html.indexOf(">", start);
        if (endStart < 0) {
            throw new IllegalArgumentException("No > after index=" + start + "\n" + html);
        }
        int end = html.indexOf("<footer class=\"footer pb-5\">", endStart);
        if (end < 0) {
            throw new IllegalArgumentException("No <footer class=\"footer pb-5\"> found in\n" + html);
        }
        end = html.lastIndexOf("<hr />", end); // we want this one, but it is not specific enough to match so we go through footer first
        if (end < 0) {
            throw new IllegalArgumentException("No <hr /> found in\n" + html);
        }
        return simplifyAdmonition(
                rewriteLinksFromPages(
                        hrefReplacements, baseWikiLink,
                        rewriteLinks(state,
                                stripNavigationRight(
                                        stripNavigationLeft(html.substring(start, end).strip())), base, baseWikiLink, existingPages, path)));
    }

    // this one is a bit blind but for the case you use link:mydoc.html[...] kind of syntax or xref:mydoc.adoc[...]
    // WARNING: can need 2 builds/updates to be relevant
    private String rewriteLinksFromPages(final Map<Pattern, String> replacements, final String baseWikiLink, final String content) {
        var output = content;
        for (final var ctx : replacements.entrySet()) {
            output = ctx.getKey().matcher(output).replaceAll("href=\"" + baseWikiLink + ctx.getValue() + '"');
        }
        return output;
    }

    private String simplifyAdmonition(final String content) { // makes the title appearing since confluence does not show icons until you tune the css
        return content.replace("class=\"icon\"", "");
    }

    private String rewriteLinks(final State state, final String content, final String siteBase, final String baseWikiLink,
                                final Map<String, Content> existingPages, final Path path) {
        int nextLink = content.indexOf("<a ");
        if (nextLink < 0) {
            return content;
        }
        final int end = content.indexOf("</a>", nextLink);
        if (end < 0) {
            return content;
        }
        final var matchingHref = "href=\"" + siteBase;
        final int startHref = content.indexOf(matchingHref, nextLink);
        if (startHref < 0) {
            return content;
        }
        final int endHref = content.indexOf("\"", startHref + matchingHref.length() + 1);
        if (endHref < 0) {
            return content;
        }
        final var link = content.substring(startHref + matchingHref.length(), endHref);
        final var rewritten = relink(state, existingPages, siteBase, link, baseWikiLink, path);
        return content.substring(0, startHref) + "href=\"" + rewritten + rewriteLinks(
                state, content.substring(endHref), siteBase, baseWikiLink, existingPages, path);
    }

    private String relink(final State state, final Map<String, Content> existingPages,
                          final String siteBase, final String link, final String baseWikiLink,
                          final Path path) {
        final var id = urlifier.toUrlName(link.startsWith("/") ? link.substring(1) : link).replace('/', '-');
        final var found = existingPages.get(id);
        if (found == null) { // keep the link to rewrite it with next iteration
            state.needsRecomputation.add(path);
            state.badLinks.computeIfAbsent(path.getFileName().toString(), p -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(id);
            return siteBase + link;
        }
        return baseWikiLink + found.getLinks().get("webui");
    }

    private String stripNavigationRight(final String html) {
        final var start = html.indexOf("<div class=\"page-navigation-right\">");
        if (start < 0) {
            return html;
        }
        final var end = html.indexOf("</div>", start);
        if (end < 0) {
            return html;
        }
        return html.substring(0, start).stripTrailing() + html.substring(end + "</div>".length()).stripLeading();
    }

    private String stripNavigationLeft(final String html) {
        final var start = html.indexOf("<div class=\"page-navigation-left\">");
        if (start < 0) {
            return html;
        }
        final var end = html.indexOf("</div>", start);
        if (end < 0) {
            return html;
        }
        return html.substring(0, start).stripTrailing() + html.substring(end + "</div>".length()).stripLeading();
    }

    private Optional<String> findTitle(final String content) {
        final var start = content.indexOf("<h1>");
        if (start > 0) {
            final var end = content.indexOf("</h1>", start);
            if (end > start) {
                return Optional.of(content.substring(start + "<h1>".length(), end).strip());
            }
        }
        return Optional.empty();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Content {
        private String type;
        private String id;
        private String title;
        private Space space;
        private Body body;
        private Version version;

        @JsonbProperty("_links")
        private Map<String, String> links;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Version {
        private int number;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Space {
        private String key;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Body {
        private Storage storage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Storage {
        private String representation;
        private String value;
    }

    @Data
    public static class Contents {
        private List<Content> results;

        private int size;
        private int start;
        private int limit;

        @JsonbProperty("_links")
        private Map<String, String> links;
    }

    private static class State {
        private Set<Path> needsRecomputation = new HashSet<>();
        private Map<String, Set<String>> badLinks = new TreeMap<>();
    }
}
