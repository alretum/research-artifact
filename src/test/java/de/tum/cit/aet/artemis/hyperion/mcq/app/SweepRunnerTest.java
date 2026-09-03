package de.tum.cit.aet.artemis.hyperion.mcq.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import de.tum.cit.aet.artemis.hyperion.mcq.approach.AgenticApproach;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.TwoPhaseApproach;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.SnippetSource;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Course;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelRegistry;
import de.tum.cit.aet.artemis.hyperion.mcq.select.PoolSelectionService;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;

class SweepRunnerTest {

    private static final Pattern CANDIDATE_ID = Pattern.compile("\\[(\\d+)\\]");

    @TempDir
    private Path directory;

    private RunStore store;

    private ChatModel model;

    private SweepRunner runner;

    @BeforeEach
    void setUp() {
        store = new RunStore(directory.resolve("run.db"));
        model = mock(ChatModel.class);
        lenient().when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> respondByRole(invocation.getArgument(0)));
        runner = runner(plan(2));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void run_assemblesEveryConfigurationRequestAndRepetition() {
        int assembled = runner.run(Map.of("deck.pdf", "v1"));

        assertThat(assembled).isEqualTo(4);
        List<RunStore.StoredQuiz> quizzes = store.quizzes("test-sweep");
        assertThat(quizzes).hasSize(4);
        assertThat(quizzes).allSatisfy(quiz -> assertThat(quiz.complete()).isTrue());
        assertThat(quizzes).extracting(RunStore.StoredQuiz::configurationId).containsExactlyInAnyOrder("agentic|m|m", "agentic|m|m", "two-phase|m|m", "two-phase|m|m");
    }

    @Test
    void run_resumesWithoutASingleModelCall() {
        runner.run(Map.of("deck.pdf", "v1"));
        clearInvocations(model);

        int assembled = runner(plan(2)).run(Map.of("deck.pdf", "v1"));

        assertThat(assembled).isZero();
        verifyNoInteractions(model);
    }

    @Test
    void run_refusesWhenThePlanChangedSinceTheRunWasRegistered() {
        runner.run(Map.of("deck.pdf", "v1"));

        assertThatThrownBy(() -> runner(plan(3)).run(Map.of("deck.pdf", "v1"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("different configuration");
    }

    private SweepRunner runner(SweepPlan plan) {
        PromptTemplateService templates = new PromptTemplateService();
        McqGenerationService generation = new McqGenerationService(templates);
        McqFilterService filter = new McqFilterService(templates);
        GroundingAssemblyService grounding = new GroundingAssemblyService();
        SnippetSource snippets = (query, limit, courseKey) -> List.of(snippet(1), snippet(2), snippet(3), snippet(4));
        ModelCatalogue catalogue = new ModelCatalogue(Map.of("main", new ModelCatalogue.Backend("main", "http://localhost/v1", "PATH", true)),
                Map.of("m", new ModelCatalogue.ModelEntry("m", "main", "test-model")));
        ModelRegistry registry = new ModelRegistry(catalogue, ChatClient.create(model));
        GenerationRequest request = new GenerationRequest("r1", "EIDI", null, List.of("arrays"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 1, Difficulty.MEDIUM);
        PipelineProperties properties = properties();
        return new SweepRunner(plan, List.of(request), new SweepRunner.Dependencies(Map.of("EIDI", manifest()), snippets, grounding, generation, filter,
                new AgenticApproach(grounding, generation, filter), new TwoPhaseApproach(store, new PoolSelectionService(templates)), store, registry, properties));
    }

    private static SweepPlan plan(int repetitions) {
        return new SweepPlan("test-sweep", "unused.yml", repetitions,
                new SweepPlan.Pool(2, 2, 4, Set.of(Language.DE), Set.of(QuestionType.SINGLE_CHOICE), Set.of(Difficulty.MEDIUM)), new SweepPlan.Selection(40, 0.7, 1),
                new SweepPlan.Agentic(3), List.of(new SweepPlan.Configuration("agentic-m", SweepPlan.Approach.AGENTIC, "m", "m", "m"),
                        new SweepPlan.Configuration("two-phase-m", SweepPlan.Approach.TWO_PHASE, "m", "m", "m")));
    }

    private static PipelineProperties properties() {
        return new PipelineProperties("corpus", "data/run-log.jsonl", "data/items.md", "data/extraction.csv", "data/topics.csv", "data/probe.csv", "config/pricing.yml",
                "config/models.yml", "data/benchmark", "config/runs", "", "config/competencies.yml", "de", List.of(50), new PipelineProperties.Chunking(500, 1200, "data/index"),
                new PipelineProperties.Retrieval(4, 6000), new PipelineProperties.Generation("test-model", 0.7, 1), new PipelineProperties.Filter("test-model", 0.2, 1, 0.7, null),
                new PipelineProperties.Batch("data/run.db", 1, 2));
    }

    /** One mock serves all four roles, telling them apart by the prompt's own wording. */
    private ChatResponse respondByRole(Prompt prompt) {
        String text = prompt.getContents();
        if (text.contains("## Candidates")) {
            Matcher matcher = CANDIDATE_ID.matcher(text.substring(text.indexOf("## Candidates")));
            matcher.find();
            return response("{ \"chosen\": [" + matcher.group(1) + "], \"rejected\": [], \"rationale\": \"fits\" }");
        }
        if (text.contains("COMPETENCY_MISMATCH")) {
            return response(verdictJson(true));
        }
        if (text.contains("FACTUAL_ERROR")) {
            return response(verdictJson(false));
        }
        return response("""
                { "questions": [
                  { "type": "single-choice", "title": "Idempotenz", "questionText": "Welche Methode ist idempotent?",
                    "options": [ { "text": "PUT", "correct": true }, { "text": "POST", "correct": false },
                                 { "text": "PATCH", "correct": false }, { "text": "CONNECT", "correct": false } ],
                    "explanation": "PUT ist idempotent." } ] }
                """);
    }

    private static String verdictJson(boolean combined) {
        String fitModes = """
                , { "mode": "COMPETENCY_MISMATCH", "severity": 0.0, "triggered": false, "justification": "f" },
                  { "mode": "DIFFICULTY_MISMATCH", "severity": 0.0, "triggered": false, "justification": "g" },
                  { "mode": "INSTRUCTION_VIOLATION", "severity": 0.0, "triggered": false, "justification": "h" }""";
        return """
                { "rationale": "checked", "modes": [
                  { "mode": "FACTUAL_ERROR", "severity": 0.0, "triggered": false, "justification": "a" },
                  { "mode": "AMBIGUOUS_CORRECT_ANSWER", "severity": 0.0, "triggered": false, "justification": "b" },
                  { "mode": "OFF_TOPIC", "severity": 0.0, "triggered": false, "justification": "c" },
                  { "mode": "NEAR_DUPLICATE", "severity": 0.0, "triggered": false, "justification": "d" },
                  { "mode": "ILL_FORMED_DISTRACTORS", "severity": 0.0, "triggered": false, "justification": "e" }%s ] }
                """.formatted(combined ? fitModes : "");
    }

    private static CompetencyManifest manifest() {
        return new CompetencyManifest(new Course("EIDI", "EIDI", ""),
                List.of(new Competency("arrays", "Arrays", "Du kannst Arrays erstellen.", null, Taxonomy.APPLY, false, null, List.of(), List.of(), List.of())));
    }

    private static Snippet snippet(int page) {
        return new Snippet("Deck", "deck.pdf", "Page " + page, "PUT ist idempotent. Seite " + page, "deck.pdf#" + page + "-" + page, SourceRole.LECTURE_DECK, 0.9);
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
