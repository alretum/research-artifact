package de.tum.cit.aet.artemis.hyperion.mcq.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.ItemKey;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.PoolCandidate;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.PoolItem;

class RunStorePoolTest {

    private static final PoolCell CELL = new PoolCell("EIDI", "arrays", Language.DE, QuestionType.SINGLE_CHOICE, Difficulty.MEDIUM);

    @TempDir
    private Path directory;

    private RunStore store;

    @BeforeEach
    void setUp() {
        store = new RunStore(directory.resolve("run.db"));
        store.registerRun("run1", "two-phase|local|local", "manifest");
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void enqueuePool_labelsRowsAndIgnoresDuplicates() {
        List<PoolItem> items = poolItems(3);

        assertThat(store.enqueuePool(items)).isEqualTo(3);
        assertThat(store.enqueuePool(items)).isZero();
        assertThat(store.itemCountForTopic("run1", "two-phase|local|local", CELL.key())).isEqualTo(3);
    }

    @Test
    void poolCandidates_returnsOnlyItemsTheGivenJudgeAccepted() {
        store.enqueuePool(poolItems(3));
        generateAll(3);
        long first = idOf(0);
        long second = idOf(1);
        store.recordVerdict(first, "judge-local", "GENERAL", true, "{}", null);
        store.recordVerdict(second, "judge-local", "GENERAL", false, "{}", null);
        store.recordVerdict(second, "judge-cloud", "GENERAL", true, "{}", null);

        List<PoolCandidate> local = store.poolCandidates(CELL, "gen-local", "judge-local", null);
        List<PoolCandidate> cloud = store.poolCandidates(CELL, "gen-local", "judge-cloud", null);

        assertThat(local).hasSize(1);
        assertThat(local.getFirst().id()).isEqualTo(first);
        assertThat(cloud).hasSize(1);
        assertThat(cloud.getFirst().id()).isEqualTo(second);
    }

    @Test
    void poolCandidates_carryTheSectionIndexAndTheJudgesDecision() {
        store.enqueuePool(poolItems(2));
        generateAll(2);
        long id = idOf(1);
        store.recordVerdict(id, "judge-local", "GENERAL", true, "{\"accepted\":true}", null);

        PoolCandidate candidate = store.poolCandidates(CELL, "gen-local", "judge-local", null).getFirst();

        assertThat(candidate.sectionIndex()).isEqualTo(1);
        assertThat(candidate.decisionJson()).contains("accepted");
    }

    @Test
    void poolCandidates_matchOnEveryLabelOfTheCell() {
        store.enqueuePool(poolItems(1));
        generateAll(1);
        long id = idOf(0);
        store.recordVerdict(id, "judge-local", "GENERAL", true, "{}", null);
        PoolCell otherDifficulty = new PoolCell("EIDI", "arrays", Language.DE, QuestionType.SINGLE_CHOICE, Difficulty.HARD);

        assertThat(store.poolCandidates(CELL, "gen-local", "judge-local", null)).hasSize(1);
        assertThat(store.poolCandidates(otherDifficulty, "gen-local", "judge-local", null)).isEmpty();
        assertThat(store.poolCandidates(CELL, "gen-cloud", "judge-local", null)).isEmpty();
    }

    @Test
    void poolCandidates_asOfExcludesItemsUpdatedLater() {
        store.enqueuePool(poolItems(1));
        generateAll(1);
        long id = idOf(0);
        store.recordVerdict(id, "judge-local", "GENERAL", true, "{}", null);

        assertThat(store.poolCandidates(CELL, "gen-local", "judge-local", "1970-01-01T00:00:00Z")).isEmpty();
        assertThat(store.poolCandidates(CELL, "gen-local", "judge-local", "9999-01-01T00:00:00Z")).hasSize(1);
    }

    @Test
    void recordVerdict_replacesAnEarlierDecisionOfTheSameJudgeAndScope() {
        store.enqueuePool(poolItems(1));
        generateAll(1);
        long id = idOf(0);
        store.recordVerdict(id, "judge-local", "GENERAL", false, "{}", null);
        store.recordVerdict(id, "judge-local", "GENERAL", true, "{}", null);

        assertThat(store.verdict(id, "judge-local", "GENERAL")).hasValueSatisfying(verdict -> assertThat(verdict.accepted()).isTrue());
        assertThat(store.poolCandidates(CELL, "gen-local", "judge-local", null)).hasSize(1);
    }

    @Test
    void documentHashes_roundTripAndReplace() {
        store.recordDocumentHash("EIDI", "01_Introduction.pdf", "aaa");
        store.recordDocumentHash("EIDI", "02_ControlFlow.pdf", "bbb");
        store.recordDocumentHash("EIDI", "01_Introduction.pdf", "ccc");

        assertThat(store.documentHashes("EIDI")).containsExactly(java.util.Map.entry("01_Introduction.pdf", "ccc"), java.util.Map.entry("02_ControlFlow.pdf", "bbb"));
        assertThat(store.documentHashes("EIST")).isEmpty();
    }

    private static List<PoolItem> poolItems(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new PoolItem(new ItemKey("run1", "two-phase|local|local", CELL.key(), index), CELL, index % 2, "gen-local")).toList();
    }

