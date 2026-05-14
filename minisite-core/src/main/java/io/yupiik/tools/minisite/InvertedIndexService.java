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

import io.yupiik.tools.minisite.stem.PorterStemmer;
import io.yupiik.tools.minisite.stem.TokenStream;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.config.PropertyOrderStrategy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.stream.Collectors.joining;

public class InvertedIndexService {
    private static final int MAX_TEXT_SNIPPET_LENGTH = 800;

    private static final int WEIGHT_TITLE = 10;
    private static final int WEIGHT_KEYWORDS = 6;
    private static final int WEIGHT_DESCRIPTION = 5;
    private static final int WEIGHT_H1 = 4;
    private static final int WEIGHT_H2 = 3;
    private static final int WEIGHT_H3 = 2;
    private static final int WEIGHT_TEXT = 1;

    private static final Set<String> EMPTY_SET = Collections.emptySet();

    public InvertedIndex index(final Path base, final String siteBase, final Predicate<Path> filter) {
        final var result = new InvertedIndex();
        result.documents = new ArrayList<>();
        result.invertedIndex = new LinkedHashMap<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().endsWith(".html") && filter.test(file)) {
                        final var url = siteBase + '/' + base.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                        doIndex(file, url, result);
                    }
                    return super.visitFile(file, attrs);
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        result.documents.sort((a, b) -> a.url.compareTo(b.url));

        // rebuild inverted index with correct doc indices after sorting
        final var rebuilt = new LinkedHashMap<String, List<TermEntry>>();
        for (final var entry : result.invertedIndex.entrySet()) {
            for (final var te : entry.getValue()) {
                final var newIdx = result.documents.indexOf(te.docRef);
                if (newIdx >= 0) {
                    te.docIdx = newIdx;
                    rebuilt.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(te);
                }
            }
        }
        result.invertedIndex = rebuilt;

        result.allTerms = new ArrayList<>(result.allTermsSet);
        Collections.sort(result.allTerms);
        result.allTermsSet = null;

        result.stopWords = new ArrayList<>(TokenStream.getStopWords());
        Collections.sort(result.stopWords);

