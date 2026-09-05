package de.tum.cit.aet.artemis.hyperion.mcq.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.JudgedQuestion;
import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkQuiz.BenchmarkQuestion;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.StoredQuiz;

/**
 * Writes a sweep's assembled quizzes as benchmark input: a public quiz JSON per quiz, a private
 * {@code *.key.json} sidecar next to it, one instructions JSON per request, and a ready benchmark config.
 * <p>
 * The split follows the validation corpus's two-file model. The public file carries only what a rater or
 * judge may see: no explanation, no source reference wording that reveals the course material, and no
 * generator identity. Everything hidden — provenance, the generating configuration, explanations, filter
 * decisions — lives in the sidecar, keyed by the same question ids, and joins benchmark results back to
 * configurations by {@code quiz_id}.
 */
@Service
public class SweepExporter {

    private static final Logger log = LoggerFactory.getLogger(SweepExporter.class);

    /** Course keys of the study mapped to the benchmark's two domains; other courses map to themselves. */
    private static final Map<String, String> DOMAINS = Map.of("EIDI", "java", "EIST", "se", "PSE", "se");

    private final JsonMapper writer = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final JsonMapper reader = StructuredOutputs.outputMapper();

    /**
     * Export every stored quiz of a sweep.
     *
     * @param store     store holding the assembled quizzes
     * @param sweepId   the sweep run id
     * @param requests  the requests the sweep answered, for intent files and objective lookup
     * @param manifests the course model per course key
     * @param directory output root; {@code quizzes/}, {@code sidecars/}, {@code instructions/} and the
     *                  config are created beneath it
     * @return the paths of the public quiz files written
     * @throws UncheckedIOException if a file cannot be written
     */
    public List<Path> export(RunStore store, String sweepId, List<GenerationRequest> requests, Map<String, CompetencyManifest> manifests, Path directory) {
        Map<String, GenerationRequest> requestsByKey = new LinkedHashMap<>();
        requests.forEach(request -> requestsByKey.put(request.key(), request));

        Path quizzes = directory.resolve("quizzes");
        Path sidecars = directory.resolve("sidecars");
        Path instructions = directory.resolve("instructions");
        try {
            Files.createDirectories(quizzes);
            Files.createDirectories(sidecars);
            Files.createDirectories(instructions);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to create export directories under " + directory, e);
        }

        for (GenerationRequest request : requests) {
            writeJson(instructions.resolve(request.key() + ".json"), intent(request));
        }

        List<Path> written = new ArrayList<>();
        int questionCounter = 0;
        for (StoredQuiz stored : store.quizzes(sweepId)) {
            GenerationRequest request = requestsByKey.get(stored.requestKey());
            if (request == null) {
                log.warn("Quiz {} answers request '{}', which the given request list does not contain; skipping it", stored.quizId(), stored.requestKey());
                continue;
            }
            List<JudgedQuestion> questions = reader.readValue(stored.quizJson(), new TypeReference<List<JudgedQuestion>>() {
            });
            if (questions.isEmpty()) {
                log.warn("Quiz {} holds no questions; skipping it", stored.quizId());
                continue;
            }

            List<BenchmarkQuestion> publicQuestions = new ArrayList<>();
            Map<String, Map<String, Object>> sidecarQuestions = new LinkedHashMap<>();
            for (JudgedQuestion question : questions) {
                String questionId = "IT%04d".formatted(++questionCounter);
                publicQuestions.add(publicQuestion(questionId, question.item(), request, manifests));
                sidecarQuestions.put(questionId, sidecarQuestion(question, stored));
            }

            BenchmarkQuiz quiz = new BenchmarkQuiz(stored.quizId(), title(request), sourceMaterial(request), request.key() + ".json",
                    Map.of("learning_objectives", objectives(request, manifests)), publicQuestions);
            Path file = quizzes.resolve(stored.quizId() + ".json");
            writeJson(file, quiz);
            writeJson(sidecars.resolve(stored.quizId() + ".key.json"),
                    Map.of("quiz", Map.of("provenance", "ai", "generator_id", stored.configurationId(), "complete", stored.complete()), "questions", sidecarQuestions));
            written.add(file);
        }

        writeConfig(directory, sweepId);
        writeMaterialManifest(directory, requests, manifests);
        log.info("Exported {} quizzes of sweep {} to {}", written.size(), sweepId, directory);
        return List.copyOf(written);
    }

