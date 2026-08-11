package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.RelationType;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;

class CompetencyManifestTest {

    @TempDir
    Path directory;

    @Test
    void parsesCompetenciesLinksAndRelations() throws IOException {
        var manifest = load("""
                course: { key: or, title: Operations Research, description: d }
                competencies:
                  - key: a
                    title: Alpha
                    taxonomy: APPLY
                    description: "- You do alpha."
                    lectureUnits:
                      - document: deck-a.pdf
                        weight: 0.5
                    exercises:
                      - document: sheet-a.pdf
                  - key: b
                    title: Beta
                    taxonomy: ANALYZE
                    relations:
                      - type: ASSUMES
                        target: a
                """);

        assertThat(manifest.course().title()).isEqualTo("Operations Research");
        assertThat(manifest.competencies()).hasSize(2);

        var alpha = manifest.byKey("a").orElseThrow();
        assertThat(alpha.taxonomy()).isEqualTo(Taxonomy.APPLY);
        assertThat(alpha.lectureUnits().getFirst().weight()).isEqualTo(0.5);
        assertThat(alpha.exercises().getFirst().weight()).isEqualTo(1.0);
        assertThat(alpha.linkedDocuments()).containsExactly("deck-a.pdf", "sheet-a.pdf");
        assertThat(manifest.byKey("b").orElseThrow().relations().getFirst().type()).isEqualTo(RelationType.ASSUMES);
    }

    @Test
    void usesTitleAndDescriptionAsTheQueryWhenNoOverrideIsGiven() throws IOException {
        var competency = load("""
                competencies:
                  - key: a
                    title: Alpha
                    description: "- You do alpha.\\n- You also do beta."
                """).byKey("a").orElseThrow();

        assertThat(competency.retrievalQuery()).startsWith("Alpha.").contains("You do alpha").doesNotContain("\\n");
    }

    @Test
    void prefersAnExplicitQueryOverride() throws IOException {
        var competency = load("""
                competencies:
                  - key: a
                    title: Alpha
                    query: shadow prices and ranging
                    description: "- ignored"
                """).byKey("a").orElseThrow();

        assertThat(competency.retrievalQuery()).isEqualTo("shadow prices and ranging");
    }

    @Test
    void reportsLinksToDocumentsAbsentFromTheCorpus() throws IOException {
        var manifest = load("""
                competencies:
                  - key: a
                    title: Alpha
                    lectureUnits:
                      - document: present.pdf
                      - document: missing.pdf
                """);

        assertThat(manifest.unresolvedLinks(Set.of("present.pdf"))).containsExactly("a -> missing.pdf");
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThatThrownBy(() -> load("""
                competencies:
                  - { key: a, title: Alpha }
                  - { key: a, title: Again }
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate competency key");
    }

    @Test
    void rejectsARelationToAnUndeclaredCompetency() {
        assertThatThrownBy(() -> load("""
                competencies:
                  - key: a
                    title: Alpha
                    relations:
                      - { type: EXTENDS, target: nowhere }
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown target");
    }

    @Test
    void rejectsAnUnknownTaxonomy() {
        assertThatThrownBy(() -> load("""
                competencies:
                  - { key: a, title: Alpha, taxonomy: MEMORISE }
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown taxonomy");
    }

    @Test
    void rejectsAManifestWithoutCompetencies() {
        assertThatThrownBy(() -> load("course: { key: or, title: T }")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no competencies");
    }

    @Test
    void rejectsACompetencyWithoutATitle() {
        assertThatThrownBy(() -> load("competencies:\n  - { key: a }")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key and a title");
    }

    private CompetencyManifest load(String yaml) throws IOException {
        Path file = Files.createTempFile(directory, "manifest", ".yml");
        Files.writeString(file, yaml);
        return CompetencyManifest.load(file);
    }
}
