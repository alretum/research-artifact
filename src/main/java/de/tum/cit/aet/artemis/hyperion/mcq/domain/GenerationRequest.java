package de.tum.cit.aet.artemis.hyperion.mcq.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

/**
 * One quiz-generation request, the unit of work both approaches answer.
 * <p>
 * The component names must stay compatible with Artemis's {@code QuizQuestionGenerationRequestDTO}. Exactly
 * one of {@code topic} and {@code competencyKeys} is given: a request is either free-topic or
 * competency-grounded, never both.
 *
 * @param key               stable identifier, unique within a request file; part of job keys and quiz ids
 * @param courseKey         course whose material grounds the request
 * @param topic             free topic, or {@code null} in competency mode
 * @param competencyKeys    competencies to assess, empty in free-topic mode
 * @param optionalPrompt    extra instructions, at most 2000 characters, or {@code null}
 * @param questionTypes     question types the quiz may contain
 * @param numberOfQuestions questions to produce, from 1 to 10
 */
public record GenerationRequest(String key, String courseKey, String topic, List<String> competencyKeys, String optionalPrompt, Language language, Set<QuestionType> questionTypes,
        int numberOfQuestions, Difficulty difficulty) {

    private static final int MAX_QUESTIONS = 10;

    private static final int MAX_OPTIONAL_PROMPT_LENGTH = 2000;

    public GenerationRequest {
        requireText(key, "key");
        requireText(courseKey, "courseKey");
        competencyKeys = competencyKeys == null ? List.of() : List.copyOf(competencyKeys);
        boolean hasTopic = topic != null && !topic.isBlank();
        if (hasTopic == !competencyKeys.isEmpty()) {
            throw new IllegalArgumentException("Request '" + key + "' must give either a topic or competencies, got topic=" + topic + " and competencies=" + competencyKeys);
        }
        if (optionalPrompt != null && optionalPrompt.length() > MAX_OPTIONAL_PROMPT_LENGTH) {
            throw new IllegalArgumentException("Request '" + key + "' has an optional prompt of " + optionalPrompt.length() + " characters, the maximum is "
                    + MAX_OPTIONAL_PROMPT_LENGTH);
        }
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(difficulty, "difficulty");
        questionTypes = Set.copyOf(Objects.requireNonNull(questionTypes, "questionTypes"));
        if (questionTypes.isEmpty()) {
            throw new IllegalArgumentException("Request '" + key + "' names no question types");
        }
        if (numberOfQuestions < 1 || numberOfQuestions > MAX_QUESTIONS) {
            throw new IllegalArgumentException("Request '" + key + "' asks for " + numberOfQuestions + " questions, allowed is 1 to " + MAX_QUESTIONS);
        }
    }

    /**
     * @return whether this request is grounded in competencies rather than a free topic
     */
    public boolean competencyMode() {
        return !competencyKeys.isEmpty();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Request field '" + name + "' must not be blank, got " + value);
        }
    }
}
