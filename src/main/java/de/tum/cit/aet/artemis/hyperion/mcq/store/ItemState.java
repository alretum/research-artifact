package de.tum.cit.aet.artemis.hyperion.mcq.store;

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
    FAILED_FILTER
}
