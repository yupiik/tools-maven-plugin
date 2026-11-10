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

import io.yupiik.tools.common.asciidoctor.AsciidoctorConfiguration;
import io.yupiik.tools.minisite.language.Asciidoc;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static lombok.AccessLevel.PRIVATE;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = PRIVATE)
public class MiniSiteConfiguration {
    /**
     * Default patterns for the not-indexed pages, applied unless the configured list
     * contains the {@code -default} marker. They exclude the blog listing/pagination pages
     * from the search index.
     */
    public static final List<String> DEFAULT_NOT_INDEXED_PAGES = List.of(
            "prefix:blog/page-", // blog pagination pages
            "blog/index.html"); // blog index

    private Supplier<ClassLoader> actionClassLoader;
    private Path source;
    private Path target;
    private List<File> templateDirs;
    private Map<String, Object> attributes;
    private String title;
    private String description;
    private String rssFeedFile;
    private String logoText;
    private String logo;
    private String favicon;
    private String indexText;
    private String indexSubTitle;
    private String copyright;
    private String linkedInCompany;
    private String customHead;
    private String customScripts;
    private String customMenu;
    private String siteBase;
    private boolean useDefaultAssets;
    private String searchIndexName;
    private List<String> notIndexedPages;
    private boolean generateBlog;
    private int blogPageSize;
    private boolean generateIndex;
    private boolean generateSiteMap;
    private List<String> templatePrefixes;
    private boolean templateAddLeftMenu;
    private List<String> templateSuffixes;
    private List<PreAction> preActions;
    private boolean skipRendering;
    private String customGems;
    private String projectVersion;
    private String projectName;
    private String projectArtifactId;
    private List<String> requires;
    private AsciidoctorConfiguration asciidoctorConfiguration;
    private Asciidoc asciidoc;
    private boolean reverseBlogOrder;
    private boolean addIndexRegistrationPerCategory;
    private boolean skipIndexTitleDocumentationText;
    private String logoSideText;
    private Map<String, BlogCategoryConfiguration> blogCategoriesCustomizations;
    private Map<String, String> templateExtensionPoints;
    private boolean injectYupiikTemplateExtensionPoints;
    private boolean injectBlogMeta;
    private String blogPublicationDate;
    private OffsetDateTime runtimeBlogPublicationDate;
    /**
     * Number of threads used to render pages and blog pages concurrently.
     * <ul>
     *   <li>{@code -1} (default): virtual threads when available, otherwise {@link java.util.concurrent.ForkJoinPool#commonPool()}.</li>
     *   <li>{@code 0}: sequential rendering (previous behaviour), each page rendered on the calling thread.</li>
     *   <li>{@code > 0}: a fixed thread pool of the given size.</li>
     * </ul>
     */
    @Builder.Default
    private int renderingThreads = -1;
    /**
     * Number of threads used for the LLM embedding computation.
     * <ul>
     *   <li>{@code -3} (default): a dedicated fixed thread pool of {@link Runtime#availableProcessors()} threads, the
     *       "default ONNX behaviour" — one ONNX session at a time per core, so the (bounded) pool that
     *       {@code LlmEmbeddingGenerator} reuses across batches never over-subscribes the CPU with per-batch session
     *       worker threads.</li>
     *   <li>{@code -2}: re-use the {@link #renderingThreads} pool — the exact same instance, so
     *       embeddings share the rendering thread budget instead of doubling it.</li>
     *   <li>{@code -1}: virtual threads when available, otherwise {@link java.util.concurrent.ForkJoinPool#commonPool()}
     *       (a dedicated executor, not the rendering one).</li>
     *   <li>{@code 0}: sequential (previous behaviour), each embedding batch computed on the calling thread.</li>
     *   <li>{@code > 0}: a dedicated fixed thread pool of the given size (not shared with rendering).</li>
     * </ul>
     * When {@code embeddingsThreads > -2} and equals {@link #renderingThreads}, the rendering pool instance is
     * reused instead of allocating a second one.
     */
    @Builder.Default
    private int embeddingsThreads = -3;
    @Builder.Default
    private GravatarConfiguration gravatar = new GravatarConfiguration();
    @Builder.Default
    private boolean llmChatEnabled = false;
    @Builder.Default
    private boolean selfHostLlmAssets = false;
    private String llmModelId = "Llama-3.2-3B-Instruct-q4f16_1-MLC";
    @Builder.Default
    private LlmModelConfiguration llmModel = new LlmModelConfiguration();
    @Builder.Default
    private boolean addCodeCopyButton = true;
    @Builder.Default
    private boolean createADefault404Page = true;
    private boolean createRobotsTxt;
    private boolean createLlmsTxt;

