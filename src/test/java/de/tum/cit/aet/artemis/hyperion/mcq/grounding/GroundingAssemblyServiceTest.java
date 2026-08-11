package de.tum.cit.aet.artemis.hyperion.mcq.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;

class GroundingAssemblyServiceTest {

    private final GroundingAssemblyService service = new GroundingAssemblyService();

    @Test
    void wrapsTheBlockInUntrustedInputMarkers() {
        var context = service.assemble("topic", List.of(snippet("a", SourceRole.LECTURE_DECK, 100)), 1000);

        assertThat(context.renderedBlock()).startsWith("-----BEGIN UNTRUSTED INPUT-----").endsWith("-----END UNTRUSTED INPUT-----");
    }

    @Test
    void includesThePageRangeInEachSnippetHeader() {
        var context = service.assemble("topic", List.of(snippet("a", SourceRole.LECTURE_DECK, 100)), 1000);

        assertThat(context.renderedBlock()).contains("Lecture: lecture-a, Unit: unit-a, Pages 1–2");
    }

    @Test
    void stopsAddingSnippetsOnceTheBudgetWouldBeExceeded() {
        var context = service.assemble("topic", List.of(snippet("a", SourceRole.LECTURE_DECK, 500), snippet("b", SourceRole.LECTURE_DECK, 500),
                snippet("c", SourceRole.LECTURE_DECK, 500)), 300);

        assertThat(context.snippets()).hasSize(1);
    }

    @Test
    void alwaysIncludesTheLeadingSnippetEvenWhenItAloneExceedsTheBudget() {
        var context = service.assemble("topic", List.of(snippet("a", SourceRole.LECTURE_DECK, 5000)), 10);

        assertThat(context.snippets()).hasSize(1);
        assertThat(context.approxTokens()).isGreaterThan(10);
    }

    @Test
    void summarisesTheSourceRolesOfIncludedSnippets() {
        var context = service.assemble("topic", List.of(snippet("a", SourceRole.LECTURE_DECK, 50), snippet("b", SourceRole.SOLUTION, 50),
                snippet("c", SourceRole.SOLUTION, 50), snippet("d", SourceRole.TUTORIAL, 50)), 1000);

        var composition = context.composition();

        assertThat(composition.countsByRole()).containsEntry(SourceRole.SOLUTION, 2).containsEntry(SourceRole.LECTURE_DECK, 1);
        assertThat(composition.solutionFraction()).isEqualTo(0.5);
        assertThat(composition.describe()).contains("50% solution");
    }

    @Test
    void countsSnippetsWithoutARoleSeparately() {
        var context = service.assemble("topic", List.of(snippet("a", null, 50)), 1000);

        assertThat(context.composition().unknownRoles()).isEqualTo(1);
        assertThat(context.composition().countsByRole()).isEmpty();
    }

    @Test
    void rejectsANonPositiveBudget() {
        assertThatThrownBy(() -> service.assemble("topic", List.of(), 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTokens");
    }

    private static Snippet snippet(String key, SourceRole role, int chars) {
        return new Snippet("lecture-" + key, "unit-" + key, "Pages 1–2", "x".repeat(chars), "doc-" + key + "#p1-2", role, 0.5);
    }
}
