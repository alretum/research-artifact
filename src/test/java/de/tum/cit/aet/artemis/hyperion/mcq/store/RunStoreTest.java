package de.tum.cit.aet.artemis.hyperion.mcq.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.ItemKey;

class RunStoreTest {

    private static final String RUN = "run-1";

    private static final String CONFIG = "gen|filt";

    @TempDir
    Path directory;

    private RunStore store;

    @BeforeEach
    void setUp() {
        store = new RunStore(directory.resolve("run.db"));
        store.registerRun(RUN, CONFIG, "manifest-v1");
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void enqueuesOnlyMissingRows() {
        assertThat(store.enqueue(keys(3))).isEqualTo(3);
        assertThat(store.enqueue(keys(3))).isZero();
        assertThat(store.enqueue(keys(5))).isEqualTo(2);
        assertThat(store.stateCounts(RUN)).containsEntry(ItemState.PENDING.name(), 5);
    }

    @Test
    void claimsPendingWorkForGeneration() {
        store.enqueue(keys(2));

        var claim = store.claimNext(RUN).orElseThrow();

        assertThat(claim.state()).isEqualTo(ItemState.GENERATING);
        assertThat(claim.key().runId()).isEqualTo(RUN);
        assertThat(store.stateCounts(RUN)).containsEntry(ItemState.GENERATING.name(), 1).containsEntry(ItemState.PENDING.name(), 1);
    }

    @Test
    void neverClaimsTheSameItemTwice() {
        store.enqueue(keys(3));

        var first = store.claimNext(RUN).orElseThrow();
        var second = store.claimNext(RUN).orElseThrow();
        var third = store.claimNext(RUN).orElseThrow();

        assertThat(List.of(first.key(), second.key(), third.key())).doesNotHaveDuplicates();
        assertThat(store.claimNext(RUN)).isEmpty();
    }

    @Test
    void prefersFilteringOverGeneratingSoItemsAreCompletedFirst() {
        store.enqueue(keys(2));
        var generating = store.claimNext(RUN).orElseThrow();
        store.recordGenerated(generating.key(), "{}", "{}", "[]");

        var next = store.claimNext(RUN).orElseThrow();

        assertThat(next.state()).isEqualTo(ItemState.FILTERING);
        assertThat(next.key()).isEqualTo(generating.key());
    }

    @Test
    void carriesTheStoredItemIntoTheFilterClaim() {
        store.enqueue(keys(1));
        var generating = store.claimNext(RUN).orElseThrow();
        store.recordGenerated(generating.key(), "{\"title\":\"T\"}", "{\"topic\":\"x\"}", "[1]");

        var filtering = store.claimNext(RUN).orElseThrow();

        assertThat(filtering.generatedItemJson()).contains("\"title\":\"T\"");
        assertThat(filtering.provenanceJson()).contains("\"topic\":\"x\"");
        assertThat(filtering.callsJson()).isEqualTo("[1]");
    }

    @Test
    void returnsClaimsAbandonedByADeadProcessToTheirPreviousState() {
        store.enqueue(keys(2));
        var generating = store.claimNext(RUN).orElseThrow();
        store.recordGenerated(generating.key(), "{}", "{}", "[]");
        store.claimNext(RUN);
        store.claimNext(RUN);
        assertThat(store.stateCounts(RUN)).containsEntry(ItemState.FILTERING.name(), 1).containsEntry(ItemState.GENERATING.name(), 1);

        assertThat(store.releaseStaleClaims(RUN)).isEqualTo(2);

        assertThat(store.stateCounts(RUN)).containsEntry(ItemState.GENERATED.name(), 1).containsEntry(ItemState.PENDING.name(), 1);
    }

    @Test
    void completesAnItemThroughBothStages() {
        store.enqueue(keys(1));
        var generating = store.claimNext(RUN).orElseThrow();
        store.recordGenerated(generating.key(), "{\"title\":\"T\"}", "{}", "[]");
        var filtering = store.claimNext(RUN).orElseThrow();
        store.recordFiltered(filtering.key(), "{\"accepted\":true}", "[]");

        assertThat(store.isComplete(RUN)).isTrue();
        assertThat(store.completedItems(RUN)).singleElement().satisfies(item -> assertThat(item.decisionJson()).contains("accepted"));
    }

    @Test
    void returnsAFailedItemForAnotherAttemptAndCountsIt() {
        store.enqueue(keys(1));
        var claim = store.claimNext(RUN).orElseThrow();

        store.recordFailure(claim.key(), ItemState.GENERATING, "MALFORMED_JSON", "[]", true);

        assertThat(store.stateCounts(RUN)).containsEntry(ItemState.PENDING.name(), 1);
        assertThat(store.claimNext(RUN).orElseThrow().generationAttempts()).isEqualTo(1);
    }

    @Test
    void failsAnItemPermanentlyWhenNotRetrying() {
        store.enqueue(keys(1));
        var claim = store.claimNext(RUN).orElseThrow();

        store.recordFailure(claim.key(), ItemState.GENERATING, "MALFORMED_JSON", "[]", false);

        assertThat(store.stateCounts(RUN)).containsEntry(ItemState.FAILED_GENERATION.name(), 1);
        assertThat(store.claimNext(RUN)).isEmpty();
        assertThat(store.isComplete(RUN)).isTrue();
    }

    @Test
    void returnsAFailedFilterToTheGeneratedState() {
        store.enqueue(keys(1));
        var generating = store.claimNext(RUN).orElseThrow();
        store.recordGenerated(generating.key(), "{}", "{}", "[]");
        var filtering = store.claimNext(RUN).orElseThrow();

        store.recordFailure(filtering.key(), ItemState.FILTERING, "FILTER_UNPARSEABLE", "[]", true);

        assertThat(store.stateCounts(RUN)).containsEntry(ItemState.GENERATED.name(), 1);
    }

    @Test
    void refusesToResumeARunWhoseConfigurationChanged() {
        assertThatThrownBy(() -> store.registerRun(RUN, CONFIG, "manifest-v2")).isInstanceOf(IllegalStateException.class).hasMessageContaining("different configuration");
    }

    @Test
    void resumesARunWithAnUnchangedConfiguration() {
        store.registerRun(RUN, CONFIG, "manifest-v1");

        assertThat(store.runIds()).containsExactly(RUN);
    }

    @Test
    void survivesReopeningTheDatabase() {
        store.enqueue(keys(2));
        var claim = store.claimNext(RUN).orElseThrow();
        store.recordGenerated(claim.key(), "{}", "{}", "[]");
        store.close();

        store = new RunStore(directory.resolve("run.db"));

        assertThat(store.stateCounts(RUN)).containsEntry(ItemState.GENERATED.name(), 1).containsEntry(ItemState.PENDING.name(), 1);
        assertThat(store.runIds()).containsExactly(RUN);
    }

    private static List<ItemKey> keys(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(index -> new ItemKey(RUN, CONFIG, "topic-" + index, 0)).toList();
    }
}
