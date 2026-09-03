package de.tum.cit.aet.artemis.hyperion.mcq.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable domain types for the MCQ pipeline.
 * <p>
 * The component names of {@link McqItem} and {@link AnswerOption} must stay identical to Artemis's
 * {@code GeneratedQuizQuestionDTO} and {@code GeneratedQuizAnswerOptionDTO}. Research metadata belongs
 * in {@link ItemProvenance} and must not be added to {@link McqItem}.
 */
public final class Mcq {

    private Mcq() {
    }

    /**
     * One extracted PDF page.
     *
     * @param documentId  corpus-relative path of the source document
     * @param lectureName topic folder the document belongs to
     * @param unitName    document filename without extension
     * @param pageNumber  one-based page number
     * @param text        extracted text, unmodified
     */
    public record Page(String documentId, String lectureName, String unitName, int pageNumber, String text) {
    }

    /**
     * A contiguous page range of one document, treated as a single retrievable unit.
     *
     * @param chunkId   stable identifier of the form {@code documentId#pFirst-pLast}
     * @param firstPage first page in the range, inclusive
     * @param lastPage  last page in the range, inclusive
     */
    public record Chunk(String chunkId, String documentId, String lectureName, String unitName, int firstPage, int lastPage, SourceRole role, String text) {

        /**
         * @return {@code "Page 4"} or {@code "Pages 4–6"} depending on the range covered
         */
        public String pageRange() {
            return firstPage == lastPage ? "Page " + firstPage : "Pages " + firstPage + "–" + lastPage;
        }

        /**
         * @return provenance line naming the lecture, unit and page range
         */
        public String header() {
            return "Lecture: " + lectureName + ", Unit: " + unitName + ", " + pageRange();
        }

        /**
         * @return {@link #header()} followed by the chunk text, the form that is embedded and retrieved
         */
        public String embeddable() {
            return header() + "\n" + text;
        }
    }

    /** Kind of document a chunk came from, inferred from its filename. */
    public enum SourceRole {
        LECTURE_DECK, CENTRAL_EXERCISE, TUTORIAL, SOLUTION, NOTEBOOK, OTHER
    }

    /**
     * A retrieved piece of lecture material.
     * <p>
     * {@code source}, {@code unit} and {@code text} correspond to {@code IrisLectureSnippetDTO}.
     * {@code pageRange} and {@code role} are supplied only by retrieval sources that know them, and are
     * {@code null} otherwise.
     *
     * @param score relevance in [0, 1]
     */
    public record Snippet(String source, String unit, String pageRange, String text, String chunkId, SourceRole role, double score) {
    }

    /**
     * How the grounding for one item was composed by source role.
     *
     * @param countsByRole     number of included snippets per role
     * @param solutionFraction share of included snippets whose role is {@link SourceRole#SOLUTION}
     * @param unknownRoles     snippets whose role the retrieval source did not supply
     */
    public record GroundingComposition(Map<SourceRole, Integer> countsByRole, double solutionFraction, int unknownRoles) {

        /**
         * Summarise the roles of the given snippets.
         *
         * @param snippets snippets included in the grounding block
         * @return the composition, with a zero solution fraction when {@code snippets} is empty
         */
        public static GroundingComposition of(List<Snippet> snippets) {
            Map<SourceRole, Integer> counts = new java.util.EnumMap<>(SourceRole.class);
            int unknown = 0;
            for (Snippet snippet : snippets) {
                if (snippet.role() == null) {
                    unknown++;
                }
                else {
                    counts.merge(snippet.role(), 1, Integer::sum);
                }
            }
            int solutions = counts.getOrDefault(SourceRole.SOLUTION, 0);
            double fraction = snippets.isEmpty() ? 0 : (double) solutions / snippets.size();
            return new GroundingComposition(Map.copyOf(counts), fraction, unknown);
        }

        /**
         * @return a compact rendering such as {@code "3 lecture deck, 2 solution (25% solution)"}
         */
        public String describe() {
            String roles = countsByRole.entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                    .collect(java.util.stream.Collectors.joining(", "));
            if (unknownRoles > 0) {
                roles = roles.isEmpty() ? unknownRoles + " unknown" : roles + ", " + unknownRoles + " unknown";
            }
            return roles + String.format(" (%.0f%% solution)", solutionFraction * 100);
        }
    }

    /**
     * Grounding material assembled for one generation request.
     *
     * @param renderedBlock the exact text inserted into the prompt
     * @param approxTokens  estimated size of {@code renderedBlock}
     */
    public record GroundingContext(String topic, List<Snippet> snippets, String renderedBlock, int approxTokens, GroundingComposition composition) {
    }

    /** Question types supported by the pipeline. */
    public enum QuestionType {

        SINGLE_CHOICE("single-choice"), MULTIPLE_CHOICE("multiple-choice"), TRUE_FALSE("true-false");

        private final String value;

        QuestionType(String value) {
            this.value = value;
        }

        /**
         * @return the wire value used in prompts and persisted records
         */
        public String value() {
            return value;
        }

