package de.tum.cit.aet.artemis.hyperion.mcq.plan;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.yaml.snakeyaml.Yaml;

/**
 * The chat models a run plan may name, and the backends serving them, from {@code config/models.yml}.
 * <p>
 * A backend is a base URL plus the environment variable holding its key; a model is a provider model id
 * plus the backend it lives on. The split matters because one backend serves any number of models: the
 * model name travels with each request, so adding a second model on an existing backend needs no new
 * client. Adding a model on a new backend does, which is why {@link Backend#isDefault()} exists.
 */
public record ModelCatalogue(Map<String, Backend> backends, Map<String, ModelEntry> models) {

    /**
     * One OpenAI-compatible endpoint.
     *
     * @param name      catalogue key
     * @param baseUrl   OpenAI-compatible base URL
     * @param apiKeyEnv name of the environment variable holding the key; never the key itself
     * @param isDefault whether this is the backend the application is already configured against
     */
    public record Backend(String name, String baseUrl, String apiKeyEnv, boolean isDefault) {
    }

    /**
     * One chat model and the backend serving it.
     *
     * @param key     catalogue key, used in run plans
     * @param backend backend name, which must exist in {@link #backends()}
     * @param model   provider model id, exactly as the provider reports it and as calls record it
     */
    public record ModelEntry(String key, String backend, String model) {
    }

    /**
     * @param key catalogue key
     * @return the entry, or empty when the key is unknown
     */
    public Optional<ModelEntry> model(String key) {
        return Optional.ofNullable(models.get(key));
    }

    /**
     * @param key catalogue key
     * @return the entry
     * @throws IllegalArgumentException naming the available keys when this one is absent
     */
    public ModelEntry requireModel(String key) {
        return model(key).orElseThrow(() -> new IllegalArgumentException("Unknown model '" + key + "'. Declared models: " + models.keySet()));
    }

    /**
     * @param entry a model entry
     * @return the backend serving it
     * @throws IllegalArgumentException when the entry names a backend that is not declared
     */
    public Backend backendFor(ModelEntry entry) {
        Backend backend = backends.get(entry.backend());
        if (backend == null) {
            throw new IllegalArgumentException("Model '" + entry.key() + "' names backend '" + entry.backend() + "', which is not declared. Declared backends: "
                    + backends.keySet());
        }
        return backend;
    }

    /**
     * Load a catalogue from YAML.
     *
     * @param file the catalogue file
     * @return the parsed catalogue
     * @throws IllegalArgumentException if the file is absent or malformed
     * @throws UncheckedIOException     if it cannot be read
     */
    public static ModelCatalogue load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No model catalogue at " + file.toAbsolutePath());
        }
        try (InputStream in = Files.newInputStream(file)) {
            return parse(new Yaml().load(in));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read model catalogue " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    static ModelCatalogue parse(Map<String, Object> root) {
        if (root == null) {
            throw new IllegalArgumentException("Model catalogue is empty");
        }
        Map<String, Backend> backends = new LinkedHashMap<>();
        ((Map<String, Object>) root.getOrDefault("backends", Map.of())).forEach((name, body) -> {
            Map<String, Object> fields = (Map<String, Object>) body;
            String baseUrl = string(fields, "base-url", "backend " + name);
            String apiKeyEnv = string(fields, "api-key-env", "backend " + name);
            backends.put(name, new Backend(name, baseUrl, apiKeyEnv, Boolean.TRUE.equals(fields.get("default"))));
        });

        Map<String, ModelEntry> models = new LinkedHashMap<>();
        ((Map<String, Object>) root.getOrDefault("models", Map.of())).forEach((key, body) -> {
            Map<String, Object> fields = (Map<String, Object>) body;
            models.put(key, new ModelEntry(key, string(fields, "backend", "model " + key), string(fields, "model", "model " + key)));
        });

        if (models.isEmpty()) {
            throw new IllegalArgumentException("Model catalogue declares no models");
        }
        ModelCatalogue catalogue = new ModelCatalogue(Map.copyOf(backends), Map.copyOf(models));
        models.values().forEach(catalogue::backendFor);
        return catalogue;
    }

    private static String string(Map<String, Object> fields, String field, String owner) {
        Object value = fields == null ? null : fields.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing '" + field + "' on " + owner);
        }
        return String.valueOf(value);
    }
}
