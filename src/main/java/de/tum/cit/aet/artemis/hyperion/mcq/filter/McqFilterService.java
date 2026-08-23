package de.tum.cit.aet.artemis.hyperion.mcq.filter;

import java.util.EnumMap;
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
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ModeVerdict;

/**
 * Scores a generated question against each {@link FailureMode} and decides whether to accept it.
 * <p>
 * All five modes are judged in a single model call. The accept threshold is applied here rather than by
 * the model, so a stored decision can be re-derived under a different threshold.
 */
@Service
public class McqFilterService {

    private static final Logger log = LoggerFactory.getLogger(McqFilterService.class);

    private static final String SYSTEM_PROMPT = "/prompts/mcq/mcq_filter_system.st";

    private static final String USER_PROMPT = "/prompts/mcq/mcq_filter_user.st";

    private final PromptTemplateService templates;

    public McqFilterService(PromptTemplateService templates) {
        this.templates = templates;
    }

    /**
     * Outcome of one filter attempt.
     *
     * @param decision the decision, or {@code null} when the filter call itself failed
     */
    public record Result(FilterDecision decision, CallRecord call) {

        public boolean succeeded() {
            return decision != null;
        }
    }

    /**
     * Judge an item.
     *
     * @param item      the question to judge
     * @param grounding the material the question was generated from
     * @param threshold   minimum aggregate score in [0, 1] required for acceptance, where the aggregate is
     *                    {@code 1 - mean(severity)}
     * @param model       provider model name, sent with the request
     * @param temperature sampling temperature
     * @param maxAttempts total attempts including the first
     * @param chatClient  client to issue the call with
     * @return the result, which always carries a {@link CallRecord}
     */
    public Result evaluate(McqItem item, GroundingContext grounding, double threshold, String model, double temperature, int maxAttempts, ChatClient chatClient) {
        BeanOutputConverter<FilterOutput> converter = StructuredOutputs.converterFor(FilterOutput.class);
        String system = templates.render(SYSTEM_PROMPT, Map.of());
        String user = templates.render(USER_PROMPT,
                Map.of("groundingBlock", grounding.renderedBlock(), "questionTitle", item.title(), "questionText", item.questionText(), "options", renderOptions(item.options()),
                        "explanation", item.explanation() == null ? "" : item.explanation(), "format", converter.getFormat()));

        ChatCall.Outcome outcome = ChatCall.execute("filter", model, temperature, maxAttempts, chatClient, system, user);
        CallRecord call = outcome.record();
        if (!outcome.succeeded()) {
            return new Result(null, call);
        }

        String text = outcome.text();
        if (text == null || text.isBlank()) {
            return new Result(null, call.withFailureCategory("FILTER_EMPTY_RESPONSE"));
        }

        FilterOutput output;
        try {
            output = converter.convert(text);
        }
        catch (Exception e) {
            log.warn("Could not parse filter verdict: {}", e.getMessage());
            return new Result(null, call.withFailureCategory("FILTER_MALFORMED_JSON"));
        }
        if (output == null || output.modes() == null || output.modes().isEmpty()) {
            return new Result(null, call.withFailureCategory("FILTER_NO_MODES"));
        }

        Map<FailureMode, ModeVerdict> verdicts = toVerdicts(output.modes());
        if (verdicts.size() != FailureMode.values().length) {
            log.warn("Filter judged {} of {} modes; discarding incomplete verdict", verdicts.size(), FailureMode.values().length);
            return new Result(null, call.withFailureCategory("FILTER_INCOMPLETE_VERDICT"));
        }
        double worstSeverity = verdicts.values().stream().mapToDouble(ModeVerdict::severity).max().orElse(1);
        double meanSeverity = verdicts.values().stream().mapToDouble(ModeVerdict::severity).average().orElse(1);
        double aggregate = 1 - worstSeverity;
        return new Result(new FilterDecision(aggregate >= threshold, aggregate, meanSeverity, verdicts, model, output.rationale()), call);
    }

    private static Map<FailureMode, ModeVerdict> toVerdicts(List<ModeScore> scores) {
        Map<FailureMode, ModeVerdict> verdicts = new EnumMap<>(FailureMode.class);
        for (ModeScore score : scores) {
            parseMode(score.mode())
                    .ifPresent(mode -> verdicts.put(mode, new ModeVerdict(clamp(score.severity()), Boolean.TRUE.equals(score.triggered()), score.justification())));
        }
        return verdicts;
    }

    private static java.util.Optional<FailureMode> parseMode(String raw) {
        if (raw == null) {
            return java.util.Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return java.util.Optional.of(FailureMode.valueOf(normalised));
        }
        catch (IllegalArgumentException e) {
            log.debug("Unknown failure mode in filter output: {}", raw);
            return java.util.Optional.empty();
        }
    }

    private static double clamp(Double value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private static String renderOptions(List<AnswerOption> options) {
        StringBuilder rendered = new StringBuilder();
        for (AnswerOption option : options) {
            rendered.append("- [").append(option.correct() ? "correct" : "wrong").append("] ").append(option.text()).append('\n');
        }
        return rendered.toString().strip();
    }


    record FilterOutput(List<ModeScore> modes, String rationale) {
    }

    record ModeScore(String mode, Double severity, Boolean triggered, String justification) {
    }
}
