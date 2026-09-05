package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

/**
 * A declared experiment: which configurations answer which requests, how often, and how the pool behind the
 * two-phase configurations is built.
 * <p>
 * Loaded from {@code config/sweeps/<name>.yml}. Model names are catalogue keys, resolved against
 * {@code config/models.yml} at run time. The sweep name doubles as the run id, so re-running the same file
 * resumes rather than repeats.
 *
 * @param sweep        name of the sweep, used as the run id and in quiz ids
 * @param requestsFile the request file every configuration answers
 * @param repetitions  quizzes per configuration per request
 */
public record SweepPlan(String sweep, String requestsFile, int repetitions, Pool pool, Selection selection, Agentic agentic, List<Configuration> configurations) {

    /**
     * Returns a copy of this plan under a different sweep name, leaving everything else unchanged.
     *
     * @param name the sweep name the copy runs as
     * @return the renamed plan
     */
    public SweepPlan named(String name) {
        return new SweepPlan(name, requestsFile, repetitions, pool, selection, agentic, configurations);
    }

    /** The approaches a configuration can run. */
    public enum Approach {
        AGENTIC, TWO_PHASE
    }

    /**
     * One configuration of the sweep.
     *
     * @param id        stable identifier, stamped on every quiz this configuration produces
     * @param generator catalogue key of the model that writes questions
     * @param judge     catalogue key of the model that filters; for two-phase also the pool judge whose
     *                  acceptance defines the candidate set
     * @param selector  catalogue key of the model that selects from the pool; defaults to the judge
     */
    public record Configuration(String id, Approach approach, String generator, String judge, String selector) {

        /**
         * @return the identifier persisted with every quiz: {@code approach|generator|judge}
         */
        public String configurationId() {
            return approach.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "|" + generator + "|" + judge;
        }
    }

    /**
     * How the pool behind the two-phase configurations is built.
     *
     * @param itemsPerCell  questions a fresh cell is filled with
     * @param subsections   groups a competency's retrieved material is cut into
     * @param retrievalTopM snippets retrieved per competency before partitioning
     * @param languages     pool grid languages
     * @param questionTypes pool grid question types
     * @param difficulties  pool grid difficulties
     */
    public record Pool(int itemsPerCell, int subsections, int retrievalTopM, Set<Language> languages, Set<QuestionType> questionTypes, Set<Difficulty> difficulties) {
    }

    /**
     * How the two-phase configurations select.
     *
     * @param maxCandidates most candidates one selection call may carry
     * @param temperature   selector sampling temperature; non-zero is what makes repetitions differ
     * @param maxAttempts   transport attempts per selection call including the first
     * @param topUpRounds   pool-growth rounds allowed when the pool cannot fill a request; {@code 0}
     *                      forbids request-time generation and returns the quiz incomplete
     */
    public record Selection(int maxCandidates, double temperature, int maxAttempts, int topUpRounds) {
    }

    /**
     * How the agentic configurations loop.
     *
     * @param maxRounds generation rounds allowed before an incomplete quiz is returned
     */
    public record Agentic(int maxRounds) {
    }

    public SweepPlan {
        if (sweep == null || sweep.isBlank()) {
            throw new IllegalArgumentException("A sweep needs a name");
        }
        if (requestsFile == null || requestsFile.isBlank()) {
            throw new IllegalArgumentException("Sweep '" + sweep + "' names no requests file");
        }
        if (repetitions < 1) {
            throw new IllegalArgumentException("Sweep '" + sweep + "' asks for " + repetitions + " repetitions, at least 1 is required");
        }
        if (configurations == null || configurations.isEmpty()) {
            throw new IllegalArgumentException("Sweep '" + sweep + "' declares no configurations");
        }
        configurations = List.copyOf(configurations);
        Set<String> ids = new HashSet<>();
        for (Configuration configuration : configurations) {
            if (!ids.add(configuration.id())) {
                throw new IllegalArgumentException("Sweep '" + sweep + "' uses configuration id '" + configuration.id() + "' more than once");
            }
        }
    }

