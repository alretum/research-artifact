package de.tum.cit.aet.artemis.hyperion.mcq.approach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Course;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;

class AgenticApproachTest {

    private ChatModel generatorModel;

    private ChatModel judgeModel;

    private AgenticApproach approach;

    private ApproachContext context;

    @BeforeEach
    void setUp() {
        generatorModel = mock(ChatModel.class);
        judgeModel = mock(ChatModel.class);
        lenient().when(generatorModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        lenient().when(judgeModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        PromptTemplateService templates = new PromptTemplateService();
        approach = new AgenticApproach(new GroundingAssemblyService(), new McqGenerationService(templates), new McqFilterService(templates));
        context = new ApproachContext(manifest(), (query, limit, courseKey) -> List.of(snippet()), new ModelCall(ChatClient.create(generatorModel), "gen-model", 0.7, 1),
                new ModelCall(ChatClient.create(judgeModel), "judge-model", 0.2, 1), 8, 6000, 0.7, 3, null);
    }

    @Test
    void generate_returnsExactlyTheRequestedNumberOfAcceptedQuestions() {
        when(generatorModel.call(any(Prompt.class))).thenReturn(response(quiz(question("Q one", "PUT"), question("Q two", "DELETE"))));
        when(judgeModel.call(any(Prompt.class))).thenReturn(response(verdict(0.0)), response(verdict(0.0)));

        Quiz quiz = approach.generate(request(2), context);

        assertThat(quiz.complete()).isTrue();
        assertThat(quiz.accepted()).hasSize(2);
        assertThat(quiz.rejected()).isEmpty();
        assertThat(quiz.generatedCount()).isEqualTo(2);
        assertThat(quiz.calls()).hasSize(3);
    }

    @Test
    void generate_topsUpTheShortfallWhenTheFilterRejects() {
        when(generatorModel.call(any(Prompt.class))).thenReturn(response(quiz(question("Q one", "PUT"), question("Q two", "DELETE"))),
                response(quiz(question("Q three", "PATCH"))));
        when(judgeModel.call(any(Prompt.class))).thenReturn(response(verdict(0.0)), response(verdict(0.9)), response(verdict(0.0)));

        Quiz quiz = approach.generate(request(2), context);

        assertThat(quiz.complete()).isTrue();
        assertThat(quiz.accepted()).hasSize(2);
        assertThat(quiz.rejected()).hasSize(1);
        assertThat(quiz.generatedCount()).isEqualTo(3);
        verify(generatorModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void generate_stopsAfterMaxRoundsAndReportsTheQuizIncomplete() {
        when(generatorModel.call(any(Prompt.class))).thenReturn(response(quiz(question("Q one", "PUT"))), response(quiz(question("Q two", "DELETE"))),
                response(quiz(question("Q three", "PATCH"))));
        when(judgeModel.call(any(Prompt.class))).thenReturn(response(verdict(0.9)));

        Quiz quiz = approach.generate(request(2), context);

        assertThat(quiz.complete()).isFalse();
        assertThat(quiz.accepted()).isEmpty();
        assertThat(quiz.rejected()).hasSize(3);
        verify(generatorModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void generate_skipsADuplicateQuestionWithoutSpendingAJudgeCall() {
        when(generatorModel.call(any(Prompt.class))).thenReturn(response(quiz(question("Q one", "PUT"), question("Q one", "PUT"))), response(quiz(question("Q two", "DELETE"))));
        when(judgeModel.call(any(Prompt.class))).thenReturn(response(verdict(0.0)), response(verdict(0.0)));

        Quiz quiz = approach.generate(request(2), context);

        assertThat(quiz.complete()).isTrue();
        verify(judgeModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void generate_rejectsACompetencyTheCourseModelDoesNotDeclare() {
        GenerationRequest request = new GenerationRequest("r1", "EIDI", null, List.of("streams"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 2, Difficulty.MEDIUM);

        assertThatThrownBy(() -> approach.generate(request, context)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("streams");
    }

    private static GenerationRequest request(int questions) {
        return new GenerationRequest("r1", "EIDI", null, List.of("arrays"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), questions, Difficulty.MEDIUM);
    }

    private static CompetencyManifest manifest() {
        return new CompetencyManifest(new Course("EIDI", "EIDI", ""),
                List.of(new Competency("arrays", "Arrays", "Du kannst Arrays erstellen.", null, Taxonomy.APPLY, false, null, List.of(), List.of(), List.of())));
    }

    private static Snippet snippet() {
        return new Snippet("Deck", "Unit", "Page 1", "PUT is idempotent. DELETE and PATCH are HTTP methods.", "deck.pdf#1", SourceRole.LECTURE_DECK, 0.9);
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static String quiz(String... questions) {
        return "{ \"questions\": [" + String.join(",", questions) + "] }";
    }

    private static String question(String title, String correct) {
        return """
                { "type": "single-choice", "title": "%s", "questionText": "%s?",
                  "options": [ { "text": "%s", "correct": true }, { "text": "W1", "correct": false },
                               { "text": "W2", "correct": false }, { "text": "W3", "correct": false } ],
                  "explanation": "Because." }
                """.formatted(title, title, correct);
    }

    private static String verdict(double competencyMismatch) {
        return """
                { "rationale": "checked", "modes": [
                  { "mode": "FACTUAL_ERROR", "severity": 0.0, "triggered": false, "justification": "a" },
                  { "mode": "AMBIGUOUS_CORRECT_ANSWER", "severity": 0.0, "triggered": false, "justification": "b" },
                  { "mode": "OFF_TOPIC", "severity": 0.0, "triggered": false, "justification": "c" },
                  { "mode": "NEAR_DUPLICATE", "severity": 0.0, "triggered": false, "justification": "d" },
                  { "mode": "ILL_FORMED_DISTRACTORS", "severity": 0.0, "triggered": false, "justification": "e" },
                  { "mode": "COMPETENCY_MISMATCH", "severity": %s, "triggered": false, "justification": "f" },
                  { "mode": "DIFFICULTY_MISMATCH", "severity": 0.0, "triggered": false, "justification": "g" },
                  { "mode": "INSTRUCTION_VIOLATION", "severity": 0.0, "triggered": false, "justification": "h" } ] }
                """.formatted(competencyMismatch);
    }
}
