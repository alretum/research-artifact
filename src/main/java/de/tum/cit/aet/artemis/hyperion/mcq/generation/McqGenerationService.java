package de.tum.cit.aet.artemis.hyperion.mcq.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.llm.ChatCall;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

/**
 * Generates multiple-choice questions from grounded lecture material, one per call or a whole quiz per
 * call.
 * <p>
 * The chat client is supplied per call rather than injected, so generator and filter models can differ
 * within a single run.
 */
@Service
public class McqGenerationService {

    private static final Logger log = LoggerFactory.getLogger(McqGenerationService.class);

    private static final String SYSTEM_PROMPT = "/prompts/mcq/mcq_generate_system.st";

    private static final String USER_PROMPT = "/prompts/mcq/mcq_generate_user.st";

    private static final String QUIZ_SYSTEM_PROMPT = "/prompts/mcq/mcq_generate_quiz_system.st";

    private static final String QUIZ_USER_PROMPT = "/prompts/mcq/mcq_generate_quiz_user.st";

    private static final String QUIZ_USER_COMPETENCY_PROMPT = "/prompts/mcq/mcq_generate_quiz_user_competency.st";

    private static final int TRUE_FALSE_OPTION_COUNT = 2;

    /** Minimum absolute length excess before the correct option counts as conspicuously long. */
    private static final int MAX_CORRECT_OPTION_LENGTH_MARGIN = 20;

    /** Minimum proportional length excess before the correct option counts as conspicuously long. */
    private static final double MIN_CORRECT_OPTION_LENGTH_RATIO = 1.15;

    private static final int REQUIRED_OPTION_COUNT = 4;

    /**
     * Options that refer to the option set instead of the subject matter: "all of the above", "none of
     * these options", "both a and b", "all answers are correct" and their variants. Matching is
     * case-insensitive and anchored on word boundaries.
     */
    private static final Pattern BANNED_CONSTRUCTION = Pattern.compile(
            "\\b(all|none|both|any|either|neither) of (the |these |those )?(above|below|these|those|options|answers|preceding)\\b"
                    + "|\\bboth [a-d] and [a-d]\\b|\\ba and b\\b|\\ball answers are correct\\b",
            Pattern.CASE_INSENSITIVE);

    private final PromptTemplateService templates;

    public McqGenerationService(PromptTemplateService templates) {
        this.templates = templates;
    }

    /**
     * Outcome of one generation attempt.
     *
     * @param item    the generated question, or {@code null} when generation failed
     * @param prompt  the rendered user prompt that was sent
     * @param call    telemetry for the underlying model call
     * @param failure {@code null} on success, otherwise the failure category
     */
    public record Result(McqItem item, String prompt, CallRecord call, Failure failure) {

        public boolean succeeded() {
            return item != null;
        }
    }

    /** Why a generation attempt produced no usable item. */
    /** Why a generation attempt yielded no item. */
    public enum Failure {

        /** 401 or 403 from the provider. Permanent. */
        AUTH,

        /** A 4xx that is not an auth, timeout or throttling response. Permanent. */
        BAD_REQUEST,

        /** 429 from the provider. */
        RATE_LIMIT,

        /** A request timeout. */
        TIMEOUT,

        /** An IO failure, a 5xx, or an unrecognised error. */
        TRANSPORT,

        EMPTY_RESPONSE, MALFORMED_JSON, SCHEMA_VIOLATION, VALIDATION_VIOLATION;

        /**
         * @return whether another attempt could plausibly succeed
         */
        public boolean retryable() {
            return ChatCall.retryable(name());
        }
    }

    /**
     * @param kind the transport-layer classification, or {@code null}
     * @return the matching failure, defaulting to {@link Failure#TRANSPORT}
     */
    private static Failure toFailure(ChatCall.Kind kind) {
        if (kind == null) {
            return Failure.TRANSPORT;
        }
        return switch (kind) {
            case AUTH -> Failure.AUTH;
            case BAD_REQUEST -> Failure.BAD_REQUEST;
            case RATE_LIMIT -> Failure.RATE_LIMIT;
            case TIMEOUT -> Failure.TIMEOUT;
            case TRANSPORT -> Failure.TRANSPORT;
        };
    }

