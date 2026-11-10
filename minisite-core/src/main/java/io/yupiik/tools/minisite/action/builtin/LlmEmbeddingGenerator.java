package io.yupiik.tools.minisite.action.builtin;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.yupiik.tools.minisite.MiniSiteConfiguration;
import io.yupiik.tools.minisite.MiniSiteConfiguration.Pooling;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.config.PropertyOrderStrategy;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

// Generates the documentation embeddings consumed by the in-browser LLM chat (see the
// frontend under minisite-core/src/main/frontend/llm and the minisite-llm.adoc doc page).
// Runs at minisite render time when llmChatEnabled is set: it reads the generated HTML,
// chunks the textual content, embeds each chunk with the bundled all-MiniLM-L6-v2 ONNX
// model (mean pooling + L2 normalization) and writes the chunk + index JSON the browser
// fetches to perform retrieval-augmented chat.
public class LlmEmbeddingGenerator implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(LlmEmbeddingGenerator.class.getName());

    /**
     * Sentinel executor marking sequential batch computation on the calling thread, used to distinguish the
     * {@code null}-executor default from an explicitly sequential mode when comparing executor identity.
     */
    public static final Executor SEQUENTIAL = Runnable::run;

    // model output shape is depended on by the JS consumer; defaults keep all-MiniLM-L6-v2 behaviour
    private static final int EMBEDDING_DIM = 384;

    // chunking constants
    private static final int MAX_CHUNK_CHARS = 800;
    private static final int CHUNK_OVERLAP = 120;

    // tokenizer normalization patterns
    private static final Pattern NORMALIZE_CLEAN = Pattern.compile("[\\p{InCombiningDiacriticalMarks}]");
    private static final Pattern NORMALIZE_CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern PRE_TOKENIZE = Pattern.compile("[\\s\\p{Punct}]+");

    // Running session.run() once per chunk means N JNI round-trips and N sets of tensor
    // allocations. Batching amortises that cost: 32 holds 32 x 128 x 3 longs ~ 100 KB but
    // throughput is typically 10-20x better than batch=1.
    private static final int BATCH_SIZE = 32;

    private final MiniSiteConfiguration.LlmModelConfiguration llmModel;
    private final Path sourceBase;
    private final Path outputBase;
    private final int maxLength;
    private final Predicate<Path> ignoredFile;
    // Where the embedding batches run. null / Runnable::run means sequential on the calling thread
    // (previous behaviour); any real executor is given one task per ONNX batch and awaited.
    private final Executor embeddingExecutor;

    public LlmEmbeddingGenerator(final Map<String, String> configuration,
                                 final Path sourceBase,
                                 final Path outputBase) {
        this(configuration, sourceBase, outputBase, p -> false);
    }

    /**
     * @param ignoredFile predicate over the HTML file; pages matching it are not embedded. Wired from
     *                    {@code notIndexedPages} so pages excluded from search/index (e.g. mass-generated detail
     *                    pages) are also skipped by the CPU-heavy embedding pass instead of being parsed and embedded
     *                    for nothing.
     */
    public LlmEmbeddingGenerator(final Map<String, String> configuration,
                                 final Path sourceBase,
                                 final Path outputBase,
                                 final Predicate<Path> ignoredFile) {
        this(configuration, sourceBase, outputBase, ignoredFile, null);
    }

    /**
     * @param embeddingExecutor executor used to run the ONNX batch inference concurrently; {@code null} (or a
     *                          {@link Runnable#run()}-executing executor) falls back to sequential computation on the
     *                          calling thread. A real executor receives one task per batch of {@link #BATCH_SIZE}
     *                          chunks; the caller is responsible for its lifecycle but the computation is awaited here
     *                          before {@link #run()} returns.
     */
    public LlmEmbeddingGenerator(final Map<String, String> configuration,
                                 final Path sourceBase,
                                 final Path outputBase,
                                 final Predicate<Path> ignoredFile,
                                 final Executor embeddingExecutor) {
        this(new MiniSiteConfiguration.LlmModelConfiguration(), configuration, sourceBase, outputBase, ignoredFile, embeddingExecutor);
    }

    /**
     * Configurable model variant: {@code llmModel} drives the ONNX/tokenizer classpath resources, the embedding
     * dimension, the pooling mode and the model id written in {@code embeddings.index.json} (and, upstream, the
     * in-browser embedder model id shipped to the page templates).
     */
    public LlmEmbeddingGenerator(final MiniSiteConfiguration.LlmModelConfiguration llmModel,
                                 final Map<String, String> configuration,
                                 final Path sourceBase,
                                 final Path outputBase,
                                 final Predicate<Path> ignoredFile,
                                 final Executor embeddingExecutor) {
        checkModel(llmModel);
        this.llmModel = llmModel == null ? new MiniSiteConfiguration.LlmModelConfiguration() : llmModel;
        this.sourceBase = sourceBase;
        this.outputBase = outputBase;
        this.maxLength = Integer.parseInt(configuration.getOrDefault("maxLength", "128"));
        this.ignoredFile = ignoredFile == null ? p -> false : ignoredFile;
        this.embeddingExecutor = embeddingExecutor;
    }

    private static void checkModel(final MiniSiteConfiguration.LlmModelConfiguration llmModel) {
        final var model = llmModel == null ? new MiniSiteConfiguration.LlmModelConfiguration() : llmModel;
        final var cl = LlmEmbeddingGenerator.class.getClassLoader();
        final boolean missingOnnx = model.getOnnxResources().stream().anyMatch(r -> cl.getResource(r) == null);
        if (missingOnnx || cl.getResource(model.getTokenizerResource()) == null) {
            throw new IllegalStateException("Embedding model missing on the classpath.\n" +
                    "Check that the ONNX resources " + model.getOnnxResources() + " and the tokenizer \"" +
                    model.getTokenizerResource() + "\" are available (e.g. add the matching langchain4j " +
                    "embedding dependency as a plugin dependency).");
        }
    }

    // =========================================================
    // ENTRY POINT
    // =========================================================

    @Override
    public void run() {
        final var chunks = extractChunks();
        if (chunks.isEmpty()) {
            return;
        }
        // Prepend a synthetic overview chunk listing the documented topics so vague
        // meta-questions ("what is this about?", "what are the main features?") have
        // something to match — otherwise they retrieve scattered, low-similarity chunks.
        final var overview = buildOverviewChunk(chunks);
        if (overview != null) {
            chunks.add(0, overview);
        }
        computeEmbeddings(chunks);
        writeEmbeddings(chunks);
    }

    Chunk buildOverviewChunk(final List<Chunk> chunks) {
        final var titles = new LinkedHashSet<String>();
        for (final var c : chunks) {
            if (c.title != null && !c.title.isBlank()) {
                titles.add(c.title.strip());
            }
        }
        if (titles.isEmpty()) {
            return null;
        }
        final var text = String.format(
                "Title: Overview\nContent:\nThis documentation covers the following topics, modules and features: %s.",
                String.join(", ", titles));
        return new Chunk(hash(text, "/#overview"), text, "/", "Overview", null);
    }

    // =========================================================
    // CHUNKING
    // =========================================================

    List<Chunk> extractChunks() {
        final var chunks = new ArrayList<Chunk>();
        try {
            Files.walkFileTree(outputBase, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".html") && !isIgnored(file)) {
                        chunks.addAll(extractFromHtml(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return chunks;
    }

    private boolean isIgnored(final Path file) {
        if (ignoredFile.test(file)) {
            return true;
        }
        final var name = file.getFileName().toString();
        if (name.equals("404.html") || name.startsWith("page-")) {
            return true;
        }
        if (name.equals("index.html")) {
            // Keep authored index pages (those backed by an index.adoc source, e.g.
            // fusion/index.html with its overview/features list); drop generated landing
            // pages (root index.html, section auto-TOCs) which have no .adoc source.
            return !hasAdocSource(file);
        }
        return false;
    }

    private boolean hasAdocSource(final Path htmlFile) {
        final var rel = outputBase.relativize(htmlFile).toString().replace('\\', '/');
        final var adocRel = rel.substring(0, rel.length() - ".html".length()) + ".adoc";
        return Files.exists(sourceBase.resolve("content").resolve(adocRel));
    }

    private List<Chunk> extractFromHtml(final Path file) throws IOException {
        final var doc = Jsoup.parse(file.toFile());
        final var title = doc.title();
        // todo: use site base path before or take it from the configuration as optional?
        final var url = "/" + outputBase.relativize(file).toString().replace('\\', '/');
        final var body = doc.selectFirst(".page-content-body");
        if (body == null) {
            return List.of();
        }
        final var text = body.text();
        if (text.isBlank()) {
            return List.of();
        }
        return splitIntoChunks(text, title, url);
    }

    private List<Chunk> splitIntoChunks(final String text, final String title, final String url) {
        final var result = new ArrayList<Chunk>();
        int start = 0;
        int idx = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_CHARS, text.length());
            if (end < text.length()) {
                final int lastDot = text.lastIndexOf('.', end);
                if (lastDot > start + 200) {
                    end = lastDot + 1;
                }
            }
            final var slice = String.format("Title: %s\nContent:\n%s", title, text.substring(start, end)).strip();
            if (!slice.isBlank()) {
                // anchor the url with the chunk index so links/ids are stable and unique
                final var anchorUrl = url + "#" + idx++;
                result.add(new Chunk(hash(slice, anchorUrl), slice, anchorUrl, title, null));
            }
            // Reached the end of the text: stop. Without this, short texts (shorter than
            // CHUNK_OVERLAP) would advance by a single character and re-emit near-duplicate
            // fragment chunks ("Test", "est", "st", "t"), flooding the index with noise.
            if (end >= text.length()) {
                break;
            }
            int nextStart = end - CHUNK_OVERLAP;
            if (nextStart <= start) {
                nextStart = end; // overlap larger than progress: move on instead of re-slicing
            }
            start = nextStart;
        }
        return result;
    }

    void computeEmbeddings(final List<Chunk> chunks) {
        try {
            final var cl = getClass().getClassLoader();
            final var vocab = loadVocab(cl.getResourceAsStream(llmModel.getTokenizerResource()));
            final var env = OrtEnvironment.getEnvironment();

            // Session source: either the raw bytes of a single-file model, or the path of an
            // external-data graph extracted to a temp dir (so ORT finds the sibling .onnx_data).
            final var resources = llmModel.getOnnxResources();
            if (resources.size() == 1) {
                final byte[] modelBytes;
                try (var stream = cl.getResourceAsStream(resources.get(0))) {
                    if (stream == null) {
                        throw new IllegalStateException("Missing embedding model resource: " + resources.get(0));
                    }
                    modelBytes = stream.readAllBytes();
                }
                computeEmbeddings(chunks, env, vocab, modelBytes, null);
            } else {
                final var dir = Files.createTempDirectory("minisite-embedding-model");
                try {
                    for (final var resource : resources) {
                        try (var stream = cl.getResourceAsStream(resource)) {
                            if (stream == null) {
                                throw new IllegalStateException("Missing embedding model resource: " + resource);
                            }
                            Files.write(dir.resolve(Path.of(resource).getFileName()), stream.readAllBytes());
                        }
                    }
                    computeEmbeddings(chunks, env, vocab, null, dir.resolve(Path.of(resources.get(0)).getFileName()));
                } finally {
                    // best-effort cleanup of the extracted model
                    try {
                        try (var paths = Files.walk(dir)) {
                            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (final IOException e) {
                                    // best-effort
                                }
                            });
                        }
                    } catch (final IOException ignored) {
                        // best-effort
                    }
                }
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void computeEmbeddings(final List<Chunk> chunks,
                                   final OrtEnvironment env,
                                   final Map<String, Integer> vocab,
                                   final byte[] modelBytes,
                                   final Path modelPath) throws Exception {
        final int clsId = vocab.getOrDefault("[CLS]", 101);
        final int sepId = vocab.getOrDefault("[SEP]", 102);
        final int padId = vocab.getOrDefault("[PAD]", 0);
        final int unkId = vocab.getOrDefault("[UNK]", 100);

        // A single session.run() with batchSize=32 is much faster than 32 individual
        // runs: ONNX Runtime parallelises the matrix mults across the batch dimension
        // and the JNI overhead is paid only once.
        if (embeddingExecutor == null || embeddingExecutor == SEQUENTIAL) {
            // sequential (previous behaviour): each batch computed on the calling thread with a single shared session
            try (var session = openSession(env, modelBytes, modelPath)) {
                for (int base = 0; base < chunks.size(); base += BATCH_SIZE) {
                    computeBatch(session, env, vocab, clsId, sepId, padId, unkId, chunks, base);
                }
            }
        } else {
            // concurrent batches must each own their session: ONNX Runtime is NOT deterministic when multiple
            // threads call run() on the same session concurrently, so a per-batch session keeps the embeddings
            // bit-identical to the sequential run while still parallelising across the executor.
            parallelComputeBatches(modelBytes, modelPath, env, vocab, clsId, sepId, padId, unkId, chunks);
        }
    }

    private OrtSession openSession(final OrtEnvironment env, final byte[] modelBytes, final Path modelPath)
            throws Exception {
        final var options = new OrtSession.SessionOptions();
        try (options) {
            if (modelPath != null) {
                return env.createSession(modelPath.toFile().getAbsolutePath(), options);
            }
            return env.createSession(modelBytes, options);
        }
    }

    /**
     * Runs one ONNX batch and writes the resulting embeddings into {@code chunks} at {@code [base, base+bSize)}.
     * Each chunk is written by exactly one task at a distinct index, so concurrent invocations on distinct ranges
     * are safe (no shared mutable state except the shared, read-only {@code session}/{@code vocab}).
     */
    private void computeBatch(final OrtSession session,
                              final OrtEnvironment env,
                              final Map<String, Integer> vocab,
                              final int clsId,
                              final int sepId,
                              final int padId,
                              final int unkId,
                              final List<Chunk> chunks,
                              final int base) throws Exception {
        final int end = Math.min(base + BATCH_SIZE, chunks.size());
        final int bSize = end - base;

        final long[] inputIds = new long[bSize * maxLength];
        final long[] masks = new long[bSize * maxLength];

        // tokenise all chunks in this batch
        for (int b = 0; b < bSize; b++) {
            final int[] tokens = tokenize(chunks.get(base + b).text, vocab, clsId, sepId, padId, unkId);
            final int offset = b * maxLength;
            for (int i = 0; i < tokens.length && i < maxLength; i++) {
                inputIds[offset + i] = tokens[i];
                masks[offset + i] = 1L;
            }
            // remaining positions stay 0 (padding) — Java zero-initialises arrays
        }

        final long[] shape = {bSize, maxLength};
        try (var inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape);
             var maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(masks), shape);
             var typeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[bSize * maxLength]), shape)) {

            final var inputs = new HashMap<String, OnnxTensor>();
            inputs.put("input_ids", inputTensor);
            inputs.put("attention_mask", maskTensor);
            inputs.put("token_type_ids", typeTensor);

            try (final var result = session.run(inputs)) {
                // The first output (or llmModel.outputIndex when pooling="baked") is the embedding source.
                // all-MiniLM-L6-v2's first output is last_hidden_state [batch, seq, 384]; the canonical
                // sentence embedding is the MEAN over the attention-masked tokens (NOT [CLS]). mdbr-leaf-ir
                // exposes a pre-pooled, L2-normalised "sentence_embedding" output (dim 768). The browser
                // query embedder (transformers.js, pooling: {type: poolingMode, normalize}) must do the same
                // to share a vector space, so the model config (pooling + dimension + outputIndex) is what
                // keeps both sides consistent.
                final Pooling pooling = llmModel.getPooling() == null ? Pooling.MEAN : llmModel.getPooling();
                final int dim = llmModel.getDimension() <= 0 ? EMBEDDING_DIM : llmModel.getDimension();
                final int outputIndex = llmModel.getOutputIndex();

                final OnnxTensor outputTensor = (OnnxTensor) (pooling == Pooling.BAKED ? result.get(outputIndex) : result.get(0));
                float[] data;
                if (outputTensor.getInfo().getShape().length == 2) {
                    data = new float[(int) outputTensor.getInfo().getNumElements()];
                    outputTensor.getFloatBuffer().get(data);
                } else {
                    data = flatten((float[][][]) outputTensor.getValue());
                }

                final float[] pooled = new float[bSize * dim]; // already pooled per row when baked
                if (pooling == Pooling.BAKED) {
                    System.arraycopy(data, 0, pooled, 0, pooled.length);
                } else {
                    final int perItem = data.length / bSize;
                    final int tokenCount = perItem / dim;
                    if (tokenCount <= 1) {
                        // already pooled output [batch, dim]
                        System.arraycopy(data, 0, pooled, 0, pooled.length);
                    } else if (pooling == Pooling.CLS) {
                        // [CLS] token per row (bge family style)
                        for (int b = 0; b < bSize; b++) {
                            System.arraycopy(data, b * perItem, pooled, b * dim, dim);
                        }
                    } else {
                        // mean over the attention-masked tokens
                        for (int b = 0; b < bSize; b++) {
                            final int baseDim = b * dim;
                            int counted = 0;
                            for (int t = 0; t < tokenCount; t++) {
                                final boolean masked = tokenCount == maxLength && masks[b * maxLength + t] == 0L;
                                if (masked) {
                                    continue;
                                }
                                final int offset = (b * tokenCount + t) * dim;
                                for (int d = 0; d < dim; d++) {
                                    pooled[baseDim + d] += data[offset + d];
                                }
                                counted++;
                            }
                            if (counted > 0) {
                                for (int d = 0; d < dim; d++) {
                                    pooled[baseDim + d] /= counted;
                                }
                            }
                        }
                    }
                }

                for (int b = 0; b < bSize; b++) {
                    final var emb = new float[dim];
                    System.arraycopy(pooled, b * dim, emb, 0, dim);
                    normalize(emb);
                    chunks.get(base + b).embedding = emb;
                }
            }
        }
    }

    /**
     * Dispatches one task per ONNX batch to {@link #embeddingExecutor}, waits for all of them and rethrows the first
     * failure (with the others as suppressed) so the caller sees a failure just like the sequential path does.
     * Completed batches are written in-place into {@code chunks} at their own indices, so completion order is irrelevant.
     *
     * <p>The ONNX sessions are created once and shared across the batches through a bounded pool of a
     * {@code #maxConcurrentSessions(byte[])}-sized reuse. ONNX Runtime is not deterministic when multiple threads
     * call {@code run()} on the same session concurrently, so each session is handed to one batch at a time and
     * returned to the pool afterwards; the pool bounds how many batches run at once, keeping the results
     * bit-identical to the sequential run while not spawning an unbounded number of sessions (each session also
     * creates its own ONNX worker threads, so capping the sessions to the core count avoids over-subscribing the
     * CPU the way one fresh session per batch on an unbounded executor does).</p>
     */
    private void parallelComputeBatches(final byte[] modelBytes,
                                        final Path modelPath,
                                        final OrtEnvironment env,
                                        final Map<String, Integer> vocab,
                                        final int clsId,
                                        final int sepId,
                                        final int padId,
                                        final int unkId,
                                        final List<Chunk> chunks) {
        final int batchCount = (chunks.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        if (batchCount == 0) {
            return;
        }
        final int poolSize = Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()), batchCount);
        final var sessions = new ArrayBlockingQueue<OrtSession>(poolSize);
        final var done = new CountDownLatch(batchCount);
        final var failure = new AtomicReference<Throwable>();
        try {
            final var options = new OrtSession.SessionOptions();
            try (options) {
                for (int i = 0; i < poolSize; i++) {
                    try {
                        sessions.add(openSession(env, modelBytes, modelPath));
                    } catch (final Exception e) {
                        throw new IllegalStateException("Unable to create an ONNX session for the embeddings", e);
                    }
                }
            }
            for (int base = 0; base < chunks.size(); base += BATCH_SIZE) {
                final int b = base;
                embeddingExecutor.execute(() -> {
                    OrtSession session = null;
                    try {
                        session = sessions.take();
                        computeBatch(session, env, vocab, clsId, sepId, padId, unkId, chunks, b);
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        failure.accumulateAndGet(ie, (acc, cur) -> {
                            if (acc == null) {
                                return cur;
                            }
                            acc.addSuppressed(cur);
                            return acc;
                        });
                    } catch (final Throwable t) {
                        LOGGER.log(Level.FINE, "Embedding batch failed", t);
                        failure.accumulateAndGet(t, (acc, cur) -> {
                            if (acc == null) {
                                return cur;
                            }
                            acc.addSuppressed(cur);
                            return acc;
                        });
                    } finally {
                        if (session != null) {
                            sessions.offer(session); // back to the pool for the next batch, one session per concurrent batch
                        }
                        done.countDown();
                    }
                });
            }
            try {
                done.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while computing embeddings", e);
            }
        } finally {
            sessions.forEach(s -> {
                try {
                    s.close();
                } catch (final Throwable e) {
                    LOGGER.log(Level.FINE, "Unable to close an embedding ONNX session", e);
                }
            });
        }
        final var f = failure.get();
        if (f != null) {
            throw new IllegalStateException("Embedding computation failed", f);
        }
    }

    private static float[] flatten(final float[][][] array) {
        int total = 0;
        for (final var mat : array) {
            for (final var row : mat) {
                total += row.length;
            }
        }
        final var result = new float[total];
        int idx = 0;
        for (final var mat : array) {
            for (final var row : mat) {
                System.arraycopy(row, 0, result, idx, row.length);
                idx += row.length;
            }
        }
        return result;
    }

    private static float[] flatten(final float[][] array) {
        int total = 0;
        for (final var row : array) {
            total += row.length;
        }
        final var result = new float[total];
        int idx = 0;
        for (final var row : array) {
            System.arraycopy(row, 0, result, idx, row.length);
            idx += row.length;
        }
        return result;
    }

    private static void normalize(final float[] v) {
        float n = 0;
        for (final var f : v) {
            n += f * f;
        }
        n = (float) Math.sqrt(n);
        if (n == 0) {
            return;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= n;
        }
    }

    // TOKENIZER — WordPiece subword tokenisation

    // all-MiniLM-L6-v2 uses WordPiece: unknown whole-words are broken into known sub-pieces
    // (e.g. "embeddings" -> "em", "##bed", "##ding", "##s"). Falling back to [UNK] for every
    // out-of-vocabulary word collapses many semantically rich tokens into a single
    // placeholder, degrading cosine similarity significantly. This is a minimal pure-Java
    // WordPiece matching the HuggingFace fast-tokenizer output closely enough for retrieval.
    private int[] tokenize(final String text, final Map<String, Integer> vocab,
                           final int cls, final int sep, final int pad, final int unk) {
        final var tokens = new ArrayList<Integer>();
        tokens.add(cls);

        // basic normalisation: lower-case, strip accents, collapse control chars
        var normalised = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD);
        normalised = NORMALIZE_CLEAN.matcher(normalised).replaceAll("");
        normalised = NORMALIZE_CONTROL.matcher(normalised).replaceAll(" ");

        for (final var word : PRE_TOKENIZE.split(normalised)) {
            if (word.isBlank()) {
                continue;
            }

            // try full word first
            if (vocab.containsKey(word)) {
                tokens.add(vocab.get(word));
            } else {
                // WordPiece greedy left-to-right
                boolean bad = false;
                final var subTokens = new ArrayList<Integer>();
                int start = 0;
                while (start < word.length()) {
                    int end = word.length();
                    Integer found = null;
                    while (start < end) {
                        final var sub = (start == 0 ? "" : "##") + word.substring(start, end);
                        if (vocab.containsKey(sub)) {
                            found = vocab.get(sub);
                            break;
                        }
                        end--;
                    }
                    if (found == null) {
                        bad = true;
                        break;
                    }
                    subTokens.add(found);
                    start = end;
                }
                if (bad) {
                    tokens.add(unk);
                } else {
                    tokens.addAll(subTokens);
                }
            }

            if (tokens.size() >= maxLength - 1) {
                break;
            }
        }

        tokens.add(sep);
        return tokens.stream().mapToInt(i -> i).toArray();
    }

    private static Map<String, Integer> loadVocab(final InputStream stream) throws Exception {
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
             var jsonb = JsonbBuilder.create()) {

            @SuppressWarnings("unchecked")
            final var root = (Map<String, Object>) jsonb.fromJson(reader, Map.class);
            @SuppressWarnings("unchecked")
            final var model = (Map<String, Object>) root.get("model");
            @SuppressWarnings("unchecked")
            final var vocab = (Map<String, Object>) model.get("vocab");

            final var map = new HashMap<String, Integer>(vocab.size() * 2);
            for (final var e : vocab.entrySet()) {
                map.put(e.getKey(), ((Number) e.getValue()).intValue());
            }
            return map;
        }
    }

    void writeEmbeddings(final List<Chunk> chunks) {
        final var dir = outputBase.resolve("assets/llm/embeddings");
        final var chunkFile = "chunk-0001.json";
        try {
            Files.createDirectories(dir);
            try (var jsonb = JsonbBuilder.create(
                    new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL));
                 var w = Files.newBufferedWriter(dir.resolve(chunkFile))) {
                jsonb.toJson(chunks, w);
            }
            // The browser loader fetches this index first to discover the chunk files and
            // the model/dimension. Without it nothing loads (see embeddings.ts:fetchIndex).
            try (var jsonb = JsonbBuilder.create(
                    new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL));
                 var w = Files.newBufferedWriter(dir.resolve("embeddings.index.json"))) {
                jsonb.toJson(new EmbeddingIndex(llmModel.getModel(), llmModel.getDimension() <= 0 ? EMBEDDING_DIM : llmModel.getDimension(),
                        List.of(chunkFile), chunks.size()), w);
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hash(final String text, final String url) {
        try {
            final var md = MessageDigest.getInstance("SHA-256");
            md.update(text.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(url.getBytes(StandardCharsets.UTF_8));
            return hex(md.digest());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(final byte[] bytes) {
        final var hex = "0123456789abcdef".toCharArray();
        final var out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = hex[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = hex[bytes[i] & 0xF];
        }
        return new String(out);
    }

    // Fields are public so JSON-B (Yasson) actually serializes them; with package-private
    // fields and no getters the chunk file would be written as empty {} objects, shipping
    // no embeddings/text to the browser at all.
    public static class Chunk {
        public String id;
        public String text;
        public String url;
        public String title;
        public float[] embedding;

        Chunk(final String id, final String text, final String url, final String title, final float[] embedding) {
            this.id = id;
            this.text = text;
            this.url = url;
            this.title = title;
            this.embedding = embedding;
        }
    }

    // Shape consumed by the browser loader (frontend embeddings.ts: EmbeddingIndex).
    public static class EmbeddingIndex {
        public String model;
        public int dimension;
        public List<String> files;
        public int totalChunks;

        public EmbeddingIndex() {
            // no-op
        }

        EmbeddingIndex(final String model, final int dimension, final List<String> files, final int totalChunks) {
            this.model = model;
            this.dimension = dimension;
            this.files = files;
            this.totalChunks = totalChunks;
        }
    }
}
