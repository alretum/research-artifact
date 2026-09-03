package de.tum.cit.aet.artemis.hyperion.mcq.approach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
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

import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.ApproachContext;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.ModelCall;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.Quiz;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.SelectionSettings;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Course;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;
import de.tum.cit.aet.artemis.hyperion.mcq.select.PoolSelectionService;
import de.tum.cit.aet.artemis.hyperion.mcq.store.ItemState;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.ItemKey;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.PoolItem;

class TwoPhaseApproachTest {

    private static final PoolCell CELL = new PoolCell("EIDI", "arrays", Language.DE, QuestionType.SINGLE_CHOICE, Difficulty.MEDIUM);

    @TempDir
    private Path directory;

    private RunStore store;

    private ChatModel selectorModel;

    private TwoPhaseApproach approach;

    private ApproachContext context;

    private List<Long> pooledIds;

    @BeforeEach
    void setUp() {
        store = new RunStore(directory.resolve("run.db"));
        store.registerRun("run1", "two-phase|gen|judge", "manifest");
        selectorModel = mock(ChatModel.class);
        lenient().when(selectorModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        approach = new TwoPhaseApproach(store, new PoolSelectionService(new PromptTemplateService()));
        context = new ApproachContext(manifest(), (query, limit, courseKey) -> List.of(), new ModelCall(null, "gen-model", 0.7, 1), new ModelCall(null, "judge-model", 0.2, 1), 8,
                6000, 0.7, 3, new SelectionSettings(new ModelCall(ChatClient.create(selectorModel), "selector-model", 0.7, 1), 40, null));
        pooledIds = seedPool(4);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void generate_assemblesTheQuizFromChosenCandidatesWithTheirPoolDecisions() {
        respondWith(chosen(pooledIds.get(2), pooledIds.get(0)));

        Quiz quiz = approach.generate(request(2), context);

        assertThat(quiz.complete()).isTrue();
        assertThat(quiz.accepted()).hasSize(2);
        assertThat(quiz.accepted().getFirst().item().title()).isEqualTo("Q2");
        assertThat(quiz.accepted().getFirst().decision()).isNotNull();
        assertThat(quiz.accepted().getFirst().decision().accepted()).isTrue();
        assertThat(quiz.generatedCount()).isZero();
        assertThat(quiz.calls()).hasSize(1);
    }

    @Test
    void generate_repetitionsDifferWhenTheSelectorChoosesDifferently() {
        when(selectorModel.call(any(Prompt.class))).thenReturn(response(chosen(pooledIds.get(0), pooledIds.get(1))), response(chosen(pooledIds.get(2), pooledIds.get(3))),
                response(chosen(pooledIds.get(1), pooledIds.get(3))));

        Set<Set<String>> quizzes = new LinkedHashSet<>();
        for (int repetition = 0; repetition < 3; repetition++) {
            Quiz quiz = approach.generate(request(2), context);
            quizzes.add(Set.copyOf(quiz.accepted().stream().map(question -> question.item().title()).toList()));
        }

        assertThat(quizzes).hasSize(3);
    }

    @Test
    void generate_returnsAnIncompleteQuizWhenThePoolHasNoCandidates() {
        GenerationRequest hard = new GenerationRequest("r2", "EIDI", null, List.of("arrays"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 2, Difficulty.HARD);

        Quiz quiz = approach.generate(hard, context);

        assertThat(quiz.complete()).isFalse();
        assertThat(quiz.accepted()).isEmpty();
        verifyNoInteractions(selectorModel);
    }

    @Test
    void generate_rejectsAFreeTopicRequest() {
        GenerationRequest freeTopic = new GenerationRequest("r3", "EIDI", "Arrays", null, null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 2, Difficulty.MEDIUM);

        assertThatThrownBy(() -> approach.generate(freeTopic, context)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("free-topic");
    }

    /** Seeds accepted pool items Q0..Qn-1 and one item the judge rejected, which must never be selectable. */
    private List<Long> seedPool(int accepted) {
        List<PoolItem> items = new java.util.ArrayList<>();
        for (int index = 0; index <= accepted; index++) {
            items.add(new PoolItem(new ItemKey("run1", "two-phase|gen|judge", CELL.key(), index), CELL, index % 2, "gen-model"));
        }
        store.enqueuePool(items);
        int generated = 0;
        while (generated <= accepted) {
            var claim = store.claimNext("run1").orElseThrow();
            if (claim.state() == ItemState.FILTERING) {
                store.recordFiltered(claim.key(), "{}", "[]");
            }
            else {
                store.recordGenerated(claim.key(), itemJson("Q" + claim.key().itemIndex()), "{}", "[]");
                generated++;
            }
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (int index = 0; index <= accepted; index++) {
            long id = store.rowIdOf(new ItemKey("run1", "two-phase|gen|judge", CELL.key(), index)).orElseThrow();
            boolean accept = index < accepted;
            store.recordVerdict(id, "judge-model", "GENERAL", accept, "{\"accepted\":" + accept + ",\"aggregateScore\":1.0,\"meanSeverity\":0.0,\"modeVerdicts\":{},"
                    + "\"filterModel\":\"judge-model\",\"rationale\":\"r\"}");
            if (accept) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static GenerationRequest request(int questions) {
        return new GenerationRequest("r1", "EIDI", null, List.of("arrays"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), questions, Difficulty.MEDIUM);
    }

    private static CompetencyManifest manifest() {
        return new CompetencyManifest(new Course("EIDI", "EIDI", ""),
                List.of(new Competency("arrays", "Arrays", "Du kannst Arrays erstellen.", null, Taxonomy.APPLY, false, null, List.of(), List.of(), List.of())));
    }

    private static String itemJson(String title) {
        return """
                { "type": "SINGLE_CHOICE", "title": "%s", "questionText": "Which method is idempotent?",
                  "options": [ { "text": "PUT", "correct": true }, { "text": "POST", "correct": false },
                               { "text": "PATCH", "correct": false }, { "text": "GET", "correct": false } ],
                  "hint": null, "explanation": "PUT is idempotent." }
                """.formatted(title);
    }

    private String chosen(long first, long second) {
        return "{ \"chosen\": [" + first + ", " + second + "], \"rejected\": [], \"rationale\": \"fits\" }";
    }

    private void respondWith(String content) {
        when(selectorModel.call(any(Prompt.class))).thenReturn(response(content));
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
