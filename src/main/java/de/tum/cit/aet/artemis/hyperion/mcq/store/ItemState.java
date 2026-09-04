package de.tum.cit.aet.artemis.hyperion.mcq.store;

import java.util.Map;

/**
 * Lifecycle of one intended item.
 * <p>
 * {@link #GENERATING} and {@link #FILTERING} are claimed states held only while a worker is acting on the
 * item; finding one at startup means a previous process died, and it is returned to the preceding stable
 * state.
 */
public enum ItemState {

    /** Awaiting generation. */
    PENDING,

    /** Claimed for generation. */
    GENERATING,

    /** Generated and stored, awaiting filtering. */
    GENERATED,

    /** Claimed for filtering. */
    FILTERING,

    /** Generated and judged; terminal. */
    FILTERED,

    /** Generation failed permanently; terminal. */
    FAILED_GENERATION,

    /** Filtering failed permanently; terminal. */
    FAILED_FILTER,
    ;

    /**
     * Renders state counts as one progress line.
     * <p>
     * States collapse into four categories: done ({@link #FILTERED}), awaiting the judge
     * ({@link #GENERATED}, {@link #FILTERING}), still to generate ({@link #PENDING}, {@link #GENERATING}),
     * and permanently failed.
     *
     * @param counts item counts keyed by state name, as {@code RunStore.stateCounts} returns them
     * @return a line such as {@code "14/32 done, 2 awaiting judge, 15 to generate, 1 failed"}
     */
    public static String progress(Map<String, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int done = counts.getOrDefault(FILTERED.name(), 0);
        int awaitingJudge = counts.getOrDefault(GENERATED.name(), 0) + counts.getOrDefault(FILTERING.name(), 0);
        int toGenerate = counts.getOrDefault(PENDING.name(), 0) + counts.getOrDefault(GENERATING.name(), 0);
        int failed = counts.getOrDefault(FAILED_GENERATION.name(), 0) + counts.getOrDefault(FAILED_FILTER.name(), 0);
        return done + "/" + total + " done, " + awaitingJudge + " awaiting judge, " + toGenerate + " to generate, " + failed + " failed";
    }
}
