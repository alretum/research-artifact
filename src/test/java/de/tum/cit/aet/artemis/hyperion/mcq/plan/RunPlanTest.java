package de.tum.cit.aet.artemis.hyperion.mcq.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RunPlanTest {

    private static Map<String, Object> configuration(String id, String generator, String filter) {
        return Map.of("id", id, "generator", generator, "filter", filter);
    }

    @Test
    void parsesConfigurationsInOrder() {
        var plan = RunPlan.parse(Map.of("plan", "p", "items-per-topic", 3, "configurations", List.of(configuration("a", "m1", "m2"), configuration("b", "m2", "m1"))));

        assertThat(plan.itemsPerTopic()).isEqualTo(3);
        assertThat(plan.configurations()).extracting(RunPlan.RunConfiguration::id).containsExactly("a", "b");
        assertThat(plan.topics()).isEmpty();
    }

    @Test
    void reportsWhichConfigurationsJudgeTheirOwnOutput() {
        var plan = RunPlan.parse(Map.of("plan", "p", "items-per-topic", 1, "configurations", List.of(configuration("same", "m1", "m1"), configuration("cross", "m1", "m2"))));

        assertThat(plan.configurations().get(0).isSelfJudging()).isTrue();
        assertThat(plan.configurations().get(1).isSelfJudging()).isFalse();
    }

    @Test
    void collectsEveryReferencedModelWithoutDuplicates() {
        var plan = RunPlan.parse(Map.of("plan", "p", "items-per-topic", 1, "configurations", List.of(configuration("a", "m1", "m1"), configuration("b", "m1", "m2"))));

        assertThat(plan.referencedModels()).containsExactly("m1", "m2");
    }

    @Test
    void rejectsDuplicateConfigurationIdsBecauseItemsAreKeyedByThem() {
        assertThatThrownBy(() -> RunPlan.parse(Map.of("plan", "p", "items-per-topic", 1, "configurations", List.of(configuration("dup", "m1", "m1"), configuration("dup", "m1", "m2")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
    }

    @Test
    void rejectsAPlanWithNoConfigurations() {
        assertThatThrownBy(() -> RunPlan.parse(Map.of("plan", "p", "items-per-topic", 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no configurations");
    }

    @Test
    void rejectsANonPositiveItemCount() {
        assertThatThrownBy(() -> RunPlan.parse(Map.of("plan", "p", "items-per-topic", 0, "configurations", List.of(configuration("a", "m1", "m1")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("items-per-topic");
    }

    @Test
    void validatesEveryModelAgainstTheCatalogueBeforeAnyWorkIsQueued() {
        var catalogue = ModelCatalogue.parse(Map.of("backends", Map.of("b", Map.of("base-url", "http://x/v1", "api-key-env", "K", "default", true)), "models",
                Map.of("known", Map.of("backend", "b", "model", "provider/known"))));
        var plan = RunPlan.parse(Map.of("plan", "p", "items-per-topic", 1, "configurations", List.of(configuration("a", "known", "missing"))));

        assertThatThrownBy(() -> plan.validateAgainst(catalogue)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown model 'missing'");
    }

    @Test
    void parsesTheProjectsOwnPlanAndCatalogue() {
        var plan = RunPlan.load(Path.of("config/runs/baseline.yml"));
        var catalogue = ModelCatalogue.load(Path.of("config/models.yml"));

        plan.validateAgainst(catalogue);
        assertThat(catalogue.backendFor(catalogue.requireModel("gpt-oss-120b")).isDefault()).isTrue();
    }
}
