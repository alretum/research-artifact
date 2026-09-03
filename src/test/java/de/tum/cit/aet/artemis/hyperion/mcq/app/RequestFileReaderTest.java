package de.tum.cit.aet.artemis.hyperion.mcq.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

class RequestFileReaderTest {

    @TempDir
    private Path directory;

    @Test
    void read_roundTripsEveryField() throws IOException {
        Path file = file("""
                - key: eidi-r1
                  course: EIDI
                  competencies: [Arrays, Basistypen]
                  optional-prompt: focus on pitfalls
                  language: de
                  question-types: [single-choice, multiple-choice]
                  number-of-questions: 10
                  difficulty: hard
                - key: eist-r1
                  course: EIST
                  topic: Scrum
                  language: en
                  question-types: [single-choice]
                  number-of-questions: 5
                  difficulty: easy
                """);

        List<GenerationRequest> requests = RequestFileReader.read(file);

        assertThat(requests).hasSize(2);
        GenerationRequest first = requests.getFirst();
        assertThat(first.key()).isEqualTo("eidi-r1");
        assertThat(first.courseKey()).isEqualTo("EIDI");
        assertThat(first.competencyKeys()).containsExactly("Arrays", "Basistypen");
        assertThat(first.optionalPrompt()).isEqualTo("focus on pitfalls");
        assertThat(first.language()).isEqualTo(Language.DE);
        assertThat(first.questionTypes()).containsExactlyInAnyOrder(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE);
        assertThat(first.numberOfQuestions()).isEqualTo(10);
        assertThat(first.difficulty()).isEqualTo(Difficulty.HARD);
        GenerationRequest second = requests.get(1);
        assertThat(second.topic()).isEqualTo("Scrum");
        assertThat(second.competencyMode()).isFalse();
    }

    @Test
    void read_rejectsAMisspelledField() throws IOException {
        Path file = file("""
                - key: r1
                  course: EIDI
                  topic: Arrays
                  language: de
                  question-types: [single-choice]
                  number-of-quesitons: 5
                  difficulty: medium
                """);

        assertThatThrownBy(() -> RequestFileReader.read(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("number-of-quesitons");
    }

    @Test
    void read_rejectsDuplicateKeys() throws IOException {
        Path file = file("""
                - key: r1
                  course: EIDI
                  topic: Arrays
                  language: de
                  question-types: [single-choice]
                  number-of-questions: 5
                  difficulty: medium
                - key: r1
                  course: EIDI
                  topic: Streams
                  language: de
                  question-types: [single-choice]
                  number-of-questions: 5
                  difficulty: medium
                """);

        assertThatThrownBy(() -> RequestFileReader.read(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'r1' more than once");
    }

    @Test
    void read_namesTheRequestWhenAValueIsInvalid() throws IOException {
        Path file = file("""
                - key: r1
                  course: EIDI
                  topic: Arrays
                  language: de
                  question-types: [single-choice]
                  number-of-questions: 5
                  difficulty: impossible
                """);

        assertThatThrownBy(() -> RequestFileReader.read(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'r1'").hasMessageContaining("impossible");
    }

    @Test
    void read_rejectsAnEmptyFile() throws IOException {
        assertThatThrownBy(() -> RequestFileReader.read(file(""))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-empty YAML list");
    }

    private Path file(String yaml) throws IOException {
        Path file = directory.resolve("requests.yml");
        Files.writeString(file, yaml);
        return file;
    }
}
