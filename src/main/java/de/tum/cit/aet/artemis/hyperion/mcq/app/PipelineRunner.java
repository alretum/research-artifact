package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import de.tum.cit.aet.artemis.hyperion.mcq.batch.BatchRunner;
import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkExporter;
import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkExporter.Condition;
import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkExporter.Granularity;
import de.tum.cit.aet.artemis.hyperion.mcq.cost.CostReporter;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.AgenticApproach;
import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.SweepExporter;
import de.tum.cit.aet.artemis.hyperion.mcq.cost.SweepCostReporter;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.TwoPhaseApproach;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelRegistry;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.RunPlan;
import de.tum.cit.aet.artemis.hyperion.mcq.readiness.ReadinessService;
import de.tum.cit.aet.artemis.hyperion.mcq.batch.BatchRunner.TopicQuery;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Chunk;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ItemProvenance;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.LengthStats;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.RunRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusLoader;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.ExtractionReportWriter;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.PageChunker;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.TopicCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.TopicCatalogue.Topic;
import de.tum.cit.aet.artemis.hyperion.mcq.retrieval.EmbeddingSnippetSource;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.telemetry.CompositionReporter;
import de.tum.cit.aet.artemis.hyperion.mcq.telemetry.FailureReporter;
import de.tum.cit.aet.artemis.hyperion.mcq.telemetry.RunExporter;
import de.tum.cit.aet.artemis.hyperion.mcq.telemetry.ThresholdSweep;
import de.tum.cit.aet.artemis.hyperion.mcq.telemetry.RunLogWriter;

/**
 * Runs the pipeline once from the command line: load the corpus, retrieve grounding for each topic,
 * generate a question, filter it, and append the result to the run log.
 * <p>
 * Topics are taken from the corpus folder names, or from {@code mcq.topics-file} when that is set. A
 * single ad-hoc topic can be passed as {@code --topic=<text>}, but values containing spaces have to
 * survive shell and Gradle argument splitting, so the topics file is the reliable route.
 * {@code --count=<n>} sets how many questions to generate. {@code --retrieval-only} reports what each
 * topic retrieves and exits without issuing any generation or filter calls.
 */
