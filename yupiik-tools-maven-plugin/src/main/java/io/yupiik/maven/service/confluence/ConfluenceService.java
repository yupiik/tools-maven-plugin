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

import lombok.Data;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
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
import java.util.Optional;
import java.util.function.Consumer;

/**
 *
 <profile> <!--  mvn clean package -Pconfluence  -->
 <id>confluence</id>
 <build>
 <plugins>
 <plugin>
 <groupId>io.yupiik.maven</groupId>
 <artifactId>yupiik-tools-maven-plugin</artifactId>
 <executions>
 <execution>
 <id>confluence</id>
 <phase>prepare-package</phase>
 <goals>
 <goal>minisite</goal>
 </goals>
 <configuration>
 <confluence>
 <ignore>false</ignore>
 <url>xxxxxxxx/wiki/</url>
 <bearer>xxxxxxx</bearer>
 <space>TESTSPACE</space>
 </confluence>
 </configuration>
 </execution>
 </executions>
 </plugin>
 </plugins>
 </build>
 </profile>
 */
@Named
@Singleton
public class ConfluenceService {
    public void upload(final Confluence confluence, final Path path,
                       final Consumer<String> info) {
        final var httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        final var uri = URI.create(confluence.getUrl());
        final var createContent = uri.resolve("rest/api/content");
        final var space = new Space(confluence.getSpace());
        final var auth = "Bearer " + confluence.getBearer();
        try (final var jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon.skip-cdi", true))) {

            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final var relative = path.relativize(file).toString();
                    /*
                    final var hasParent = uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath());
                    final var target = !hasParent ? relative : String.join("/", uri.getPath().substring(1), relative);

                    final var segments = relative.split("/");
                    final var current = new StringBuilder(hasParent ? uri.getPath().substring(1) : "");
                    for (int i = 0; i < segments.length - 1; i++) {
                        if (current.length() > 0) {
                            current.append('/');
                        }
                        current.append(segments[i]);
                        final var test = current.toString();
                        final var shouldCreate = existingFolders.add(test);
                        if (shouldCreate && !ftpClient.makeDirectory(test)) {
                            throw new IllegalArgumentException("Can't create folder '" + test + "'");
                        }
                        if (shouldCreate) {
                            info.accept("Created directory '" + relative + "' on " + uri);
                        }
                    }
                    */

                    createOrUpdate(httpClient, createContent, auth, space, jsonb, file);
                    info.accept("Uploaded file '" + relative + "' on " + uri);

                    return super.visitFile(file, attrs);
                }
            });
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void createOrUpdate(final HttpClient httpClient, final URI createContent, final String auth, final Space space,
                                final Jsonb jsonb, final Path path) {
        try {
            final var content = Files.readString(path, StandardCharsets.UTF_8);
            final var title = findTitle(content).orElseGet(() -> path.getFileName().toString());
            final var createResponse = httpClient.send(
                    HttpRequest.newBuilder()
                            .POST(HttpRequest.BodyPublishers.ofString(jsonb.toJson(
                                    new Content(
                                            null,
                                            title,
                                            space,
                                            new Body(new Storage(content)),
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
                throw new IllegalStateException("Invalid content creation: " + createResponse + "\n" + createResponse.body());
            }
            // todo: if exists then recreate
            /*
            {
    "version": {
        "number": 2
    },
    "title": "My new title",
    "type": "page",
    "body": {
        "storage": {
            "value": "<p>New page data.</p>",
            "representation": "storage"
        }
    }
}
             */
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
    public static class Content {
        private final String type = "page";
        private final String id;
        private final String title;
        private final Space space;
        private final Body body;
        private final Version version;
    }

    @Data
    public static class Version {
        private final int number;
    }

    @Data
    public static class Space {
        private final String key;
    }

    @Data
    public static class Body {
        private final Storage storage;
    }

    @Data
    public static class Storage {
        private final String representation = "export_view";
        private final String value;
    }
}
