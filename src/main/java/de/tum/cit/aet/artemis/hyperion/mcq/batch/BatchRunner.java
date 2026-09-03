package de.tum.cit.aet.artemis.hyperion.mcq.batch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ItemProvenance;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.LengthStats;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.FilterScope;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.SnippetSource;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusLoader;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.ItemState;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.Claim;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.ItemKey;

import tools.jackson.core.type.TypeReference;

/**
 * Drives a run to completion, claiming one unit of work at a time from the store.
 * <p>
 * Generation and filtering are separate claims, so a process that dies after generating an item does not
 * discard that item's generation call: on resume the item is claimed again for filtering only. Attempts
 * are counted in the store, so a retry budget survives a restart.
 */
public class BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private static final int SHUTDOWN_TIMEOUT_MINUTES = 30;

    private final RunStore store;

    private final Settings settings;

    private final Dependencies dependencies;

    private volatile BooleanSupplier stopRequested = () -> false;

    /**
     * Everything the runner needs from the surrounding pipeline.
     *
     * @param topicQueries retrieval query per topic key, in run order
     */
    public record Dependencies(SnippetSource snippetSource, GroundingAssemblyService groundingAssembly, McqGenerationService generation, McqFilterService filter,
            ChatClient generationClient, ChatClient filterClient, List<TopicQuery> topicQueries) {
    }

    /**
     * A topic and the text used to retrieve grounding for it.
     *
     * @param key   stable topic identifier recorded on every item
     * @param query text embedded to retrieve grounding
     */
    public record TopicQuery(String key, String query) {
    }

    /**
     * Per-run parameters, fixed for the lifetime of the runner.
     *
     * @param maxOutputAttempts attempts allowed per stage before an item fails permanently
     */
    public record Settings(String runId, String configurationId, int topK, int maxGroundingTokens, int difficulty, String language, String generationModel,
            double generationTemperature, int generationCallAttempts, String filterModel, double filterTemperature, int filterCallAttempts, double acceptThreshold,
            int maxOutputAttempts, int concurrency) {
    }

    public BatchRunner(RunStore store, Settings settings, Dependencies dependencies) {
        this.store = store;
        this.settings = settings;
        this.dependencies = dependencies;
    }

    /**
     * Enqueue work spread evenly across every available topic.
     * <p>
     * Existing rows are left untouched, so calling this on a resumed run adds only what is missing.
     *
     * @param totalItems number of items the run should contain in total
     * @return the number of rows newly created
     */
    public int enqueue(int totalItems) {
        List<TopicQuery> topics = dependencies.topicQueries();
        if (topics.isEmpty()) {
            throw new IllegalStateException("No topics available to enqueue work for");
        }
        List<ItemKey> keys = new ArrayList<>(totalItems);
        for (int i = 0; i < totalItems; i++) {
            TopicQuery topic = topics.get(i % topics.size());
            keys.add(new ItemKey(settings.runId(), settings.configurationId(), topic.key(), i / topics.size()));
        }
        return store.enqueue(keys);
    }

    /**
     * Enqueue a fixed number of items for each of the named topics.
     * <p>
     * Item indices continue from whatever the run already holds for that topic, so enqueueing twice adds
     * further items rather than colliding with existing ones.
     *
     * @param topicKeys     topics to generate for; each must be known to this runner
     * @param itemsPerTopic items to add per topic
     * @return the number of rows newly created
     * @throws IllegalArgumentException if a topic key is not among the available topics
     */
    public int enqueueTopics(List<String> topicKeys, int itemsPerTopic) {
        List<String> known = dependencies.topicQueries().stream().map(TopicQuery::key).toList();
        List<String> unknown = topicKeys.stream().filter(key -> !known.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown topics: " + unknown);
        }
        List<ItemKey> keys = new ArrayList<>();
        for (String topicKey : topicKeys) {
            int offset = store.itemCountForTopic(settings.runId(), settings.configurationId(), topicKey);
            for (int i = 0; i < itemsPerTopic; i++) {
                keys.add(new ItemKey(settings.runId(), settings.configurationId(), topicKey, offset + i));
            }
        }
        return store.enqueue(keys);
    }

    /**
     * Ask the runner to stop claiming new work.
     * <p>
     * Items already claimed run to completion and record their results, so nothing in flight is discarded.
     *
     * @param stopRequested consulted before each claim
     */
    public void onStopRequested(BooleanSupplier stopRequested) {
        this.stopRequested = stopRequested;
    }

    /**
     * Work the run to completion, or until stopping is requested.
     *
     * @return the number of units of work completed by this invocation
     */
    public int run() {
        store.releaseStaleClaims(settings.runId());
        AtomicInteger completed = new AtomicInteger();

        if (settings.concurrency() <= 1) {
            workUntilDrained(completed);
            return completed.get();
        }

        ExecutorService workers = Executors.newFixedThreadPool(settings.concurrency());
        try {
            for (int i = 0; i < settings.concurrency(); i++) {
                workers.submit(() -> workUntilDrained(completed));
            }
            workers.shutdown();
            if (!workers.awaitTermination(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                log.warn("Workers did not finish within {} minutes; leaving remaining work claimed", SHUTDOWN_TIMEOUT_MINUTES);
                workers.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        return completed.get();
    }

    private void workUntilDrained(AtomicInteger completed) {
        while (!Thread.currentThread().isInterrupted()) {
            if (stopRequested.getAsBoolean()) {
                log.info("Stop requested; no further work will be claimed");
                return;
            }
            Optional<Claim> claim = store.claimNext(settings.runId());
            if (claim.isEmpty()) {
                return;
            }
            try {
                process(claim.get());
                completed.incrementAndGet();
            }
            catch (RuntimeException e) {
                log.error("Unexpected error on {}; returning it for another attempt", claim.get().key(), e);
                store.recordFailure(claim.get().key(), claim.get().state(), "internal:" + e.getClass().getSimpleName(), claim.get().callsJson(), true);
            }
        }
    }

    private void process(Claim claim) {
        if (claim.state() == ItemState.GENERATING) {
            generate(claim);
        }
        else {
            filter(claim);
        }
    }

    private void generate(Claim claim) {
        GroundingContext grounding = ground(claim.key().topicKey());
        var result = dependencies.generation().generate(grounding, settings.difficulty(), settings.language(), settings.generationModel(), settings.generationTemperature(),
                settings.generationCallAttempts(), dependencies.generationClient());

        List<CallRecord> calls = append(claim.callsJson(), result.call());
        if (!result.succeeded()) {
            boolean retry = claim.generationAttempts() + 1 < settings.maxOutputAttempts();
            log.warn("Generation of {} failed with {} (attempt {}/{}){}", claim.key(), result.failure(), claim.generationAttempts() + 1, settings.maxOutputAttempts(),
                    retry ? ", will retry" : ", giving up");
            store.recordFailure(claim.key(), ItemState.GENERATING, result.failure().name(), write(calls), retry);
            return;
        }

        ItemProvenance provenance = provenance(claim.key(), grounding, result.item(), result.prompt());
        store.recordGenerated(claim.key(), write(result.item()), write(provenance), write(calls));
        log.info("Generated {} | {}", claim.key().topicKey(), result.item().title());
    }

    private void filter(Claim claim) {
        McqItem item = read(claim.generatedItemJson(), new TypeReference<McqItem>() {
        });
        ItemProvenance provenance = read(claim.provenanceJson(), new TypeReference<ItemProvenance>() {
        });
        GroundingContext grounding = ground(provenance.topic());

        var result = dependencies.filter().evaluate(item, grounding, FilterScope.GENERAL, null, settings.acceptThreshold(), settings.filterModel(), settings.filterTemperature(),
                settings.filterCallAttempts(), dependencies.filterClient());

        List<CallRecord> calls = append(claim.callsJson(), result.call());
        if (!result.succeeded()) {
            boolean retry = claim.filterAttempts() + 1 < settings.maxOutputAttempts();
            log.warn("Filtering of {} failed (attempt {}/{}){}", claim.key(), claim.filterAttempts() + 1, settings.maxOutputAttempts(), retry ? ", will retry" : ", giving up");
            store.recordFailure(claim.key(), ItemState.FILTERING, "FILTER_UNPARSEABLE", write(calls), retry);
            return;
        }

        store.recordFiltered(claim.key(), write(result.decision()), write(calls));
        log.info("Filtered {} | {} | accepted={} score={}", claim.key().topicKey(), item.title(), result.decision().accepted(),
                String.format("%.2f", result.decision().aggregateScore()));
    }

    private GroundingContext ground(String topicKey) {
        String query = dependencies.topicQueries().stream().filter(topic -> topic.key().equals(topicKey)).map(TopicQuery::query).findFirst().orElse(topicKey);
        return dependencies.groundingAssembly().assemble(topicKey, dependencies.snippetSource().search(query, settings.topK(), null), settings.maxGroundingTokens());
    }

    private ItemProvenance provenance(ItemKey key, GroundingContext grounding, McqItem item, String prompt) {
        List<String> chunkIds = grounding.snippets().stream().map(snippet -> snippet.chunkId()).toList();
        boolean damaged = CorpusLoader.looksDamaged(item.questionText()) || item.options().stream().anyMatch(option -> CorpusLoader.looksDamaged(option.text()));
        return new ItemProvenance(key.runId(), key.configurationId(), settings.generationModel(), settings.filterModel(), key.topicKey(), chunkIds, prompt, settings.difficulty(),
                LengthStats.of(item), damaged, grounding.composition(), Instant.now());
    }

    private List<CallRecord> append(String existingJson, CallRecord call) {
        List<CallRecord> calls = new ArrayList<>();
        if (existingJson != null && !existingJson.isBlank()) {
            calls.addAll(read(existingJson, new TypeReference<List<CallRecord>>() {
            }));
        }
        calls.add(call);
        return calls;
    }

    private static String write(Object value) {
        return StructuredOutputs.outputMapper().writeValueAsString(value);
    }

    private static <T> T read(String json, TypeReference<T> type) {
        return StructuredOutputs.outputMapper().readValue(json, type);
    }
}
