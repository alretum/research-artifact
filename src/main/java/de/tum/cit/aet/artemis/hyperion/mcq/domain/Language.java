package de.tum.cit.aet.artemis.hyperion.mcq.domain;

/**
 * Language a question is generated in.
 */
public enum Language {

    EN("en"), DE("de");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    /**
     * @return the ISO 639-1 code used in prompts, files and exports
     */
    public String code() {
        return code;
    }

    /**
     * Resolves a language from its case-insensitive ISO 639-1 code.
     *
     * @param code serialized language code, for example {@code de}
     * @return the matching language
     * @throws IllegalArgumentException if the code matches no language
     */
    public static Language fromCode(String code) {
        for (Language language : values()) {
            if (language.code.equalsIgnoreCase(code)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unknown language '" + code + "', expected one of en, de");
    }
}
