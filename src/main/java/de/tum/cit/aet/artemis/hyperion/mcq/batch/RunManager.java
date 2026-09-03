package de.tum.cit.aet.artemis.hyperion.mcq.batch;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import de.tum.cit.aet.artemis.hyperion.mcq.app.PipelineProperties;
import de.tum.cit.aet.artemis.hyperion.mcq.batch.BatchRunner.TopicQuery;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusIndexService;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.ModelRegistry;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.TopicCatalogue.Topic;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.telemetry.RunExporter;

/**
 * Starts, observes and stops runs in the background.
 * <p>
 * At most one run executes at a time; starting another while one is active is rejected. Stopping is
 * cooperative: the runner finishes whatever it has already claimed and records the result, then stops
 * claiming further work.
 */
@Service
public class RunManager {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);

    private final PipelineProperties properties;

    private final CorpusIndexService corpus;

    private final GroundingAssemblyService groundingAssembly;

    private final McqGenerationService generation;

    private final McqFilterService filter;

    private final ModelRegistry models;

    private final RunStore store;

    private final RunExporter exporter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcq-run");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Active active;

    /** A run currently executing. */
    private record Active(String runId, String description, Instant startedAt, AtomicBoolean stopRequested, Future<?> future) {
    }

    /**
     * What a caller wants generated.
     *
     * @param topicKeys     topics to generate for; empty means every grounded topic
     * @param itemsPerTopic items to add for each selected topic
     * @param concurrency   workers, or {@code null} to use the configured default
     */
    public record StartRequest(List<String> topicKeys, int itemsPerTopic, Integer concurrency) {
    }

    /**
     * Progress of a run.
     *
     * @param running     whether this run is the one currently executing
     * @param stopping    whether a stop has been requested but work is still finishing
     * @param stateCounts item counts by state
     */
    public record Progress(String runId, boolean running, boolean stopping, boolean complete, Map<String, Integer> stateCounts, String description, Instant startedAt) {

        /**
         * @return items the run contains in total
         */
        @JsonProperty
        public int total() {
            return stateCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        /**
         * @return items that have reached a terminal state, judged or permanently failed
         */
        @JsonProperty
        public int done() {
            return stateCounts.getOrDefault("FILTERED", 0) + stateCounts.getOrDefault("FAILED_GENERATION", 0) + stateCounts.getOrDefault("FAILED_FILTER", 0);
        }

        /**
         * @return completion as a whole-number percentage
         */
        @JsonProperty
        public int percent() {
            return total() == 0 ? 0 : done() * 100 / total();
        }
    }

    public RunManager(PipelineProperties properties, CorpusIndexService corpus, GroundingAssemblyService groundingAssembly, McqGenerationService generation,
            McqFilterService filter, ModelRegistry models, RunStore store, RunExporter exporter) {
        this.properties = properties;
        this.corpus = corpus;
        this.groundingAssembly = groundingAssembly;
        this.generation = generation;
        this.filter = filter;
        this.models = models;
        this.store = store;
        this.exporter = exporter;
    }

    /**
     * Start a new run.
     *
     * @param request what to generate
     * @return the new run's identifier
     * @throws IllegalStateException if a run is already executing
     */
    public synchronized String start(StartRequest request) {
        requireIdle();
        String runId = UUID.randomUUID().toString().substring(0, 8);
        List<Topic> topics = selectTopics(request.topicKeys());
        String description = describe(topics, request.itemsPerTopic());

        BatchRunner runner = runnerFor(runId, request.concurrency());
        store.registerRun(runId, properties.configurationId(), manifest());
        int created = runner.enqueueTopics(topics.stream().map(Topic::key).toList(), request.itemsPerTopic());
        log.info("Run {} enqueued {} items: {}", runId, created, description);

        launch(runId, description, runner);
        return runId;
    }

    /**
     * Resume an existing run, picking up anything outstanding including items awaiting filtering.
     *
     * @param runId run to resume
     * @throws IllegalStateException if a run is already executing
     */
    public synchronized void resume(String runId) {
        requireIdle();
        BatchRunner runner = runnerFor(runId, null);
        store.registerRun(runId, properties.configurationId(), manifest());
        launch(runId, "resuming outstanding work", runner);
    }

    /**
     * Request that the executing run stop claiming new work.
     *
     * @param runId run to stop
     */
    public void requestStop(String runId) {
        Active current = active;
        if (current != null && current.runId().equals(runId)) {
            current.stopRequested().set(true);
            log.info("Stop requested for run {}", runId);
        }
    }

    /**
     * @param runId run to inspect
     * @return progress for that run
     */
    public Progress progress(String runId) {
        Active current = active;
        boolean running = current != null && current.runId().equals(runId) && !current.future().isDone();
        boolean stopping = running && current.stopRequested().get();
        return new Progress(runId, running, stopping, store.isComplete(runId), store.stateCounts(runId), running ? current.description() : null,
                running ? current.startedAt() : null);
    }

    /**
     * @return identifier of the executing run, if any
     */
    public Optional<String> activeRunId() {
        Active current = active;
        return current == null || current.future().isDone() ? Optional.empty() : Optional.of(current.runId());
    }

    /**
     * @return every run in the store, most recent first
     */
    public List<Progress> runs() {
        return store.runIds().stream().map(this::progress).toList();
    }

    private void requireIdle() {
        activeRunId().ifPresent(runId -> {
            throw new IllegalStateException("Run " + runId + " is still executing; stop it before starting another");
        });
    }

    private void launch(String runId, String description, BatchRunner runner) {
        AtomicBoolean stopRequested = new AtomicBoolean();
        runner.onStopRequested(stopRequested::get);
        Future<?> future = executor.submit(() -> {
            try {
                int processed = runner.run();
                log.info("Run {} finished, {} units processed", runId, processed);
            }
            catch (RuntimeException e) {
                log.error("Run {} failed", runId, e);
            }
            finally {
                exportQuietly(runId);
            }
        });
        active = new Active(runId, description, Instant.now(), stopRequested, future);
    }

    private void exportQuietly(String runId) {
        try {
            exporter.export(store, runId, java.nio.file.Path.of(properties.runLogPath()), java.nio.file.Path.of(properties.itemsMarkdownPath()));
        }
        catch (RuntimeException e) {
            log.warn("Could not export run {}: {}", runId, e.getMessage());
        }
    }

    private List<Topic> selectTopics(List<String> requested) {
        List<Topic> available = corpus.index().topics();
        if (requested == null || requested.isEmpty()) {
            return available;
        }
        return available.stream().filter(topic -> requested.contains(topic.key())).toList();
    }

    private static String describe(List<Topic> topics, int itemsPerTopic) {
        return itemsPerTopic + " item(s) each for " + (topics.size() == 1 ? topics.getFirst().key() : topics.size() + " topics");
    }

    private BatchRunner runnerFor(String runId, Integer concurrency) {
        var index = corpus.index();
        List<TopicQuery> queries = index.topics().stream().map(topic -> new TopicQuery(topic.key(), topic.query())).toList();
        BatchRunner.Settings settings = new BatchRunner.Settings(runId, properties.configurationId(), properties.retrieval().topK(), properties.retrieval().maxGroundingTokens(),
                properties.difficulty(), properties.language(), models.model(properties.generation().backend()), properties.generation().temperature(),
                properties.generation().maxAttempts(), models.model(properties.filter().backend()), properties.filter().temperature(), properties.filter().maxAttempts(),
                properties.filter().acceptThreshold(),
                properties.batch().maxOutputAttempts(), concurrency == null ? properties.batch().concurrency() : concurrency);
        return new BatchRunner(store, settings, new BatchRunner.Dependencies(index.source(), groundingAssembly, generation, filter,
                models.client(properties.generation().backend()), models.client(properties.filter().backend()), queries));
    }

    private String manifest() {
        var index = corpus.index();
        return String.join("\n", "generation=" + properties.generation(), "filter=" + properties.filter(), "retrieval=" + properties.retrieval(),
                "chunking=" + properties.chunking(), "language=" + properties.language(), "difficulty=" + properties.difficulty(), "chunks=" + index.chunkCount());
    }
}