    public void fixConfig() {
        if (requires == null) { // ensure we don't load reveal.js by default since we disabled extraction of gems
            requires = List.of();
        }
        if (blogCategoriesCustomizations == null) {
            blogCategoriesCustomizations = Map.of();
        }
        // the defaults are inherited unless the user explicitly opts out with the "-default" marker
        if (notIndexedPages == null) {
            notIndexedPages = DEFAULT_NOT_INDEXED_PAGES;
        } else {
            final boolean skipDefaults = notIndexedPages.contains("-default");
            final List<String> user = notIndexedPages.stream().filter(it -> !"-default".equals(it)).toList();
            if (skipDefaults) {
                notIndexedPages = user;
            } else {
                final List<String> merged = new ArrayList<>(DEFAULT_NOT_INDEXED_PAGES.size() + user.size());
                merged.addAll(DEFAULT_NOT_INDEXED_PAGES);
                merged.addAll(user);
                notIndexedPages = List.copyOf(merged);
            }
        }

        if (blogPublicationDate == null) {
            runtimeBlogPublicationDate = OffsetDateTime.now();
        } else {
            switch (blogPublicationDate) {
                case "today":
                    runtimeBlogPublicationDate = OffsetDateTime.now();
                    break;
                case "yesterday":
                    runtimeBlogPublicationDate = OffsetDateTime.now().minusDays(1);
                    break;
                case "tomorrow":
                    runtimeBlogPublicationDate = OffsetDateTime.now().plusDays(1);
                    break;
                case "infinite":
                    runtimeBlogPublicationDate = OffsetDateTime.MAX;
                    break;
                default:
                    try {
                        runtimeBlogPublicationDate = OffsetDateTime.parse(blogPublicationDate.strip());
                    } catch (final DateTimeParseException dtpe) {
                        runtimeBlogPublicationDate = LocalDate.parse(blogPublicationDate.strip()).atTime(LocalTime.MIN.atOffset(ZoneOffset.UTC));
                    }
            }
        }

        // ensure writable
        templateExtensionPoints = new HashMap<>(templateExtensionPoints == null ? Map.of() : templateExtensionPoints);
        // ensure there are (dynamic defaults) for built-in custom extension points (empty for not yupiik case)
        // note that for static ones, they can be put in resources
        templateExtensionPoints.putIfAbsent("socialLinks", (injectYupiikTemplateExtensionPoints ? "" +
                // add github
                "<li class=\"list-inline-item\"><a title=\"Github\" href=\"https://www.github.com/{{{linkedInCompany}}}/\"><i class=\"fab fa-github fa-fw\"></i></a></li>" +
                "" : "") +
                // always linkedin
                "<li class=\"list-inline-item\"><a title=\"LinkedIn\" href=\"https://www.linkedin.com/company/{{{linkedInCompany}}}/\"><i class=\"fab fa-linkedin fa-fw\"></i></a></li>");
        templateExtensionPoints.putIfAbsent("copyrightLine", "" +
                "<nav class=\"footer-bar\">" +
                "<small class=\"copyright\">{{{copyright}}}</small>" + (injectYupiikTemplateExtensionPoints ? "" +
                // add terms of service and privacy policy
                "<a class=\"legal-link\" href=\"https://www.{{{linkedInCompany}}}.com/terms-of-use/\">Terms of use</a>" +
                "<a class=\"legal-link\" href=\"https://www.{{{linkedInCompany}}}.com/privacy-policy/\">Privacy policy</a>" : "") +
                "</nav>");
        templateExtensionPoints.putIfAbsent("socialLinksFooter", templateExtensionPoints.get("socialLinks"));
    }

    @Data
    public static class BlogCategoryConfiguration {
        private int order = -1;
        private String homePageName;
        private String icon;
        private String description;
    }

    @Data
    public static class GravatarConfiguration {
        private String url = "https://www.gravatar.com/avatar/%s?d=identicon&size=40";
        private Function<String, String> nameToMailMapper = this::defaultMailMappingStrategy;

        public String defaultMailMappingStrategy(final String name) {
            final String[] segments = name.split(" ");
            return Character.toLowerCase(segments[0].charAt(0)) +
                    Stream.of(segments).skip(1).map(it -> it.replace("-", "").toLowerCase(Locale.ROOT)).collect(joining()) + "@yupiik.com";
        }