        /**
         * Resolves a question type from its case-insensitive wire value.
         *
         * @param value serialized question type value, for example {@code single-choice}
         * @return the matching question type
         * @throws IllegalArgumentException if the value matches no question type
         */
        public static QuestionType fromValue(String value) {
            for (QuestionType type : values()) {
                if (type.value.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown question type '" + value + "', expected one of single-choice, multiple-choice, true-false");
        }
    }

    /**
     * One answer option.
     *
     * @param hint        optional, may be {@code null}
     * @param explanation optional, may be {@code null}
     */
    public record AnswerOption(String text, boolean correct, String hint, String explanation) {
    }

    /**
     * One generated multiple-choice question.
     *
     * @param hint        optional, may be {@code null}
     * @param explanation optional, may be {@code null}
     */
    public record McqItem(QuestionType type, String title, String questionText, List<AnswerOption> options, String hint, String explanation) {
    }

    /** Character lengths of an item's text fields. */
    public record LengthStats(int title, int questionText, int explanation, int longestOption) {

        /**
         * Measure an item's fields.
         *
         * @param item item to measure
         * @return lengths, counting {@code null} fields as zero
         */
        public static LengthStats of(McqItem item) {
            int longestOption = item.options().stream().mapToInt(option -> length(option.text())).max().orElse(0);
            return new LengthStats(length(item.title()), length(item.questionText()), length(item.explanation()), longestOption);
        }

        private static int length(String value) {
            return value == null ? 0 : value.length();
        }
    }

    /**
     * How an item was produced.
     *
     * @param groundingChunkIds       ids of the chunks that were in the prompt
     * @param promptText              the full rendered user prompt
     * @param damageSuspectedInItem   whether the item's own text contains suspected extraction damage
     * @param groundingComposition    source-role make-up of the grounding the item was generated from
     */
    public record ItemProvenance(String runId, String configurationId, String generatorModel, String filterModel, String topic, List<String> groundingChunkIds, String promptText,
            int requestedDifficulty, LengthStats lengths, boolean damageSuspectedInItem, GroundingComposition groundingComposition, Instant generatedAt) {
    }

    /**
     * One LLM call, recorded for successful and failed calls alike.
     * <p>
     * {@code outcome} describes the call itself, so a response that arrived but could not be parsed is
     * {@code "success"} here. {@code failureCategory} describes what the pipeline made of the response and
     * is the field that distinguishes malformed output from a schema or validation failure. Because these
     * records accumulate across attempts and are never cleared on eventual success, they preserve the full
     * attempt history of an item.
     *
     * @param stage           {@code "generation"} or {@code "filter"}
     * @param promptTokens    reported by the provider, {@code null} when unavailable
     * @param outcome         {@code "success"}, {@code "error"} or {@code "timeout"} for the call
     * @param errorMessage    {@code null} unless the call itself failed
     * @param failureCategory why the attempt did not yield a usable result, {@code null} when it did
     */
    public record CallRecord(String requestId, String stage, String model, Integer promptTokens, Integer completionTokens, long wallClockMs, int retryCount, String outcome,
            String errorMessage, String failureCategory) {

        /**
         * @param category failure category to attach
         * @return a copy carrying the given category
         */
        public CallRecord withFailureCategory(String category) {
            return new CallRecord(requestId, stage, model, promptTokens, completionTokens, wallClockMs, retryCount, outcome, errorMessage, category);
        }
    }

    /**
     * Defects the filter judges.
     * <p>
     * The first five are properties of an item and its grounding alone. The last three compare an item to
     * the request it is meant to serve, so they can only be judged once a request exists.
     */
    public enum FailureMode {
        FACTUAL_ERROR, AMBIGUOUS_CORRECT_ANSWER, OFF_TOPIC, NEAR_DUPLICATE, ILL_FORMED_DISTRACTORS, COMPETENCY_MISMATCH, DIFFICULTY_MISMATCH, INSTRUCTION_VIOLATION
    }

    /**
     * The filter's judgement on one failure mode.
     *
     * @param severity  0.0 when the defect is absent, 1.0 when it is severe
     * @param triggered whether the defect is material enough to reject the item
     */
    public record ModeVerdict(double severity, boolean triggered, String justification) {
    }

    /**
     * Accept/reject outcome together with the scores it was derived from.
     * <p>
     * {@code accepted} is exactly {@code aggregateScore >= threshold}, so any decision can be recomputed
     * at a different threshold from stored data without issuing new calls. A decision is only produced
     * when every mode in the judged scope was scored.
     * <p>
     * Each mode's {@code triggered} flag is the model's own holistic verdict. It is recorded but takes no
     * part in the decision, so it stays usable as an independent check on the judge's self-consistency.
     *
     * @param aggregateScore {@code 1 - max(severity)} across all modes, so higher is better
     * @param meanSeverity   mean severity across all modes; a descriptive statistic only, never used to
     *                       decide acceptance
     */
    public record FilterDecision(boolean accepted, double aggregateScore, double meanSeverity, Map<FailureMode, ModeVerdict> modeVerdicts, String filterModel, String rationale) {
    }

    /** One row of the run log. Rejected items are recorded alongside accepted ones. */
    public record RunRecord(int schemaVersion, String runId, String configurationId, McqItem item, ItemProvenance provenance, FilterDecision filterDecision,
            List<CallRecord> calls) {

        public static final int SCHEMA_VERSION = 2;
    }
}
