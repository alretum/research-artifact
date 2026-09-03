package de.tum.cit.aet.artemis.hyperion.mcq.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;

/**
 * Derives the pool cells for one course: the cross-product of its competencies with every requested
 * language, question type and difficulty.
 */
public final class PoolGrid {

    private PoolGrid() {
    }

    /**
     * Derives every cell of the grid, in a fixed order.
     *
     * @param courseKey    course the cells belong to
     * @param manifest     the course's competencies
     * @param languages    languages to generate in
     * @param types        question types to generate
     * @param difficulties difficulties to generate at
     * @return the cells, ordered by competency, language, type, then difficulty
     * @throws IllegalArgumentException if any dimension is empty
     */
    public static List<PoolCell> derive(String courseKey, CompetencyManifest manifest, Set<Language> languages, Set<QuestionType> types, Set<Difficulty> difficulties) {
        if (languages.isEmpty() || types.isEmpty() || difficulties.isEmpty()) {
            throw new IllegalArgumentException(
                    "Every grid dimension needs at least one value, got languages=" + languages + ", types=" + types + ", difficulties=" + difficulties);
        }
        List<PoolCell> cells = new ArrayList<>();
        for (CompetencyManifest.Competency competency : manifest.competencies()) {
            for (Language language : Language.values()) {
                if (!languages.contains(language)) {
                    continue;
                }
                for (QuestionType type : QuestionType.values()) {
                    if (!types.contains(type)) {
                        continue;
                    }
                    for (Difficulty difficulty : Difficulty.values()) {
                        if (!difficulties.contains(difficulty)) {
                            continue;
                        }
                        cells.add(new PoolCell(courseKey, competency.key(), language, type, difficulty));
                    }
                }
            }
        }
        return List.copyOf(cells);
    }
}
