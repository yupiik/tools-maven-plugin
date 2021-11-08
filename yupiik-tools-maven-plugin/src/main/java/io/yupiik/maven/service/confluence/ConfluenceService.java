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
package io.yupiik.maven.service.confluence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.annotation.JsonbProperty;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Named
@Singleton
public class ConfluenceService {
    public void upload(final Confluence confluence, final Path path,
                       final Consumer<String> info) {
        final var httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        final var uri = URI.create(confluence.getUrl());
        final var uriSep = confluence.getUrl().endsWith("/") ? "" : "/";
        final var createContent = uri.resolve(uriSep + "rest/api/content");
        final var space = new Space(confluence.getSpace());
        final var auth = confluence.getAuthorization();
        try (final var jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {
            final var contents = findAllPages(httpClient, createContent, auth, space, jsonb);
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
                    final var relativeLink = createOrUpdate(httpClient, createContent, auth, space, jsonb, file, contents);
                    info.accept("Saved '" + relative + "' on " + uri.resolve(uriSep + (uriSep.isBlank() ? relativeLink.substring(1) : relativeLink)));

                    return super.visitFile(file, attrs);
                }
            });
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
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
                                  final Jsonb jsonb, final Path path, final Map<String, Content> contents) {
        try {
            final var content = toContent(Files.readString(path, StandardCharsets.UTF_8));
            final var title = findTitle(content).orElseGet(() -> path.getFileName().toString());
            final var id = extractId(content).orElseThrow(() -> new IllegalArgumentException("No id in " + path));
            final var existing = contents.get(id);
            if (existing != null) {
                return doUpdate(httpClient, createContent, auth, space, existing, jsonb, id, title, content);
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
                            final Jsonb jsonb, final String id, final String title, final String content) {
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
    private String toContent(final String html) {
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
        return stripNavigation(html.substring(start, end).strip());
    }

    private String stripNavigation(final String html) {
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
}
