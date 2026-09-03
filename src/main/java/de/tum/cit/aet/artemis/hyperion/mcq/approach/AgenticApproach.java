package de.tum.cit.aet.artemis.hyperion.mcq.approach;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
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
 * quality and request fit are decided in one call per question. A question whose normalised text duplicates
 * an already accepted one is rejected without a filter call.
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
        String competencies = request.competencyMode() ? renderCompetencies(request, context) : null;
        GroundingContext grounding = ground(request, context);
        McqFilterService.RequestContext fit = new McqFilterService.RequestContext(competencies == null ? request.topic() : competencies, request.difficulty().promptValue(),
                request.optionalPrompt());

        List<JudgedQuestion> accepted = new ArrayList<>();
        List<JudgedQuestion> rejected = new ArrayList<>();
        List<CallRecord> calls = new ArrayList<>();
        Set<String> acceptedTexts = new HashSet<>();
        int generated = 0;

        for (int round = 1; round <= context.maxRounds() && accepted.size() < request.numberOfQuestions(); round++) {
            int missing = request.numberOfQuestions() - accepted.size();
            McqGenerationService.QuizResult batch = generation.generateQuiz(request, competencies, grounding, missing, context.generator().model(),
                    context.generator().temperature(), context.generator().maxAttempts(), context.generator().client());
            calls.add(batch.call());
            if (batch.failure() != null) {
                log.warn("Round {}/{} of request {} yielded no usable questions: {}", round, context.maxRounds(), request.key(), batch.failure());
                continue;
            }
            generated += batch.items().size();

            for (McqItem item : batch.items()) {
                if (accepted.size() >= request.numberOfQuestions()) {
                    break;
                }
                if (!acceptedTexts.add(normalise(item.questionText()))) {
                    continue;
                }
                McqFilterService.Result judged = filter.evaluate(item, grounding, FilterScope.COMBINED, fit, context.acceptThreshold(), context.judge().model(),
                        context.judge().temperature(), context.judge().maxAttempts(), context.judge().client());
                calls.add(judged.call());
                if (!judged.succeeded()) {
                    continue;
                }
                if (judged.decision().accepted()) {
                    accepted.add(new JudgedQuestion(item, judged.decision()));
                }
                else {
                    rejected.add(new JudgedQuestion(item, judged.decision()));
                }
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
        Map<String, Snippet> merged = new LinkedHashMap<>();
        for (String key : request.competencyKeys()) {
            Competency competency = resolve(request, context, key);
            for (Snippet snippet : context.snippets().search(competency.retrievalQuery(), context.topK(), request.courseKey())) {
                merged.putIfAbsent(snippet.chunkId(), snippet);
            }
        }
        String label = String.join(", ", request.competencyKeys());
        return groundingAssembly.assemble(label, List.copyOf(merged.values()), context.maxGroundingTokens());
    }

    private static String renderCompetencies(GenerationRequest request, ApproachContext context) {
        StringBuilder rendered = new StringBuilder();
        for (String key : request.competencyKeys()) {
            Competency competency = resolve(request, context, key);
            rendered.append(competency.title()).append(" (").append(competency.taxonomy()).append(")\n");
            if (competency.description() != null && !competency.description().isBlank()) {
                rendered.append(competency.description()).append('\n');
            }
            rendered.append('\n');
        }
        return rendered.toString().strip();
    }

    private static Competency resolve(GenerationRequest request, ApproachContext context, String key) {
        return context.manifest().byKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Request '" + request.key() + "' names competency '" + key + "', which the course model does not declare"));
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