    /**
     * Generate a single question.
     *
     * @param grounding   material the question must be based on
     * @param difficulty  target difficulty from 0 (easiest) to 100 (hardest)
     * @param language    ISO 639-1 language code for the generated text
     * @param model       provider model name, sent with the request
     * @param temperature sampling temperature
     * @param maxAttempts total attempts including the first
     * @param chatClient  client to issue the call with
     * @return the result, which always carries a {@link CallRecord} even on failure
     */
    public Result generate(GroundingContext grounding, int difficulty, String language, String model, double temperature, int maxAttempts, ChatClient chatClient) {
        BeanOutputConverter<GeneratedItem> converter = StructuredOutputs.converterFor(GeneratedItem.class);
        String system = templates.render(SYSTEM_PROMPT, Map.of());
        String user = templates.render(USER_PROMPT, Map.of("groundingBlock", grounding.renderedBlock(), "topic", grounding.topic(), "language", language, "difficulty", difficulty,
                "optionCount", REQUIRED_OPTION_COUNT, "format", converter.getFormat()));

        ChatCall.Outcome outcome = ChatCall.execute("generation", model, temperature, maxAttempts, chatClient, system, user);
        CallRecord call = outcome.record();
        if (!outcome.succeeded()) {
            return new Result(null, user, call, toFailure(outcome.kind()));
        }

        String text = outcome.text();
        if (text == null || text.isBlank()) {
            return new Result(null, user, call.withFailureCategory(Failure.EMPTY_RESPONSE.name()), Failure.EMPTY_RESPONSE);
        }

        GeneratedItem generated;
        try {
            generated = converter.convert(text);
        }
        catch (Exception e) {
            log.warn("Could not parse generated item: {}", e.getMessage());
            return new Result(null, user, call.withFailureCategory(Failure.MALFORMED_JSON.name()), Failure.MALFORMED_JSON);
        }
        if (generated == null || generated.options() == null) {
            return new Result(null, user, call.withFailureCategory(Failure.SCHEMA_VIOLATION.name()), Failure.SCHEMA_VIOLATION);
        }

        McqItem item = toItem(generated);
        return validate(item) ? new Result(item, user, call, null)
                : new Result(null, user, call.withFailureCategory(Failure.VALIDATION_VIOLATION.name()), Failure.VALIDATION_VIOLATION);
    }

    /**
     * Outcome of one whole-quiz generation attempt.
     *
     * @param items         the structurally valid questions, possibly fewer than requested
     * @param invalidCount  questions the model returned that failed validation or carried an unknown type
     * @param prompt        the rendered user prompt
     * @param call          telemetry for the call, always present
     * @param failure       {@code null} unless the call as a whole yielded nothing usable
     */
    public record QuizResult(List<McqItem> items, int invalidCount, String prompt, CallRecord call, Failure failure) {

        public QuizResult {
            items = List.copyOf(items);
        }
    }

