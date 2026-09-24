package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

class ChatCallTest {

    @Mock
    private ChatModel chatModel;

    @Captor
    private ArgumentCaptor<Prompt> prompt;

    private AutoCloseable mocks;

    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        lenient().when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        chatClient = ChatClient.create(chatModel);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void execute_sendsTheRequestedModelOnTheRequest() {
        respondWith("ok");

        ChatCall.execute("generation", "some-generator-model", 0.7, 1, chatClient, "system", "user");

        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getModel()).isEqualTo("some-generator-model");
    }

    @Test
    void execute_recordsTheSameModelItSent() {
        respondWith("ok");

        var outcome = ChatCall.execute("filter", "some-filter-model", 0.2, 1, chatClient, "system", "user");

        verify(chatModel).call(prompt.capture());
        assertThat(outcome.record().model()).isEqualTo(prompt.getValue().getOptions().getModel());
    }

    @Test
    void execute_sendsTheRequestedTemperature() {
        respondWith("ok");

        ChatCall.execute("generation", "some-generator-model", 0.2, 1, chatClient, "system", "user");

        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getTemperature()).isEqualTo(0.2);
    }

    @Test
    void execute_sendsNoTemperatureWhenTheProviderDefaultIsRequested() {
        respondWith("ok");

        ChatCall.execute("filter", "claude-opus-5", ChatCall.PROVIDER_DEFAULT_TEMPERATURE, 1, chatClient, "system", "user");

        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getTemperature()).isNull();
        assertThat(prompt.getValue().getOptions().getModel()).isEqualTo("claude-opus-5");
    }

    @Test
    void execute_recordsTheModelEvenWhenEveryAttemptFails() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("connection refused"));

        var outcome = ChatCall.execute("generation", "some-generator-model", 0.7, 2, chatClient, "system", "user");

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.record().model()).isEqualTo("some-generator-model");
        assertThat(outcome.record().retryCount()).isEqualTo(1);
    }

    @Test
    void execute_recordsTheProviderCostAndCacheTokensFromTheNativeUsage() {
        CompletionUsage nativeUsage = CompletionUsage.builder().promptTokens(24).completionTokens(4).totalTokens(28)
                .promptTokensDetails(CompletionUsage.PromptTokensDetails.builder().cachedTokens(20).putAdditionalProperty("created_cache_tokens", JsonValue.from(4)).build())
                .putAdditionalProperty("cost", JsonValue.from(0.00022)).putAdditionalProperty("cost_currency", JsonValue.from("USD")).build();
        respondWith("ok", new DefaultUsage(24, 4, 28, nativeUsage));

        var record = ChatCall.execute("generation", "claude-opus-5", 1.0, 1, chatClient, "system", "user").record();

        assertThat(record.promptTokens()).isEqualTo(24);
        assertThat(record.completionTokens()).isEqualTo(4);
        assertThat(record.cacheReadTokens()).isEqualTo(20);
        assertThat(record.cacheWriteTokens()).isEqualTo(4);
        assertThat(record.providerCost()).isEqualTo(0.00022);
        assertThat(record.providerCostCurrency()).isEqualTo("USD");
    }

    @Test
    void execute_leavesCostAndCacheTokensNullWhenTheProviderReportsNone() {
        respondWith("ok", new DefaultUsage(24, 4, 28, CompletionUsage.builder().promptTokens(24).completionTokens(4).totalTokens(28).build()));

        var record = ChatCall.execute("generation", "google/gemma-3-12b-it", 1.0, 1, chatClient, "system", "user").record();

        assertThat(record.promptTokens()).isEqualTo(24);
        assertThat(record.cacheReadTokens()).isNull();
        assertThat(record.cacheWriteTokens()).isNull();
        assertThat(record.providerCost()).isNull();
        assertThat(record.providerCostCurrency()).isNull();
    }

    private void respondWith(String text) {
        when(chatModel.call(any(Prompt.class))).thenAnswer(_ -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private void respondWith(String text, Usage usage) {
        when(chatModel.call(any(Prompt.class)))
                .thenAnswer(_ -> new ChatResponse(List.of(new Generation(new AssistantMessage(text))), ChatResponseMetadata.builder().usage(usage).build()));
    }
}
