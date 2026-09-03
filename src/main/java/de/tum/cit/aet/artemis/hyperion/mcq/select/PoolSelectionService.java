package de.tum.cit.aet.artemis.hyperion.mcq.select;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.ChatCall;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.PromptTemplateService;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;

/**
 * Selects the questions of a quiz from pooled candidates with a model call.
 * <p>
 * The model judges request fit — competency match, difficulty, instruction compliance — and set
 * composition; general quality is not re-judged, because every candidate passed the pool-entry filter.
 * When more candidates exist than one call should carry, they are shortlisted in batches first and the
 * final selection is made over the union of the shortlists.
 */
@Service
public class PoolSelectionService {

    private static final Logger log = LoggerFactory.getLogger(PoolSelectionService.class);

    private static final String SYSTEM_PROMPT = "/prompts/mcq/mcq_select_system.st";

    private static final String USER_PROMPT = "/prompts/mcq/mcq_select_user.st";

    private final PromptTemplateService templates;

    public PoolSelectionService(PromptTemplateService templates) {
        this.templates = templates;
    }

    /**
     * One selectable pooled question.
     *
     * @param id stable identifier the model refers to it by
     */
    public record Candidate(long id, McqItem item) {
    }

    /**
     * The selector's choice.
     *
     * @param chosenIds    ids of the selected candidates, in the model's order, deduplicated and
     *                     restricted to real candidate ids
     * @param rejections   the model's per-candidate rejection reasons, possibly empty
     * @param rationale    the model's rationale for the set
     * @param candidateIds every id the selector could choose from
     */
    public record Selection(List<Long> chosenIds, List<Rejection> rejections, String rationale, List<Long> candidateIds) {

        public Selection {
            chosenIds = List.copyOf(chosenIds);
            rejections = List.copyOf(rejections);
            candidateIds = List.copyOf(candidateIds);
        }
    }

    /**
     * One rejected candidate and why.
     */
    public record Rejection(Long id, String reason) {
    }

    /**
     * Outcome of a selection, successful or not.
     *
     * @param selection the selection, or {@code null} when no usable choice was obtained
     * @param calls     every model call made, including shortlist rounds and failures
     */
    public record Result(Selection selection, List<CallRecord> calls) {

        public Result {
            calls = List.copyOf(calls);
        }

        public boolean succeeded() {
            return selection != null;
        }
    }

