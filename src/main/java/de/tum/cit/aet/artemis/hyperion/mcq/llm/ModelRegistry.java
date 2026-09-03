package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Resolves a backend key to the {@link ChatClient} that talks to it.
 * <p>
 * A backend's {@code baseUrl} and {@code apiKey} are read by {@code OpenAiChatModel.Builder} when the model
 * is constructed and are ignored on a per-request basis, so reaching two endpoints in one run requires two
 * clients rather than two sets of request options. Clients are built on first use and cached for the
 * lifetime of the registry; construction is thread-safe.
 */
public class ModelRegistry {

    /**
     * One OpenAI-compatible endpoint and the model served from it.
     *
     * @param baseUrl endpoint root, including the version segment
     * @param apiKey  bearer token; a placeholder such as {@code ollama} where the endpoint ignores it
     * @param model   provider model name, sent with every request and recorded in each {@code CallRecord}
     */
    public record Backend(String baseUrl, String apiKey, String model) {
    }

    private final Map<String, Backend> backends;

    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();

    /**
     * @param backends the configured backends, keyed by the name configurations refer to them by
     */
    public ModelRegistry(Map<String, Backend> backends) {
        this.backends = Map.copyOf(backends);
    }

    /**
     * Returns the client for a backend, building it on first use.
     *
     * @param key backend key
     * @return the client for that backend
     * @throws IllegalArgumentException if the key is not configured
     */
    public ChatClient client(String key) {
        return clients.computeIfAbsent(key, k -> ChatClient.create(chatModel(backend(k))));
    }

    /**
     * Returns the provider model name a backend serves, for recording alongside each call.
     *
     * @param key backend key
     * @return the model name
     * @throws IllegalArgumentException if the key is not configured
     */
    public String model(String key) {
        return backend(key).model();
    }

    /**
     * @return every configured backend key
     */
    public Set<String> keys() {
        return backends.keySet();
    }

    private Backend backend(String key) {
        Backend backend = backends.get(key);
        if (backend == null) {
            throw new IllegalArgumentException("Unknown model backend '" + key + "', configured backends are " + backends.keySet());
        }
        return backend;
    }

    private static OpenAiChatModel chatModel(Backend backend) {
        return OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(backend.baseUrl()).apiKey(backend.apiKey()).model(backend.model()).build()).build();
    }
}
