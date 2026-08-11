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
import de.tum.cit.aet.artemis.hyperion.mcq.telemetry.CompositionReporter;
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

    public PipelineRunner(PipelineProperties properties, EmbeddingModel embeddingModel, ChatClient.Builder chatClientBuilder, GroundingAssemblyService groundingAssembly,
            McqGenerationService generation, McqFilterService filter, RunLogWriter runLog, ExtractionReportWriter reportWriter, CompositionReporter compositionReporter) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.chatClientBuilder = chatClientBuilder;
        this.groundingAssembly = groundingAssembly;
        this.generation = generation;
        this.filter = filter;
        this.runLog = runLog;
        this.reportWriter = reportWriter;
        this.compositionReporter = compositionReporter;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption("report")) {
            compositionReporter.report(Path.of(properties.runLogPath()));
            return;
        }

        String runId = UUID.randomUUID().toString().substring(0, 8);
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
        Path runLogPath = Path.of(properties.runLogPath());
        Path itemsPath = Path.of(properties.itemsMarkdownPath());
        Map<String, Integer> failures = new HashMap<>();
        int accepted = 0;
        long start = System.nanoTime();

        for (int i = 0; i < count; i++) {
            Topic topic = topics.get(i % topics.size());
            log.info("--- item {}/{} | topic: {}", i + 1, count, topic.query());

            GroundingContext grounding = groundingAssembly.assemble(topic.query(), indexed.source().search(topic.query(), properties.retrieval().topK(), null),
                    properties.retrieval().maxGroundingTokens());

            var generated = generation.generate(grounding, properties.difficulty(), properties.language(), properties.generation().model(), properties.generation().temperature(),
                    properties.generation().maxAttempts(), chatClient);
            if (!generated.succeeded()) {
                failures.merge(generated.failure().name(), 1, Integer::sum);
                log.warn("Generation failed: {} after {} ms", generated.failure(), generated.call().wallClockMs());
                continue;
            }

            var judged = filter.evaluate(generated.item(), grounding, properties.filter().acceptThreshold(), properties.filter().model(), properties.filter().temperature(),
                    properties.filter().maxAttempts(), chatClient);
            if (judged.decision() != null && judged.decision().accepted()) {
                accepted++;
            }

            RunRecord record = new RunRecord(RunRecord.SCHEMA_VERSION, runId, properties.configurationId(), generated.item(),
                    provenance(runId, topic.query(), grounding, generated.item(), generated.prompt()), judged.decision(), List.of(generated.call(), judged.call()));
            runLog.append(runLogPath, record);
            runLog.appendMarkdown(itemsPath, record);
            runLog.logSummary(record);
            logTiming(generated.call(), judged.call());
        }

        summarise(runId, count, accepted, failures, start, runLogPath);
    }

    private record Indexed(EmbeddingSnippetSource source, List<Topic> topics) {
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

    private ItemProvenance provenance(String runId, String topic, GroundingContext grounding, McqItem item, String prompt) {
        List<String> chunkIds = grounding.snippets().stream().map(snippet -> snippet.chunkId()).toList();
        boolean damaged = CorpusLoader.looksDamaged(item.questionText()) || item.options().stream().anyMatch(option -> CorpusLoader.looksDamaged(option.text()));
        return new ItemProvenance(runId, properties.configurationId(), properties.generation().model(), properties.filter().model(), topic, chunkIds, prompt, properties.difficulty(),
                LengthStats.of(item), damaged, grounding.composition(), Instant.now());
    }

    private static void logTiming(CallRecord generation, CallRecord filter) {
        log.info("Timing: generation {} ms ({} in / {} out tokens), filter {} ms ({} in / {} out tokens)", generation.wallClockMs(), generation.promptTokens(),
                generation.completionTokens(), filter.wallClockMs(), filter.promptTokens(), filter.completionTokens());
    }

    private void summarise(String runId, int requested, int accepted, Map<String, Integer> failures, long start, Path runLogPath) {
        long seconds = (System.nanoTime() - start) / 1_000_000_000;
        log.info("=== run {} complete ===", runId);
        log.info("requested={} accepted={} failures={} elapsed={}s", requested, accepted, failures.isEmpty() ? "none" : failures, seconds);
        if (requested > 0 && seconds > 0) {
            log.info("throughput: {} s/item, {} items/hour", seconds / requested, requested * 3600 / Math.max(1, seconds));
        }
        log.info("run log: {}", runLogPath.toAbsolutePath());
        log.info("readable items: {}", Path.of(properties.itemsMarkdownPath()).toAbsolutePath());
    }

    private static int intArg(ApplicationArguments args, String name, int fallback) {
        if (!args.containsOption(name)) {
            return fallback;
        }
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : Integer.parseInt(values.getFirst());
    }
}
