package io.yupiik.tools.minisite.action.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmEmbeddingGeneratorTest {

    @Test
    void extractChunksShouldSplitByH2(@TempDir final Path work) throws IOException {
        final var html = """
                <html><head><title>Test Page</title></head>
                <body>
                <div class="page-content-body">
                <h2>Section One</h2>
                <p>Content for section one.</p>
                <h2>Section Two</h2>
                <p>Content for section two.</p>
                </div>
                </body>
                </html>
                """;

        Files.createDirectories(work.resolve("docs"));
        Files.writeString(work.resolve("docs/page.html"), html);

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        final var chunks = gen.extractChunks();

        assertFalse(chunks.isEmpty());

        // order is stable but we avoid brittle assumptions about slicing
        assertEquals("Test Page", chunks.get(0).title);

        assertTrue(chunks.stream().anyMatch(c ->
                c.url.equals("/docs/page.html#0") || c.url.startsWith("/docs/page.html")
        ));

        assertTrue(chunks.stream().anyMatch(c ->
                c.text.contains("Content for section one")
        ));

        assertTrue(chunks.stream().anyMatch(c ->
                c.text.contains("Content for section two")
        ));
    }

    @Test
    void extractChunksShouldUseWholePageWhenNoH2(@TempDir final Path work) throws IOException {
        final var html = """
                <html><head><title>No Headings</title></head>
                <body>
                <div class="page-content-body">
                <p>Single paragraph without any heading.</p>
                </div>
                </body>
                </html>
                """;

        Files.writeString(work.resolve("noheadings.html"), html);

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        final var chunks = gen.extractChunks();

        assertFalse(chunks.isEmpty());
        assertEquals("/noheadings.html#0", chunks.get(0).url);
        assertTrue(chunks.get(0).text.contains("Single paragraph"));
        assertTrue(chunks.get(0).text.contains("Title: No Headings"));
    }

    @Test
    void extractChunksShouldSkipIgnoredPages(@TempDir final Path work) throws IOException {
        final var html = """
                <html><head><title>Skip Me</title></head>
                <body><div class="page-content-body"><p>Test</p></div></body>
                </html>
                """;

        Files.writeString(work.resolve("index.html"), html);
        Files.writeString(work.resolve("404.html"), html);
        Files.writeString(work.resolve("page-2.html"), html);
        Files.writeString(work.resolve("valid.html"), html);

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        final var chunks = gen.extractChunks();

        assertEquals(1, chunks.size());
        assertEquals("/valid.html#0", chunks.get(0).url);
    }

    @Test
    void extractChunksShouldKeepAuthoredIndex(@TempDir final Path work) throws IOException {
        final var html = """
                <html><head><title>%s</title></head>
                <body><div class="page-content-body"><p>%s</p></div></body>
                </html>
                """;

        // authored hub page: has a matching content/sub/index.adoc source
        Files.createDirectories(work.resolve("content/sub"));
        Files.writeString(work.resolve("content/sub/index.adoc"), "= Sub\n\nsome authored content");
        Files.createDirectories(work.resolve("sub"));
        Files.writeString(work.resolve("sub/index.html"), html.formatted("Sub Hub", "Authored hub content"));

        // generated root landing: index.html with content but NO content/index.adoc
        Files.writeString(work.resolve("index.html"), html.formatted("Landing", "Generated landing"));

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        final var chunks = gen.extractChunks();

        assertTrue(chunks.stream().anyMatch(c -> c.url.startsWith("/sub/index.html")),
                "authored sub/index.html (has index.adoc) should be indexed");
        assertFalse(chunks.stream().anyMatch(c -> c.url.startsWith("/index.html")),
                "root index.html (no index.adoc) should be skipped");
    }

    @Test
    void extractChunksShouldSkipMissingContentBody(@TempDir final Path work) throws IOException {
        final var html = """
                <html><head><title>No Body</title></head>
                <body><p>No page-content-body div.</p></body>
                </html>
                """;

        Files.writeString(work.resolve("nobody.html"), html);

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        final var chunks = gen.extractChunks();

        assertTrue(chunks.isEmpty());
    }

    @Test
    void extractChunksShouldHandleLongText(@TempDir final Path work) throws IOException {
        final var longText = "A".repeat(5000);

        final var html = """
                <html><head><title>Long Page</title></head>
                <body>
                <div class="page-content-body">
                <h2>Long Section</h2>
                <p>%s</p>
                </div>
                </body>
                </html>
                """.formatted(longText);

        Files.writeString(work.resolve("long.html"), html);

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        final var chunks = gen.extractChunks();

        assertFalse(chunks.isEmpty());

        assertTrue(chunks.get(0).text.length() <= 2000);

        assertTrue(chunks.stream().anyMatch(c ->
                c.url.startsWith("/long.html")
        ));
    }

    @Test
    void writeEmbeddingsShouldProduceCorrectStructure(@TempDir final Path work) throws IOException {
        final var chunks = List.of(
                new LlmEmbeddingGenerator.Chunk("hash1", "text1", "/url1", "Title1", new float[]{0.1f, 0.2f, 0.3f}),
                new LlmEmbeddingGenerator.Chunk("hash2", "text2", "/url2", "Title2", new float[]{0.4f, 0.5f, 0.6f}));

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        gen.writeEmbeddings(chunks);

        final var embedDir = work.resolve("assets/llm/embeddings");
        assertTrue(Files.isDirectory(embedDir));

        final var chunkFile = embedDir.resolve("chunk-0001.json");
        assertTrue(Files.exists(chunkFile));

        final var content = Files.readString(chunkFile);
        assertTrue(content.contains("hash1"));
        assertTrue(content.contains("hash2"));
        assertTrue(content.contains("text1"));
        assertTrue(content.contains("text2"));
    }

    @Test
    void writeEmbeddingsShouldProduceIndexFile(@TempDir final Path work) throws IOException {
        final var chunks = List.of(
                new LlmEmbeddingGenerator.Chunk("hash1", "text1", "/url1", "Title1", new float[]{0.1f, 0.2f, 0.3f}),
                new LlmEmbeddingGenerator.Chunk("hash2", "text2", "/url2", "Title2", new float[]{0.4f, 0.5f, 0.6f}));

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        gen.writeEmbeddings(chunks);

        final var indexFile = work.resolve("assets/llm/embeddings/embeddings.index.json");
        assertTrue(Files.exists(indexFile), "embeddings.index.json must be generated for the browser loader");

        final var content = Files.readString(indexFile);
        assertTrue(content.contains("\"chunk-0001.json\""), content);
        assertTrue(content.contains("\"totalChunks\":2") || content.contains("\"totalChunks\": 2"), content);
        assertTrue(content.contains("\"dimension\":384") || content.contains("\"dimension\": 384"), content);
        assertTrue(content.contains("all-minilm-l6-v2"), content);
    }

    @Test
    void computeEmbeddingsShouldBeSemantic(@TempDir final Path work) {
        final var question = new LlmEmbeddingGenerator.Chunk("q", "How do I configure the cache size in the server settings?", "/q", "q", null);
        final var paraphrase = new LlmEmbeddingGenerator.Chunk("p", "Setting the cache size through the server configuration options.", "/p", "p", null);
        final var unrelated = new LlmEmbeddingGenerator.Chunk("u", "The weather today is sunny with a slight chance of afternoon rain.", "/u", "u", null);

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        gen.computeEmbeddings(List.of(question, paraphrase, unrelated));

        final var simRelated = cosine(question.embedding, paraphrase.embedding);
        final var simUnrelated = cosine(question.embedding, unrelated.embedding);

        assertTrue(simRelated > simUnrelated,
                "paraphrase (" + simRelated + ") should be more similar than unrelated (" + simUnrelated + ")");
    }

    @Test
    void buildOverviewChunkShouldListDistinctTitles(@TempDir final Path work) {
        final var chunks = List.of(
                new LlmEmbeddingGenerator.Chunk("a", "t", "/http.html#0", "HTTP Client", null),
                new LlmEmbeddingGenerator.Chunk("b", "t", "/http.html#1", "HTTP Client", null),
                new LlmEmbeddingGenerator.Chunk("c", "t", "/json.html#0", "JSON", null));

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        final var overview = gen.buildOverviewChunk(chunks);

        assertEquals("Overview", overview.title);
        assertEquals("/", overview.url);
        assertTrue(overview.text.contains("HTTP Client"), overview.text);
        assertTrue(overview.text.contains("JSON"), overview.text);
        // distinct: "HTTP Client" must appear only once
        assertEquals(1, overview.text.split("HTTP Client", -1).length - 1, overview.text);
    }

    @Test
    void writeEmbeddingsShouldHandleManyChunks(@TempDir final Path work) throws IOException {
        final var chunks = new ArrayList<LlmEmbeddingGenerator.Chunk>();

        for (int i = 0; i < 250; i++) {
            chunks.add(new LlmEmbeddingGenerator.Chunk(
                    "hash-" + i,
                    "text-" + i,
                    "/url-" + i,
                    "Title-" + i,
                    null
            ));
        }

        final var gen = new LlmEmbeddingGenerator(Map.of(), work, work);
        gen.writeEmbeddings(chunks);

        final var embedDir = work.resolve("assets/llm/embeddings");
        assertTrue(Files.exists(embedDir.resolve("chunk-0001.json")));
    }

    private static double cosine(final float[] a, final float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        final var denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }
}