    /**
     * The material path a quiz is evaluated against, relative to the benchmark's source directory.
     * <p>
     * A single-competency request is scoped to {@code <course>/<competency>}: the benchmark loads the whole
     * folder behind this path into every material-reading metric call, so whole-course material overruns
     * the evaluator's context window and makes the coverage metric judge against material the quiz never
     * targeted. Requests naming several competencies fall back to the course folder.
     */
    private static String sourceMaterial(GenerationRequest request) {
        if (request.competencyMode() && request.competencyKeys().size() == 1) {
            return request.courseKey() + "/" + request.competencyKeys().getFirst();
        }
        return request.courseKey();
    }

    private void writeMaterialManifest(Path directory, List<GenerationRequest> requests, Map<String, CompetencyManifest> manifests) {
        Map<String, List<String>> documents = new TreeMap<>();
        for (GenerationRequest request : requests) {
            String path = sourceMaterial(request);
            if (documents.containsKey(path) || !request.competencyMode() || request.competencyKeys().size() != 1) {
                continue;
            }
            manifests.get(request.courseKey()).byKey(request.competencyKeys().getFirst())
                    .ifPresent(competency -> documents.put(path,
                            Stream.concat(competency.lectureUnits().stream(), competency.exercises().stream()).map(CompetencyManifest.Link::document).distinct()
                                    .sorted().toList()));
        }
        StringBuilder out = new StringBuilder("""
                # How to assemble the benchmark's material directory.
                #
                # Each quiz's source_material names a folder below the benchmark's source_directory; the
                # benchmark loads that folder in full for every material-reading metric call. Create one
                # folder per key below and place exactly the listed course documents (paths relative to the
                # course material root) inside it. A quiz whose source_material is a bare course key is
                # evaluated against the whole course folder.
                """);
        documents.forEach((path, files) -> {
            out.append('\n').append('"').append(path).append("\":\n");
            files.forEach(file -> out.append("  - \"").append(file).append("\"\n"));
        });
        try {
            Files.writeString(directory.resolve("material-manifest.yaml"), out.toString());
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to write the material manifest under " + directory, e);
        }
    }

    private BenchmarkQuestion publicQuestion(String questionId, McqItem item, GenerationRequest request, Map<String, CompetencyManifest> manifests) {
        List<String> options = item.options().stream().map(AnswerOption::text).toList();
        List<String> correct = item.options().stream().filter(AnswerOption::correct).map(AnswerOption::text).toList();
        Object correctAnswer = switch (item.type()) {
            case MULTIPLE_CHOICE -> correct;
            case SINGLE_CHOICE, TRUE_FALSE -> correct.getFirst();
        };
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("domain", DOMAINS.getOrDefault(request.courseKey(), request.courseKey().toLowerCase(Locale.ROOT)));
        metadata.put("language", request.language().code());
        metadata.put("learning_objective", String.join("\n", objectives(request, manifests)));
        metadata.put("bloom_intended", bloom(request, manifests));
        return new BenchmarkQuestion(questionId, type(item), item.questionText(), options, correctAnswer, null, metadata);
    }

    private Map<String, Object> sidecarQuestion(JudgedQuestion question, StoredQuiz stored) {
        Map<String, Object> sidecar = new LinkedHashMap<>();
        sidecar.put("provenance", "ai");
        sidecar.put("generator_id", stored.configurationId());
        String correct = question.item().options().stream().filter(AnswerOption::correct).map(AnswerOption::text).findFirst().orElse("");
        if (question.item().explanation() != null && !question.item().explanation().isBlank()) {
            sidecar.put("explanation", Map.of(correct, question.item().explanation()));
        }
        if (question.decision() != null) {
            sidecar.put("filter_decision", question.decision());
        }
        return sidecar;
    }

    private static Map<String, Object> intent(GenerationRequest request) {
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("language", request.language() == de.tum.cit.aet.artemis.hyperion.mcq.domain.Language.DE ? "German" : "English");
        intent.put("num_questions", request.numberOfQuestions());
        intent.put("question_types", request.questionTypes().stream().map(type -> type.value().replace('-', '_')).sorted().toList());
        intent.put("difficulty", request.difficulty().value());
        intent.put("custom_prompt", request.optionalPrompt());
        return intent;
    }

