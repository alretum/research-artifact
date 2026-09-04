package de.tum.cit.aet.artemis.hyperion.mcq.web;

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
}