    private void generateAll(int count) {
        int generated = 0;
        while (generated < count) {
            var claim = store.claimNext("run1").orElseThrow();
            if (claim.state() == ItemState.FILTERING) {
                store.recordFiltered(claim.key(), "{}", "[]");
            }
            else {
                store.recordGenerated(claim.key(), "{\"title\":\"Q\"}", "{}", "[]");
                generated++;
            }
        }
    }

    private long idOf(int index) {
        return store.browse("run1", CELL.key(), null, 100).stream().filter(summary -> summary.itemIndex() == index).findFirst().orElseThrow().id();
    }

    @Test
    void browsePool_listsPoolItemsIncludingFailedGenerations() {
        store.registerRun("pool-local", "pool|local", "manifest");
        store.enqueuePool(List.of(new PoolItem(new ItemKey("pool-local", "pool|local", CELL.key(), 0), CELL, 0, "gen-local"),
                new PoolItem(new ItemKey("pool-local", "pool|local", CELL.key(), 1), CELL, 1, "gen-local")));
        var generation = store.claimNext("pool-local").orElseThrow();
        store.recordGenerated(generation.key(), "{\"title\":\"Duality\"}", "{}", "[]");
        var judging = store.claimNext("pool-local").orElseThrow();
        store.recordFiltered(judging.key(), "{\"accepted\":true}", "[]");
        store.recordVerdict(store.rowIdOf(judging.key()).orElseThrow(), "judge-local", "GENERAL", true, "{}", null);
        var failing = store.claimNext("pool-local").orElseThrow();
        store.recordFailure(failing.key(), ItemState.GENERATING, "VALIDATION_VIOLATION", "[]", false);

        List<RunStore.PoolItemSummary> items = store.browsePool(10);

        assertThat(items).hasSize(2);
        assertThat(items).extracting(RunStore.PoolItemSummary::state).containsExactlyInAnyOrder(ItemState.FILTERED, ItemState.FAILED_GENERATION);
        RunStore.PoolItemSummary judged = items.stream().filter(item -> item.state() == ItemState.FILTERED).findFirst().orElseThrow();
        assertThat(judged.title()).isEqualTo("Duality");
        assertThat(judged.competencyKey()).isEqualTo("arrays");
        assertThat(judged.generatorModel()).isEqualTo("gen-local");
        assertThat(judged.judgeDecisions()).containsExactly(Map.entry("judge-local", true));
    }

    @Test
    void switchTo_movesTheStoreBetweenDatabases() {
        store.saveQuiz(new RunStore.StoredQuiz("q1", "s1", "agentic|m|m", "EIDI", "r1", 1, true, "[]", "[]", "[]"));

        store.switchTo(directory.resolve("other.db"));

        assertThat(store.databasePath().getFileName().toString()).isEqualTo("other.db");
        assertThat(store.quizzes("s1")).isEmpty();

        store.switchTo(directory.resolve("run.db"));

        assertThat(store.quizzes("s1")).hasSize(1);
    }

    @Test
    void quizzesOfApproach_returnsOnlyThatApproach() {
        store.saveQuiz(new RunStore.StoredQuiz("q1", "s1", "agentic|cloud|cloud", "EIDI", "r1", 1, true, "[]", "[]", "[]"));
        store.saveQuiz(new RunStore.StoredQuiz("q2", "s1", "two-phase|local|cloud", "EIDI", "r1", 1, true, "[]", "[]", "[]"));

        assertThat(store.quizzesOfApproach("agentic")).extracting(RunStore.StoredQuiz::quizId).containsExactly("q1");
    }
}
