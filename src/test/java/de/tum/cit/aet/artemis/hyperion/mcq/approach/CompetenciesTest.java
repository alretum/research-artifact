package de.tum.cit.aet.artemis.hyperion.mcq.approach;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Course;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;

class CompetenciesTest {

    @Test
    void match_resolvesASingleCompetencyRequestWithoutADeclaration() {
        GenerationRequest request = new GenerationRequest("r1", "EIDI", null, List.of("arrays"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 2, Difficulty.MEDIUM);

        assertThat(Competencies.match(request, manifest(), null)).contains("arrays");
    }

    @Test
    void match_resolvesADeclaredTitleIgnoringCaseAndWhitespace() {
        GenerationRequest request = new GenerationRequest("r1", "EIDI", null, List.of("arrays", "streams"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 2,
                Difficulty.MEDIUM);

        assertThat(Competencies.match(request, manifest(), "  streams  ")).contains("streams");
        assertThat(Competencies.match(request, manifest(), "ARRAYS")).contains("arrays");
    }

    @Test
    void match_resolvesATitleDeclaredWithTheTaxonomyThePromptShowed() {
        GenerationRequest request = new GenerationRequest("r1", "EIDI", null, List.of("arrays", "streams"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 2,
                Difficulty.MEDIUM);

        assertThat(Competencies.match(request, manifest(), "Arrays (APPLY)")).contains("arrays");
        assertThat(Competencies.match(request, manifest(), "Streams (UNDERSTAND)")).contains("streams");
    }

    @Test
    void match_rejectsACompetencyTheRequestDoesNotName() {
        GenerationRequest request = new GenerationRequest("r1", "EIDI", null, List.of("arrays", "streams"), null, Language.DE, Set.of(QuestionType.SINGLE_CHOICE), 2,
                Difficulty.MEDIUM);

        assertThat(Competencies.match(request, manifest(), "Generics")).isEmpty();
        assertThat(Competencies.match(request, manifest(), null)).isEmpty();
    }

    private static CompetencyManifest manifest() {
        return new CompetencyManifest(new Course("EIDI", "EIDI", ""),
                List.of(new Competency("arrays", "Arrays", "Du kannst Arrays erstellen.", null, Taxonomy.APPLY, false, null, List.of(), List.of(), List.of()),
                        new Competency("streams", "Streams", "Du kannst Streams nutzen.", null, Taxonomy.UNDERSTAND, false, null, List.of(), List.of(), List.of())));
    }
}
