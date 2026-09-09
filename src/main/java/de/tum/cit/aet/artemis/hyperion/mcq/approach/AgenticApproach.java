package de.tum.cit.aet.artemis.hyperion.mcq.approach;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.FilterScope;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;

/**
 * The agentic approach: generate a whole quiz per call, judge every question inline, and keep generating
 * until the request is satisfied.
 * <p>
 * Grounding is retrieved once per request across all requested competencies and reused for every round and
 * every filter call. Each question is judged individually at {@link FilterScope#COMBINED}, so general
 * quality and request fit are decided in one call per question. Gating spans the configured general modes
 * plus every {@link FilterScope#REQUEST_FIT} mode, so a question that does not fit the request is rejected
 * here just as an ill-fitting candidate is rejected at selection in the two-phase approach. A question
 * whose normalised text duplicates an already accepted one is rejected without a filter call.
 * <p>
 * The round loop ends at {@code maxRounds}, when the quiz is full, or early after any judged round that
 * adds no accepted question — the inputs do not change between rounds, so a fruitless round is not
 * repeated. A round whose whole batch fails before judging is retried: it is a fresh sample, not a
 * judged outcome.
 */
@Service
public class AgenticApproach implements QuizGenerator {

    private static final Logger log = LoggerFactory.getLogger(AgenticApproach.class);

    private final GroundingAssemblyService groundingAssembly;

    private final McqGenerationService generation;

    private final McqFilterService filter;

    public AgenticApproach(GroundingAssemblyService groundingAssembly, McqGenerationService generation, McqFilterService filter) {
        this.groundingAssembly = groundingAssembly;
        this.generation = generation;
        this.filter = filter;
    }

    @Override
    public Quiz generate(GenerationRequest request, ApproachContext context) {
        String competencies = request.competencyMode() ? Competencies.render(request, context.manifest()) : null;
        GroundingContext grounding = ground(request, context);
        McqFilterService.RequestContext fit = new McqFilterService.RequestContext(competencies == null ? request.topic() : competencies, request.difficulty().promptValue(),
                request.optionalPrompt());

        Set<FailureMode> gating = EnumSet.copyOf(context.gatingModes());
        gating.addAll(FilterScope.REQUEST_FIT.modes());

        List<JudgedQuestion> accepted = new ArrayList<>();
        List<JudgedQuestion> rejected = new ArrayList<>();
        List<CallRecord> calls = new ArrayList<>();
        Set<String> acceptedTexts = new HashSet<>();
        int generated = 0;

        for (int round = 1; round <= context.maxRounds() && accepted.size() < request.numberOfQuestions(); round++) {
            int acceptedBeforeRound = accepted.size();
            int missing = request.numberOfQuestions() - accepted.size();
            McqGenerationService.QuizResult batch = generation.generateQuiz(request, competencies, grounding, missing, context.generator().model(),
                    context.generator().temperature(), context.generator().maxAttempts(), context.generator().client());
            calls.add(batch.call());
            if (batch.failure() != null) {
                log.warn("Round {}/{} of request {} yielded no usable questions: {}", round, context.maxRounds(), request.key(), batch.failure());
                continue;
            }
            generated += batch.questions().size();

            for (McqGenerationService.GeneratedQuestion question : batch.questions()) {
                if (accepted.size() >= request.numberOfQuestions()) {
                    break;
                }
                McqItem item = question.item();
                if (!acceptedTexts.add(normalise(item.questionText()))) {
                    continue;
                }
                Optional<String> competencyKey = request.competencyMode() ? Competencies.match(request, context.manifest(), question.declaredCompetency())
                        : Optional.empty();
                if (request.competencyMode() && competencyKey.isEmpty()) {
                    log.warn("Question '{}' of request {} declares competency '{}', which the request does not name; discarding it", item.title(), request.key(),
                            question.declaredCompetency());
                    continue;
                }
                McqFilterService.Result judged = filter.evaluate(item, grounding, FilterScope.COMBINED, fit, context.acceptThreshold(), gating,
                        context.judge().model(), context.judge().temperature(), context.judge().maxAttempts(), context.judge().client());
                calls.add(judged.call());
                if (!judged.succeeded()) {
                    continue;
                }
                if (judged.decision().accepted()) {
                    accepted.add(new JudgedQuestion(item, judged.decision(), competencyKey.orElse(null)));
                }
                else {
                    rejected.add(new JudgedQuestion(item, judged.decision(), competencyKey.orElse(null)));
                }
            }
            if (accepted.size() == acceptedBeforeRound) {
                log.warn("Round {}/{} of request {} added no accepted question; stopping early", round, context.maxRounds(), request.key());
                break;
            }
        }

        boolean complete = accepted.size() >= request.numberOfQuestions();
        if (!complete) {
            log.warn("Request {} remained incomplete after {} rounds: {} of {} questions accepted", request.key(), context.maxRounds(), accepted.size(),
                    request.numberOfQuestions());
        }
        return new Quiz(accepted, rejected, calls, generated, complete);
    }

    private GroundingContext ground(GenerationRequest request, ApproachContext context) {
        if (!request.competencyMode()) {
            return groundingAssembly.assemble(request.topic(), context.snippets().search(request.topic(), context.topK(), request.courseKey()), context.maxGroundingTokens());
        }
        List<List<Snippet>> perCompetency = new ArrayList<>();
        for (String key : request.competencyKeys()) {
            Competency competency = Competencies.resolve(request, context.manifest(), key);
            perCompetency.add(context.snippets().search(competency.retrievalQuery(), context.topK(), request.courseKey()));
        }
        String label = String.join(", ", request.competencyKeys());
        return groundingAssembly.assemble(label, interleave(perCompetency), context.maxGroundingTokens());
    }


    /**
     * Merges per-competency snippet lists by taking one snippet from each in turn, dropping repeats.
     * <p>
     * Assembly truncates in list order once the token budget is reached, so concatenating the lists would
     * spend the whole budget on the first competencies and leave the last ones unrepresented.
     *
     * @param perCompetency one ranked snippet list per requested competency
     * @return the interleaved snippets, each chunk appearing once
     */
    static List<Snippet> interleave(List<List<Snippet>> perCompetency) {
        Map<String, Snippet> merged = new LinkedHashMap<>();
        int depth = perCompetency.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < depth; rank++) {
            for (List<Snippet> snippets : perCompetency) {
                if (rank < snippets.size()) {
                    merged.putIfAbsent(snippets.get(rank).chunkId(), snippets.get(rank));
                }
            }
        }
        return List.copyOf(merged.values());
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
