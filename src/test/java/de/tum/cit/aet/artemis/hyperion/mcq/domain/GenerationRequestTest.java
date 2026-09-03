package de.tum.cit.aet.artemis.hyperion.mcq.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

class GenerationRequestTest {

    @Test
    void constructor_acceptsACompetencyModeRequest() {
        GenerationRequest request = request(null, List.of("arrays"));

        assertThat(request.competencyMode()).isTrue();
        assertThat(request.competencyKeys()).containsExactly("arrays");
    }

    @Test
    void constructor_acceptsAFreeTopicRequest() {
        GenerationRequest request = request("Duality", null);

        assertThat(request.competencyMode()).isFalse();
        assertThat(request.competencyKeys()).isEmpty();
    }

    @Test
    void constructor_rejectsTopicAndCompetenciesTogether() {
        assertThatThrownBy(() -> request("Duality", List.of("arrays"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("either a topic or competencies");
    }

    @Test
    void constructor_rejectsNeitherTopicNorCompetencies() {
        assertThatThrownBy(() -> request(null, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("either a topic or competencies");
    }

    @Test
    void constructor_rejectsAQuestionCountOutsideOneToTen() {
        assertThatThrownBy(() -> new GenerationRequest("r1", "EIDI", "Duality", null, null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 11, Difficulty.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("11");
    }

    @Test
    void constructor_rejectsAnOverlongOptionalPrompt() {
        assertThatThrownBy(() -> new GenerationRequest("r1", "EIDI", "Duality", null, "x".repeat(2001), Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 5, Difficulty.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2001");
    }

    @Test
    void constructor_copiesTheCompetencyList() {
        List<String> keys = new ArrayList<>(List.of("arrays"));
        GenerationRequest request = request(null, keys);

        keys.add("basistypen");

        assertThat(request.competencyKeys()).containsExactly("arrays");
    }

    @Test
    void difficulty_mapsToThePromptScaleOneWay() {
        assertThat(Difficulty.EASY.promptValue()).isEqualTo(20);
        assertThat(Difficulty.MEDIUM.promptValue()).isEqualTo(50);
        assertThat(Difficulty.HARD.promptValue()).isEqualTo(80);
    }

    private static GenerationRequest request(String topic, List<String> competencyKeys) {
        return new GenerationRequest("r1", "EIDI", topic, competencyKeys, null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 10, Difficulty.MEDIUM);
    }
}
