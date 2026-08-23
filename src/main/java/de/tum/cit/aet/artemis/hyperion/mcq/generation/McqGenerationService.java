package de.tum.cit.aet.artemis.hyperion.mcq.generation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.llm.ChatCall;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

/**
 * Generates one multiple-choice question from grounded lecture material.
 * <p>
 * The chat client is supplied per call rather than injected, so generator and filter models can differ
 * within a single run.
 */
@Service
public class McqGenerationService {

    private static final Logger log = LoggerFactory.getLogger(McqGenerationService.class);

    private static final String SYSTEM_PROMPT = "/prompts/mcq/mcq_generate_system.st";

    private static final String USER_PROMPT = "/prompts/mcq/mcq_generate_user.st";

    private static final int REQUIRED_OPTION_COUNT = 4;

    /**
     * Constructions that make an option refer to the option set rather than to the subject matter. The
     * prompt forbids them; this enforces it and makes the rate at which a model ignores the instruction
     * measurable.
     */
    private static final java.util.regex.Pattern BANNED_CONSTRUCTION = java.util.regex.Pattern
            .compile("(?i)\\b(all|none|both|any|either|neither) of (the |these |those )?(above|below|these|those|options|answers|preceding)\\b"
                    + "|(?i)\\bboth [a-d] and [a-d]\\b|(?i)\\ba and b\\b|(?i)\\ball answers are correct\\b");

    /**
     * Characters by which the correct option may exceed the longest distractor before its length gives the
     * answer away.
     * <p>
     * Absolute difference, not a ratio, because absolute difference is what a reader scanning four options
     * actually sees. Measured over 64 items the ratio proved to be the wrong instrument in both directions:
     * one item was 10 characters against 6 -- a ratio of 1.67 that no reader could exploit -- while another
     * was 132 against 94, a ratio of only 1.40 but 38 characters longer than every alternative, which is
     * precisely the cue this rule exists to catch. Gating on the ratio would have excused the second for
     * narrowly missing a threshold the first cleared on a four-character difference.
     */
    private static final int MAX_CORRECT_OPTION_LENGTH_MARGIN = 20;

    /**
     * A floor on the relative difference, so a long-option item is not failed for a difference that is
     * large in characters but small in proportion -- 25 characters more than a 250-character distractor is
     * not a cue. The margin above does the real work; this only guards that edge.
     */
    private static final double MIN_CORRECT_OPTION_LENGTH_RATIO = 1.15;

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

    /**
     * Why a generation attempt produced no usable item.
     * <p>
     * The first group mirrors {@link ChatCall.Kind} so a transport-layer cause is not flattened into one
     * bucket: a rejected key and a throttled endpoint need different responses, and only one of them is
     * worth retrying. The second group covers output the model did produce but which was unusable.
     */
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
            Failure failure = toFailure(outcome.kind());
            return new Result(null, user, call.withFailureCategory(failure.name()), failure);
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

    private static McqItem toItem(GeneratedItem generated) {
        List<AnswerOption> options = generated.options().stream().map(option -> new AnswerOption(strip(option.text()), Boolean.TRUE.equals(option.correct()), null, null)).toList();
        return new McqItem(QuestionType.SINGLE_CHOICE, strip(generated.title()), strip(generated.questionText()), options, null, strip(generated.explanation()));
    }

    private static boolean validate(McqItem item) {
        if (isBlank(item.title()) || isBlank(item.questionText())) {
            return false;
        }
        if (item.options().size() != REQUIRED_OPTION_COUNT) {
            return false;
        }
        if (item.options().stream().anyMatch(option -> isBlank(option.text()))) {
            return false;
        }
        long distinct = item.options().stream().map(option -> option.text().toLowerCase(Locale.ROOT)).distinct().count();
        if (distinct != REQUIRED_OPTION_COUNT) {
            return false;
        }
        if (item.options().stream().anyMatch(option -> BANNED_CONSTRUCTION.matcher(option.text()).find())) {
            return false;
        }
        if (hasContainedOption(item.options())) {
            return false;
        }
        if (item.options().stream().filter(AnswerOption::correct).count() != 1) {
            return false;
        }
        return !correctOptionIsConspicuouslyLong(item.options());
    }

    /**
     * Detect the correct option being so much longer than every distractor that its length gives it away.
     * <p>
     * Instructed in the prompt as well, but prompting alone did not move the measured distribution, so the
     * rule is enforced here where the other structural checks live.
     *
     * @param options the item's options, already known to contain exactly one correct entry
     * @return {@code true} when the correct option exceeds the longest distractor by at least
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
                if (shorter.length() < longer.length() && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(shorter) + "\\b").matcher(longer).find()) {
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

    record GeneratedItem(String title, String questionText, List<GeneratedOption> options, String explanation) {
    }

    record GeneratedOption(String text, Boolean correct) {
    }
}
