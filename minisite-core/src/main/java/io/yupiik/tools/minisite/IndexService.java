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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.config.PropertyOrderStrategy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

public class IndexService {
    public Index index(final Path base, final String siteBase, final Predicate<Path> filter) {
        final Index result = new Index(new ArrayList<>());
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().endsWith(".html") && filter.test(file)) {
                        doIndex(file, siteBase + '/' + base.relativize(file)).ifPresent(result.getEntries()::add);
                    }
                    return super.visitFile(file, attrs);
                }
            });
            result.getEntries().sort(Comparator.<IndexEntry, String>comparing(it -> it.title)
                    .thenComparing(comparing(v -> v.url)));
            return result;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void write(final Index index, final Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        try (final Jsonb jsonb = JsonbBuilder
                .create(new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL));
             final BufferedWriter writer = Files.newBufferedWriter(target)) {
            jsonb.toJson(index.getEntries(), writer);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<IndexEntry> doIndex(final Path file, final String url) throws IOException {
        final List<String> lines = Files.readAllLines(file);
        if (lines.contains(":minisite-index-skip: true")) {
            return Optional.empty();
        }

        final String content = String.join("\n", lines);
        final Document document = Jsoup.parse(content);
        return Optional.of(new IndexEntry(
                "en",
                document.title(),
                url,
                selectMeta(document, "description").orElse(null),
                selectMeta(document, "keywords").orElse(null),
                select(document, ".page-content-body h1").orElse(null),
                select(document, ".page-content-body h2").orElse(null),
                select(document, ".page-content-body h3").orElse(null),
                document.select(".page-content-body p, .page-content-body td.content, .page-content-body th.tableblock, .page-content-body code > span").stream()
                        .map(Element::text)
                        .distinct()
                        .collect(joining("\n"))));
    }

    private static Optional<String> select(final Document document, final String selector) {
        final Elements select = document.select(selector);
        if (select.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(select.stream()
                .map(Element::text)
                .distinct()
                .collect(joining("\n")));
    }

    private static Optional<String> selectMeta(final Document document, final String metaName) {
        return document
                .select("meta")
                .stream()
                .filter(it -> it.hasAttr("name") && it.hasAttr("value") && metaName.equals(it.attr("name")))
                .findFirst()
                .map(it -> it.attr("value"));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Index {
        private List<IndexEntry> entries;
    }

    @Data
    @AllArgsConstructor
    public static class IndexEntry {
        private String lang;
        private String title;
        private String url;
        private String description;
        private String keywords;
        private String lvl1;
        private String lvl2;
        private String lvl3;
        private String text;
    }
}