    /**
     * Generate up to {@code count} questions in a single call.
     * <p>
     * Unlike {@link #generate}, a whole quiz is requested at once, so the model can spread questions across
     * the material and avoid repeating itself. Returned questions that fail validation are dropped and
     * counted rather than failing the batch, so the caller can top up the shortfall.
     *
     * @param request     the request being answered; supplies course, language, types, difficulty and
     *                    further instructions
     * @param competencies rendered titles and objectives of the requested competencies, or {@code null} in
     *                    free-topic mode
     * @param grounding   the material to generate from
     * @param count       questions to ask for, at least 1
     * @param model       provider model name, sent with the request
     * @param temperature sampling temperature
     * @param maxAttempts total attempts including the first
     * @param chatClient  client to issue the call with
     * @return the result, which always carries a {@link CallRecord}
     */
    public QuizResult generateQuiz(GenerationRequest request, String competencies, GroundingContext grounding, int count, String model, double temperature, int maxAttempts,
            ChatClient chatClient) {
        BeanOutputConverter<GeneratedQuiz> converter = StructuredOutputs.converterFor(GeneratedQuiz.class);
        Map<String, Object> variables = new HashMap<>();
        variables.put("groundingBlock", grounding.renderedBlock());
        variables.put("course", request.courseKey());
        variables.put("language", request.language().code());
        variables.put("questionTypes", String.join(", ", request.questionTypes().stream().map(QuestionType::value).toList()));
        variables.put("numberOfQuestions", count);
        variables.put("optionCount", REQUIRED_OPTION_COUNT);
        variables.put("difficulty", request.difficulty().promptValue());
        variables.put("instructions", request.optionalPrompt() == null || request.optionalPrompt().isBlank() ? "(none)" : request.optionalPrompt());
        variables.put("format", converter.getFormat());
        String userTemplate;
        if (request.competencyMode()) {
            variables.put("competencies", competencies);
            userTemplate = QUIZ_USER_COMPETENCY_PROMPT;
        }
        else {
            variables.put("topic", request.topic());
            userTemplate = QUIZ_USER_PROMPT;
        }
        String system = templates.render(QUIZ_SYSTEM_PROMPT, Map.of());
        String user = templates.render(userTemplate, variables);

        ChatCall.Outcome outcome = ChatCall.execute("generation", model, temperature, maxAttempts, chatClient, system, user);
        CallRecord call = outcome.record();
        if (!outcome.succeeded()) {
            return new QuizResult(List.of(), 0, user, call, toFailure(outcome.kind()));
        }
        String text = outcome.text();
        if (text == null || text.isBlank()) {
            return new QuizResult(List.of(), 0, user, call.withFailureCategory(Failure.EMPTY_RESPONSE.name()), Failure.EMPTY_RESPONSE);
        }
        GeneratedQuiz quiz;
        try {
            quiz = converter.convert(text);
        }
        catch (Exception e) {
            log.warn("Could not parse generated quiz: {}", e.getMessage());
            return new QuizResult(List.of(), 0, user, call.withFailureCategory(Failure.MALFORMED_JSON.name()), Failure.MALFORMED_JSON);
        }
        if (quiz == null || quiz.questions() == null || quiz.questions().isEmpty()) {
            return new QuizResult(List.of(), 0, user, call.withFailureCategory(Failure.SCHEMA_VIOLATION.name()), Failure.SCHEMA_VIOLATION);
        }

        List<McqItem> items = new ArrayList<>();
        Set<QuestionType> allowedTypes = new LinkedHashSet<>(request.questionTypes());
        int invalid = 0;
        for (GeneratedQuizItem generated : quiz.questions()) {
            McqItem item = toQuizItem(generated);
            if (item == null || !allowedTypes.contains(item.type()) || !validate(item)) {
                invalid++;
                continue;
            }
            items.add(item);
        }
        if (items.isEmpty()) {
            return new QuizResult(List.of(), invalid, user, call.withFailureCategory(Failure.VALIDATION_VIOLATION.name()), Failure.VALIDATION_VIOLATION);
        }
        return new QuizResult(items, invalid, user, call, null);
    }

    private static McqItem toQuizItem(GeneratedQuizItem generated) {
        if (generated == null || generated.options() == null) {
            return null;
        }
        QuestionType type;
        try {
            type = QuestionType.fromValue(generated.type());
        }
        catch (IllegalArgumentException _) {
            return null;
        }
        List<AnswerOption> options = generated.options().stream().map(option -> new AnswerOption(strip(option.text()), Boolean.TRUE.equals(option.correct()), null, null)).toList();
        return new McqItem(type, strip(generated.title()), strip(generated.questionText()), options, null, strip(generated.explanation()));
    }

