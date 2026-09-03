package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.llm.ModelRegistry.Backend;

class ModelRegistryTest {

    private static final Backend LOCAL = new Backend("http://localhost:11434/v1", "ollama", "gpt-oss:20b");

    private static final Backend CLOUD = new Backend("https://example.invalid/v1", "test-key", "some-cloud-model");

    private static ModelRegistry registry() {
        Map<String, Backend> backends = new LinkedHashMap<>();
        backends.put("local", LOCAL);
        backends.put("cloud", CLOUD);
        return new ModelRegistry(backends);
    }

    @Test
    void model_resolvesTheProviderModelNameForABackendKey() {
        assertThat(registry().model("local")).isEqualTo("gpt-oss:20b");
        assertThat(registry().model("cloud")).isEqualTo("some-cloud-model");
    }

    @Test
    void client_returnsADistinctClientPerBackend() {
        ModelRegistry registry = registry();

        assertThat(registry.client("local")).isNotSameAs(registry.client("cloud"));
    }

    @Test
    void client_cachesTheClientPerBackend() {
        ModelRegistry registry = registry();

        assertThat(registry.client("local")).isSameAs(registry.client("local"));
    }

    @Test
    void client_reportsTheConfiguredKeysWhenAskedForAnUnknownOne() {
        assertThatThrownBy(() -> registry().client("frontier")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("frontier").hasMessageContaining("local")
                .hasMessageContaining("cloud");
    }

    @Test
    void model_rejectsAnUnknownBackendKey() {
        assertThatThrownBy(() -> registry().model("frontier")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("frontier");
    }

    @Test
    void keys_listsEveryConfiguredBackend() {
        assertThat(registry().keys()).containsExactlyInAnyOrder("local", "cloud");
    }

    @Test
    void construction_copiesTheBackendMap() {
        Map<String, Backend> backends = new HashMap<>();
        backends.put("local", LOCAL);
        ModelRegistry registry = new ModelRegistry(backends);

        backends.put("cloud", CLOUD);

        assertThat(registry.keys()).containsExactly("local");
    }
}