@Component
public class PipelineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final PipelineProperties properties;

    private final EmbeddingModel embeddingModel;

    private final ChatClient.Builder chatClientBuilder;

    private final GroundingAssemblyService groundingAssembly;

    private final McqGenerationService generation;

    private final McqFilterService filter;

    private final RunLogWriter runLog;

    private final ExtractionReportWriter reportWriter;

    private final CompositionReporter compositionReporter;

    private final RunExporter exporter;

    private final ThresholdSweep sweep;

    private final FailureReporter failures;

    private final CostReporter cost;

    private final BenchmarkExporter benchmark;

    private final ReadinessService readiness;

    private final AgenticApproach agentic;

    private final TwoPhaseApproach twoPhase;

    private final SweepExporter sweepExporter;

    private final SweepCostReporter sweepCost;

    private final tools.jackson.databind.json.JsonMapper mapper = de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs.outputMapper();

    public PipelineRunner(PipelineProperties properties, EmbeddingModel embeddingModel, ChatClient.Builder chatClientBuilder, GroundingAssemblyService groundingAssembly,
            McqGenerationService generation, McqFilterService filter, RunLogWriter runLog, ExtractionReportWriter reportWriter, CompositionReporter compositionReporter
            , RunExporter exporter, ThresholdSweep sweep, FailureReporter failures, CostReporter cost, BenchmarkExporter benchmark, ReadinessService readiness,
            AgenticApproach agentic, TwoPhaseApproach twoPhase, SweepExporter sweepExporter, SweepCostReporter sweepCost) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.chatClientBuilder = chatClientBuilder;
        this.groundingAssembly = groundingAssembly;
        this.generation = generation;
        this.filter = filter;
        this.runLog = runLog;
        this.reportWriter = reportWriter;
        this.compositionReporter = compositionReporter;
        this.exporter = exporter;
        this.sweep = sweep;
        this.failures = failures;
        this.cost = cost;
        this.benchmark = benchmark;
        this.readiness = readiness;
        this.agentic = agentic;
        this.twoPhase = twoPhase;
        this.sweepExporter = sweepExporter;
        this.sweepCost = sweepCost;
    }

    private ApplicationArguments arguments;

    @Override
    public void run(ApplicationArguments args) {
        this.arguments = args;
        if (!args.containsOption("count") && !args.containsOption("resume") && !args.containsOption("report") && !args.containsOption("sweep")
                && !args.containsOption("cost") && !args.containsOption("plan") && !args.containsOption("run-plan") && !args.containsOption("export-benchmark")
                && !args.containsOption("doctor") && !args.containsOption("redecide") && !args.containsOption("experiment") && !args.containsOption("export-experiment")
                && !args.containsOption("experiment-cost")
                && !args.containsOption("retrieval-only")) {
            log.info("No command argument given; the web interface is available at http://localhost:8080");
            return;
        }
        if (args.containsOption("report")) {
            compositionReporter.report(Path.of(properties.runLogPath()));
            return;
        }
        if (args.containsOption("sweep")) {
            sweep.report(Path.of(properties.runLogPath()));
            return;
        }
        if (args.containsOption("cost")) {
            try (RunStore store = new RunStore(Path.of(properties.batch().databasePath()))) {
                cost.report(store, Path.of(properties.pricingPath()));
            }
            return;
        }
        if (args.containsOption("plan")) {
            describePlan(Path.of(args.getOptionValues("plan").getFirst()));
            return;
        }
        if (args.containsOption("experiment")) {
            runExperiment(Path.of(args.getOptionValues("experiment").getFirst()));
            return;
        }
        if (args.containsOption("export-experiment")) {
            exportExperiment(Path.of(args.getOptionValues("export-experiment").getFirst()));
            return;
        }
        if (args.containsOption("experiment-cost")) {
            SweepPlan plan = SweepPlan.load(Path.of(args.getOptionValues("experiment-cost").getFirst()));
            ModelCatalogue costCatalogue = ModelCatalogue.load(Path.of(properties.modelCataloguePath()));
            Map<String, String> keyToModel = new java.util.LinkedHashMap<>();
            plan.configurations().forEach(configuration -> List.of(configuration.generator(), configuration.judge(), configuration.selector())
                    .forEach(key -> keyToModel.computeIfAbsent(key, k -> costCatalogue.requireModel(k).model())));
            try (RunStore store = new RunStore(Path.of(properties.batch().databasePath()))) {
                sweepCost.report(store, plan, keyToModel, Path.of(properties.pricingPath()));
            }
            return;
        }
        if (args.containsOption("run-plan")) {
            runPlan(Path.of(args.getOptionValues("run-plan").getFirst()));
            return;
        }
        if (args.containsOption("redecide")) {
            redecide();
            return;
        }
        if (args.containsOption("doctor")) {
            readiness.report();
            return;
        }
        if (args.containsOption("export-benchmark")) {
            Granularity granularity = Granularity.parse(stringArg(args, "export-granularity", "configuration-topic"));
            Condition condition = Condition.parse(stringArg(args, "export-condition", "all"));
            try (RunStore store = new RunStore(Path.of(properties.batch().databasePath()))) {
                benchmark.export(store, Path.of(args.getOptionValues("export-benchmark").getFirst()), granularity, condition, properties.language());
            }
            return;
        }

        String runId = args.containsOption("resume") ? args.getOptionValues("resume").getFirst() : UUID.randomUUID().toString().substring(0, 8);
        int count = intArg(args, "count", 1);

        Indexed indexed = buildIndex();
        List<Topic> topics = resolveTopics(args, indexed.topics());
        if (topics.isEmpty()) {
            log.error("No grounded topics available; nothing to generate");
            return;
        }

        if (args.containsOption("retrieval-only")) {
            probeRetrieval(indexed, topics);
            return;
        }

        ChatClient chatClient = chatClientBuilder.build();
        try (RunStore store = new RunStore(java.nio.file.Path.of(properties.batch().databasePath()))) {
            store.registerRun(runId, properties.configurationId(), manifest(indexed));

            BatchRunner batch = new BatchRunner(store, batchSettings(runId), new BatchRunner.Dependencies(indexed.source(), groundingAssembly, generation, filter, chatClient,
                    topics.stream().map(topic -> new TopicQuery(topic.key(), topic.query())).toList()));

            int created = batch.enqueue(count);
            log.info("Run {}: {} items enqueued ({} new), states {}", runId, count, created, store.stateCounts(runId));

            long start = System.nanoTime();
            int processed = batch.run();
            long seconds = (System.nanoTime() - start) / 1_000_000_000;

            log.info("=== run {} ===", runId);
            log.info("processed {} units in {} s, states now {}", processed, seconds, store.stateCounts(runId));
            log.info("complete: {}", store.isComplete(runId));
            exporter.export(store, runId, Path.of(properties.runLogPath()), Path.of(properties.itemsMarkdownPath()));
            failures.report(store, runId);
        }
    }


    private record Indexed(EmbeddingSnippetSource source, List<Topic> topics) {
    }

    /**
     * Settings that must not change between the start of a run and its resumption, rendered for comparison.
     */
    private String manifest(Indexed indexed) {
        return String.join("\n", "generation=" + properties.generation(), "filter=" + properties.filter(), "retrieval=" + properties.retrieval(),
                "chunking=" + properties.chunking(), "language=" + properties.language(), "difficulty=" + properties.difficulty(), "chunks=" + indexed.source().size(),
                "topics=" + indexed.topics().stream().map(Topic::key).toList());
    }

    /**
     * Settings for one configuration of a plan, taking the models from the plan rather than the
     * properties, and the configuration id from the plan rather than deriving it from model names.
     *
     * @param runId         run this configuration writes into
     * @param configuration the plan entry
     * @param generator     resolved generator model name
     * @param filterModel   resolved filter model name
     * @return settings for a batch
     */
    private BatchRunner.Settings planSettings(String runId, RunPlan.RunConfiguration configuration, String generator, String filterModel) {
        return new BatchRunner.Settings(runId, configuration.id(), properties.retrieval().topK(), properties.retrieval().maxGroundingTokens(), properties.difficulty(),
                properties.language(), generator, properties.generation().temperature(), properties.generation().maxAttempts(), filterModel, properties.filter().temperature(),
                properties.filter().maxAttempts(), properties.filter().acceptThreshold(), properties.filter().gatingModes(), properties.batch().maxOutputAttempts(),
                intArgOrDefault("concurrency", properties.batch().concurrency()));
    }

    /**
     * Run every configuration in a plan, one after another.
     * <p>
     * Sequential on purpose: the model server is the scarce resource, and running cells concurrently would
     * inflate per-call latency so that no cell's timings could be reported (THESIS_NOTES N5). Each
     * configuration gets its own run id, because the store records one configuration per run, and its
     * items are keyed by the plan's configuration id so the cells never collide.
     *
     * @param planFile the plan to run
     */
    private void runPlan(Path planFile) {
        RunPlan plan = RunPlan.load(planFile);
        ModelCatalogue catalogue = ModelCatalogue.load(Path.of(properties.modelCataloguePath()));
        plan.validateAgainst(catalogue);
        ModelRegistry registry = new ModelRegistry(catalogue, chatClientBuilder.build());
        registry.validate(plan);

        Indexed indexed = buildIndex();
        List<Topic> topics = plan.topics().isEmpty() ? indexed.topics().stream().filter(Topic::grounded).toList()
                : indexed.topics().stream().filter(topic -> plan.topics().contains(topic.key())).toList();
        if (topics.isEmpty()) {
            log.error("Plan '{}' matched no grounded topics; nothing to generate", plan.plan());
            return;
        }

        int perConfiguration = plan.itemsPerTopic() * topics.size();
        log.info("Plan '{}': {} configuration(s) x {} topic(s) x {} items = {} items", plan.plan(), plan.configurations().size(), topics.size(), plan.itemsPerTopic(),
                plan.configurations().size() * perConfiguration);

        List<TopicQuery> queries = topics.stream().map(topic -> new TopicQuery(topic.key(), topic.query())).toList();
        try (RunStore store = new RunStore(Path.of(properties.batch().databasePath()))) {
            for (RunPlan.RunConfiguration configuration : plan.configurations()) {
                var generator = registry.resolve(configuration.generator());
                var filterModel = registry.resolve(configuration.filter());
                String runId = plan.plan() + "-" + configuration.id();

                log.info("--- configuration {} ({} items) ---", configuration.id(), perConfiguration);
                store.registerRun(runId, configuration.id(), manifest(indexed));
                store.releaseStaleClaims(runId);

                BatchRunner batch = new BatchRunner(store, planSettings(runId, configuration, generator.model(), filterModel.model()),
                        new BatchRunner.Dependencies(indexed.source(), groundingAssembly, generation, filter, generator.client(), filterModel.client(), queries));
                int created = batch.enqueue(perConfiguration);
                long start = System.nanoTime();
                int processed = batch.run();
                log.info("configuration {}: {} enqueued ({} new), {} units processed in {} s, states {}", configuration.id(), perConfiguration, created, processed,
                        (System.nanoTime() - start) / 1_000_000_000, store.stateCounts(runId));
                failures.report(store, runId);
            }
        }
        log.info("Plan '{}' complete. Report cost with --cost and quality with --report.", plan.plan());
    }

    private BatchRunner.Settings batchSettings(String runId) {
        return new BatchRunner.Settings(runId, properties.configurationId(), properties.retrieval().topK(), properties.retrieval().maxGroundingTokens(), properties.difficulty(),
                properties.language(), properties.generation().model(), properties.generation().temperature(), properties.generation().maxAttempts(), properties.filter().model(),
                properties.filter().temperature(), properties.filter().maxAttempts(), properties.filter().acceptThreshold(), properties.filter().gatingModes()
            , properties.batch().maxOutputAttempts(),
                intArgOrDefault("concurrency", properties.batch().concurrency()));
    }

    private int intArgOrDefault(String name, int fallback) {
        return arguments == null || !arguments.containsOption(name) ? fallback : Integer.parseInt(arguments.getOptionValues(name).getFirst());
    }

    /**
     * Report which chunks each topic retrieves, without generating anything.
     *
     * @param indexed the built index
     * @param topics  topics to probe
     */
    private void probeRetrieval(Indexed indexed, List<Topic> topics) {
        Path output = Path.of(properties.retrievalProbePath());
        StringBuilder csv = new StringBuilder("topic,rank,score,document,chunkId\n");
        for (Topic topic : topics) {
            var snippets = indexed.source().search(topic.query(), properties.retrieval().topK(), null);
            int rank = 1;
            for (var snippet : snippets) {
                csv.append('"').append(topic.key().replace("\"", "\"\"")).append("\",").append(rank++).append(',').append(String.format("%.4f", snippet.score())).append(",\"")
                        .append(snippet.chunkId().substring(0, snippet.chunkId().lastIndexOf('#')).replace("\"", "\"\"")).append("\",\"").append(snippet.chunkId()).append("\"\n");
            }
            log.info("{} -> {}", topic.key(), snippets.stream().map(sn -> sn.unit() + " (" + String.format("%.3f", sn.score()) + ")").toList());
        }
        try {
            if (output.getParent() != null) {
                java.nio.file.Files.createDirectories(output.getParent());
            }
            java.nio.file.Files.writeString(output, csv.toString());
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to write retrieval probe to " + output, e);
        }
        log.info("Retrieval probe for {} topics at top-k {} -> {}", topics.size(), properties.retrieval().topK(), output.toAbsolutePath());
    }

    /**
     * Run a sweep plan: build the pools its two-phase configurations need, then answer every request with
     * every configuration.
     *
     * @param sweepFile the sweep plan
     */
    private void runExperiment(Path sweepFile) {
        SweepPlan plan = SweepPlan.load(sweepFile);
        List<GenerationRequest> requests = RequestFileReader.read(Path.of(plan.requestsFile()));
        Indexed indexed = buildIndex();
        Map<String, de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest> manifests = resolveManifests(requests);
        ModelCatalogue catalogue = ModelCatalogue.load(Path.of(properties.modelCataloguePath()));
        ModelRegistry registry = new ModelRegistry(catalogue, chatClientBuilder.build());
        Map<String, String> hashes = SweepRunner.hashDocuments(Path.of(properties.corpusPath()));

        try (RunStore store = new RunStore(Path.of(properties.batch().databasePath()))) {
            SweepRunner runner = new SweepRunner(plan, requests,
                    new SweepRunner.Dependencies(manifests, indexed.source(), groundingAssembly, generation, filter, agentic, twoPhase, store, registry, properties));
            int assembled = runner.run(hashes);
            log.info("Sweep {} complete: {} quizzes newly assembled, {} stored in total", plan.sweep(), assembled, store.quizzes(plan.sweep()).size());
        }
    }

    /**
     * Export a completed sweep as benchmark input.
     *
     * @param sweepFile the sweep plan the quizzes were assembled from
     */
    private void exportExperiment(Path sweepFile) {
        SweepPlan plan = SweepPlan.load(sweepFile);
        List<GenerationRequest> requests = RequestFileReader.read(Path.of(plan.requestsFile()));
        Map<String, de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest> manifests = resolveManifests(requests);
        try (RunStore store = new RunStore(Path.of(properties.batch().databasePath()))) {
            Path directory = Path.of(properties.benchmarkExportPath()).resolve(plan.sweep());
            List<Path> written = sweepExporter.export(store, plan.sweep(), requests, manifests, directory);
            log.info("Exported {} quizzes to {}", written.size(), directory);
        }
    }

    /**
     * Resolves the course model for every course the requests name.
     * <p>
     * When {@code mcq.competency-manifest} points at a directory, each course reads
     * {@code <courseKey>.json} from it as a catalogue; a {@code .json} file is one catalogue for every
     * course; anything else is one YAML manifest shared by every course.
     *
     * @param requests the requests naming the courses
     * @return the course model per course key
     */
    private Map<String, de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest> resolveManifests(List<GenerationRequest> requests) {
        Path setting = Path.of(properties.competencyManifest());
        Map<String, de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest> manifests = new java.util.LinkedHashMap<>();
        for (GenerationRequest request : requests) {
            manifests.computeIfAbsent(request.courseKey(), courseKey -> {
                if (java.nio.file.Files.isDirectory(setting)) {
                    return CompetencyCatalogue.load(setting.resolve(courseKey + ".json"));
                }
                if (setting.toString().endsWith(".json")) {
                    return CompetencyCatalogue.load(setting);
                }
                return de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.load(setting);
            });
        }
        return manifests;
    }

    private Indexed buildIndex() {
        Path corpus = Path.of(properties.corpusPath());
        log.info("Loading corpus from {}", corpus.toAbsolutePath());

        CorpusLoader.LoadResult loaded = new CorpusLoader().load(corpus);
        Map<String, SourceRole> roles = new HashMap<>();
        loaded.reports().forEach(report -> roles.put(report.documentId(), report.role()));

        reportWriter.write(Path.of(properties.extractionReportPath()), loaded.reports());
        log.info("Extraction: {} docs, {} pages, ~{} tokens, {} text-poor pages, {} damaged tokens, {} alt-text lines -> {}", loaded.reports().size(), loaded.pages().size(),
                loaded.totalApproxTokens(), sum(loaded, CorpusLoader.DocumentReport::textPoorPages), sum(loaded, CorpusLoader.DocumentReport::suspectedDamagedTokens),
                sum(loaded, CorpusLoader.DocumentReport::altTextLines), properties.extractionReportPath());

        List<Chunk> chunks = new PageChunker(properties.chunking().targetTokens(), properties.chunking().maxTokens()).chunk(loaded.pages(),
                documentId -> roles.getOrDefault(documentId, SourceRole.OTHER));

        List<Topic> topics = resolveCatalogue(chunks, loaded.reports().stream().map(CorpusLoader.DocumentReport::documentId).collect(java.util.stream.Collectors.toSet()));
        reportWriter.writeTopics(Path.of(properties.topicReportPath()), topics);
        topics.stream().filter(topic -> !topic.grounded()).forEach(topic -> log.warn("Topic '{}' has no linked material and is skipped", topic.key()));

        EmbeddingSnippetSource source = new EmbeddingSnippetSource(embeddingModel);
        long start = System.nanoTime();
        boolean cached = source.indexCached(chunks, Path.of(properties.chunking().indexPath()));
        log.info("{} {} chunks across {} topics in {} s", cached ? "Loaded" : "Embedded", source.size(), topics.size(), (System.nanoTime() - start) / 1_000_000_000);
        return new Indexed(source, topics);
    }

    private List<Topic> resolveCatalogue(List<Chunk> chunks, java.util.Set<String> knownDocuments) {
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

    private static int sum(CorpusLoader.LoadResult loaded, java.util.function.ToIntFunction<CorpusLoader.DocumentReport> field) {
        return loaded.reports().stream().mapToInt(field).sum();
    }

    /**
     * Validate a run plan and log what it would run, without generating anything.
     * <p>
     * Checks every model the plan names is declared and reachable, so a mistyped key or an unset key
     * variable fails here rather than part-way through a paid run.
     *
     * @param planFile the plan to describe
     */
    private void describePlan(Path planFile) {
        RunPlan plan = RunPlan.load(planFile);
        ModelCatalogue catalogue = ModelCatalogue.load(Path.of(properties.modelCataloguePath()));
        plan.validateAgainst(catalogue);
        ModelRegistry registry = new ModelRegistry(catalogue, chatClientBuilder.build());
        registry.validate(plan);

        String scope = plan.topics().isEmpty() ? "every grounded topic" : plan.topics().size() + " named topic(s)";
        log.info("Plan '{}': {} configuration(s), {} items per topic, over {}", plan.plan(), plan.configurations().size(), plan.itemsPerTopic(), scope);
        for (RunPlan.RunConfiguration configuration : plan.configurations()) {
            log.info("  {} | generator {} -> {} | filter {} -> {}{}", configuration.id(), configuration.generator(), registry.modelNameOf(configuration.generator()).orElseThrow(),
                    configuration.filter(), registry.modelNameOf(configuration.filter()).orElseThrow(), configuration.isSelfJudging() ? "  (self-judging)" : "");
        }
        long selfJudging = plan.configurations().stream().filter(RunPlan.RunConfiguration::isSelfJudging).count();
        if (selfJudging == plan.configurations().size()) {
            log.warn("Every configuration has one model both writing and judging, so accept rate includes self-agreement. "
                    + "Add a configuration with a different filter model to measure independently.");
        }
    }

    /**
     * Recompute every stored decision from the severities already judged, under the current threshold and
     * gating modes, and write the results back.
     * <p>
     * Needs no model call: the five severities per item are already stored, and the decision is a function
     * of them. Changing which modes may reject therefore does not require regenerating anything. The
     * previous decisions are overwritten.
     */
    private void redecide() {
        int changed = 0;
        int accepted = 0;
        int total = 0;
        try (RunStore store = new RunStore(Path.of(properties.batch().databasePath()))) {
            for (String runId : store.runIds()) {
                for (RunStore.CompletedItem item : store.completedItems(runId)) {
                    if (item.decisionJson() == null || item.decisionJson().isBlank()) {
                        continue;
                    }
                    FilterDecision before = mapper.readValue(item.decisionJson(), FilterDecision.class);
                    FilterDecision after = McqFilterService.decide(before.modeVerdicts(), properties.filter().acceptThreshold(), properties.filter().gatingModes(),
                            before.filterModel(), before.rationale());
                    total++;
                    if (after.accepted()) {
                        accepted++;
                    }
                    if (after.accepted() != before.accepted() || after.aggregateScore() != before.aggregateScore()) {
                        store.replaceDecision(item.key(), mapper.writeValueAsString(after));
                        changed++;
                    }
                }
            }
        }
        log.info("Recomputed {} decision(s) with threshold {} gating on {}", total, properties.filter().acceptThreshold(), properties.filter().gatingModes());
        log.info("{} changed; {} of {} now accepted ({}%)", changed, accepted, total, total == 0 ? 0 : Math.round(100.0 * accepted / total));
    }

    private String stringArg(ApplicationArguments args, String name, String fallback) {
        return args.containsOption(name) ? args.getOptionValues(name).getFirst() : fallback;
    }

    private List<Topic> resolveTopics(ApplicationArguments args, List<Topic> fromCorpus) {
        if (args.containsOption("topic")) {
            return args.getOptionValues("topic").stream().map(value -> new Topic(value, value, 1)).toList();
        }
        if (properties.topicsFile() != null && !properties.topicsFile().isBlank()) {
            List<Topic> fromFile = TopicCatalogue.fromFile(Path.of(properties.topicsFile()));
            log.info("Using {} topics from {}", fromFile.size(), properties.topicsFile());
            return fromFile;
        }
        return fromCorpus.stream().filter(Topic::grounded).toList();
    }




    private static int intArg(ApplicationArguments args, String name, int fallback) {
        if (!args.containsOption(name)) {
            return fallback;
        }
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : Integer.parseInt(values.getFirst());
    }
}