        /**
         * Enables to specify how to map an author name to a mail.
         * It uses a properties syntax.
         * {@code default} enables to force the default implementation to be used.
         *
         * @param config configuration in properties format.
         */
        public void setMailMappingStrategy(final String config) { // enables to be set from maven config or any IoC
            if ("default".equals(config)) {
                nameToMailMapper = this::defaultMailMappingStrategy;
                return;
            }
            final Properties props = new Properties();
            try (final StringReader reader = new StringReader(config)) {
                try {
                    props.load(reader);
                } catch (final IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            switch (props.getProperty("strategy", "custom")) {
                case "default":
                    nameToMailMapper = this::defaultMailMappingStrategy;
                    break;
                case "custom":
                default:
                    final String mail = '@' + props.getProperty("mail", "yupiik.com");
                    final boolean firstNameFirstLetter = Boolean.parseBoolean(props.getProperty("firstName.firstLetter", "true"));
                    final boolean firstNameLowerCase = Boolean.parseBoolean(props.getProperty("firstName.lower", "true"));
                    final String lastNameConcatenationSep = props.getProperty("lastName.concatenationSeparator", "");
                    final boolean lastNameStripNotAlphaChars = Boolean.parseBoolean(props.getProperty("lastName.stripNotAlphaChars", "true"));
                    final boolean lastNameLowerCase = Boolean.parseBoolean(props.getProperty("lastName.lower", "true"));
                    final String firstNameLastNameSeparator = props.getProperty("names.separator", "");
                    nameToMailMapper = name -> {
                        final String[] segments = name.split(" ");

                        String firstname = segments[0];
                        if (firstNameFirstLetter) {
                            firstname = firstname.substring(0, 1);
                        }
                        if (firstNameLowerCase) {
                            firstname = firstname.toLowerCase(Locale.ROOT);
                        }

                        final String lastName = Stream.of(segments)
                                .skip(1)
                                .map(it -> {
                                    String value = it;
                                    if (lastNameStripNotAlphaChars) {
                                        value = value.replaceAll("[\\W]", "");
                                    }
                                    if (lastNameLowerCase) {
                                        value = value.toLowerCase(Locale.ROOT);
                                    }
                                    return value;
                                })
                                .collect(joining(lastNameConcatenationSep));
                        return firstname + firstNameLastNameSeparator + lastName + mail;
                    };
            }
        }
    }

    /**
     * Configuration of the LLM feature as a whole, shared by the on-disk embedding computation and the in-browser
     * query embedding / retrieval. A model is not hardcoded: every knob the two sides need is here, so switching the
     * embedding model is a configuration change (the matching ONNX + tokenizer resources simply need to be on the
     * plugin/CLI classpath).
     *
     * <p>Default values keep the historical {@code all-MiniLM-L6-v2} behaviour (mean pooling over the masked tokens,
     * 384 dimensions, no query prefix, cosine + lexical hybrid retrieval with a 0.15 floor).</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmModelConfiguration {
        /**
         * The model identifier written into {@code embeddings.index.json} and consumed by the browser embedder
         * (e.g. transformers.js {@code 'Xenova/all-MiniLM-L6-v2'} or {@code 'onnx-community/mdbr-leaf-ir-ONNX'}).
         */
        @Builder.Default
        private String model = "all-minilm-l6-v2";

        /**
         * Classpath resources of the ONNX graph. A model split between a graph file and external data
         * ({@code model.onnx} + {@code model.onnx_data}) lists both, in order; a single-file model lists one.
         */
        @Builder.Default
        private List<String> onnxResources = List.of("all-minilm-l6-v2.onnx");

        /** Classpath resource of the tokenizer JSON (HF {@code tokenizer.json}). */
        @Builder.Default
        private String tokenizerResource = "all-minilm-l6-v2-tokenizer.json";

        /** Expected size of one embedding vector, used by pooling and written to {@code embeddings.index.json}. */
        private int dimension = 384;

        /**
         * How the embedding is produced from the small model encoder:
         * <ul>
         *   <li>{@link Pooling#MEAN}: average the token vectors weighted by the attention mask (MiniLM).</li>
         *   <li>{@link Pooling#CLS}: take the {@code [CLS]} vector (bge family).</li>
         *   <li>{@link Pooling#BAKED}: the graph already outputs the pooled, normalized embedding
         *       ({@code sentence_embedding}, e.g. mdbr-leaf-ir) - no Java pooling. The ONNX output to read is
         *       {@link #outputIndex}.</li>
         * </ul>
         */
        private Pooling pooling = Pooling.MEAN;

        /** Index of the ONNX output to consume when {@link Pooling#BAKED}. */
        private int outputIndex = 0;

        /**
         * Text prepended to the user query by the browser embedder. Asymmetric models (mdbr-leaf-ir, bge-small)
         * require e.g. {@code "Represent this sentence for searching relevant passages: "}; passages/chunks are
         * embedded without it, so this prefix is applied to queries only.
         */
        private String queryPrefix = "";

        /** Retrieval knobs shared with {@code rag.ts}. */
        @Builder.Default
        private RetrievalConfiguration retrieval = new RetrievalConfiguration();
    }

    /** How the small model encoder output is turned into a single embedding vector. */
    public enum Pooling {
        /** Average the token vectors weighted by the attention mask (MiniLM). */
        MEAN,
        /** Take the {@code [CLS]} vector (bge family). */
        CLS,
        /** The graph already outputs the pooled, normalized embedding; read {@link LlmModelConfiguration#outputIndex}. */
        BAKED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalConfiguration {
        /** Minimum cosine for a chunk to be considered relevant (rag.ts MIN_SCORE, 0.15 historically). */
        @Builder.Default
        private double minScore = 0.15;
        /** Weight of the lexical/keyword score relative to cosine (rag.ts LEX_WEIGHT, 0.5 historically). */
        @Builder.Default
        private double lexWeight = 0.5;
        /** Weight of a synonym match inside the lexical term (rag.ts SYNONYM_WEIGHT, 0.3 historically). */
        @Builder.Default
        private double synonymWeight = 0.3;
        /** How many chunks are sent to the model as context (rag.ts topK, 15 historically). */
        @Builder.Default
        private int topK = 15;
    }
}
