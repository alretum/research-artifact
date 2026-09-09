package de.tum.cit.aet.artemis.hyperion.mcq.approach;

import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClient;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.SnippetSource;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;

/**
 * Answers one {@link GenerationRequest} with a quiz.
 */
public interface QuizGenerator {

    /**
     * Generate a quiz for the request.
     *
     * @param request the request to answer
     * @param context course model, retrieval access and per-call model bindings
     * @return the quiz, complete or not, always carrying the full call history
     * @throws IllegalArgumentException if the request names a competency the course model does not declare
     */
    Quiz generate(GenerationRequest request, ApproachContext context);

    /**
     * One model role, bound for the duration of a request.
     *
     * @param model       provider model name, sent with every request and recorded per call
     * @param temperature sampling temperature
     * @param maxAttempts transport attempts per call including the first
     */
    record ModelCall(ChatClient client, String model, double temperature, int maxAttempts) {
    }

    /**
     * Everything a generator needs besides the request itself.
     *
     * @param manifest           the course's competencies
     * @param store              the run's store; the two-phase approach reads its pool candidates here, so
     *                           it must be the store the run's pools were built in
     * @param snippets           retrieval over the course's indexed material
     * @param generator          the writing role
     * @param judge              the filtering role; for the two-phase approach also the pool judge whose
     *                           acceptance defines the candidate set
     * @param topK               snippets retrieved per query
     * @param maxGroundingTokens upper bound on the assembled grounding block
     * @param acceptThreshold    minimum aggregate filter score in [0, 1] for acceptance
     * @param gatingModes        failure modes whose severity decides acceptance, as configured; fit modes
     *                           outside this set are still judged and recorded but do not reject
     * @param maxRounds          generation rounds allowed before an incomplete quiz is returned
     * @param selection          selection parameters; {@code null} for approaches that never select
     */
    record ApproachContext(CompetencyManifest manifest, RunStore store, SnippetSource snippets, ModelCall generator, ModelCall judge, int topK, int maxGroundingTokens,
            double acceptThreshold, Set<FailureMode> gatingModes, int maxRounds, SelectionSettings selection) {
    }

    /**
     * How the two-phase approach selects from the pool.
     *
     * @param selector      the selecting role
     * @param maxCandidates most candidates one selection call may carry
     * @param poolAsOf      answer from the pool as it stood at this ISO-8601 instant, or {@code null} for
     *                      the current pool
     */
    record SelectionSettings(ModelCall selector, int maxCandidates, String poolAsOf) {
    }

    /**
     * One judged question.
     *
     * @param competencyKey the competency this question assesses; {@code null} on quizzes stored before
     *                      questions carried an attribution, and on free-topic requests
     */
    record JudgedQuestion(McqItem item, FilterDecision decision, String competencyKey) {
    }

    /**
     * The outcome of answering one request.
     * <p>
     * {@code accepted} holds at most the requested number of questions; {@code complete} is whether that
     * number was reached. Rejected questions and every call are retained so the run log carries the full
     * attempt history, and {@code generatedCount} over the accepted size is the cost of one accepted
     * question in generated ones.
     *
     * @param generatedCount every structurally valid question the generator produced, accepted or not
     * @param candidateCount pooled questions the selector chose from; {@code 0} for approaches that
     *                       generate at request time, and the denominator of the selection ratio
     */
    record Quiz(List<JudgedQuestion> accepted, List<JudgedQuestion> rejected, List<CallRecord> calls, int generatedCount, int candidateCount, boolean complete) {

        public Quiz {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
            calls = List.copyOf(calls);
        }
    }
}
