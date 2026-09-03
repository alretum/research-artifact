package de.tum.cit.aet.artemis.hyperion.mcq.select;

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

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;
import de.tum.cit.aet.artemis.hyperion.mcq.select.PoolSelectionService.Candidate;

class PoolSelectionServiceTest {

    private ChatModel chatModel;

    private ChatClient chatClient;

    private PoolSelectionService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        lenient().when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        chatClient = ChatClient.create(chatModel);
        service = new PoolSelectionService(new PromptTemplateService());
    }

    @Test
    void select_returnsTheChosenIdsInTheModelsOrder() {
        respondWith("""
                { "chosen": [12, 10], "rejected": [ { "id": 11, "reason": "same fact as 12" } ], "rationale": "covers both objectives" }
                """);

        var result = select(candidates(10, 11, 12), 2, 40);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.selection().chosenIds()).containsExactly(12L, 10L);
        assertThat(result.selection().rejections()).hasSize(1);
        assertThat(result.selection().rationale()).isEqualTo("covers both objectives");
        assertThat(result.selection().candidateIds()).containsExactly(10L, 11L, 12L);
        assertThat(result.calls()).hasSize(1);
    }

    @Test
    void select_dropsUnknownAndDuplicateIdsAndCapsAtTheRequestedCount() {
        respondWith("""
                { "chosen": [99, 10, 10, 11, 12], "rejected": [], "rationale": "r" }
                """);

        var result = select(candidates(10, 11, 12), 2, 40);

        assertThat(result.selection().chosenIds()).containsExactly(10L, 11L);
    }

    @Test
    void select_shortlistsInBatchesWhenCandidatesExceedTheCap() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{ \"chosen\": [10, 11], \"rejected\": [], \"rationale\": \"batch one\" }"),
                response("{ \"chosen\": [12, 13], \"rejected\": [], \"rationale\": \"batch two\" }"),
                response("{ \"chosen\": [14], \"rejected\": [], \"rationale\": \"batch three\" }"),
                response("{ \"chosen\": [11, 14], \"rejected\": [], \"rationale\": \"final\" }"));

        var result = select(candidates(10, 11, 12, 13, 14), 2, 2);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.selection().chosenIds()).containsExactly(11L, 14L);
        assertThat(result.calls()).hasSize(4);
        verify(chatModel, times(4)).call(any(Prompt.class));
    }

    @Test
    void select_reportsAMalformedResponseAsAFailedCall() {
        respondWith("not json");

        var result = select(candidates(10), 1, 40);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.calls()).hasSize(1);
        assertThat(result.calls().getFirst().failureCategory()).isEqualTo("SELECT_MALFORMED_JSON");
    }

    @Test
    void select_reportsAChoiceOfNothingAsAFailure() {
        respondWith("{ \"chosen\": [], \"rejected\": [], \"rationale\": \"nothing fits\" }");

        var result = select(candidates(10), 1, 40);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.calls().getFirst().failureCategory()).isEqualTo("SELECT_NO_CHOICE");
    }

    @Test
    void select_rejectsAnEmptyCandidateList() {
        assertThatThrownBy(() -> select(List.of(), 1, 40)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zero candidates");
    }

    private PoolSelectionService.Result select(List<Candidate> candidates, int count, int maxCandidates) {
        GenerationRequest request = new GenerationRequest("r1", "EIDI", null, List.of("arrays"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), count, Difficulty.MEDIUM);
        return service.select(request, "Arrays (APPLY)", candidates, count, maxCandidates, "selector-model", 0.7, 1, chatClient);
    }

    private static List<Candidate> candidates(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> new Candidate(id, item("Q" + id))).toList();
    }

    private static McqItem item(String title) {
        List<AnswerOption> options = List.of(new AnswerOption("PUT", true, null, null), new AnswerOption("POST", false, null, null), new AnswerOption("PATCH", false, null, null),
                new AnswerOption("GET", false, null, null));
        return new McqItem(QuestionType.SINGLE_CHOICE, title, "Which method is idempotent?", options, null, "PUT is idempotent.");
    }

    private void respondWith(String content) {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(content));
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