        return result;
    }

    public void write(final InvertedIndex index, final Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        try (final var jsonb = JsonbBuilder.create(
                new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL));
             final var writer = Files.newBufferedWriter(target)) {
            jsonb.toJson(toSerializable(index), writer);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SerializableIndex toSerializable(final InvertedIndex index) {
        final var result = new SerializableIndex();

        // convert docs
        result.documents = new ArrayList<>();
        for (final var doc : index.documents) {
            final var sd = new SerializableDoc();
            sd.url = doc.url;
            sd.title = doc.title;
            sd.description = doc.description;
            sd.searchableText = doc.searchableText;
            result.documents.add(sd);
        }

        result.stopWords = index.stopWords;
        result.allTerms = index.allTerms;

        // convert inverted index: term -> [[docIdx, score, [pos...]], ...]
        result.invertedIndex = new LinkedHashMap<>();
        for (final var entry : index.invertedIndex.entrySet()) {
            final var entries = new ArrayList<List<Object>>();
            for (final var te : entry.getValue()) {
                final var e = new ArrayList<>(3);
                e.add(te.docIdx);
                e.add((double) te.score);
                if (te.positions != null && te.positions.length > 0) {
                    final var positions = new ArrayList<Integer>(te.positions.length);
                    for (final int p : te.positions) {
                        positions.add(p);
                    }
                    e.add(positions);
                }
                entries.add(e);
            }
            result.invertedIndex.put(entry.getKey(), entries);
        }

        return result;
    }

    private void doIndex(final Path file, final String url, final InvertedIndex result) throws IOException {
        final var lines = Files.readAllLines(file);
        if (lines.contains(":minisite-index-skip: true")) {
            return;
        }

        final var content = String.join("\n", lines);
        final var document = Jsoup.parse(content);

        final var title = document.title();
        final var description = selectMeta(document, "description").orElse(null);
        final var keywords = selectMeta(document, "keywords").orElse(null);
        final var h1 = select(document, ".page-content-body h1").orElse(null);
        final var h2 = select(document, ".page-content-body h2").orElse(null);
        final var h3 = select(document, ".page-content-body h3").orElse(null);
        final var text = document.select(
                        ".page-content-body p, .page-content-body td.content, " +
                                ".page-content-body th.tableblock, .page-content-body code > span")
                .stream()
                .map(Element::text)
                .distinct()
                .collect(joining("\n"));

        final var searchableText = buildSearchableText(description, h1, h2, h3, text);

        // tokenize each field independently for per-field scoring
        final var fieldTerms = new HashMap<String, Set<String>>();
        final var ps = new PorterStemmer();

        fieldTerms.put("title", tokenizeSet(title, ps));
        if (description != null) {
            fieldTerms.put("description", tokenizeSet(description, ps));
        }
        if (keywords != null) {
            fieldTerms.put("keywords", tokenizeSet(keywords, ps));
        }
        if (h1 != null) {
            fieldTerms.put("h1", tokenizeSet(h1, ps));
        }
        if (h2 != null) {
            fieldTerms.put("h2", tokenizeSet(h2, ps));
        }
        if (h3 != null) {
            fieldTerms.put("h3", tokenizeSet(h3, ps));
        }
        if (text != null && !text.isEmpty()) {
            fieldTerms.put("text", tokenizeSet(text, ps));
        }

        final var docEntry = new DocEntry();
        docEntry.url = url;
        docEntry.title = title;
        docEntry.description = description;
        docEntry.searchableText = searchableText;
        result.documents.add(docEntry);

        // tokenize searchable text for positions + collect raw terms
        final var normalizedSearchable = normalizeText(searchableText);
        final var tokens = tokenizeWithPositions(normalizedSearchable, ps);
        for (final var tp : tokens) {
            result.allTermsSet.add(tp.raw);
        }

        // group tokens by stemmed form for inverted index
        final var stemToPositions = new HashMap<String, List<Integer>>();
        for (final var tp : tokens) {
            stemToPositions.computeIfAbsent(tp.stemmed, k -> new ArrayList<>()).add(tp.position);
        }

        for (final var entry : stemToPositions.entrySet()) {
            final var stemmed = entry.getKey();
            final var score = computeScore(stemmed, fieldTerms);

            final var te = new TermEntry();
            te.docRef = docEntry;
            te.docIdx = -1;
            te.score = score;
            te.positions = toIntArray(entry.getValue());

            result.invertedIndex.computeIfAbsent(stemmed, k -> new ArrayList<>()).add(te);
        }
    }

    private static int[] toIntArray(final List<Integer> list) {
        final var arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private double computeScore(final String stemmed, final Map<String, Set<String>> fieldTerms) {
        var score = 0.0;
        if (fieldTerms.getOrDefault("title", EMPTY_SET).contains(stemmed)) {
            score += WEIGHT_TITLE;
        }
        if (fieldTerms.getOrDefault("keywords", EMPTY_SET).contains(stemmed)) {
            score += WEIGHT_KEYWORDS;
        }
        if (fieldTerms.getOrDefault("description", EMPTY_SET).contains(stemmed)) {
            score += WEIGHT_DESCRIPTION;
        }
        if (fieldTerms.getOrDefault("h1", EMPTY_SET).contains(stemmed)) {
            score += WEIGHT_H1;
        }
        if (fieldTerms.getOrDefault("h2", EMPTY_SET).contains(stemmed)) {
            score += WEIGHT_H2;
        }
        if (fieldTerms.getOrDefault("h3", EMPTY_SET).contains(stemmed)) {
            score += WEIGHT_H3;
        }
        if (fieldTerms.getOrDefault("text", EMPTY_SET).contains(stemmed)) {
            score += WEIGHT_TEXT;
        }
        return score;
    }

    private Set<String> tokenizeSet(final String text, final PorterStemmer ps) {
        if (text == null || text.isEmpty()) {
            return EMPTY_SET;
        }
        final var normalized = normalizeText(text);
        final var parts = normalized.split("[^a-z0-9]+");
        final var result = new HashSet<String>();
        for (final var part : parts) {
            if (!part.isEmpty() && !TokenStream.getStopWords().contains(part)) {
                result.add(ps.stem(part));
            }
        }
        return result;
    }

    private List<TokenPosition> tokenizeWithPositions(final String normalized, final PorterStemmer ps) {
        final var result = new ArrayList<TokenPosition>();
        final var token = new StringBuilder();
        var tokenStart = -1;
        for (int i = 0; i < normalized.length(); i++) {
            final var c = normalized.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (token.isEmpty()) {
                    tokenStart = i;
                }
                token.append(c);
            } else if (!token.isEmpty()) {
                final var raw = token.toString();
                if (!TokenStream.getStopWords().contains(raw)) {
                    result.add(new TokenPosition(raw, ps.stem(raw), tokenStart));
                }
                token.setLength(0);
            }
        }
        if (!token.isEmpty()) {
            final var raw = token.toString();
            if (!TokenStream.getStopWords().contains(raw)) {
                result.add(new TokenPosition(raw, ps.stem(raw), tokenStart));
            }
        }
        return result;
    }

    private static String normalizeText(final String text) {
        if (text == null) {
            return "";
        }
        final var lower = text.toLowerCase();
        final var sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            final var c = lower.charAt(i);
            if (c <= 0x7F) {
                sb.append(c);
            } else {
                final var decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
                for (int j = 0; j < decomposed.length(); j++) {
                    final var dc = decomposed.charAt(j);
                    if (dc <= 0x7F) {
                        sb.append(dc);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String buildSearchableText(final String description, final String h1,
                                              final String h2, final String h3, final String text) {
        final var sb = new StringBuilder();
        sb.append(description != null ? description : "").append('\n');
        sb.append(h1 != null ? h1 : "").append('\n');
        sb.append(h2 != null ? h2 : "").append('\n');
        sb.append(h3 != null ? h3 : "").append('\n');
        if (text != null) {
            sb.append(text, 0, Math.min(text.length(), MAX_TEXT_SNIPPET_LENGTH));
        }
        return sb.toString();
    }

    private static Optional<String> selectMeta(final Document document, final String metaName) {
        return document.select("meta").stream()
                .filter(it -> it.hasAttr("name") && it.hasAttr("value") && metaName.equals(it.attr("name")))
                .findFirst()
                .map(it -> it.attr("value"));
    }

    private static Optional<String> select(final Document document, final String selector) {
        final var select = document.select(selector);
        if (select.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(select.stream()
                .map(Element::text)
                .distinct()
                .collect(joining("\n")));
    }

    public static class InvertedIndex {
        private List<DocEntry> documents;
        private List<String> stopWords;
        private List<String> allTerms;
        private Set<String> allTermsSet = new HashSet<>();
        private Map<String, List<TermEntry>> invertedIndex;
    }

    private static class DocEntry {
        private String url;
        private String title;
        private String description;
        private String searchableText;
    }

    private static class TermEntry {
        private DocEntry docRef;
        private int docIdx;
        private double score;
        private int[] positions;
    }

    private static class TokenPosition {
        private final String raw;
        private final String stemmed;
        private final int position;

        private TokenPosition(final String raw, final String stemmed, final int position) {
            this.raw = raw;
            this.stemmed = stemmed;
            this.position = position;
        }
    }

    private static class SerializableIndex {
        @JsonbProperty("v")
        private int version = 1;

        @JsonbProperty("d")
        private List<SerializableDoc> documents;

        @JsonbProperty("s")
        private List<String> stopWords;

        @JsonbProperty("t")
        private List<String> allTerms;

        @JsonbProperty("i")
        private Map<String, List<List<Object>>> invertedIndex;
    }

    private static class SerializableDoc {
        @JsonbProperty("u")
        private String url;

        @JsonbProperty("t")
        private String title;

        @JsonbProperty("m")
        private String description;

        @JsonbProperty("x")
        private String searchableText;
    }
}
