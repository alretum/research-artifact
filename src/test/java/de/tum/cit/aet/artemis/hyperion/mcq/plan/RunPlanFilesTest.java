package de.tum.cit.aet.artemis.hyperion.mcq.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunPlanFilesTest {

    @TempDir
    Path directory;

    @Test
    void writesEveryPairingOfGeneratorsAndFilters() {
        Path file = RunPlanFiles.create(directory, "Local vs Cloud", 4, List.of(), List.of("local", "cloud"), List.of("local", "cloud"));

        RunPlan plan = RunPlan.load(file);
        assertThat(plan.configurations()).hasSize(4);
        assertThat(plan.configurations()).extracting(RunPlan.RunConfiguration::id).containsExactly("local-gen-local-filter", "local-gen-cloud-filter", "cloud-gen-local-filter",
                "cloud-gen-cloud-filter");
        assertThat(plan.configurations().stream().filter(RunPlan.RunConfiguration::isSelfJudging)).hasSize(2);
    }

    @Test
    void namesTheFileFromASlugOfThePlanName() {
        Path file = RunPlanFiles.create(directory, "Local vs Cloud", 1, List.of(), List.of("a"), List.of("a"));

        assertThat(file.getFileName().toString()).isEqualTo("local-vs-cloud.yml");
        assertThat(RunPlan.load(file).plan()).isEqualTo("local-vs-cloud");
    }

    @Test
    void writesTopicsSoTheyRoundTrip() {
        Path file = RunPlanFiles.create(directory, "p", 2, List.of("Duality Theory", "Simplex Algorithm"), List.of("a"), List.of("a"));

        assertThat(RunPlan.load(file).topics()).containsExactly("Duality Theory", "Simplex Algorithm");
    }

    @Test
    void refusesToOverwriteAnExistingPlan() {
        RunPlanFiles.create(directory, "p", 1, List.of(), List.of("a"), List.of("a"));

        assertThatThrownBy(() -> RunPlanFiles.create(directory, "p", 1, List.of(), List.of("a"), List.of("a"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void refusesAPlanWithNoModels() {
        assertThatThrownBy(() -> RunPlanFiles.create(directory, "p", 1, List.of(), List.of(), List.of("a"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one generator");
    }

    @Test
    void refusesANameThatSlugsToNothing() {
        assertThatThrownBy(() -> RunPlanFiles.create(directory, "!!!", 1, List.of(), List.of("a"), List.of("a"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("letters, digits or dashes");
    }

    @Test
    void refusesANonPositiveItemCount() {
        assertThatThrownBy(() -> RunPlanFiles.create(directory, "p", 0, List.of(), List.of("a"), List.of("a"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void deletesAPlan() {
        Path file = RunPlanFiles.create(directory, "p", 1, List.of(), List.of("a"), List.of("a"));

        RunPlanFiles.delete(directory, file.getFileName().toString());

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void refusesAFileNameThatEscapesThePlanDirectory() {
        assertThatThrownBy(() -> RunPlanFiles.delete(directory, "../application.yml")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid plan file name");
        assertThatThrownBy(() -> RunPlanFiles.delete(directory, "sub/other.yml")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid plan file name");
        assertThatThrownBy(() -> RunPlanFiles.resolveInside(directory, "/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsOnlyYamlFiles() throws Exception {
        RunPlanFiles.create(directory, "one", 1, List.of(), List.of("a"), List.of("a"));
        Files.writeString(directory.resolve("notes.txt"), "ignore me");

        assertThat(RunPlanFiles.list(directory)).extracting(path -> path.getFileName().toString()).containsExactly("one.yml");
    }
}
