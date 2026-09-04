package de.tum.cit.aet.artemis.hyperion.mcq.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.JudgedQuestion;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.StoredQuiz;

/**
 * Turns a stored quiz into a flat shape the templates can render without deserialising JSON themselves.
 */
@Service
public class QuizView {

    private final JsonMapper mapper = StructuredOutputs.outputMapper();

    /**
     * One row of the quiz list.
     */
    public record Summary(String quizId, String configurationId, String courseKey, String requestKey, int repetition, boolean complete, int questionCount) {
    }

    /**
     * One quiz with everything a detail view needs.
     */
    public record Rendered(String quizId, String runId, String configurationId, String courseKey, String requestKey, int repetition, boolean complete,
            List<Question> questions) {
    }

    /**
     * One question of a rendered quiz.
     *
     * @param decision {@code null} when the question carries no filter decision
     */
    public record Question(String title, String questionText, String explanation, List<AnswerOption> options, FilterDecision decision) {

        /**
         * Whether the question text spans several lines, so a template can render it in a
         * layout-preserving font.
         */
        public boolean multiline() {
            return questionText != null && questionText.contains("\n");
        }
    }

    /**
     * One request-time generation, accepted into its quiz or rejected by the judge on the way.
     *
     * @param decision {@code null} when the question carries no filter decision
     */
    public record Generation(String quizId, String configurationId, String requestKey, int repetition, String title, boolean accepted, FilterDecision decision) {
    }

    /**
     * Summarises a stored quiz for the list view.
     *
     * @param stored the stored quiz
     * @return the summary row
     */
    public Summary summarise(StoredQuiz stored) {
        return new Summary(stored.quizId(), stored.configurationId(), stored.courseKey(), stored.requestKey(), stored.repetition(), stored.complete(),
                questions(stored).size());
    }

    /**
     * Flattens a stored quiz into one row per generated question, accepted ones first.
     * <p>
     * Quizzes stored before rejected questions were recorded contribute their accepted questions only.
     *
     * @param stored the stored quiz
     * @return one row per question
     */
    public List<Generation> generations(StoredQuiz stored) {
        List<Generation> rows = new ArrayList<>();
        for (JudgedQuestion question : questions(stored)) {
            rows.add(new Generation(stored.quizId(), stored.configurationId(), stored.requestKey(), stored.repetition(), question.item().title(), true, question.decision()));
        }
        for (JudgedQuestion question : rejected(stored)) {
            rows.add(new Generation(stored.quizId(), stored.configurationId(), stored.requestKey(), stored.repetition(), question.item().title(), false, question.decision()));
        }
        return rows;
    }

    /**
     * Renders a stored quiz for the detail view.
     *
     * @param stored the stored quiz
     * @return the rendered quiz
     */
    public Rendered render(StoredQuiz stored) {
        List<Question> questions = questions(stored).stream()
                .map(question -> new Question(question.item().title(), question.item().questionText(), question.item().explanation(), question.item().options(),
                        question.decision()))
                .toList();
        return new Rendered(stored.quizId(), stored.runId(), stored.configurationId(), stored.courseKey(), stored.requestKey(), stored.repetition(), stored.complete(),
                questions);
    }

    private List<JudgedQuestion> questions(StoredQuiz stored) {
        return mapper.readValue(stored.quizJson(), new TypeReference<List<JudgedQuestion>>() {
        });
    }

    private List<JudgedQuestion> rejected(StoredQuiz stored) {
        if (stored.rejectedJson() == null || stored.rejectedJson().isBlank()) {
            return List.of();
        }
        return mapper.readValue(stored.rejectedJson(), new TypeReference<List<JudgedQuestion>>() {
        });
    }
}
