package de.tum.cit.aet.artemis.hyperion.mcq.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingComposition;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService.Failure;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;

class McqGenerationServiceTest {

    private static final String MODEL = "test-model";

    private ChatModel chatModel;

    private ChatClient chatClient;

    private McqGenerationService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        lenient().when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        chatClient = ChatClient.create(chatModel);
        service = new McqGenerationService(new PromptTemplateService());
    }

    @Test
    void returnsAValidatedItem() {
        respondWith(itemJson("PUT", true, "PATCH", false, "POST", false, "GET", false));

        var result = generate();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.item().title()).isEqualTo("HTTP Methods");
        assertThat(result.item().options()).hasSize(4);
        assertThat(result.item().options().stream().filter(option -> option.correct())).hasSize(1);
    }

    @Test
    void recordsTheModelItWasAskedToUse() {
        respondWith(itemJson("PUT", true, "PATCH", false, "POST", false, "GET", false));

        assertThat(generate().call().model()).isEqualTo(MODEL);
    }

    @Test
    void recordsTheRenderedPromptIncludingTheGrounding() {
        respondWith(itemJson("PUT", true, "PATCH", false, "POST", false, "GET", false));

        assertThat(generate().prompt()).contains("BEGIN UNTRUSTED INPUT").contains("idempotent methods");
    }

    @Test
    void reportsMalformedJson() {
        respondWith("this is not json at all");

        var result = generate();

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failure()).isEqualTo(Failure.MALFORMED_JSON);
    }

    @Test
    void toleratesLatexEscapesThatStrictJsonWouldReject() {
        respondWith(itemJson("\\(x^*\\)", true, "b", false, "c", false, "d", false));

        assertThat(generate().succeeded()).isTrue();
    }

    @Test
    void rejectsTheWrongNumberOfOptions() {
        respondWith("""
                { "title": "T", "questionText": "Q", "explanation": "E", "options": [
                  { "text": "a", "correct": true }, { "text": "b", "correct": false } ] }
                """);

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void rejectsMoreThanOneCorrectOption() {
        respondWith(itemJson("a", true, "b", true, "c", false, "d", false));

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void rejectsNoCorrectOption() {
        respondWith(itemJson("a", false, "b", false, "c", false, "d", false));

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void rejectsDuplicateOptionTextRegardlessOfCase() {
        respondWith(itemJson("PUT", true, "put", false, "c", false, "d", false));

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void rejectsAnOptionReferringToTheOptionSet() {
        respondWith(itemJson("PUT", true, "POST", false, "GET", false, "All of the above", false));

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void rejectsNoneOfTheAboveRegardlessOfCasing() {
        respondWith(itemJson("PUT", true, "POST", false, "GET", false, "none of these options", false));

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void rejectsAnOptionContainedWholeInsideAnother() {
        respondWith(itemJson("PUT", true, "PUT and DELETE", false, "POST", false, "GET", false));

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void allowsCoincidentalSubstringsThatAreNotWholeWords() {
        respondWith(itemJson("12", true, "2", false, "120", false, "21", false));

        assertThat(generate().succeeded()).isTrue();
    }

    @Test
    void rejectsACorrectOptionThatIsConspicuouslyLongerThanEveryDistractor() {
        String longCorrect = "the dual attains the same objective value and every complementary slackness condition holds simultaneously";
        respondWith(itemJson(longCorrect, true, "the dual is unbounded", false, "the primal is infeasible", false, "the gap is positive", false));

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void rejectsACorrectOptionOnlyFortyPercentLongerWhenThatIsStillManyCharacters() {
        String correct = "a".repeat(132);
        respondWith(itemJson(correct, true, "b".repeat(94), false, "c".repeat(90), false, "d".repeat(88), false));

        assertThat(generate().failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
    }

    @Test
    void allowsALargeCharacterDifferenceWhenTheOptionsAreLongEnoughForItNotToShow() {
        respondWith(itemJson("a".repeat(275), true, "b".repeat(250), false, "c".repeat(248), false, "d".repeat(245), false));

        assertThat(generate().succeeded()).isTrue();
    }

    @Test
    void allowsALongerCorrectOptionWhenTheDifferenceIsTooSmallToExploit() {
        respondWith(itemJson("x* is optimal", true, "x* is dual", false, "x* is basic", false, "x* is free", false));

        assertThat(generate().succeeded()).isTrue();
    }

    @Test
    void generateQuiz_returnsEveryValidQuestionAndCountsTheInvalid() {
        respondWith("""
                { "questions": [
                  { "type": "single-choice", "title": "Q1", "questionText": "A?",
                    "options": [ {"text":"a","correct":true}, {"text":"b","correct":false}, {"text":"c","correct":false}, {"text":"d","correct":false} ],
                    "explanation": "e" },
                  { "type": "multiple-choice", "title": "Q2", "questionText": "B?",
                    "options": [ {"text":"a","correct":true}, {"text":"b","correct":true}, {"text":"c","correct":false}, {"text":"d","correct":false} ],
                    "explanation": "e" },
                  { "type": "single-choice", "title": "Q3", "questionText": "C?",
                    "options": [ {"text":"a","correct":true}, {"text":"b","correct":true}, {"text":"c","correct":false}, {"text":"d","correct":false} ],
                    "explanation": "e" } ] }
                """);

        var result = generateQuiz(Set.of(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE));

        assertThat(result.failure()).isNull();
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).type()).isEqualTo(QuestionType.SINGLE_CHOICE);
        assertThat(result.items().get(1).type()).isEqualTo(QuestionType.MULTIPLE_CHOICE);
        assertThat(result.invalidCount()).isEqualTo(1);
    }

    @Test
    void generateQuiz_rejectsAMultipleChoiceQuestionWithEveryOptionCorrect() {
        respondWith("""
                { "questions": [
                  { "type": "multiple-choice", "title": "Q", "questionText": "A?",
                    "options": [ {"text":"a","correct":true}, {"text":"b","correct":true}, {"text":"c","correct":true}, {"text":"d","correct":true} ],
                    "explanation": "e" } ] }
                """);

        var result = generateQuiz(Set.of(QuestionType.MULTIPLE_CHOICE));

        assertThat(result.failure()).isEqualTo(Failure.VALIDATION_VIOLATION);
        assertThat(result.invalidCount()).isEqualTo(1);
    }

    @Test
    void generateQuiz_dropsAQuestionOfATypeTheRequestDidNotAskFor() {
        respondWith("""
                { "questions": [
                  { "type": "multiple-choice", "title": "Q1", "questionText": "A?",
                    "options": [ {"text":"a","correct":true}, {"text":"b","correct":true}, {"text":"c","correct":false}, {"text":"d","correct":false} ],
                    "explanation": "e" },
                  { "type": "single-choice", "title": "Q2", "questionText": "B?",
                    "options": [ {"text":"a","correct":true}, {"text":"b","correct":false}, {"text":"c","correct":false}, {"text":"d","correct":false} ],
                    "explanation": "e" } ] }
                """);

        var result = generateQuiz(Set.of(QuestionType.SINGLE_CHOICE));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("Q2");
        assertThat(result.invalidCount()).isEqualTo(1);
    }

    @Test
    void generateQuiz_reportsMalformedJson() {
        respondWith("no json here");

        var result = generateQuiz(Set.of(QuestionType.SINGLE_CHOICE));

        assertThat(result.failure()).isEqualTo(Failure.MALFORMED_JSON);
        assertThat(result.call().failureCategory()).isEqualTo("MALFORMED_JSON");
    }

    private McqGenerationService.QuizResult generateQuiz(Set<QuestionType> types) {
        GenerationRequest request = new GenerationRequest("r1", "EIDI", "HTTP", null, null, Language.EN, types, 3, Difficulty.MEDIUM);
        return service.generateQuiz(request, null, grounding(), 3, "test-model", 0.7, 1, chatClient);
    }

    @Test
    void reportsTransportFailureAfterExhaustingAttempts() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("connection refused"));

        var result = service.generate(grounding(), 50, "en", MODEL, 0.7, 1, chatClient);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failure()).isEqualTo(Failure.TRANSPORT);
        assertThat(result.call().outcome()).isEqualTo("error");
        assertThat(result.call().errorMessage()).contains("connection refused");
    }

    private McqGenerationService.Result generate() {
        return service.generate(grounding(), 50, "en", MODEL, 0.7, 1, chatClient);
    }

    private void respondWith(String content) {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private static GroundingContext grounding() {
        String block = "-----BEGIN UNTRUSTED INPUT-----\nLecture: HTTP, Unit: Methods, Page 1\nPUT and DELETE are idempotent methods.\n-----END UNTRUSTED INPUT-----";
        return new GroundingContext("HTTP methods", List.of(), block, 40, GroundingComposition.of(List.of()));
    }

    private static String itemJson(String a, boolean aCorrect, String b, boolean bCorrect, String c, boolean cCorrect, String d, boolean dCorrect) {
        return """
                { "title": "HTTP Methods", "questionText": "Which method is idempotent?", "explanation": "PUT is idempotent.",
                  "options": [ { "text": "%s", "correct": %s }, { "text": "%s", "correct": %s },
                               { "text": "%s", "correct": %s }, { "text": "%s", "correct": %s } ] }
                """.formatted(a, aCorrect, b, bCorrect, c, cCorrect, d, dCorrect);
    }
}