    private static List<String> objectives(GenerationRequest request, Map<String, CompetencyManifest> manifests) {
        CompetencyManifest manifest = manifests.get(request.courseKey());
        if (manifest == null) {
            throw new IllegalArgumentException("No course model for course '" + request.courseKey() + "'");
        }
        List<String> objectives = new ArrayList<>();
        for (String key : request.competencyKeys()) {
            Competency competency = manifest.byKey(key)
                    .orElseThrow(() -> new IllegalArgumentException("Request '" + request.key() + "' names competency '" + key + "', which the course model does not declare"));
            String description = competency.description() == null || competency.description().isBlank() ? "" : ": " + competency.description();
            objectives.add(competency.title() + description);
        }
        return objectives;
    }

    private static String bloom(GenerationRequest request, Map<String, CompetencyManifest> manifests) {
        CompetencyManifest manifest = manifests.get(request.courseKey());
        return request.competencyKeys().stream().map(key -> manifest.byKey(key).orElseThrow().taxonomy().name()).distinct().sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String type(McqItem item) {
        return switch (item.type()) {
            case SINGLE_CHOICE -> BenchmarkQuiz.SINGLE_CHOICE;
            case MULTIPLE_CHOICE -> BenchmarkQuiz.MULTIPLE_CHOICE;
            case TRUE_FALSE -> "true_false";
        };
    }

    private static String title(GenerationRequest request) {
        String focus = request.competencyMode() ? String.join(", ", request.competencyKeys()) : request.topic();
        return request.courseKey() + " – " + focus + " – " + request.difficulty().value();
    }

    private void writeJson(Path file, Object value) {
        try {
            Files.writeString(file, writer.writeValueAsString(value), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
    }

    private void writeConfig(Path directory, String sweepId) {
        String yaml = """
                # Written by --export-experiment. All paths are relative to this file's directory, so run
                # the benchmark from here:
                #   cd <this directory> && python <benchmark checkout>/main.py --config benchmark.yaml
                #
                # Evaluator models must differ from every generator, judge and selector of the sweep: an
                # evaluator that also produced or filtered these questions measures self-agreement.
                benchmark:
                  name: "%s"
                  version: "1.0.0"
                  runs: 3

                evaluators:
                  independent_judge:
                    provider: "openai_compatible"
                    model: "<a model no configuration of this sweep used>"
                    base_url: "https://logos.aet.cit.tum.de/v1"
                    temperature: 0.0
                    max_tokens: 2000

                # Metric names and versions must match the benchmark's registry; an unknown name fails the
                # run. Names below match the registry as of 2026-09; re-check after a benchmark update.
                metrics:
                  - {name: "accuracy", version: "1.2", evaluators: ["independent_judge"], enabled: true}
                  - {name: "answer_key_correctness", version: "1.0", evaluators: ["independent_judge"], enabled: true}
                  - {name: "objective_alignment", version: "1.0", evaluators: ["independent_judge"], enabled: true}
                  - {name: "cognitive_level", version: "1.0", evaluators: ["independent_judge"], enabled: true}
                  - {name: "clarity", version: "2.0", evaluators: ["independent_judge"], enabled: true}
                  - {name: "distractor_quality", version: "2.0", evaluators: ["independent_judge"], enabled: true}
                  - {name: "absence_of_cueing", version: "1.0", evaluators: ["independent_judge"], enabled: true}
                  - {name: "grammatical_correctness", version: "2.1", evaluators: ["independent_judge"], enabled: true}
                  - {name: "difficulty", version: "1.2", evaluators: ["independent_judge"], enabled: true}
                  - {name: "coverage", version: "1.6", evaluators: ["independent_judge"], enabled: true}
                  - {name: "homogeneous_options", version: "2.0", evaluators: ["independent_judge"], enabled: true}
                  - {name: "cross_item_redundancy", version: "1.0", evaluators: ["independent_judge"], enabled: false}
                  - {name: "difficulty_spread", version: "1.0", evaluators: ["independent_judge"], enabled: false}
                  - {name: "objective_balance", version: "1.0", evaluators: ["independent_judge"], enabled: false}

                inputs:
                  quiz_directory: "quizzes"
                  # Point this at the material directory. Each quiz's source_material names a folder below
                  # it — <course>/<competency> for single-competency quizzes — and the benchmark loads that
                  # folder in full per material-reading call. material-manifest.yaml in this export lists
                  # exactly which course documents belong in each folder.
                  source_directory: "<path to the course material>"
                  instructions_directory: "instructions"

                outputs:
                  results_directory: "results"
                  aggregate: true
                """.formatted(sweepId);
        try {
            Files.writeString(directory.resolve("benchmark.yaml"), yaml, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to write benchmark config in " + directory, e);
        }
    }
}
