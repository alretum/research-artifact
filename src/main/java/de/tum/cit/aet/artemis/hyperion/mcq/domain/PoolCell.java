package de.tum.cit.aet.artemis.hyperion.mcq.domain;

import java.util.Objects;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

/**
 * One cell of the question pool: the input combination a pooled question was generated for.
 * <p>
 * The cell carries every request dimension except the free-text instructions, which cannot be
 * pre-generated. Retrieval at request time matches on these labels exactly.
 *
 * @param courseKey     course whose material grounds the cell
 * @param competencyKey competency the cell's questions assess
 */
public record PoolCell(String courseKey, String competencyKey, Language language, QuestionType questionType, Difficulty difficulty) {

    public PoolCell {
        requireText(courseKey, "courseKey");
        requireText(competencyKey, "competencyKey");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(questionType, "questionType");
        Objects.requireNonNull(difficulty, "difficulty");
    }

    /**
     * Stable identifier of this cell, used as the stored item's topic key.
     *
     * @return the cell key, of the form {@code course|competency|language|type|difficulty}
     */
    public String key() {
        return courseKey + "|" + competencyKey + "|" + language.code() + "|" + questionType.value() + "|" + difficulty.value();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cell field '" + name + "' must not be blank, got " + value);
        }
    }
}