    /**
     * Select {@code count} questions for the request.
     *
     * @param request       the request being answered
     * @param competencies  rendered titles and objectives of the requested competencies
     * @param candidates    the pooled candidates to choose from
     * @param count         questions to select
     * @param maxCandidates most candidates one call may carry; above it, batches are shortlisted first
     * @param model         provider model name, sent with the request
     * @param temperature   sampling temperature; a non-zero value is what makes repetitions differ
     * @param maxAttempts   transport attempts per call including the first
     * @param chatClient    client to issue the calls with
     * @return the result, carrying every call made
     * @throws IllegalArgumentException if {@code candidates} is empty or {@code count} is below 1
     */
    public Result select(GenerationRequest request, String competencies, List<Candidate> candidates, int count, int maxCandidates, String model, double temperature,
            int maxAttempts, ChatClient chatClient) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Cannot select from zero candidates for request '" + request.key() + "'");
        }
        if (count < 1) {
            throw new IllegalArgumentException("Require at least 1 question to select, got " + count);
        }

        List<CallRecord> calls = new ArrayList<>();
        List<Candidate> shortlist = candidates;
        if (candidates.size() > maxCandidates) {
            shortlist = shortlist(request, competencies, candidates, count, maxCandidates, model, temperature, maxAttempts, chatClient, calls);
            if (shortlist.isEmpty()) {
                return new Result(null, calls);
            }
        }

        SelectionOutput output = call(request, competencies, shortlist, count, model, temperature, maxAttempts, chatClient, calls);
        if (output == null) {
            return new Result(null, calls);
        }
        List<Long> chosen = clean(output.chosen(), shortlist, count);
        if (chosen.isEmpty()) {
            CallRecord last = calls.removeLast();
            calls.add(last.withFailureCategory("SELECT_NO_CHOICE"));
            return new Result(null, calls);
        }
        List<Long> candidateIds = candidates.stream().map(Candidate::id).toList();
        List<Rejection> rejections = output.rejected() == null ? List.of() : output.rejected().stream().filter(rejection -> rejection != null && rejection.id() != null).toList();
        return new Result(new Selection(chosen, rejections, output.rationale(), candidateIds), calls);
    }

    private List<Candidate> shortlist(GenerationRequest request, String competencies, List<Candidate> candidates, int count, int maxCandidates, String model, double temperature,
            int maxAttempts, ChatClient chatClient, List<CallRecord> calls) {
        List<Candidate> union = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += maxCandidates) {
            List<Candidate> batch = candidates.subList(start, Math.min(start + maxCandidates, candidates.size()));
            SelectionOutput output = call(request, competencies, batch, Math.min(count, batch.size()), model, temperature, maxAttempts, chatClient, calls);
            if (output == null) {
                continue;
            }
            for (Long id : clean(output.chosen(), batch, count)) {
                batch.stream().filter(candidate -> candidate.id() == id).findFirst().ifPresent(union::add);
            }
        }
        log.debug("Shortlisted {} of {} candidates across {} batches", union.size(), candidates.size(), (candidates.size() + maxCandidates - 1) / maxCandidates);
        return union;
    }

    private SelectionOutput call(GenerationRequest request, String competencies, List<Candidate> candidates, int count, String model, double temperature, int maxAttempts,
            ChatClient chatClient, List<CallRecord> calls) {
        BeanOutputConverter<SelectionOutput> converter = StructuredOutputs.converterFor(SelectionOutput.class);
        String system = templates.render(SYSTEM_PROMPT, Map.of());
        String user = templates.render(USER_PROMPT,
                Map.of("competencies", competencies, "difficulty", request.difficulty().promptValue(), "instructions",
                        request.optionalPrompt() == null || request.optionalPrompt().isBlank() ? "(none)" : request.optionalPrompt(), "candidates", render(candidates), "count",
                        count, "format", converter.getFormat()));

        ChatCall.Outcome outcome = ChatCall.execute("selection", model, temperature, maxAttempts, chatClient, system, user);
        if (!outcome.succeeded()) {
            calls.add(outcome.record());
            return null;
        }
        String text = outcome.text();
        if (text == null || text.isBlank()) {
            calls.add(outcome.record().withFailureCategory("SELECT_EMPTY_RESPONSE"));
            return null;
        }
        try {
            SelectionOutput output = converter.convert(text);
            calls.add(outcome.record());
            return output;
        }
        catch (Exception e) {
            log.warn("Could not parse selection: {}", e.getMessage());
            calls.add(outcome.record().withFailureCategory("SELECT_MALFORMED_JSON"));
            return null;
        }
    }

    private static List<Long> clean(List<Long> chosen, List<Candidate> candidates, int count) {
        if (chosen == null) {
            return List.of();
        }
        Set<Long> known = new LinkedHashSet<>(candidates.stream().map(Candidate::id).toList());
        List<Long> cleaned = new ArrayList<>();
        for (Long id : chosen) {
            if (id != null && known.contains(id) && !cleaned.contains(id) && cleaned.size() < count) {
                cleaned.add(id);
            }
        }
        return cleaned;
    }

    private static String render(List<Candidate> candidates) {
        StringBuilder rendered = new StringBuilder();
        for (Candidate candidate : candidates) {
            rendered.append('[').append(candidate.id()).append("] ").append(candidate.item().title()).append('\n');
            rendered.append(candidate.item().questionText()).append('\n');
            for (AnswerOption option : candidate.item().options()) {
                rendered.append("- [").append(option.correct() ? "correct" : "wrong").append("] ").append(option.text()).append('\n');
            }
            rendered.append('\n');
        }
        return rendered.toString().strip();
    }

    record SelectionOutput(List<Long> chosen, List<Rejection> rejected, String rationale) {
    }
}