    private static McqItem toItem(GeneratedItem generated) {
        List<AnswerOption> options = generated.options().stream().map(option -> new AnswerOption(strip(option.text()), Boolean.TRUE.equals(option.correct()), null, null)).toList();
        return new McqItem(QuestionType.SINGLE_CHOICE, strip(generated.title()), strip(generated.questionText()), options, null, strip(generated.explanation()));
    }

    private static boolean validate(McqItem item) {
        if (isBlank(item.title()) || isBlank(item.questionText())) {
            return false;
        }
        int requiredOptions = item.type() == QuestionType.TRUE_FALSE ? TRUE_FALSE_OPTION_COUNT : REQUIRED_OPTION_COUNT;
        if (item.options().size() != requiredOptions) {
            return false;
        }
        if (item.options().stream().anyMatch(option -> isBlank(option.text()))) {
            return false;
        }
        long distinct = item.options().stream().map(option -> option.text().toLowerCase(Locale.ROOT)).distinct().count();
        if (distinct != requiredOptions) {
            return false;
        }
        if (item.options().stream().anyMatch(option -> BANNED_CONSTRUCTION.matcher(option.text()).find())) {
            return false;
        }
        if (hasContainedOption(item.options())) {
            return false;
        }
        long correct = item.options().stream().filter(AnswerOption::correct).count();
        boolean correctCountValid = switch (item.type()) {
            case SINGLE_CHOICE, TRUE_FALSE -> correct == 1;
            case MULTIPLE_CHOICE -> correct >= 1 && correct <= 3;
        };
        return correctCountValid && !correctOptionIsConspicuouslyLong(item.options());
    }

    /**
     * Detect the correct option being so much longer than every distractor that its length gives it away.
     *
     * @param options the item's options
     * @return {@code true} when the longest correct option exceeds the longest distractor by at least
     *         {@link #MAX_CORRECT_OPTION_LENGTH_MARGIN} characters and by more than
     *         {@link #MIN_CORRECT_OPTION_LENGTH_RATIO} in proportion
     */
    private static boolean correctOptionIsConspicuouslyLong(List<AnswerOption> options) {
        int correct = options.stream().filter(AnswerOption::correct).mapToInt(option -> option.text().strip().length()).max().orElse(0);
        int longestDistractor = options.stream().filter(option -> !option.correct()).mapToInt(option -> option.text().strip().length()).max().orElse(0);
        if (longestDistractor == 0) {
            return false;
        }
        return correct - longestDistractor >= MAX_CORRECT_OPTION_LENGTH_MARGIN && (double) correct / longestDistractor > MIN_CORRECT_OPTION_LENGTH_RATIO;
    }

    /**
     * Detect one option appearing as a whole phrase inside another, which lets a student eliminate by
     * overlap. Matching on word boundaries avoids flagging coincidental substrings such as "2" inside "12".
     *
     * @param options the item's options
     * @return {@code true} if any option's text is contained in another's
     */
    private static boolean hasContainedOption(List<AnswerOption> options) {
        List<String> normalised = options.stream().map(option -> option.text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("[.,;:]+$", "").strip()).toList();
        for (int i = 0; i < normalised.size(); i++) {
            for (int j = 0; j < normalised.size(); j++) {
                if (i == j) {
                    continue;
                }
                String shorter = normalised.get(i);
                String longer = normalised.get(j);
                if (shorter.length() < longer.length() && Pattern.compile("\\b" + Pattern.quote(shorter) + "\\b").matcher(longer).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    record GeneratedQuiz(List<GeneratedQuizItem> questions) {
    }

    record GeneratedQuizItem(String type, String title, String questionText, List<GeneratedOption> options, String explanation) {
    }

    record GeneratedItem(String title, String questionText, List<GeneratedOption> options, String explanation) {
    }

    record GeneratedOption(String text, Boolean correct) {
    }
}
