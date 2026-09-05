package de.tum.cit.aet.artemis.hyperion.mcq.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.JudgedQuestion;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Course;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.StoredQuiz;

class SweepExporterTest {

    @TempDir
    private Path directory;

    private RunStore store;

    private final JsonMapper mapper = StructuredOutputs.outputMapper();

    private final SweepExporter exporter = new SweepExporter();

    @BeforeEach
    void setUp() throws IOException {
        store = new RunStore(directory.resolve("run.db"));
        store.registerRun("sweep1", "sweep", "manifest");
        store.saveQuiz(new StoredQuiz("sweep1-agentic-r1", "sweep1", "agentic|local|local", "EIDI", "eidi-r1", 1, true,
                mapper.writeValueAsString(List.of(judged(singleChoice()), judged(multipleChoice()))), "[]", "[]"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void export_writesAPublicQuizWithoutAnythingHidden() throws IOException {
        exporter.export(store, "sweep1", List.of(request()), manifests(), directory.resolve("out"));

        String json = Files.readString(directory.resolve("out/quizzes/sweep1-agentic-r1.json"));
        Map<String, Object> quiz = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });

        assertThat(json).doesNotContain("explanation").doesNotContain("agentic|local|local").doesNotContain("source_reference");
        assertThat(quiz.get("quiz_id")).isEqualTo("sweep1-agentic-r1");
        assertThat(quiz.get("source_material")).isEqualTo("EIDI/arrays");
        assertThat(quiz.get("instructions")).isEqualTo("eidi-r1.json");
        List<Map<String, Object>> questions = questions(quiz);
        assertThat(questions).hasSize(2);
        assertThat(questions.getFirst().get("question_id")).isEqualTo("IT0001");
        assertThat(questions.getFirst().get("question_type")).isEqualTo("single_choice");
        assertThat(questions.getFirst().get("correct_answer")).isEqualTo("PUT");
        assertThat(questions.getLast().get("question_type")).isEqualTo("multiple_choice");
        assertThat(questions.getLast().get("correct_answer")).isEqualTo(List.of("List", "Map"));
    }

    @Test
    void export_stampsTheObjectiveMetadataEveryQuestionNeeds() throws IOException {
        exporter.export(store, "sweep1", List.of(request()), manifests(), directory.resolve("out"));

        Map<String, Object> quiz = readQuiz();
        for (Map<String, Object> question : questions(quiz)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) question.get("metadata");
            assertThat(metadata.get("domain")).isEqualTo("java");
            assertThat(metadata.get("language")).isEqualTo("de");
            assertThat(String.valueOf(metadata.get("learning_objective"))).contains("Arrays").contains("Du kannst Arrays erstellen.");
            assertThat(metadata.get("bloom_intended")).isEqualTo("APPLY");
        }
    }

    @Test
    void export_writesASidecarCoveringExactlyThePublicQuestionIds() throws IOException {
        exporter.export(store, "sweep1", List.of(request()), manifests(), directory.resolve("out"));

        Map<String, Object> sidecar = mapper.readValue(Files.readString(directory.resolve("out/sidecars/sweep1-agentic-r1.key.json")),
                new TypeReference<Map<String, Object>>() {
                });
        @SuppressWarnings("unchecked")
        Map<String, Object> quizPart = (Map<String, Object>) sidecar.get("quiz");
        @SuppressWarnings("unchecked")
        Map<String, Object> questionPart = (Map<String, Object>) sidecar.get("questions");

        assertThat(quizPart.get("provenance")).isEqualTo("ai");
        assertThat(quizPart.get("generator_id")).isEqualTo("agentic|local|local");
        assertThat(questionPart.keySet()).containsExactlyInAnyOrder("IT0001", "IT0002");
        assertThat(String.valueOf(questionPart.get("IT0001"))).contains("PUT ist idempotent.");
    }

    @Test
    void export_writesTheIntentFileFromTheRequestNotFromAnyTemplate() throws IOException {
        exporter.export(store, "sweep1", List.of(request()), manifests(), directory.resolve("out"));

        Map<String, Object> intent = mapper.readValue(Files.readString(directory.resolve("out/instructions/eidi-r1.json")), new TypeReference<Map<String, Object>>() {
        });

        assertThat(intent.get("language")).isEqualTo("German");
        assertThat(intent.get("num_questions")).isEqualTo(2);
        assertThat(intent.get("question_types")).isEqualTo(List.of("multiple_choice", "single_choice"));
        assertThat(intent.get("difficulty")).isEqualTo("medium");
        assertThat(intent.containsKey("custom_prompt")).isTrue();
        assertThat(intent.get("custom_prompt")).isNull();
    }

    @Test
    void export_writesTheMaterialManifestForEveryScopedPath() throws IOException {
        exporter.export(store, "sweep1", List.of(request()), manifests(), directory.resolve("out"));

        String manifest = Files.readString(directory.resolve("out/material-manifest.yaml"));

        assertThat(manifest).contains("\"EIDI/arrays\":");
        assertThat(manifest).contains("01_Arrays.pdf");
    }

    @Test
    void export_writesARunnableBenchmarkConfig() throws IOException {
        exporter.export(store, "sweep1", List.of(request()), manifests(), directory.resolve("out"));

        String config = Files.readString(directory.resolve("out/benchmark.yaml"));

        assertThat(config).contains("objective_alignment").contains("coverage").contains("quiz_directory");
        assertThat(config).contains("runs: 3");
    }

    private Map<String, Object> readQuiz() throws IOException {
        return mapper.readValue(Files.readString(directory.resolve("out/quizzes/sweep1-agentic-r1.json")), new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> questions(Map<String, Object> quiz) {
        return (List<Map<String, Object>>) quiz.get("questions");
    }

    private static GenerationRequest request() {
        return new GenerationRequest("eidi-r1", "EIDI", null, List.of("arrays"), null, Language.DE, java.util.Set.of(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE), 2,
                Difficulty.MEDIUM);
    }

    private static Map<String, CompetencyManifest> manifests() {
        return Map.of("EIDI", new CompetencyManifest(new Course("EIDI", "EIDI", ""),
                List.of(new Competency("arrays", "Arrays", "Du kannst Arrays erstellen.", null, Taxonomy.APPLY, false, null,
                        List.of(new CompetencyManifest.Link("01_Arrays.pdf", 1.0)), List.of(), List.of()))));
    }

    private static JudgedQuestion judged(McqItem item) {
        return new JudgedQuestion(item, new FilterDecision(true, 1.0, 0.0, Map.of(), "judge", "fine"));
    }

    private static McqItem singleChoice() {
        List<AnswerOption> options = List.of(new AnswerOption("PUT", true, null, null), new AnswerOption("POST", false, null, null), new AnswerOption("PATCH", false, null, null),
                new AnswerOption("GET", false, null, null));
        return new McqItem(QuestionType.SINGLE_CHOICE, "Idempotenz", "Welche Methode ist idempotent?", options, null, "PUT ist idempotent.");
    }

    private static McqItem multipleChoice() {
        List<AnswerOption> options = List.of(new AnswerOption("List", true, null, null), new AnswerOption("Map", true, null, null), new AnswerOption("int", false, null, null),
                new AnswerOption("var", false, null, null));
        return new McqItem(QuestionType.MULTIPLE_CHOICE, "Referenztypen", "Welche sind Referenztypen?", options, null, "List und Map sind Referenztypen.");
    }
}
