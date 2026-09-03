package de.tum.cit.aet.artemis.hyperion.mcq.benchmark;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A quiz in the input format of the ls1intum quiz-generation benchmark.
 * <p>
 * Field names are snake_case because that is what the benchmark's loader reads; they are fixed by
 * {@code src/models/quiz.py} in that project, not chosen here. Anything of ours that the schema has no
 * field for goes into {@code metadata}, which the benchmark carries through untouched, so its per-question
 * results can be cross-tabulated against our own variables afterwards.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BenchmarkQuiz(@JsonProperty("quiz_id") String quizId, @JsonProperty("title") String title, @JsonProperty("source_material") String sourceMaterial,
        @JsonProperty("instructions") String instructions, @JsonProperty("metadata") Map<String, Object> metadata, @JsonProperty("questions") List<BenchmarkQuestion> questions) {

    /**
     * One question.
     * <p>
     * {@code correctAnswer} is the answer option's <em>text</em>, not its index, and must match one of
     * {@code options} exactly; the benchmark rejects a quiz otherwise. It is a {@code String} for
     * single-choice and true/false questions and a {@code List<String>} for multiple-choice, which is why
     * the field is typed as {@code Object}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BenchmarkQuestion(@JsonProperty("question_id") String questionId, @JsonProperty("question_type") String questionType,
            @JsonProperty("question_text") String questionText, @JsonProperty("options") List<String> options, @JsonProperty("correct_answer") Object correctAnswer,
            @JsonProperty("source_reference") String sourceReference, @JsonProperty("metadata") Map<String, Object> metadata) {
    }

    /** Question types the benchmark accepts. */
    public static final String SINGLE_CHOICE = "single_choice";

    /** Question types the benchmark accepts. */
    public static final String MULTIPLE_CHOICE = "multiple_choice";
}
