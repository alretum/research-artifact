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
    public enum Failure {
        TRANSPORT, EMPTY_RESPONSE, MALFORMED_JSON, SCHEMA_VIOLATION, VALIDATION_VIOLATION
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
            return new Result(null, user, call, Failure.TRANSPORT);
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
        return item.options().stream().filter(AnswerOption::correct).count() == 1;
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
