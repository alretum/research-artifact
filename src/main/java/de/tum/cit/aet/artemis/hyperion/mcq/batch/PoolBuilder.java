package de.tum.cit.aet.artemis.hyperion.mcq.batch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import tools.jackson.core.type.TypeReference;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ItemProvenance;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.LengthStats;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.FilterScope;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.SnippetSource;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.SubsectionPartitioner;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.ItemState;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.Claim;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.ItemKey;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.PoolItem;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.UnjudgedItem;

/**
 * Builds and maintains the question pool for one course: every cell of the input grid, filled with
 * questions grounded in one subsection each, judged at {@link FilterScope#GENERAL} at pool entry.
 * <p>
 * The build is incremental. Each document's content hash is compared to the hash recorded on the previous
 * build; a cell is touched when its competency's retrieval draws on a new or changed document. A touched
 * cell grows by {@code itemsPerCell} further questions, an untouched cell that already holds its target is
 * left alone, and re-running against an unchanged corpus enqueues nothing. Grounding for an item is
 * recomputed from its cell and stored section index, so judging can happen in a later pass — or by a second
 * judge — without persisting prompt-sized grounding per item.
 */
public class PoolBuilder {

    private static final Logger log = LoggerFactory.getLogger(PoolBuilder.class);

    /**
     * Everything the builder needs from the surrounding pipeline.
     */
    public record Dependencies(CompetencyManifest manifest, SnippetSource snippets, GroundingAssemblyService groundingAssembly, McqGenerationService generation,
            McqFilterService filter, RunStore store) {
    }

    /**
     * Parameters of one pool build, fixed for the builder's lifetime.
     *
     * @param itemsPerCell    questions a fresh cell is filled with, and a touched cell grows by
     * @param subsections     groups a competency's retrieved material is cut into
     * @param retrievalTopM   snippets retrieved per competency before partitioning
     * @param gatingModes     failure modes whose severity decides pool acceptance
     */
    public record Settings(String runId, String configurationId, String courseKey, Set<Language> languages, Set<QuestionType> questionTypes, Set<Difficulty> difficulties,
            int itemsPerCell, int subsections, int retrievalTopM, int maxGroundingTokens, double acceptThreshold, Set<FailureMode> gatingModes, String generatorModel,
            double generatorTemperature, int generatorCallAttempts, String judgeModel, double judgeTemperature, int judgeCallAttempts, int maxOutputAttempts) {
    }

    private final RunStore store;

    private final Settings settings;

    private final Dependencies dependencies;

    public PoolBuilder(RunStore store, Settings settings, Dependencies dependencies) {
        this.store = store;
        this.settings = settings;
        this.dependencies = dependencies;
    }

