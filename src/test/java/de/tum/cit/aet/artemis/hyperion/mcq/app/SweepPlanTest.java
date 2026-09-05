package de.tum.cit.aet.artemis.hyperion.mcq.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

class SweepPlanTest {

    @TempDir
    private Path directory;

    @Test
    void load_readsEveryField() throws IOException {
        SweepPlan plan = SweepPlan.load(file("""
                sweep: lak27
                requests-file: config/requests/lak27.yml
                repetitions: 3
                pool:
                  items-per-cell: 20
                  subsections: 5
                  retrieval-top-m: 40
                  languages: [de]
                  question-types: [single-choice]
                  difficulties: [medium, hard]
                selection:
                  max-candidates: 60
                  temperature: 0.9
                  max-attempts: 2
                agentic:
                  max-rounds: 4
                configurations:
                  - id: agentic-local
                    approach: agentic
                    generator: local
                    judge: local
                  - id: two-phase-mixed
                    approach: two-phase
                    generator: local
                    judge: cloud
                    selector: cloud
                """));

        assertThat(plan.sweep()).isEqualTo("lak27");
        assertThat(plan.repetitions()).isEqualTo(3);
        assertThat(plan.pool().languages()).containsExactly(Language.DE);
        assertThat(plan.pool().difficulties()).containsExactlyInAnyOrder(Difficulty.MEDIUM, Difficulty.HARD);
        assertThat(plan.selection().temperature()).isEqualTo(0.9);
        assertThat(plan.agentic().maxRounds()).isEqualTo(4);
        assertThat(plan.configurations()).hasSize(2);
        assertThat(plan.configurations().getFirst().configurationId()).isEqualTo("agentic|local|local");
        assertThat(plan.configurations().getLast().configurationId()).isEqualTo("two-phase|local|cloud");
    }

    @Test
    void named_replacesOnlyTheSweepName() throws IOException {
        SweepPlan plan = SweepPlan.load(file("""
                sweep: minimal
                requests-file: requests.yml
                configurations:
                  - { id: a, approach: agentic, generator: m, judge: m }
                """));

        SweepPlan renamed = plan.named("pilot-2");

        assertThat(renamed.sweep()).isEqualTo("pilot-2");
        assertThat(renamed.requestsFile()).isEqualTo(plan.requestsFile());
        assertThat(renamed.repetitions()).isEqualTo(plan.repetitions());
        assertThat(renamed.configurations()).isEqualTo(plan.configurations());
    }

    @Test
    void load_appliesDefaultsWhenSectionsAreAbsent() throws IOException {
        SweepPlan plan = SweepPlan.load(file("""
                sweep: minimal
                requests-file: requests.yml
                configurations:
                  - { id: a, approach: agentic, generator: m, judge: m }
                """));

        assertThat(plan.repetitions()).isEqualTo(3);
        assertThat(plan.pool().itemsPerCell()).isEqualTo(20);
        assertThat(plan.pool().languages()).containsExactlyInAnyOrder(Language.values());
        assertThat(plan.pool().questionTypes()).containsExactlyInAnyOrder(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE);
        assertThat(plan.selection().maxCandidates()).isEqualTo(40);
        assertThat(plan.configurations().getFirst().selector()).isEqualTo("m");
    }

    @Test
    void load_rejectsAnUnknownApproach() throws IOException {
        Path file = file("""
                sweep: s
                requests-file: r.yml
                configurations:
                  - { id: a, approach: hybrid, generator: m, judge: m }
                """);

        assertThatThrownBy(() -> SweepPlan.load(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hybrid");
    }

    @Test
    void load_rejectsDuplicateConfigurationIds() throws IOException {
        Path file = file("""
                sweep: s
                requests-file: r.yml
                configurations:
                  - { id: a, approach: agentic, generator: m, judge: m }
                  - { id: a, approach: two-phase, generator: m, judge: m }
                """);

        assertThatThrownBy(() -> SweepPlan.load(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'a' more than once");
    }

    private Path file(String yaml) throws IOException {
        Path file = directory.resolve("sweep.yml");
        Files.writeString(file, yaml);
        return file;
    }
}
