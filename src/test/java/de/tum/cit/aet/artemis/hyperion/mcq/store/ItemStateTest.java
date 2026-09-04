package de.tum.cit.aet.artemis.hyperion.mcq.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ItemStateTest {

    @Test
    void progress_groupsStatesIntoOperatorCategories() {
        Map<String, Integer> counts = Map.of("PENDING", 10, "GENERATING", 1, "GENERATED", 3, "FILTERING", 1, "FILTERED", 14, "FAILED_GENERATION", 2, "FAILED_FILTER", 1);

        assertThat(ItemState.progress(counts)).isEqualTo("14/32 done, 4 awaiting judge, 11 to generate, 3 failed");
    }

    @Test
    void progress_reportsZeroesWhenNothingIsEnqueued() {
        assertThat(ItemState.progress(Map.of())).isEqualTo("0/0 done, 0 awaiting judge, 0 to generate, 0 failed");
    }
}
