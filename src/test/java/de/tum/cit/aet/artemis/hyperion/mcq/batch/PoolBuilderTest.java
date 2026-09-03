package de.tum.cit.aet.artemis.hyperion.mcq.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.SnippetSource;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Course;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;

class PoolBuilderTest {

    private static final PoolCell CELL_A = new PoolCell("EIDI", "arrays", Language.DE, QuestionType.SINGLE_CHOICE, Difficulty.MEDIUM);

    @TempDir
    private Path directory;

    private RunStore store;

    private ChatModel generatorModel;

    private ChatModel judgeModel;

    private PoolBuilder builder;

    /** Snippets per competency key: retrieval returns the competency whose title appears in the query. */
    private final SnippetSource snippets = (query, limit, courseKey) -> {
        String document = query.contains("Arrays") ? "arrays-deck.pdf" : "streams-deck.pdf";
        return List.of(snippet(document, 1), snippet(document, 2), snippet(document, 3), snippet(document, 4));
    };

    @BeforeEach
    void setUp() {
        store = new RunStore(directory.resolve("run.db"));
        store.registerRun("run1", "two-phase|gen|judge", "manifest");
        generatorModel = mock(ChatModel.class);
        judgeModel = mock(ChatModel.class);
        lenient().when(generatorModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        lenient().when(judgeModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        PromptTemplateService templates = new PromptTemplateService();
        builder = new PoolBuilder(store, settings(2), new PoolBuilder.Dependencies(manifest(), snippets, new GroundingAssemblyService(),
                new McqGenerationService(templates), new McqFilterService(templates), store));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void build_fillsEveryCellSpanningTheSubsections() {
        when(generatorModel.call(any(Prompt.class))).thenAnswer(_ -> response(quizJson()));
        when(judgeModel.call(any(Prompt.class))).thenAnswer(_ -> response(verdictJson(0.0)));

        int created = builder.enqueue(hashes("v1"));
        int completed = builder.build(ChatClient.create(generatorModel), ChatClient.create(judgeModel));

        assertThat(created).isEqualTo(4);
        assertThat(completed).isEqualTo(8);
        List<RunStore.PoolCandidate> candidates = store.poolCandidates(CELL_A, "judge-model", null);
        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(RunStore.PoolCandidate::sectionIndex).containsExactlyInAnyOrder(0, 1);
        assertThat(store.poolCandidates(cellB(), "judge-model", null)).hasSize(2);
    }

    @Test
    void enqueue_isANoOpOnAnUnchangedCorpus() {
        assertThat(builder.enqueue(hashes("v1"))).isEqualTo(4);
        assertThat(builder.enqueue(hashes("v1"))).isZero();
    }

    @Test
    void enqueue_growsOnlyCellsRetrievingFromAChangedDocument() {
        assertThat(builder.enqueue(hashes("v1"))).isEqualTo(4);

        Map<String, String> changed = hashes("v1");
        changed.put("arrays-deck.pdf", "v2");
        int created = builder.enqueue(changed);

        assertThat(created).isEqualTo(2);
        assertThat(store.itemCountForTopic("run1", "two-phase|gen|judge", CELL_A.key())).isEqualTo(4);
        assertThat(store.itemCountForTopic("run1", "two-phase|gen|judge", cellB().key())).isEqualTo(2);
    }

    @Test
    void judgeWith_addsASecondJudgesVerdictsWithoutRegenerating() {
        when(generatorModel.call(any(Prompt.class))).thenAnswer(_ -> response(quizJson()));
        when(judgeModel.call(any(Prompt.class))).thenAnswer(_ -> response(verdictJson(0.0)));
        builder.enqueue(hashes("v1"));
        builder.build(ChatClient.create(generatorModel), ChatClient.create(judgeModel));
        ChatModel secondJudge = mock(ChatModel.class);
        lenient().when(secondJudge.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(secondJudge.call(any(Prompt.class))).thenAnswer(_ -> response(verdictJson(0.9)));
        org.mockito.Mockito.clearInvocations(generatorModel);

        int judged = builder.judgeWith("second-judge", 0.2, 1, ChatClient.create(secondJudge));

        assertThat(judged).isEqualTo(4);
        org.mockito.Mockito.verifyNoInteractions(generatorModel);
        assertThat(store.poolCandidates(CELL_A, "judge-model", null)).hasSize(2);
        assertThat(store.poolCandidates(CELL_A, "second-judge", null)).isEmpty();
    }

    private PoolBuilder.Settings settings(int subsections) {
        return new PoolBuilder.Settings("run1", "two-phase|gen|judge", "EIDI", Set.of(Language.DE), Set.of(QuestionType.SINGLE_CHOICE), Set.of(Difficulty.MEDIUM), 2, subsections,
                8, 6000, 0.7, null, "gen-model", 0.7, 1, "judge-model", 0.2, 1, 2);
    }

    private static PoolCell cellB() {
        return new PoolCell("EIDI", "streams", Language.DE, QuestionType.SINGLE_CHOICE, Difficulty.MEDIUM);
    }

    private static CompetencyManifest manifest() {
        return new CompetencyManifest(new Course("EIDI", "EIDI", ""),
                List.of(new Competency("arrays", "Arrays", "Du kannst Arrays erstellen.", null, Taxonomy.APPLY, false, null, List.of(), List.of(), List.of()),
                        new Competency("streams", "Streams", "Du kannst Streams verwenden.", null, Taxonomy.APPLY, false, null, List.of(), List.of(), List.of())));
    }

    private static Map<String, String> hashes(String version) {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("arrays-deck.pdf", version);
        hashes.put("streams-deck.pdf", version);
        return hashes;
    }

    private static Snippet snippet(String document, int page) {
        return new Snippet("Deck", document, "Page " + page, "PUT is idempotent. DELETE and PATCH are HTTP methods. Page " + page, document + "#" + page + "-" + page,
                SourceRole.LECTURE_DECK, 0.9);
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static String quizJson() {
        return """
                { "questions": [
                  { "type": "single-choice", "title": "Idempotenz von HTTP", "questionText": "Welche Methode ist idempotent?",
                    "options": [ { "text": "PUT", "correct": true }, { "text": "POST", "correct": false },
                                 { "text": "PATCH", "correct": false }, { "text": "CONNECT", "correct": false } ],
                    "explanation": "PUT ist idempotent." } ] }
                """;
    }

    private static String verdictJson(double severity) {
        return """
                { "rationale": "checked", "modes": [
                  { "mode": "FACTUAL_ERROR", "severity": %s, "triggered": false, "justification": "a" },
                  { "mode": "AMBIGUOUS_CORRECT_ANSWER", "severity": 0.0, "triggered": false, "justification": "b" },
                  { "mode": "OFF_TOPIC", "severity": 0.0, "triggered": false, "justification": "c" },
                  { "mode": "NEAR_DUPLICATE", "severity": 0.0, "triggered": false, "justification": "d" },
                  { "mode": "ILL_FORMED_DISTRACTORS", "severity": 0.0, "triggered": false, "justification": "e" } ] }
                """.formatted(severity);
    }
}