    /**
     * Derive the touched cells from the given document hashes and enqueue their items.
     * <p>
     * On the first build every cell is filled to {@code itemsPerCell}. Afterwards only cells whose
     * competency retrieves from a new or changed document gain items, and the given hashes replace the
     * recorded ones.
     *
     * @param documentHashes content hash per corpus-relative document path, as the corpus stands now
     * @return the number of rows newly created
     */
    public int enqueue(Map<String, String> documentHashes) {
        Map<String, String> previous = store.documentHashes(settings.courseKey());
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : documentHashes.entrySet()) {
            if (!entry.getValue().equals(previous.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }

        List<PoolCell> grid = PoolGrid.derive(settings.courseKey(), dependencies.manifest(), settings.languages(), settings.questionTypes(), settings.difficulties());
        List<PoolItem> items = new ArrayList<>();
        for (PoolCell cell : grid) {
            int existing = store.itemCountForTopic(settings.runId(), settings.configurationId(), cell.key());
            int target;
            if (existing == 0) {
                target = settings.itemsPerCell();
            }
            else if (!previous.isEmpty() && !changed.isEmpty() && retrievesFromChangedDocument(cell, changed)) {
                target = existing + settings.itemsPerCell();
            }
            else {
                target = existing;
            }
            for (int index = existing; index < target; index++) {
                items.add(new PoolItem(new ItemKey(settings.runId(), settings.configurationId(), cell.key(), index), cell, index % settings.subsections(),
                        settings.generatorModel()));
            }
        }

        int created = store.enqueuePool(items);
        documentHashes.forEach((document, hash) -> store.recordDocumentHash(settings.courseKey(), document, hash));
        log.info("Pool enqueue for {}: {} cells, {} changed documents, {} new items", settings.courseKey(), grid.size(), changed.size(), created);
        return created;
    }

    /**
     * Work the pool build to completion: generate every pending item and judge every generated one with the
     * build's own judge.
     *
     * @param generatorClient client for the generator backend
     * @param judgeClient     client for the judge backend
     * @return the number of units of work completed
     */
    public int build(ChatClient generatorClient, ChatClient judgeClient) {
        store.releaseStaleClaims(settings.runId());
        int completed = 0;
        while (true) {
            var claim = store.claimNext(settings.runId());
            if (claim.isEmpty()) {
                return completed;
            }
            if (claim.get().state() == ItemState.GENERATING) {
                generate(claim.get(), generatorClient);
            }
            else {
                judge(claim.get(), judgeClient);
            }
            completed++;
        }
    }

    /**
     * Add a second judge's {@link FilterScope#GENERAL} verdicts over items already generated, without
     * regenerating anything and without touching item states.
     *
     * @param judgeModel  the additional judge
     * @param temperature sampling temperature for it
     * @param maxAttempts transport attempts per call including the first
     * @param client      client for the judge's backend
     * @return the number of verdicts recorded
     */
    public int judgeWith(String judgeModel, double temperature, int maxAttempts, ChatClient client) {
        int judged = 0;
        List<UnjudgedItem> missing = store.itemsMissingVerdict(judgeModel, FilterScope.GENERAL.name(), Integer.MAX_VALUE);
        for (UnjudgedItem unjudged : missing) {
            PoolCell cell = PoolCell.fromKey(unjudged.cellKey());
            GroundingContext grounding = ground(cell, unjudged.sectionIndex());
            McqItem item = read(unjudged.itemJson());
            McqFilterService.Result result = dependencies.filter().evaluate(item, grounding, FilterScope.GENERAL, null, settings.acceptThreshold(), settings.gatingModes(),
                    judgeModel, temperature, maxAttempts, client);
            if (!result.succeeded()) {
                log.warn("Judge {} failed on item {}: {}", judgeModel, unjudged.key(), result.call().failureCategory());
                continue;
            }
            store.recordVerdict(unjudged.id(), judgeModel, FilterScope.GENERAL.name(), result.decision().accepted(), write(result.decision()));
            judged++;
        }
        log.info("Judge {} decided {} of {} unjudged items", judgeModel, judged, missing.size());
        return judged;
    }

    private void generate(Claim claim, ChatClient client) {
        PoolCell cell = PoolCell.fromKey(claim.key().topicKey());
        Competency competency = competencyOf(cell);
        GroundingContext grounding = ground(cell, claim.sectionIndex());
        GenerationRequest request = cellRequest(cell);

        McqGenerationService.QuizResult result = dependencies.generation().generateQuiz(request, competencyBlock(competency), grounding, 1, settings.generatorModel(),
                settings.generatorTemperature(), settings.generatorCallAttempts(), client);
        List<CallRecord> calls = append(claim.callsJson(), result.call());
        if (result.failure() != null || result.items().isEmpty()) {
            boolean retry = claim.generationAttempts() + 1 < settings.maxOutputAttempts();
            log.warn("Generation of {} failed with {} (attempt {}/{}){}", claim.key(), result.failure(), claim.generationAttempts() + 1, settings.maxOutputAttempts(),
                    retry ? ", will retry" : ", giving up");
            store.recordFailure(claim.key(), ItemState.GENERATING, result.failure() == null ? "VALIDATION_VIOLATION" : result.failure().name(), write(calls), retry);
            return;
        }
        store.recordGenerated(claim.key(), write(result.items().getFirst()), write(provenance(claim, cell, grounding, result)), write(calls));
    }

    private void judge(Claim claim, ChatClient client) {
        PoolCell cell = PoolCell.fromKey(claim.key().topicKey());
        GroundingContext grounding = ground(cell, claim.sectionIndex());
        McqItem item = read(claim.generatedItemJson());

        McqFilterService.Result result = dependencies.filter().evaluate(item, grounding, FilterScope.GENERAL, null, settings.acceptThreshold(), settings.gatingModes(),
                settings.judgeModel(), settings.judgeTemperature(), settings.judgeCallAttempts(), client);
        List<CallRecord> calls = append(claim.callsJson(), result.call());
        if (!result.succeeded()) {
            boolean retry = claim.filterAttempts() + 1 < settings.maxOutputAttempts();
            log.warn("Judging of {} failed (attempt {}/{}){}", claim.key(), claim.filterAttempts() + 1, settings.maxOutputAttempts(), retry ? ", will retry" : ", giving up");
            store.recordFailure(claim.key(), ItemState.FILTERING, result.call().failureCategory() == null ? "FILTER_UNPARSEABLE" : result.call().failureCategory(), write(calls),
                    retry);
            return;
        }
        store.recordFiltered(claim.key(), write(result.decision()), write(calls));
        store.rowIdOf(claim.key())
                .ifPresent(rowId -> store.recordVerdict(rowId, settings.judgeModel(), FilterScope.GENERAL.name(), result.decision().accepted(), write(result.decision())));
    }

    private GroundingContext ground(PoolCell cell, int sectionIndex) {
        Competency competency = competencyOf(cell);
        List<Snippet> retrieved = dependencies.snippets().search(competency.retrievalQuery(), settings.retrievalTopM(), cell.courseKey());
        List<List<Snippet>> sections = SubsectionPartitioner.partitionSnippets(retrieved, settings.subsections());
        List<Snippet> section = sections.get(Math.min(sectionIndex, sections.size() - 1));
        return dependencies.groundingAssembly().assemble(competency.title(), section, settings.maxGroundingTokens());
    }

    private boolean retrievesFromChangedDocument(PoolCell cell, Set<String> changedDocuments) {
        Competency competency = competencyOf(cell);
        return dependencies.snippets().search(competency.retrievalQuery(), settings.retrievalTopM(), cell.courseKey()).stream()
                .anyMatch(snippet -> changedDocuments.stream().anyMatch(document -> snippet.chunkId().startsWith(document)));
    }

    private Competency competencyOf(PoolCell cell) {
        return dependencies.manifest().byKey(cell.competencyKey())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cell " + cell.key() + " names competency '" + cell.competencyKey() + "', which the course model does not declare"));
    }

    private GenerationRequest cellRequest(PoolCell cell) {
        return new GenerationRequest("pool-" + cell.key(), cell.courseKey(), null, List.of(cell.competencyKey()), null, cell.language(), Set.of(cell.questionType()), 1,
                cell.difficulty());
    }

    private static String competencyBlock(Competency competency) {
        String description = competency.description() == null ? "" : "\n" + competency.description();
        return competency.title() + " (" + competency.taxonomy() + ")" + description;
    }

    private ItemProvenance provenance(Claim claim, PoolCell cell, GroundingContext grounding, McqGenerationService.QuizResult result) {
        List<String> chunkIds = grounding.snippets().stream().map(Snippet::chunkId).toList();
        return new ItemProvenance(claim.key().runId(), claim.key().configurationId(), settings.generatorModel(), settings.judgeModel(), cell.competencyKey(), chunkIds,
                result.prompt(), claim.difficulty(), LengthStats.of(result.items().getFirst()), false, grounding.composition(), java.time.Instant.now());
    }

    private static List<CallRecord> append(String callsJson, CallRecord call) {
        List<CallRecord> calls = callsJson == null || callsJson.isBlank() ? new ArrayList<>()
                : new ArrayList<>(StructuredOutputs.outputMapper().readValue(callsJson, new TypeReference<List<CallRecord>>() {
                }));
        calls.add(call);
        return calls;
    }

    private static McqItem read(String json) {
        return StructuredOutputs.outputMapper().readValue(json, new TypeReference<McqItem>() {
        });
    }

    private static String write(Object value) {
        return StructuredOutputs.outputMapper().writeValueAsString(value);
    }
}
