package de.tum.cit.aet.artemis.hyperion.mcq.plan;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.credential.BearerTokenCredential;

import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue.Backend;

/**
 * Builds a chat client for a backend the application is not itself configured against.
 * <p>
 * Needed because generation and filtering may run on different providers: the model travels with each
 * request, so one backend serves any number of models, but a second <em>backend</em> needs its own base URL
 * and key and therefore its own client. Spring's autoconfiguration produces exactly one, from the
 * {@code spring.ai.openai} properties, so anything beyond that is built here.
 */
public final class BackendClientFactory {

    private static final Logger log = LoggerFactory.getLogger(BackendClientFactory.class);

    /** Matches the timeout configured for the autoconfigured client, since generation calls are slow. */
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private BackendClientFactory() {
    }

    /**
     * Build a client for one backend.
     *
     * @param backend the declared backend
     * @param apiKey  key for it, read from the environment by the caller and never from a file in the
     *                repository
     * @return a chat client pointed at that backend
     * @throws IllegalStateException if the key is missing, since every provider rejects an unauthenticated
     *                               request and failing here names the cause
     */
    public static ChatClient create(Backend backend, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Backend '" + backend.name() + "' needs $" + backend.apiKeyEnv() + ", which is unset");
        }
        // credential(), not apiKey(): the latter exists but does not satisfy the SDK's own credential
        // check, which then fails with "At least one credential source must be specified".
        ClientOptions options = ClientOptions.builder().baseUrl(backend.baseUrl()).credential(BearerTokenCredential.create(apiKey))
                .httpClient(SpringAiOpenAiHttpClient.builder().timeout(TIMEOUT).build()).build();
        // Both clients must be supplied. Given only the synchronous one, the model builder sets up an
        // async client from Spring AI's own properties, which describe a different backend entirely and
        // carry no credential for this one.
        OpenAIClient client = new OpenAIClientImpl(options);
        OpenAIClientAsync asyncClient = new OpenAIClientAsyncImpl(options);
        ChatClient chatClient = ChatClient.create(OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(asyncClient).build());
        log.info("Built a chat client for backend '{}' at {}", backend.name(), backend.baseUrl());
        return chatClient;
    }
}
