package de.tum.cit.aet.artemis.hyperion.mcq.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Course;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;

class PoolGridTest {

    @Test
    void derive_producesTheFullCrossProductInAFixedOrder() {
        List<PoolCell> cells = PoolGrid.derive("EIDI", manifest("arrays", "basistypen"), Set.of(Language.DE), Set.of(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE),
                Set.of(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD));

        assertThat(cells).hasSize(2 * 1 * 2 * 3);
        assertThat(cells.getFirst().key()).isEqualTo("EIDI|arrays|de|single-choice|easy");
        assertThat(cells.getLast().key()).isEqualTo("EIDI|basistypen|de|multiple-choice|hard");
        assertThat(cells.stream().map(PoolCell::key).distinct()).hasSize(cells.size());
    }

    @Test
    void derive_isDeterministicRegardlessOfSetIterationOrder() {
        Set<Difficulty> difficulties = new java.util.HashSet<>(List.of(Difficulty.HARD, Difficulty.EASY));

        List<PoolCell> first = PoolGrid.derive("EIDI", manifest("arrays"), Set.of(Language.DE), Set.of(QuestionType.SINGLE_CHOICE), difficulties);
        List<PoolCell> second = PoolGrid.derive("EIDI", manifest("arrays"), Set.of(Language.DE), Set.of(QuestionType.SINGLE_CHOICE), Set.copyOf(difficulties));

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(PoolCell::difficulty).containsExactly(Difficulty.EASY, Difficulty.HARD);
    }

    @Test
    void derive_rejectsAnEmptyDimension() {
        assertThatThrownBy(() -> PoolGrid.derive("EIDI", manifest("arrays"), Set.of(), Set.of(QuestionType.SINGLE_CHOICE), Set.of(Difficulty.MEDIUM)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("languages");
    }

    private static CompetencyManifest manifest(String... keys) {
        List<Competency> competencies = java.util.Arrays.stream(keys)
                .map(key -> new Competency(key, key, "", null, Taxonomy.APPLY, false, null, List.of(), List.of(), List.of())).toList();
        return new CompetencyManifest(new Course("EIDI", "EIDI", ""), competencies);
    }
}
