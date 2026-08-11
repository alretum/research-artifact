package de.tum.cit.aet.artemis.hyperion.mcq.retrieval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.SnippetSource;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;

import tools.jackson.core.type.TypeReference;

/**
 * A {@link SnippetSource} backed by an in-memory embedding index with cosine similarity ranking.
 * <p>
 * {@link #index(List)} must be called before {@link #search(String, int, String)}.
 * {@link #indexCached(List, Path)} reuses a previously written index when neither the chunks nor the
 * embedding model have changed.
 */
public class EmbeddingSnippetSource implements SnippetSource {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSnippetSource.class);

    private static final int PROGRESS_INTERVAL = 50;

    private static final String FINGERPRINT_PROBE = "__mcq_embedding_fingerprint_probe__";

    private final EmbeddingModel embeddingModel;

    private final List<Entry> entries = new ArrayList<>();

    private record Entry(Chunk chunk, float[] vector, double norm) {
    }

    public EmbeddingSnippetSource(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embed the given chunks, replacing any previously indexed content.
     *
     * @param chunks chunks to index
     */
    public void index(List<Chunk> chunks) {
        entries.clear();
        for (Chunk chunk : chunks) {
            float[] vector = embeddingModel.embed(chunk.embeddable());
            entries.add(new Entry(chunk, vector, norm(vector)));
            if (entries.size() % PROGRESS_INTERVAL == 0) {
                log.info("Embedded {}/{} chunks", entries.size(), chunks.size());
            }
        }
        log.info("Indexed {} chunks", entries.size());
    }

    /**
     * Index the given chunks, reusing vectors cached at {@code cache} when they are still valid.
     * <p>
     * The cache is keyed on a fingerprint covering both the chunk texts and the embedding model's own
     * output, so changing either the corpus or the model invalidates it without manual intervention.
     *
     * @param chunks chunks to index
     * @param cache  file to read from and write to
     * @return {@code true} when the cache was reused, {@code false} when the chunks were embedded afresh
     */
    public boolean indexCached(List<Chunk> chunks, Path cache) {
        String fingerprint = fingerprint(chunks);
        if (restore(chunks, cache, fingerprint)) {
            log.info("Reused cached index of {} chunks from {}", entries.size(), cache);
            return true;
        }
        index(chunks);
        persist(cache, fingerprint);
        return false;
    }

    private String fingerprint(List<Chunk> chunks) {
        MessageDigest digest = digest();
        for (Chunk chunk : chunks) {
            digest.update(chunk.chunkId().getBytes(StandardCharsets.UTF_8));
            digest.update(chunk.embeddable().getBytes(StandardCharsets.UTF_8));
        }
        for (float value : embeddingModel.embed(FINGERPRINT_PROBE)) {
            digest.update(Float.toString(value).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private boolean restore(List<Chunk> chunks, Path cache, String fingerprint) {
        if (!Files.isRegularFile(cache)) {
            return false;
        }
        Map<String, Object> stored;
        try {
            stored = StructuredOutputs.outputMapper().readValue(Files.readString(cache, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {
            });
        }
        catch (RuntimeException | IOException e) {
            log.warn("Ignoring unreadable index cache {}: {}", cache, e.getMessage());
            return false;
        }
        if (!fingerprint.equals(stored.get("fingerprint"))) {
            log.info("Index cache {} is stale; re-embedding", cache);
            return false;
        }

        Object raw = stored.get("vectors");
        if (!(raw instanceof Map<?, ?> vectors) || vectors.size() != chunks.size()) {
            return false;
        }
        entries.clear();
        for (Chunk chunk : chunks) {
            Object values = vectors.get(chunk.chunkId());
            if (!(values instanceof List<?> list)) {
                entries.clear();
                return false;
            }
            float[] vector = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                vector[i] = ((Number) list.get(i)).floatValue();
            }
            entries.add(new Entry(chunk, vector, norm(vector)));
        }
        return true;
    }

    private void persist(Path cache, String fingerprint) {
        Map<String, List<Float>> vectors = new java.util.LinkedHashMap<>();
        for (Entry entry : entries) {
            List<Float> values = new ArrayList<>(entry.vector().length);
            for (float value : entry.vector()) {
                values.add(value);
            }
            vectors.put(entry.chunk().chunkId(), values);
        }
        try {
            if (cache.getParent() != null) {
                Files.createDirectories(cache.getParent());
            }
            Files.writeString(cache, StructuredOutputs.outputMapper().writeValueAsString(Map.of("fingerprint", fingerprint, "vectors", vectors)), StandardCharsets.UTF_8);
            log.info("Wrote index cache to {}", cache);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to write index cache " + cache, e);
        }
    }

    @Override
    public List<Snippet> search(String query, int limit, String courseKey) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("Index is empty; call index(..) before searching");
        }
        if (courseKey != null) {
            throw new IllegalArgumentException("This source indexes one corpus and cannot scope by course; expected a null courseKey, got '" + courseKey + "'");
        }
        float[] queryVector = embeddingModel.embed(query);
        double queryNorm = norm(queryVector);

        return entries.stream()
                .map(entry -> new Snippet(entry.chunk().lectureName(), entry.chunk().unitName(), entry.chunk().pageRange(), entry.chunk().text(), entry.chunk().chunkId(),
                        entry.chunk().role(), cosine(queryVector, queryNorm, entry.vector(), entry.norm())))
                .sorted(Comparator.comparingDouble(Snippet::score).reversed()).limit(limit).toList();
    }

    /**
     * @return the number of indexed chunks
     */
    public int size() {
        return entries.size();
    }

    private static double cosine(float[] left, double leftNorm, float[] right, double rightNorm) {
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        double dot = 0;
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            dot += (double) left[i] * right[i];
        }
        return dot / (leftNorm * rightNorm);
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }
}
