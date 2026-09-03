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

        assertThat(verdicts).hasSize(FilterScope.GENERAL.modes().size());
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

    @Test
    void requestFitScope_acceptsAVerdictCoveringItsThreeModes() {
        respondWith(fitVerdict(0.0, 0.0, 0.0));

        var result = evaluate(FilterScope.REQUEST_FIT, requestContext(), 0.7);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.decision().accepted()).isTrue();
        assertThat(result.decision().modeVerdicts().keySet()).containsExactlyInAnyOrder(FailureMode.COMPETENCY_MISMATCH, FailureMode.DIFFICULTY_MISMATCH,
                FailureMode.INSTRUCTION_VIOLATION);
    }

    @Test
    void requestFitScope_ignoresOutOfScopeModesRatherThanCountingThem() {
        respondWith("""
                { "rationale": "mixed", "modes": [
                  { "mode": "FACTUAL_ERROR", "severity": 0.0, "triggered": false, "justification": "a" },
                  { "mode": "COMPETENCY_MISMATCH", "severity": 0.0, "triggered": false, "justification": "b" },
                  { "mode": "DIFFICULTY_MISMATCH", "severity": 0.0, "triggered": false, "justification": "c" } ] }
                """);

        var result = evaluate(FilterScope.REQUEST_FIT, requestContext(), 0.7);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.call().failureCategory()).isEqualTo("FILTER_INCOMPLETE_VERDICT");
    }

    @Test
    void requestFitScope_rejectsOnAMismatchSeverity() {
        respondWith(fitVerdict(0.9, 0.0, 0.0));

        var decision = evaluate(FilterScope.REQUEST_FIT, requestContext(), 0.7).decision();

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.aggregateScore()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void requestFitScope_requiresARequestContext() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluate(FilterScope.REQUEST_FIT, null, 0.7)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REQUEST_FIT");
    }

    @Test
    void combinedScope_requiresAllEightModes() {
        respondWith(verdict(0.0, 0.0, 0.0, 0.0, 0.0, false));

        var result = evaluate(FilterScope.COMBINED, requestContext(), 0.7);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.call().failureCategory()).isEqualTo("FILTER_INCOMPLETE_VERDICT");
    }

    @Test
    void combinedScope_decidesAcrossItemAndFitModesTogether() {
        respondWith("""
                { "rationale": "checked", "modes": [
                  { "mode": "FACTUAL_ERROR", "severity": 0.0, "triggered": false, "justification": "a" },
                  { "mode": "AMBIGUOUS_CORRECT_ANSWER", "severity": 0.0, "triggered": false, "justification": "b" },
                  { "mode": "OFF_TOPIC", "severity": 0.0, "triggered": false, "justification": "c" },
                  { "mode": "NEAR_DUPLICATE", "severity": 0.0, "triggered": false, "justification": "d" },
                  { "mode": "ILL_FORMED_DISTRACTORS", "severity": 0.0, "triggered": false, "justification": "e" },
                  { "mode": "COMPETENCY_MISMATCH", "severity": 0.8, "triggered": true, "justification": "f" },
                  { "mode": "DIFFICULTY_MISMATCH", "severity": 0.0, "triggered": false, "justification": "g" },
                  { "mode": "INSTRUCTION_VIOLATION", "severity": 0.0, "triggered": false, "justification": "h" } ] }
                """);

        var decision = evaluate(FilterScope.COMBINED, requestContext(), 0.7).decision();

        assertThat(decision.modeVerdicts()).hasSize(8);
        assertThat(decision.accepted()).isFalse();
    }

    private McqFilterService.Result evaluate(double threshold) {
        return evaluate(FilterScope.GENERAL, null, threshold);
    }

    private McqFilterService.Result evaluate(FilterScope scope, McqFilterService.RequestContext request, double threshold) {
        return service.evaluate(item(), grounding(), scope, request, threshold, "test-model", 0.2, 1, chatClient);
    }

    private static McqFilterService.RequestContext requestContext() {
        return new McqFilterService.RequestContext("Arrays\n- Du kannst Arrays erstellen.", 50, "avoid code snippets");
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

    private static String fitVerdict(double competency, double difficulty, double instructions) {
        return """
                { "rationale": "checked", "modes": [
                  { "mode": "COMPETENCY_MISMATCH", "severity": %s, "triggered": false, "justification": "a" },
                  { "mode": "DIFFICULTY_MISMATCH", "severity": %s, "triggered": false, "justification": "b" },
                  { "mode": "INSTRUCTION_VIOLATION", "severity": %s, "triggered": false, "justification": "c" } ] }
                """.formatted(competency, difficulty, instructions);
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
