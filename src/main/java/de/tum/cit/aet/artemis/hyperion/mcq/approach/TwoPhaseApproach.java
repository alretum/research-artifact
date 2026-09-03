package de.tum.cit.aet.artemis.hyperion.mcq.approach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.select.PoolSelectionService;
import de.tum.cit.aet.artemis.hyperion.mcq.select.PoolSelectionService.Candidate;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.PoolCandidate;

/**
 * The two-phase approach: answer a request by selecting from the pre-built question pool.
 * <p>
 * Candidates are narrowed in SQL by the request's labels — course, competency, language, question type,
 * difficulty — restricted to items the run's judge accepted at pool entry, then a model selects the quiz
 * from them. Nothing is generated at request time; a request the pool cannot serve yields an incomplete
 * quiz rather than fresh generation, so the two architectures never mix within one cell. Only
 * competency-mode requests can be answered, because the pool is keyed by competency.
 */
@Service
public class TwoPhaseApproach implements QuizGenerator {

    private static final Logger log = LoggerFactory.getLogger(TwoPhaseApproach.class);

    private final RunStore store;

    private final PoolSelectionService selection;

    public TwoPhaseApproach(RunStore store, PoolSelectionService selection) {
        this.store = store;
        this.selection = selection;
    }

    @Override
    public Quiz generate(GenerationRequest request, ApproachContext context) {
        if (!request.competencyMode()) {
            throw new IllegalArgumentException("Request '" + request.key() + "' is free-topic; the pool is keyed by competency and cannot serve it");
        }
        if (context.selection() == null) {
            throw new IllegalArgumentException("Request '" + request.key() + "' needs selection settings, and the context carries none");
        }

        Map<Long, PoolCandidate> byId = new LinkedHashMap<>();
        for (String competencyKey : request.competencyKeys()) {
            Competencies.resolve(request, context.manifest(), competencyKey);
            for (QuestionType type : request.questionTypes()) {
                PoolCell cell = new PoolCell(request.courseKey(), competencyKey, request.language(), type, request.difficulty());
                for (PoolCandidate candidate : store.poolCandidates(cell, context.judge().model(), context.selection().poolAsOf())) {
                    byId.putIfAbsent(candidate.id(), candidate);
                }
            }
        }
        if (byId.isEmpty()) {
            log.warn("Request {} matches no pooled candidates for judge {}; returning an incomplete quiz", request.key(), context.judge().model());
            return new Quiz(List.of(), List.of(), List.of(), 0, false);
        }

        List<Candidate> candidates = byId.values().stream().map(candidate -> new Candidate(candidate.id(), readItem(candidate.itemJson()))).toList();
        PoolSelectionService.Result result = selection.select(request, Competencies.render(request, context.manifest()), candidates, request.numberOfQuestions(),
                context.selection().maxCandidates(), context.selection().selector().model(), context.selection().selector().temperature(),
                context.selection().selector().maxAttempts(), context.selection().selector().client());
        if (!result.succeeded()) {
            log.warn("Selection for request {} failed; returning an incomplete quiz", request.key());
            return new Quiz(List.of(), List.of(), result.calls(), 0, false);
        }

        List<JudgedQuestion> accepted = new ArrayList<>();
        for (Long id : result.selection().chosenIds()) {
            PoolCandidate candidate = byId.get(id);
            accepted.add(new JudgedQuestion(readItem(candidate.itemJson()), readDecision(candidate.decisionJson())));
        }
        boolean complete = accepted.size() >= request.numberOfQuestions();
        if (!complete) {
            log.warn("Request {} selected {} of {} questions from {} candidates", request.key(), accepted.size(), request.numberOfQuestions(), candidates.size());
        }
        return new Quiz(accepted, List.of(), result.calls(), 0, complete);
    }

    private static McqItem readItem(String json) {
        return StructuredOutputs.outputMapper().readValue(json, new TypeReference<McqItem>() {
        });
    }

    private static FilterDecision readDecision(String json) {
        return StructuredOutputs.outputMapper().readValue(json, new TypeReference<FilterDecision>() {
        });
    }
}
