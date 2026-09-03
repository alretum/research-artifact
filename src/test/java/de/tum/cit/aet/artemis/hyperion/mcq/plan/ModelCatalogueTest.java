package de.tum.cit.aet.artemis.hyperion.mcq.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ModelCatalogueTest {

    private static final Map<String, Object> BACKENDS = Map.of("logos", Map.of("base-url", "http://logos/v1", "api-key-env", "LOGOS_API_KEY", "default", true), "azure",
            Map.of("base-url", "http://azure/v1", "api-key-env", "AZURE_KEY"));

    @Test
    void resolvesAModelToItsBackend() {
        var catalogue = ModelCatalogue.parse(Map.of("backends", BACKENDS, "models", Map.of("m", Map.of("backend", "logos", "model", "provider/m"))));

        var entry = catalogue.requireModel("m");
        assertThat(entry.model()).isEqualTo("provider/m");
        assertThat(catalogue.backendFor(entry).isDefault()).isTrue();
    }

    @Test
    void treatsABackendWithoutTheDefaultFlagAsNonDefault() {
        var catalogue = ModelCatalogue.parse(Map.of("backends", BACKENDS, "models", Map.of("m", Map.of("backend", "azure", "model", "provider/m"))));

        assertThat(catalogue.backendFor(catalogue.requireModel("m")).isDefault()).isFalse();
    }

    @Test
    void namesTheAvailableKeysWhenAModelIsUnknown() {
        var catalogue = ModelCatalogue.parse(Map.of("backends", BACKENDS, "models", Map.of("known", Map.of("backend", "logos", "model", "provider/known"))));

        assertThatThrownBy(() -> catalogue.requireModel("typo")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Declared models: [known]");
    }

    @Test
    void rejectsAModelPointingAtAnUndeclaredBackendAtParseTime() {
        assertThatThrownBy(() -> ModelCatalogue.parse(Map.of("backends", BACKENDS, "models", Map.of("m", Map.of("backend", "nowhere", "model", "provider/m")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not declared");
    }

    @Test
    void rejectsABackendWithNoKeyVariable() {
        assertThatThrownBy(() -> ModelCatalogue.parse(Map.of("backends", Map.of("b", Map.of("base-url", "http://x/v1")), "models",
                Map.of("m", Map.of("backend", "b", "model", "provider/m"))))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("api-key-env");
    }

    @Test
    void rejectsACatalogueWithNoModels() {
        assertThatThrownBy(() -> ModelCatalogue.parse(Map.of("backends", BACKENDS))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no models");
    }
}
