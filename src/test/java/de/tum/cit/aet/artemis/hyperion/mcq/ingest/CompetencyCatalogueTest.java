package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;

class CompetencyCatalogueTest {

    @TempDir
    private Path directory;

    @Test
    void load_mapsACatalogueIntoAManifest() throws IOException {
        Path file = file("""
                {
                  "course": "EIDI",
                  "name": "Einfuehrung in die Informatik",
                  "language": "de",
                  "sources": [ { "origin": "artemis-export", "entries": 24 } ],
                  "competencies": [
                    {
                      "title": "Abstrakte Klassen und Interfaces",
                      "taxonomy": "UNDERSTAND",
                      "origin": "artemis-export",
                      "description": [
                        "Du verstehst das Konzept von abstrakten Methoden.",
                        "Du kannst Default-Methoden in Interfaces erstellen."
                      ]
                    },
                    { "title": "Arrays", "taxonomy": "APPLY", "origin": "artemis-export", "description": [] }
                  ]
                }
                """);

        CompetencyManifest manifest = CompetencyCatalogue.load(file);

        assertThat(manifest.course().key()).isEqualTo("EIDI");
        assertThat(manifest.course().title()).isEqualTo("Einfuehrung in die Informatik");
        assertThat(manifest.competencies()).hasSize(2);
        Competency first = manifest.competencies().getFirst();
        assertThat(first.key()).isEqualTo("abstrakte-klassen-und-interfaces");
        assertThat(first.title()).isEqualTo("Abstrakte Klassen und Interfaces");
        assertThat(first.taxonomy()).isEqualTo(Taxonomy.UNDERSTAND);
        assertThat(first.description()).contains("abstrakten Methoden").contains("Default-Methoden");
        assertThat(first.lectureUnits()).isEmpty();
    }

    @Test
    void load_derivesARetrievalQueryFromTitleAndObjectives() throws IOException {
        Path file = file("""
                { "course": "EIDI", "competencies": [
                  { "title": "Arrays", "taxonomy": "APPLY", "description": ["Du kannst Arrays erstellen."] } ] }
                """);

        Competency competency = CompetencyCatalogue.load(file).competencies().getFirst();

        assertThat(competency.retrievalQuery()).contains("Arrays").contains("Du kannst Arrays erstellen.");
    }

    @Test
    void load_rejectsAnUnknownTaxonomyNamingTheCompetency() throws IOException {
        Path file = file("""
                { "course": "EIDI", "competencies": [ { "title": "Arrays", "taxonomy": "GUESS" } ] }
                """);

        assertThatThrownBy(() -> CompetencyCatalogue.load(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Arrays").hasMessageContaining("GUESS");
    }

    @Test
    void load_rejectsTitlesThatCollideAfterSlugging() throws IOException {
        Path file = file("""
                { "course": "EIDI", "competencies": [
                  { "title": "Call-by-Value", "taxonomy": "UNDERSTAND" },
                  { "title": "Call by Value", "taxonomy": "UNDERSTAND" } ] }
                """);

        assertThatThrownBy(() -> CompetencyCatalogue.load(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("call-by-value");
    }

    @Test
    void load_rejectsACatalogueWithoutCompetencies() throws IOException {
        assertThatThrownBy(() -> CompetencyCatalogue.load(file("{ \"course\": \"EIDI\" }"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares no competencies");
    }

    private Path file(String json) throws IOException {
        Path file = directory.resolve("catalogue.json");
        Files.writeString(file, json);
        return file;
    }
}
