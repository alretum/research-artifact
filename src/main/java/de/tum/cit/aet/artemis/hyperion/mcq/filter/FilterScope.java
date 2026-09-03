package de.tum.cit.aet.artemis.hyperion.mcq.filter;

import java.util.EnumSet;
import java.util.Set;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;

/**
 * Which failure modes one filter call judges, and against which reference material.
 * <p>
 * {@link #GENERAL} judges properties of the item and its grounding alone, so it can run once at pool entry.
 * {@link #REQUEST_FIT} judges the item against a request, so it can only run once a request exists.
 * {@link #COMBINED} judges both in a single call. Language and question type are exact properties of an
 * item and are never model-judged under any scope.
 */
public enum FilterScope {

    GENERAL(EnumSet.of(FailureMode.FACTUAL_ERROR, FailureMode.AMBIGUOUS_CORRECT_ANSWER, FailureMode.OFF_TOPIC, FailureMode.NEAR_DUPLICATE, FailureMode.ILL_FORMED_DISTRACTORS),
            "/prompts/mcq/mcq_filter_system.st", "/prompts/mcq/mcq_filter_user.st", true, false),

    REQUEST_FIT(EnumSet.of(FailureMode.COMPETENCY_MISMATCH, FailureMode.DIFFICULTY_MISMATCH, FailureMode.INSTRUCTION_VIOLATION), "/prompts/mcq/mcq_filter_request_system.st",
            "/prompts/mcq/mcq_filter_request_user.st", false, true),

    COMBINED(EnumSet.of(FailureMode.FACTUAL_ERROR, FailureMode.AMBIGUOUS_CORRECT_ANSWER, FailureMode.OFF_TOPIC, FailureMode.NEAR_DUPLICATE, FailureMode.ILL_FORMED_DISTRACTORS,
            FailureMode.COMPETENCY_MISMATCH, FailureMode.DIFFICULTY_MISMATCH, FailureMode.INSTRUCTION_VIOLATION), "/prompts/mcq/mcq_filter_combined_system.st",
            "/prompts/mcq/mcq_filter_combined_user.st", true, true);

    private final Set<FailureMode> modes;

    private final String systemTemplate;

    private final String userTemplate;

    private final boolean requiresGrounding;

    private final boolean requiresRequest;

    FilterScope(Set<FailureMode> modes, String systemTemplate, String userTemplate, boolean requiresGrounding, boolean requiresRequest) {
        this.modes = Set.copyOf(modes);
        this.systemTemplate = systemTemplate;
        this.userTemplate = userTemplate;
        this.requiresGrounding = requiresGrounding;
        this.requiresRequest = requiresRequest;
    }

    /**
     * @return the failure modes a verdict under this scope must cover, all of them
     */
    public Set<FailureMode> modes() {
        return modes;
    }

    /**
     * @return classpath location of the system prompt template
     */
    public String systemTemplate() {
        return systemTemplate;
    }

    /**
     * @return classpath location of the user prompt template
     */
    public String userTemplate() {
        return userTemplate;
    }

    /**
     * @return whether this scope's prompt includes the grounding block
     */
    public boolean requiresGrounding() {
        return requiresGrounding;
    }

    /**
     * @return whether this scope's prompt includes the request reference values
     */
    public boolean requiresRequest() {
        return requiresRequest;
    }
}
