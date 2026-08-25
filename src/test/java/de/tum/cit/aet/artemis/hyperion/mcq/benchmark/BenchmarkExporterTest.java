package de.tum.cit.aet.artemis.hyperion.mcq.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkExporter.Condition;
import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkExporter.Granularity;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingComposition;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ItemProvenance;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.LengthStats;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ModeVerdict;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.ItemKey;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.QueuedItem;

class BenchmarkExporterTest {

    @TempDir
    Path directory;

    private RunStore store;

    private final BenchmarkExporter exporter = new BenchmarkExporter();

    private final JsonMapper mapper = StructuredOutputs.outputMapper();

    @BeforeEach
    void setUp() {
        store = new RunStore(directory.resolve("run.db"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void item(String runId, String configurationId, String topic, int index, boolean accepted, int difficulty, String lecture) {
        store.registerRun(runId, configurationId, "manifest-" + runId);
        ItemKey key = new ItemKey(runId, configurationId, topic, index);
        store.enqueue(List.of(new QueuedItem(key, difficulty)));
        store.claimNext(runId);

        McqItem mcq = new McqItem(QuestionType.SINGLE_CHOICE, "T", "Why does it hold?", List.of(new AnswerOption("because of X", true, null, null),
                new AnswerOption("because of Y", false, null, null), new AnswerOption("because of Z", false, null, null), new AnswerOption("because of W", false, null, null)), null,
                "E");
        ItemProvenance provenance = new ItemProvenance(runId, configurationId, "gen-model", "filt-model", topic, List.of(lecture + "/deck.pdf#p1-2"), "prompt", difficulty,
                new LengthStats(1, 1, 1, 1), false, new GroundingComposition(Map.of(), 0.25, 0), Instant.now());
        store.recordGenerated(key, mapper.writeValueAsString(mcq), mapper.writeValueAsString(provenance), "[]");
        store.claimNext(runId);
        FilterDecision decision = new FilterDecision(accepted, accepted ? 1.0 : 0.2, 0.1, Map.of(FailureMode.FACTUAL_ERROR, new ModeVerdict(0.0, false, "fine")), "filt-model", "r");
        store.recordFiltered(key, mapper.writeValueAsString(decision), "[]");
    }

    private Map<String, Object> read(Path file) {
        return mapper.readValue(file.toFile(), Map.class);
    }

    @Test
    void writesOneFilePerConfigurationAndTopicByDefault() {
        item("r1", "cfg-a", "Duality", 0, true, 20, "05 Duality");
        item("r1", "cfg-a", "Simplex", 0, true, 20, "02 Simplex");
        item("r2", "cfg-b", "Duality", 0, true, 20, "05 Duality");

        var files = exporter.export(store, directory.resolve("out"), Granularity.CONFIGURATION_TOPIC, Condition.ALL, "en");

        assertThat(files).hasSize(3);
    }

    @Test
    void poolsConfigurationsWhenGroupingByTopic() {
        item("r1", "cfg-a", "Duality", 0, true, 20, "05 Duality");
        item("r2", "cfg-b", "Duality", 0, true, 20, "05 Duality");

        var files = exporter.export(store, directory.resolve("out"), Granularity.TOPIC, Condition.ALL, "en");

        assertThat(files).hasSize(1);
        assertThat(read(files.getFirst())).extracting("metadata").extracting("item_count").isEqualTo(2);
    }

    @Test
    void emitsTheCorrectAnswerAsOptionTextThatAppearsAmongTheOptions() {
        item("r1", "cfg-a", "Duality", 0, true, 20, "05 Duality");

        var quiz = read(exporter.export(store, directory.resolve("out"), Granularity.TOPIC, Condition.ALL, "en").getFirst());
        Map<String, Object> question = ((List<Map<String, Object>>) quiz.get("questions")).getFirst();

        assertThat(question.get("question_type")).isEqualTo("single_choice");
        assertThat(question.get("correct_answer")).isEqualTo("because of X");
        assertThat((List<String>) question.get("options")).contains((String) question.get("correct_answer"));
    }

    @Test
    void carriesOurVariablesInQuestionMetadataSoResultsCanBeCrossTabulated() {
        item("r1", "cfg-a", "Duality", 0, false, 80, "05 Duality");

        var quiz = read(exporter.export(store, directory.resolve("out"), Granularity.TOPIC, Condition.ALL, "en").getFirst());
        Map<String, Object> metadata = (Map<String, Object>) ((List<Map<String, Object>>) quiz.get("questions")).getFirst().get("metadata");

        assertThat(metadata).containsEntry("accepted", false).containsEntry("requested_difficulty", 80).containsEntry("difficulty_level", "hard")
                .containsEntry("solution_fraction", 0.25).containsEntry("configuration_id", "cfg-a").containsKey("filter_severities");
    }

    @Test
    void bandsDifficultyIntoTheWordsTheBenchmarkExamplesUse() {
        item("r1", "cfg-a", "A", 0, true, 20, "L");
        item("r1", "cfg-a", "B", 0, true, 50, "L");
        item("r1", "cfg-a", "C", 0, true, 90, "L");

        var bands = exporter.export(store, directory.resolve("out"), Granularity.CONFIGURATION, Condition.ALL, "en").stream()
                .flatMap(file -> ((List<Map<String, Object>>) read(file).get("questions")).stream())
                .map(question -> ((Map<String, Object>) question.get("metadata")).get("difficulty_level")).toList();

        assertThat(bands).containsExactlyInAnyOrder("easy", "medium", "hard");
    }

    @Test
    void namesTheLectureAsSourceMaterialWhenTheQuizDrawsOnOnlyOne() {
        item("r1", "cfg-a", "Duality", 0, true, 20, "05 Duality");

        var quiz = read(exporter.export(store, directory.resolve("out"), Granularity.TOPIC, Condition.ALL, "en").getFirst());

        assertThat(quiz.get("source_material")).isEqualTo("05 Duality");
    }

    @Test
    void fallsBackToTheWholeSourceDirectoryWhenAQuizSpansLectures() {
        item("r1", "cfg-a", "A", 0, true, 20, "05 Duality");
        item("r1", "cfg-a", "B", 0, true, 20, "02 Simplex");

        var quiz = read(exporter.export(store, directory.resolve("out"), Granularity.CONFIGURATION, Condition.ALL, "en").getFirst());

        assertThat(quiz.get("source_material")).isEqualTo(".");
    }

    @Test
    void splitEmitsOneFilePerConditionSoQuizLevelMetricsStayInterpretable() {
        item("r1", "cfg-a", "Duality", 0, true, 20, "05 Duality");
        item("r1", "cfg-a", "Duality", 1, false, 20, "05 Duality");

        var files = exporter.export(store, directory.resolve("out"), Granularity.CONFIGURATION_TOPIC, Condition.SPLIT, "en");

        assertThat(files).hasSize(2);
        assertThat(files).extracting(file -> file.getFileName().toString()).anyMatch(name -> name.contains("accepted")).anyMatch(name -> name.contains("rejected"));
    }

    @Test
    void acceptedOnlyExcludesRejectedItems() {
        item("r1", "cfg-a", "Duality", 0, true, 20, "05 Duality");
        item("r1", "cfg-a", "Duality", 1, false, 20, "05 Duality");

        var quiz = read(exporter.export(store, directory.resolve("out"), Granularity.TOPIC, Condition.ACCEPTED, "en").getFirst());

        assertThat((List<?>) quiz.get("questions")).hasSize(1);
    }

    @Test
    void skipsAGroupWithNoItemsForTheRequestedConditionRatherThanWritingAnEmptyQuiz() {
        item("r1", "cfg-a", "Duality", 0, true, 20, "05 Duality");

        var files = exporter.export(store, directory.resolve("out"), Granularity.TOPIC, Condition.REJECTED, "en");

        assertThat(files).isEmpty();
    }

    @Test
    void parsesCommandLineValuesAndNamesTheAcceptedOnes() {
        assertThat(Granularity.parse("configuration-topic")).isEqualTo(Granularity.CONFIGURATION_TOPIC);
        assertThat(Condition.parse("SPLIT")).isEqualTo(Condition.SPLIT);
        assertThatThrownBy(() -> Granularity.parse("per-item")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("configuration-topic");
        assertThatThrownBy(() -> Condition.parse("maybe")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accepted");
    }
}
