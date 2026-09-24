package de.tum.cit.aet.artemis.hyperion.mcq.batch;

import java.util.ArrayList;
import java.util.HashSet;
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
        return derive(courseKey, manifest, languages, types, difficulties, Set.of());
    }

    /**
     * Derives the cells of the grid for a subset of the course's competencies, in a fixed order.
     *
     * @param courseKey      course the cells belong to
     * @param manifest       the course's competencies
     * @param languages      languages to generate in
     * @param types          question types to generate
     * @param difficulties   difficulties to generate at
     * @param competencyKeys keys of the competencies to cover; empty covers every competency of the manifest
     * @return the cells, ordered by competency, language, type, then difficulty
     * @throws IllegalArgumentException if any dimension is empty or a key names no competency of the manifest
     */
    public static List<PoolCell> derive(String courseKey, CompetencyManifest manifest, Set<Language> languages, Set<QuestionType> types, Set<Difficulty> difficulties,
            Set<String> competencyKeys) {
        if (languages.isEmpty() || types.isEmpty() || difficulties.isEmpty()) {
            throw new IllegalArgumentException(
                    "Every grid dimension needs at least one value, got languages=" + languages + ", types=" + types + ", difficulties=" + difficulties);
        }
        List<CompetencyManifest.Competency> competencies = manifest.competencies();
        if (!competencyKeys.isEmpty()) {
            Set<String> declared = new HashSet<>();
            competencies.forEach(competency -> declared.add(competency.key()));
            for (String key : competencyKeys) {
                if (!declared.contains(key)) {
                    throw new IllegalArgumentException("Competency '" + key + "' is requested but the course model of " + courseKey + " does not declare it");
                }
            }
            competencies = competencies.stream().filter(competency -> competencyKeys.contains(competency.key())).toList();
        }
        List<PoolCell> cells = new ArrayList<>();
        for (CompetencyManifest.Competency competency : competencies) {
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
