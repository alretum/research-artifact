package de.tum.cit.aet.artemis.hyperion.mcq.batch;

import java.time.Instant;
import java.util.LinkedHashMap;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import de.tum.cit.aet.artemis.hyperion.mcq.app.PipelineProperties;
import de.tum.cit.aet.artemis.hyperion.mcq.batch.BatchRunner.TopicQuery;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import java.nio.file.Path;

import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusIndexService;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelRegistry;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.RunPlan;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.TopicCatalogue.Topic;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.telemetry.RunExporter;

/**
 * Starts, observes and stops runs in the background.
 * <p>
 * One run executes at a time: the model server is the scarce resource, so overlapping runs would compete
 * for it and make per-item timings meaningless. Stopping is cooperative — the runner finishes whatever it
 * has claimed and records the result, then stops claiming.
 */
@Service
public class RunManager {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);

    private final PipelineProperties properties;

    private final CorpusIndexService corpus;

    private final GroundingAssemblyService groundingAssembly;

    private final McqGenerationService generation;

    private final McqFilterService filter;

    private final ChatClient.Builder chatClientBuilder;

    private final RunStore store;

    private final RunExporter exporter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcq-run");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Active active;

    /** The plan currently executing, if any, so the interface can show progress across its cells. */
    private volatile PlanProgress plan;

    /**
     * How far a plan has got.
     *
     * @param name          the plan's name
     * @param cells         configuration ids in the order they will run
     * @param completed     cells already finished
     * @param currentCell   the cell executing now, or {@code null} when the plan has finished
     * @param runIdsByCell  run id assigned to each cell, so its items can be found
     * @param finished      whether every cell is done
     */
    public record PlanProgress(String name, List<String> cells, List<String> completed, String currentCell, Map<String, String> runIdsByCell, boolean finished) {
    }

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
    /**
     * What to generate, with anything left {@code null} taken from the configured defaults.
     * <p>
     * These are the settings worth varying between runs while experimenting, so they are per-request
     * rather than requiring a configuration edit and a restart. The models are included because the
     * generator and filter pairing is what {@code configurationId} records, and a run that quietly used
     * different models than its id claims would corrupt every per-configuration comparison.
     *
     * @param topicKeys        topics to generate for; empty means every grounded topic
     * @param itemsPerTopic    items to add per topic
     * @param concurrency      workers claiming items in parallel
     * @param difficultyLevels difficulty ladder, walked independently by each topic
     * @param acceptThreshold  minimum aggregate score in [0, 1] for the filter to accept
     * @param generatorModel   provider model name that writes questions
     * @param filterModel      provider model name that judges them
     */
    public record StartRequest(List<String> topicKeys, int itemsPerTopic, Integer concurrency, List<Integer> difficultyLevels, Double acceptThreshold, String generatorModel,
            String filterModel) {

        /** Convenience for a request that overrides nothing beyond the topics and count. */
        public StartRequest(List<String> topicKeys, int itemsPerTopic, Integer concurrency) {
            this(topicKeys, itemsPerTopic, concurrency, null, null, null, null);
        }
    }

    /** The settings a run actually used, after defaults are applied. */
    private record Effective(List<Integer> difficultyLevels, double acceptThreshold, String generatorModel, String filterModel) {

        String configurationId() {
            return generatorModel + "|" + filterModel;
        }
    }

    /**
     * Progress of a run.
     * <p>
     * A sweep run holds quizzes rather than items, so its {@code stateCounts} are empty and its progress is
     * the quiz count.
     *
     * @param running     whether this run is the one currently executing
     * @param stopping    whether a stop has been requested but work is still finishing
     * @param stateCounts item counts by state
     * @param quizzes     quizzes stored under the run
     */
    public record Progress(String runId, boolean running, boolean stopping, boolean complete, Map<String, Integer> stateCounts, int quizzes, String description,
            Instant startedAt) {

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
            McqFilterService filter, ChatClient.Builder chatClientBuilder, RunStore store, RunExporter exporter) {
        this.properties = properties;
        this.corpus = corpus;
        this.groundingAssembly = groundingAssembly;
        this.generation = generation;
        this.filter = filter;
        this.chatClientBuilder = chatClientBuilder;
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
        Effective effective = effective(request);

        BatchRunner runner = runnerFor(runId, request.concurrency(), effective);
        store.registerRun(runId, effective.configurationId(), manifest(effective));
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
        String stored = store.manifestOf(runId).orElseThrow(() -> new IllegalArgumentException("No run " + runId + " to resume"));
        // Continue with the settings the run started with, not whatever is configured now. Otherwise the
        // remaining items would be generated under different conditions than the ones already produced,
        // mixing two conditions inside one run id, and the manifest comparison in registerRun would fail.
        Effective effective = fromManifest(stored);
        BatchRunner runner = runnerFor(runId, null, effective);
        store.registerRun(runId, effective.configurationId(), stored);
        launch(runId, "resuming outstanding work", runner);
    }

    /**
     * Recover a run's settings from the manifest it was registered with.
     *
     * @param manifest the stored manifest
     * @return the settings recorded in it, falling back to the configured defaults for anything unreadable
     */
    private Effective fromManifest(String manifest) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : manifest.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        Effective defaults = effective(new StartRequest(List.of(), 1, null));
        try {
            List<Integer> difficulty = fields.containsKey("difficulty")
                    ? java.util.Arrays.stream(fields.get("difficulty").replaceAll("[\\[\\] ]", "").split(",")).filter(part -> !part.isBlank()).map(Integer::parseInt).toList()
                    : defaults.difficultyLevels();
            double threshold = fields.containsKey("acceptThreshold") ? Double.parseDouble(fields.get("acceptThreshold")) : defaults.acceptThreshold();
            String generator = fields.getOrDefault("generator", defaults.generatorModel());
            String filterModel = fields.getOrDefault("filter", defaults.filterModel());
            return new Effective(difficulty.isEmpty() ? defaults.difficultyLevels() : difficulty, threshold, generator, filterModel);
        }
        catch (RuntimeException e) {
            log.warn("Could not read settings from the manifest of this run ({}); resuming with the configured defaults instead", e.getMessage());
            return defaults;
        }
    }

    /**
     * Run every configuration of a plan, one after another, in the background.
     * <p>
     * Sequential because the model server is the scarce resource, and because concurrent cells would
     * inflate each other's per-call latency so that no cell's timings could be reported. Each cell gets its
     * own run id and carries the plan's declared configuration id, so the cells stay separable afterwards.
     *
     * @param runPlan             the plan to execute
     * @param itemsPerTopicOverride items per topic for this execution, or {@code null} to use the plan's own
     *                              value. An override lets the same plan be tried at a small size before it
     *                              is run at full size, without editing the file
     * @throws IllegalStateException    if a run or plan is already executing
     * @throws IllegalArgumentException if the plan names a model that is not declared
     */
    public synchronized void startPlan(RunPlan runPlan, Integer itemsPerTopicOverride) {
        requireIdle();
        ModelCatalogue catalogue = ModelCatalogue.load(Path.of(properties.modelCataloguePath()));
        runPlan.validateAgainst(catalogue);
        ModelRegistry registry = new ModelRegistry(catalogue, chatClientBuilder.build());
        registry.validate(runPlan);

        int itemsPerTopic = itemsPerTopicOverride == null ? runPlan.itemsPerTopic() : itemsPerTopicOverride;
        if (itemsPerTopic < 1) {
            throw new IllegalArgumentException("Items per topic must be at least 1");
        }
        List<String> cells = runPlan.configurations().stream().map(RunPlan.RunConfiguration::id).toList();
        Map<String, String> runIds = new java.util.LinkedHashMap<>();
        runPlan.configurations().forEach(configuration -> runIds.put(configuration.id(), runPlan.plan() + "-" + configuration.id()));
        plan = new PlanProgress(runPlan.plan(), cells, List.of(), cells.getFirst(), Map.copyOf(runIds), false);

        var index = corpus.index();
        List<Topic> topics = runPlan.topics().isEmpty() ? index.topics().stream().filter(Topic::grounded).toList()
                : index.topics().stream().filter(topic -> runPlan.topics().contains(topic.key())).toList();
        if (topics.isEmpty()) {
            plan = null;
            throw new IllegalArgumentException("Plan '" + runPlan.plan() + "' matched no grounded topics");
        }

        AtomicBoolean stopRequested = new AtomicBoolean();
        Future<?> future = executor.submit(() -> {
            List<String> done = new java.util.ArrayList<>();
            try {
                for (RunPlan.RunConfiguration configuration : runPlan.configurations()) {
                    if (stopRequested.get()) {
                        log.info("Plan '{}' stopped before {}", runPlan.plan(), configuration.id());
                        break;
                    }
                    plan = new PlanProgress(runPlan.plan(), cells, List.copyOf(done), configuration.id(), Map.copyOf(runIds), false);
                    String runId = runIds.get(configuration.id());
                    var generator = registry.resolve(configuration.generator());
                    var filterModel = registry.resolve(configuration.filter());
                    Effective effective = new Effective(properties.difficulty(), properties.filter().acceptThreshold(), generator.model(), filterModel.model());

                    store.registerRun(runId, configuration.id(), manifest(effective));
                    store.releaseStaleClaims(runId);
                    BatchRunner runner = new BatchRunner(store, planSettings(runId, configuration.id(), effective, generator.model(), filterModel.model()),
                            new BatchRunner.Dependencies(index.source(), groundingAssembly, generation, filter, generator.client(), filterModel.client(),
                                    topics.stream().map(topic -> new TopicQuery(topic.key(), topic.query())).toList()));
                    runner.onStopRequested(stopRequested::get);
                    runner.enqueueTopics(topics.stream().map(Topic::key).toList(), itemsPerTopic);
                    log.info("Plan '{}': running cell {} ({} item(s) per topic across {} topic(s))", runPlan.plan(), configuration.id(), itemsPerTopic, topics.size());
                    runner.run();
                    exportQuietly(runId);
                    done.add(configuration.id());
                }
            }
            catch (RuntimeException e) {
                log.error("Plan '{}' failed", runPlan.plan(), e);
            }
            finally {
                plan = new PlanProgress(runPlan.plan(), cells, List.copyOf(done), null, Map.copyOf(runIds), true);
            }
        });
        active = new Active(runPlan.plan(), runPlan.configurations().size() + " configuration(s) x " + topics.size() + " topic(s) x " + itemsPerTopic + " item(s)", Instant.now(),
                stopRequested, future);
    }

    /** @return the plan currently executing or last finished, if any */
    public Optional<PlanProgress> planProgress() {
        return Optional.ofNullable(plan);
    }

    private BatchRunner.Settings planSettings(String runId, String configurationId, Effective effective, String generator, String filterModel) {
        return new BatchRunner.Settings(runId, configurationId, properties.retrieval().topK(), properties.retrieval().maxGroundingTokens(), effective.difficultyLevels(),
                properties.language(), generator, properties.generation().temperature(), properties.generation().maxAttempts(), filterModel,
                properties.filter().temperature(), properties.filter().maxAttempts(), effective.acceptThreshold(), properties.filter().gatingModes()
                , properties.batch().maxOutputAttempts(),
                properties.batch().concurrency());
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
        return new Progress(runId, running, stopping, store.isComplete(runId), store.stateCounts(runId), store.quizCount(runId), running ? current.description() : null,
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

    /**
     * Apply the configured defaults to anything the request left unset, and validate what it did set.
     *
     * @param request the request
     * @return the settings the run will use
     * @throws IllegalArgumentException if an override is out of range
     */
    private Effective effective(StartRequest request) {
        List<Integer> difficulty = request.difficultyLevels() == null || request.difficultyLevels().isEmpty() ? properties.difficulty() : request.difficultyLevels();
        if (difficulty.stream().anyMatch(level -> level < 0 || level > 100)) {
            throw new IllegalArgumentException("Difficulty levels must be between 0 and 100, got " + difficulty);
        }
        double threshold = request.acceptThreshold() == null ? properties.filter().acceptThreshold() : request.acceptThreshold();
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Accept threshold must be between 0 and 1, got " + threshold);
        }
        String generator = blankToNull(request.generatorModel()) == null ? properties.generation().model() : request.generatorModel();
        String filterModel = blankToNull(request.filterModel()) == null ? properties.filter().model() : request.filterModel();
        return new Effective(List.copyOf(difficulty), threshold, generator, filterModel);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private BatchRunner runnerFor(String runId, Integer concurrency, Effective effective) {
        var index = corpus.index();
        List<TopicQuery> queries = index.topics().stream().map(topic -> new TopicQuery(topic.key(), topic.query())).toList();
        BatchRunner.Settings settings = new BatchRunner.Settings(runId, effective.configurationId(), properties.retrieval().topK(), properties.retrieval().maxGroundingTokens(),
                effective.difficultyLevels(), properties.language(), effective.generatorModel(), properties.generation().temperature(), properties.generation().maxAttempts(),
                effective.filterModel(), properties.filter().temperature(), properties.filter().maxAttempts(), effective.acceptThreshold(), properties.filter().gatingModes(),
                properties.batch().maxOutputAttempts(), concurrency == null ? properties.batch().concurrency() : concurrency);
        return new BatchRunner(store, settings, new BatchRunner.Dependencies(index.source(), groundingAssembly, generation, filter, chatClientBuilder.build(), queries));
    }

    /**
     * @param effective the settings this run will use
     * @return a snapshot recording what was actually used, not what was configured, so a resumed run is
     *         checked against the right thing
     */
    private String manifest(Effective effective) {
        var index = corpus.index();
        return String.join("\n", "generator=" + effective.generatorModel(), "filter=" + effective.filterModel(), "acceptThreshold=" + effective.acceptThreshold()
                , "gatingModes=" + properties.filter().gatingModes(),
                "difficulty=" + effective.difficultyLevels(), "retrieval=" + properties.retrieval(), "chunking=" + properties.chunking(),
                "language=" + properties.language(), "chunks=" + index.chunkCount());
    }
}
