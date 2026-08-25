package de.tum.cit.aet.artemis.hyperion.mcq.plan;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;

import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue.Backend;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue.ModelEntry;

/**
 * Resolves a catalogue model key to the model name to send and the client to send it through.
 * <p>
 * This is the seam that keeps the matrix open. Generation and filtering already take the model as a call
 * parameter, so any number of models on one backend need nothing more than a name: that is why adding a
 * second Logos model is a catalogue entry and no code. A model on a different backend needs its own
 * client, and rather than half-build one this class refuses with a message saying exactly what is missing.
 */
public class ModelRegistry {

    private final ModelCatalogue catalogue;

    private final ChatClient defaultClient;

    /**
     * @param catalogue     declared backends and models
     * @param defaultClient the client the application is configured with, serving the default backend
     */
    public ModelRegistry(ModelCatalogue catalogue, ChatClient defaultClient) {
        this.catalogue = catalogue;
        this.defaultClient = defaultClient;
    }

    /**
     * @param model  provider model name, to be sent with each request and recorded on each call
     * @param client the client to issue through
     */
    public record ResolvedModel(String model, ChatClient client) {
    }

    /**
     * Resolve a catalogue key.
     *
     * @param modelKey key as written in a run plan
     * @return the model name and the client to use
     * @throws IllegalArgumentException      if the key or its backend is not declared
     * @throws UnsupportedOperationException if the model needs a backend that has no client yet
     */
    public ResolvedModel resolve(String modelKey) {
        ModelEntry entry = catalogue.requireModel(modelKey);
        Backend backend = catalogue.backendFor(entry);
        if (!backend.isDefault()) {
            throw new UnsupportedOperationException("Model '" + modelKey + "' is served by backend '" + backend.name() + "' (" + backend.baseUrl()
                    + "), which has no client. Only the default backend is wired. To add this one: build a ChatClient for its base URL with the key from $" + backend.apiKeyEnv()
                    + ", and hand it to this registry. Models on the default backend need no such work.");
        }
        requireKey(backend);
        return new ResolvedModel(entry.model(), defaultClient);
    }

    /**
     * Check every model a plan names can be resolved, before any work is queued.
     *
     * @param plan the plan to check
     * @throws IllegalArgumentException      if a model or backend is not declared
     * @throws UnsupportedOperationException if a model needs a backend that has no client yet
     */
    public void validate(RunPlan plan) {
        plan.referencedModels().forEach(this::resolve);
    }

    /**
     * @param modelKey key as written in a run plan
     * @return the provider model name, without resolving a client
     */
    public Optional<String> modelNameOf(String modelKey) {
        return catalogue.model(modelKey).map(ModelEntry::model);
    }

    private static void requireKey(Backend backend) {
        String key = System.getenv(backend.apiKeyEnv());
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Backend '" + backend.name() + "' needs $" + backend.apiKeyEnv() + ", which is unset. Export it before running; it is never read from a file in the repository.");
        }
    }
}
