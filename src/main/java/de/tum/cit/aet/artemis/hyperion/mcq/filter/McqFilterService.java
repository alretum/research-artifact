package de.tum.cit.aet.artemis.hyperion.mcq.filter;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * Scores a generated question against every {@link FailureMode} of a {@link FilterScope} and decides
 * whether to accept it.
 * <p>
 * All modes of the scope are judged in a single model call, and a verdict that does not cover the full
 * scope is discarded rather than defaulted. Acceptance is decided by the gating modes alone: every judged
 * mode is recorded, but only a gating mode's severity can reject. The threshold is applied here rather
 * than by the model, so a stored decision can be re-derived under a different threshold or gating set via
 * {@link #decide}.
 */
@Service
public class McqFilterService {

    private static final Logger log = LoggerFactory.getLogger(McqFilterService.class);

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
     * The request-fit reference values, required by {@link FilterScope#REQUEST_FIT} and
     * {@link FilterScope#COMBINED}.
     *
     * @param competencies        rendered titles and learning objectives of the requested competencies
     * @param requestedDifficulty requested difficulty on the prompt scale 0 to 100
     * @param instructions        the request's further instructions, or {@code null} when none were given
     */
    public record RequestContext(String competencies, int requestedDifficulty, String instructions) {
    }

    /**
     * Judge an item under a scope.
     *
     * @param item      the question to judge
     * @param grounding the material the question was generated from; may be {@code null} for a scope that
     *                  does not judge against it
     * @param scope     which failure modes to judge, and against which reference material
     * @param request   the request-fit reference values; may be {@code null} for a scope that does not
     *                  judge against a request
     * @param threshold   minimum aggregate score in [0, 1] required for acceptance
     * @param gatingModes modes whose severity decides acceptance; {@code null} or empty gates on every
     *                    judged mode. Modes outside the scope are ignored
     * @param model       provider model name, sent with the request
     * @param temperature sampling temperature
     * @param maxAttempts total attempts including the first
     * @param chatClient  client to issue the call with
     * @return the result, which always carries a {@link CallRecord}
     * @throws IllegalArgumentException if the scope requires grounding or a request context and it is absent
     */
    public Result evaluate(McqItem item, GroundingContext grounding, FilterScope scope, RequestContext request, double threshold, Set<FailureMode> gatingModes, String model,
            double temperature, int maxAttempts, ChatClient chatClient) {
        if (scope.requiresGrounding() && grounding == null) {
            throw new IllegalArgumentException("Scope " + scope + " judges against grounding, but none was given");
        }
        if (scope.requiresRequest() && request == null) {
            throw new IllegalArgumentException("Scope " + scope + " judges against a request, but none was given");
        }

        BeanOutputConverter<FilterOutput> converter = StructuredOutputs.converterFor(FilterOutput.class);
        Map<String, Object> variables = new HashMap<>();
        variables.put("questionTitle", item.title());
        variables.put("questionText", item.questionText());
        variables.put("options", renderOptions(item.options()));
        variables.put("explanation", item.explanation() == null ? "" : item.explanation());
        variables.put("format", converter.getFormat());
        if (scope.requiresGrounding()) {
            variables.put("groundingBlock", grounding.renderedBlock());
        }
        if (scope.requiresRequest()) {
            variables.put("competencies", request.competencies());
            variables.put("difficulty", request.requestedDifficulty());
            variables.put("instructions", request.instructions() == null || request.instructions().isBlank() ? "(none)" : request.instructions());
        }
        String system = templates.render(scope.systemTemplate(), Map.of());
        String user = templates.render(scope.userTemplate(), variables);

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

        Map<FailureMode, ModeVerdict> verdicts = toVerdicts(output.modes(), scope);
        if (verdicts.size() != scope.modes().size()) {
            log.warn("Filter judged {} of {} modes in scope {}; discarding incomplete verdict", verdicts.size(), scope.modes().size(), scope);
            return new Result(null, call.withFailureCategory("FILTER_INCOMPLETE_VERDICT"));
        }
        return new Result(decide(verdicts, threshold, gate(scope, gatingModes), model, output.rationale()), call);
    }

    /**
     * Derive a decision from judged verdicts, so a stored decision can be recomputed under a different
     * threshold or gating set without a new model call.
     *
     * @param verdicts    every judged mode and its severity
     * @param threshold   minimum aggregate score in [0, 1] required for acceptance
     * @param gatingModes modes whose severity decides acceptance; {@code null} or empty gates on all judged
     *                    modes. The aggregate is {@code 1 - worst severity among these}
     * @param model       judge model recorded on the decision
     * @param rationale   the judge's overall rationale
     * @return the decision
     */
    public static FilterDecision decide(Map<FailureMode, ModeVerdict> verdicts, double threshold, Set<FailureMode> gatingModes, String model, String rationale) {
        Set<FailureMode> gating = gatingModes == null || gatingModes.isEmpty() ? verdicts.keySet() : gatingModes;
        double worstSeverity = verdicts.entrySet().stream().filter(entry -> gating.contains(entry.getKey())).mapToDouble(entry -> entry.getValue().severity()).max().orElse(0);
        double meanSeverity = verdicts.values().stream().mapToDouble(ModeVerdict::severity).average().orElse(1);
        double aggregate = 1 - worstSeverity;
        return new FilterDecision(aggregate >= threshold, aggregate, meanSeverity, verdicts, model, rationale);
    }

    private static Set<FailureMode> gate(FilterScope scope, Set<FailureMode> gatingModes) {
        if (gatingModes == null || gatingModes.isEmpty()) {
            return scope.modes();
        }
        Set<FailureMode> effective = gatingModes.stream().filter(scope.modes()::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (effective.isEmpty()) {
            throw new IllegalArgumentException("None of the gating modes " + gatingModes + " is judged under scope " + scope + ", so nothing could ever reject");
        }
        return effective;
    }

    private static Map<FailureMode, ModeVerdict> toVerdicts(List<ModeScore> scores, FilterScope scope) {
        Map<FailureMode, ModeVerdict> verdicts = new EnumMap<>(FailureMode.class);
        for (ModeScore score : scores) {
            parseMode(score.mode()).filter(scope.modes()::contains)
                    .ifPresent(mode -> verdicts.put(mode, new ModeVerdict(clamp(score.severity()), Boolean.TRUE.equals(score.triggered()), score.justification())));
        }
        return verdicts;
    }

    private static Optional<FailureMode> parseMode(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return Optional.of(FailureMode.valueOf(normalised));
        }
        catch (IllegalArgumentException e) {
            log.debug("Unknown failure mode in filter output: {}", raw);
            return Optional.empty();
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