    /**
     * Loads a sweep plan.
     *
     * @param file the sweep YAML file
     * @return the plan
     * @throws UncheckedIOException     if the file cannot be read
     * @throws IllegalArgumentException if the plan is malformed
     */
    public static SweepPlan load(Path file) {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            root = new Yaml().load(in);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read sweep plan " + file, e);
        }
        if (root == null) {
            throw new IllegalArgumentException("Sweep plan " + file + " is empty");
        }
        return new SweepPlan(string(root, "sweep"), string(root, "requests-file"), integer(root, "repetitions", 3), pool(section(root, "pool")),
                selection(section(root, "selection")), agentic(section(root, "agentic")), parseConfigurations(root, file));
    }

    private static List<Configuration> parseConfigurations(Map<String, Object> root, Path file) {
        if (!(root.get("configurations") instanceof List<?> nodes)) {
            throw new IllegalArgumentException("Sweep plan " + file + " declares no configurations");
        }
        List<Configuration> configurations = new ArrayList<>();
        for (Object node : nodes) {
            if (!(node instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Sweep plan " + file + " holds a configuration that is not a mapping");
            }
            String approach = String.valueOf(map.get("approach"));
            Approach parsed;
            try {
                parsed = Approach.valueOf(approach.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
            }
            catch (IllegalArgumentException _) {
                throw new IllegalArgumentException("Configuration '" + map.get("id") + "' names unknown approach '" + approach + "', expected agentic or two-phase");
            }
            String judge = text(map, "judge");
            String selector = text(map, "selector");
            configurations.add(new Configuration(text(map, "id"), parsed, text(map, "generator"), judge, selector == null ? judge : selector));
        }
        return configurations;
    }

    private static Pool pool(Map<String, Object> node) {
        return new Pool(integer(node, "items-per-cell", 20), integer(node, "subsections", 5), integer(node, "retrieval-top-m", 40), languages(node), types(node),
                difficulties(node));
    }

    private static Selection selection(Map<String, Object> node) {
        return new Selection(integer(node, "max-candidates", 40), decimal(node, "temperature", 0.7), integer(node, "max-attempts", 3), integer(node, "top-up-rounds", 3));
    }

    private static Agentic agentic(Map<String, Object> node) {
        return new Agentic(integer(node, "max-rounds", 5));
    }

    private static Set<Language> languages(Map<String, Object> node) {
        if (!(node.get("languages") instanceof List<?> values)) {
            return EnumSet.allOf(Language.class);
        }
        Set<Language> languages = EnumSet.noneOf(Language.class);
        values.forEach(value -> languages.add(Language.fromCode(String.valueOf(value))));
        return languages;
    }

    private static Set<QuestionType> types(Map<String, Object> node) {
        if (!(node.get("question-types") instanceof List<?> values)) {
            return EnumSet.of(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE);
        }
        Set<QuestionType> types = EnumSet.noneOf(QuestionType.class);
        values.forEach(value -> types.add(QuestionType.fromValue(String.valueOf(value))));
        return types;
    }

    private static Set<Difficulty> difficulties(Map<String, Object> node) {
        if (!(node.get("difficulties") instanceof List<?> values)) {
            return EnumSet.allOf(Difficulty.class);
        }
        Set<Difficulty> difficulties = EnumSet.noneOf(Difficulty.class);
        values.forEach(value -> difficulties.add(Difficulty.fromValue(String.valueOf(value))));
        return difficulties;
    }

    private static Map<String, Object> section(Map<String, Object> root, String name) {
        Object value = root.get(name);
        return value instanceof Map<?, ?> map ? castSection(map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSection(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> map, String field) {
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static String text(Map<?, ?> map, String field) {
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Map<String, Object> map, String field, int fallback) {
        Object value = map.get(field);
        return value instanceof Integer number ? number : fallback;
    }

    private static double decimal(Map<String, Object> map, String field, double fallback) {
        Object value = map.get(field);
        if (value instanceof Double number) {
            return number;
        }
        if (value instanceof Integer number) {
            return number;
        }
        return fallback;
    }
}
