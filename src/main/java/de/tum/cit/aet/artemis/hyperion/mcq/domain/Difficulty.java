package de.tum.cit.aet.artemis.hyperion.mcq.domain;

import java.util.Locale;

/**
 * Requested difficulty of a generated question.
 */
public enum Difficulty {

    EASY(20), MEDIUM(50), HARD(80);

    private final int promptValue;

    Difficulty(int promptValue) {
        this.promptValue = promptValue;
    }

    /**
     * The value rendered into prompts, on the scale 0 (very easy) to 100 (very hard).
     * <p>
     * The mapping is one-way: prompts and provenance carry this integer, while requests, retrieval and
     * exports use the enum.
     *
     * @return the prompt-scale difficulty
     */
    public int promptValue() {
        return promptValue;
    }

    /**
     * Resolves a difficulty from its case-insensitive name.
     *
     * @param value serialized difficulty value, for example {@code medium}
     * @return the matching difficulty
     * @throws IllegalArgumentException if the value matches no difficulty
     */
    public static Difficulty fromValue(String value) {
        for (Difficulty difficulty : values()) {
            if (difficulty.name().equalsIgnoreCase(value)) {
                return difficulty;
            }
        }
        throw new IllegalArgumentException("Unknown difficulty '" + value + "', expected one of easy, medium, hard");
    }

    /**
     * @return the lower-case name used in files and exports
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
