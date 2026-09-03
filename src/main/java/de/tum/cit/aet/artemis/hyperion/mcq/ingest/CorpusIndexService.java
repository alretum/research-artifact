package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.app.PipelineProperties;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusLoader.DocumentReport;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.TopicCatalogue.Topic;
import de.tum.cit.aet.artemis.hyperion.mcq.retrieval.EmbeddingSnippetSource;

/**
 * Loads, chunks and indexes the corpus once, and holds the result for the lifetime of the application.
 * <p>
 * Building the index costs about twenty seconds when the embedding cache is cold, so both the command line
 * and the web interface share one instance. Loading happens on first access rather than at startup, so tests
 * and commands that need no corpus do not pay for it.
 */
@Service
public class CorpusIndexService {

    private static final Logger log = LoggerFactory.getLogger(CorpusIndexService.class);

    private final PipelineProperties properties;

    private final EmbeddingModel embeddingModel;

    private final ExtractionReportWriter reportWriter;

    private volatile Index index;

    /**
     * The indexed corpus.
     *
     * @param topics          topics available for generation, ungrounded ones excluded
     * @param allTopics       every declared topic, including those with no linked material
     * @param documentReports per-document extraction diagnostics
     */
    public record Index(EmbeddingSnippetSource source, List<Topic> topics, List<Topic> allTopics, List<DocumentReport> documentReports, int chunkCount, boolean cacheReused) {
    }

    public CorpusIndexService(PipelineProperties properties, EmbeddingModel embeddingModel, ExtractionReportWriter reportWriter) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.reportWriter = reportWriter;
    }

    /**
     * The indexed corpus, built on first call.
     *
     * @return the index, shared across callers
     */
    public Index index() {
        Index current = index;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (index == null) {
                index = build();
            }
            return index;
        }
    }

    /**
     * Drop the cached index so the next request rebuilds it.
     * <p>
     * Also deletes the on-disk cache, because it is keyed on a fingerprint of the corpus and the embedding
     * model; leaving a stale file would have the next run silently reuse an index that no longer matches
     * the material. Called after the corpus changes.
     */
    public synchronized void invalidate() {
        index = null;
        java.nio.file.Path cache = java.nio.file.Path.of(properties.chunking().indexPath());
        try {
            if (java.nio.file.Files.deleteIfExists(cache)) {
                log.info("Corpus changed: dropped the cached index at {}; it will be rebuilt on next use", cache);
            }
        }
        catch (java.io.IOException e) {
            log.warn("Could not delete the cached index at {}: {}. Delete it by hand before the next run.", cache, e.getMessage());
        }
    }

    /**
     * @return {@code true} when the corpus has already been indexed
     */
    public boolean isLoaded() {
        return index != null;
    }

    private Index build() {
        Path corpus = Path.of(properties.corpusPath());
        log.info("Loading corpus from {}", corpus.toAbsolutePath());

        CorpusLoader.LoadResult loaded = new CorpusLoader().load(corpus);
        Map<String, SourceRole> roles = new HashMap<>();
        loaded.reports().forEach(report -> roles.put(report.documentId(), report.role()));

        reportWriter.write(Path.of(properties.extractionReportPath()), loaded.reports());
        log.info("Extraction: {} docs, {} pages, ~{} tokens, {} text-poor pages, {} damaged tokens, {} alt-text lines", loaded.reports().size(), loaded.pages().size(),
                loaded.totalApproxTokens(), sum(loaded, DocumentReport::textPoorPages), sum(loaded, DocumentReport::suspectedDamagedTokens),
                sum(loaded, DocumentReport::altTextLines));

        List<Chunk> chunks = new PageChunker(properties.chunking().targetTokens(), properties.chunking().maxTokens()).chunk(loaded.pages(),
                documentId -> roles.getOrDefault(documentId, SourceRole.OTHER));

        Set<String> documentIds = loaded.reports().stream().map(DocumentReport::documentId).collect(Collectors.toSet());
        List<Topic> allTopics = resolveTopics(chunks, documentIds);
        reportWriter.writeTopics(Path.of(properties.topicReportPath()), allTopics);
        allTopics.stream().filter(topic -> !topic.grounded()).forEach(topic -> log.warn("Topic '{}' has no linked material and is skipped", topic.key()));

        EmbeddingSnippetSource source = new EmbeddingSnippetSource(embeddingModel);
        long start = System.nanoTime();
        boolean reused = source.indexCached(chunks, Path.of(properties.chunking().indexPath()));
        log.info("{} {} chunks across {} topics in {} s", reused ? "Loaded" : "Embedded", source.size(), allTopics.size(), (System.nanoTime() - start) / 1_000_000_000);

        return new Index(source, allTopics.stream().filter(Topic::grounded).toList(), allTopics, loaded.reports(), source.size(), reused);
    }

    private List<Topic> resolveTopics(List<Chunk> chunks, Set<String> knownDocuments) {
        String manifestPath = properties.competencyManifest();
        if (manifestPath == null || manifestPath.isBlank()) {
            log.info("No competency manifest configured; deriving topics from corpus folders");
            return TopicCatalogue.fromChunks(chunks);
        }
        CompetencyManifest manifest = CompetencyManifest.load(Path.of(manifestPath));
        List<String> unresolved = manifest.unresolvedLinks(knownDocuments);
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Competency manifest links documents that are not in the corpus: " + unresolved);
        }
        log.info("Loaded {} competencies for course '{}' from {}", manifest.competencies().size(), manifest.course().title(), manifestPath);
        return TopicCatalogue.fromManifest(manifest, chunks);
    }

    private static int sum(CorpusLoader.LoadResult loaded, java.util.function.ToIntFunction<DocumentReport> field) {
        return loaded.reports().stream().mapToInt(field).sum();
    }
}
