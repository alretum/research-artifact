package de.tum.cit.aet.artemis.hyperion.mcq.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingComposition;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;

class McqFilterServiceTest {

    private ChatModel chatModel;

    private ChatClient chatClient;

    private McqFilterService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        lenient().when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        chatClient = ChatClient.create(chatModel);
        service = new McqFilterService(new PromptTemplateService());
    }

    @Test
    void acceptsAnItemWithNoDefects() {
        respondWith(verdict(0.0, 0.0, 0.0, 0.0, 0.0, false));

        var result = evaluate(0.7);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.decision().accepted()).isTrue();
        assertThat(result.decision().aggregateScore()).isEqualTo(1.0);
    }

    @Test
    void derivesTheAggregateFromTheWorstSeverityNotTheMean() {
        respondWith(verdict(0.5, 0.0, 0.0, 0.0, 0.0, false));

        var decision = evaluate(0.0).decision();

        assertThat(decision.aggregateScore()).isEqualTo(0.5);
        assertThat(decision.meanSeverity()).isEqualTo(0.1);
    }

    @Test
    void rejectsASingleDisqualifyingDefectThatAMeanWouldDiluteAway() {
        respondWith(verdict(0.0, 0.0, 0.0, 1.0, 0.0, false));

        var decision = evaluate(0.7).decision();

        assertThat(decision.meanSeverity()).isEqualTo(0.2);
        assertThat(decision.aggregateScore()).isEqualTo(0.0);
        assertThat(decision.accepted()).isFalse();
    }

    @Test
    void ignoresTheTriggeredFlagWhenDeciding() {
        respondWith(verdict(0.1, 0.0, 0.0, 0.0, 0.0, true));

        var decision = evaluate(0.5).decision();

        assertThat(decision.modeVerdicts().get(FailureMode.FACTUAL_ERROR).triggered()).isTrue();
        assertThat(decision.accepted()).isTrue();
    }

    @Test
    void rejectsWhenTheAggregateFallsBelowTheThreshold() {
        respondWith(verdict(0.4, 0.0, 0.0, 0.0, 0.0, false));

        assertThat(evaluate(0.7).decision().accepted()).isFalse();
    }

    @Test
    void discardsAVerdictThatOmitsModesRatherThanDefaultingThem() {
        respondWith("""
                { "rationale": "partial", "modes": [
                  { "mode": "FACTUAL_ERROR", "severity": 0.0, "triggered": false, "justification": "fine" } ] }
                """);

        var result = evaluate(0.7);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.decision()).isNull();
        assertThat(result.call().outcome()).isEqualTo("success");
    }

    @Test
    void reportsEverySeverityItWasGiven() {
        respondWith(verdict(0.1, 0.2, 0.3, 0.4, 0.5, false));

        var verdicts = evaluate(0.0).decision().modeVerdicts();

        assertThat(verdicts).hasSize(FailureMode.values().length);
        assertThat(verdicts.get(FailureMode.FACTUAL_ERROR).severity()).isEqualTo(0.1);
        assertThat(verdicts.get(FailureMode.ILL_FORMED_DISTRACTORS).severity()).isEqualTo(0.5);
    }

    @Test
    void clampsSeveritiesOutsideTheUnitInterval() {
        respondWith(verdict(-3.0, 9.0, 0.0, 0.0, 0.0, false));

        var verdicts = evaluate(0.0).decision().modeVerdicts();

        assertThat(verdicts.get(FailureMode.FACTUAL_ERROR).severity()).isEqualTo(0.0);
        assertThat(verdicts.get(FailureMode.AMBIGUOUS_CORRECT_ANSWER).severity()).isEqualTo(1.0);
    }

    @Test
    void reportsFailureWhenTheCallCannotBeMade() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("no route to host"));

        var result = evaluate(0.7);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.call().outcome()).isEqualTo("error");
    }

    /** All five modes gate, which is the behaviour these tests were written against. */
    private McqFilterService.Result evaluate(double threshold) {
        return service.evaluate(item(), grounding(), threshold, java.util.Set.of(FailureMode.values()), "test-model", 0.2, 1, chatClient);
    }

    private McqFilterService.Result evaluateGatingOn(double threshold, FailureMode... gating) {
        return service.evaluate(item(), grounding(), threshold, java.util.Set.of(gating), "test-model", 0.2, 1, chatClient);
    }

    private void respondWith(String content) {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private static String verdict(double factual, double ambiguous, double offTopic, double duplicate, double distractors, boolean triggerFirst) {
        return """
                { "rationale": "checked", "modes": [
                  { "mode": "FACTUAL_ERROR", "severity": %s, "triggered": %s, "justification": "a" },
                  { "mode": "AMBIGUOUS_CORRECT_ANSWER", "severity": %s, "triggered": false, "justification": "b" },
                  { "mode": "OFF_TOPIC", "severity": %s, "triggered": false, "justification": "c" },
                  { "mode": "NEAR_DUPLICATE", "severity": %s, "triggered": false, "justification": "d" },
                  { "mode": "ILL_FORMED_DISTRACTORS", "severity": %s, "triggered": false, "justification": "e" } ] }
                """.formatted(factual, triggerFirst, ambiguous, offTopic, duplicate, distractors);
    }

    private static McqItem item() {
        List<AnswerOption> options = List.of(new AnswerOption("PUT", true, null, null), new AnswerOption("POST", false, null, null),
                new AnswerOption("PATCH", false, null, null), new AnswerOption("GET", false, null, null));
        return new McqItem(QuestionType.SINGLE_CHOICE, "HTTP Methods", "Which method is idempotent?", options, null, "PUT is idempotent.");
    }

    private static GroundingContext grounding() {
        return new GroundingContext("HTTP", List.of(), "-----BEGIN UNTRUSTED INPUT-----\nPUT is idempotent.\n-----END UNTRUSTED INPUT-----", 20,
                GroundingComposition.of(List.of()));
    }
}
