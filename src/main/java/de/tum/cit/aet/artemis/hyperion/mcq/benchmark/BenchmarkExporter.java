package de.tum.cit.aet.artemis.hyperion.mcq.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkQuiz.BenchmarkQuestion;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.AnswerOption;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ItemProvenance;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.CompletedItem;

/**
 * Writes generated items as benchmark input, so quality is measured by an independent tool rather than by
 * this pipeline's own filter.
 * <p>
 * That independence is the point. The filter is part of the system under test and currently runs on the
 * same model as the generator, so its accept rate partly measures self-agreement. The benchmark brings its
 * own evaluator models and can use several at once, which is the only way to get a quality figure that is
 * not scored by the thing being judged.
 */
@Service
public class BenchmarkExporter {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkExporter.class);

    private final JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final JsonMapper reader = StructuredOutputs.outputMapper();

    /**
     * What becomes one quiz file.
     * <p>
     * The benchmark's quiz-level metrics -- coverage and homogeneous options -- compare a whole quiz
     * against its source material, so a quiz spanning every topic scores badly for reasons that say
     * nothing about question quality. The per-topic groupings keep one quiz to one lecture; the coarser
     * ones are for comparing configurations, where those two metrics should be disabled.
     */
    public enum Granularity {

        /** One quiz per topic, pooling every configuration. */
        TOPIC,

        /** One quiz per configuration and topic. The default: cells stay comparable and coverage stays meaningful. */
        CONFIGURATION_TOPIC,

        /** One quiz per configuration, spanning every topic. */
        CONFIGURATION,

        /** One quiz per run. */
        RUN;

        /**
         * @param value a command-line value such as {@code configuration-topic}
         * @return the matching granularity
         * @throws IllegalArgumentException listing the accepted values when none matches
         */
        public static Granularity parse(String value) {
            String normalised = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Granularity granularity : values()) {
                if (granularity.name().equals(normalised)) {
                    return granularity;
                }
            }
            throw new IllegalArgumentException("Unknown granularity '" + value + "'. Accepted: topic, configuration-topic, configuration, run");
        }
    }

    /**
     * Which items a quiz file holds.
     * <p>
     * Every question carries {@code accepted} in its metadata regardless, which is enough to split the
     * nine question-level metrics afterwards, since each of their scores names a question. It is not
     * enough for the two quiz-level metrics, whose single score would describe a mixture of both
     * conditions; {@link #SPLIT} exists for those.
     */
    public enum Condition {

        /** Every completed item in one file. */
        ALL,

        /** Only items the filter accepted. */
        ACCEPTED,

        /** Only items the filter rejected. */
        REJECTED,

        /** Both an accepted-only and a rejected-only file per group, so quiz-level metrics are interpretable. */
        SPLIT;

        /**
         * @param value a command-line value
         * @return the matching condition
         * @throws IllegalArgumentException listing the accepted values when none matches
         */
        public static Condition parse(String value) {
            String normalised = value.trim().toUpperCase(Locale.ROOT);
            for (Condition condition : values()) {
                if (condition.name().equals(normalised)) {
                    return condition;
                }
            }
            throw new IllegalArgumentException("Unknown condition '" + value + "'. Accepted: all, accepted, rejected, split");
        }
    }

    /**
     * Export every completed item in the store.
     *
     * @param store       the run store
     * @param directory   directory to write quiz files into, created if absent
     * @param granularity what becomes one quiz file
     * @param condition   which items each file holds
     * @return the files written
     */
    public List<Path> export(RunStore store, Path directory, Granularity granularity, Condition condition) {
        List<Loaded> loaded = new ArrayList<>();
        for (String runId : store.runIds()) {
            for (CompletedItem completed : store.completedItems(runId)) {
                Loaded item = load(runId, completed);
                if (item != null) {
                    loaded.add(item);
                }
            }
        }
        if (loaded.isEmpty()) {
            log.warn("No completed items in the store; nothing to export");
            return List.of();
        }

        try {
            Files.createDirectories(directory);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to create export directory " + directory, e);
        }

        Map<String, List<Loaded>> groups = new LinkedHashMap<>();
        loaded.forEach(item -> groups.computeIfAbsent(groupKey(item, granularity), key -> new ArrayList<>()).add(item));

        List<Path> written = new ArrayList<>();
        groups.forEach((key, items) -> {
            switch (condition) {
                case ALL -> written.add(write(directory, key, items, granularity, "all"));
                case ACCEPTED -> written.add(write(directory, key, filter(items, true), granularity, "accepted"));
                case REJECTED -> written.add(write(directory, key, filter(items, false), granularity, "rejected"));
                case SPLIT -> {
                    written.add(write(directory, key, filter(items, true), granularity, "accepted"));
                    written.add(write(directory, key, filter(items, false), granularity, "rejected"));
                }
            }
        });
        written.removeIf(java.util.Objects::isNull);

        log.info("Exported {} item(s) to {} quiz file(s) in {} (granularity {}, condition {})", loaded.size(), written.size(), directory, granularity.name().toLowerCase(Locale.ROOT),
                condition.name().toLowerCase(Locale.ROOT));
        if (granularity == Granularity.CONFIGURATION || granularity == Granularity.RUN) {
            log.warn("At this granularity a quiz spans several topics, so disable the coverage and homogeneous_options metrics: "
                    + "they compare a whole quiz against its source material and would score a multi-topic quiz unfairly.");
        }
        if (condition == Condition.ALL) {
            log.info("Every question carries 'accepted' in its metadata, so question-level metrics can be split by decision afterwards. "
                    + "Use --export-condition=split if you also need the two quiz-level metrics per condition.");
        }
        return List.copyOf(written);
    }

    private record Loaded(String runId, String configurationId, String topic, McqItem item, ItemProvenance provenance, FilterDecision decision) {

        boolean accepted() {
            return decision != null && decision.accepted();
        }
    }

    private Loaded load(String runId, CompletedItem completed) {
        if (completed.itemJson() == null || completed.itemJson().isBlank()) {
            return null;
        }
        McqItem item = reader.readValue(completed.itemJson(), McqItem.class);
        ItemProvenance provenance = completed.provenanceJson() == null ? null : reader.readValue(completed.provenanceJson(), ItemProvenance.class);
        FilterDecision decision = completed.decisionJson() == null ? null : reader.readValue(completed.decisionJson(), FilterDecision.class);
        return new Loaded(runId, completed.key().configurationId(), completed.key().topicKey(), item, provenance, decision);
    }

    private static List<Loaded> filter(List<Loaded> items, boolean accepted) {
        return items.stream().filter(item -> item.accepted() == accepted).toList();
    }

    private static String groupKey(Loaded item, Granularity granularity) {
        return switch (granularity) {
            case TOPIC -> item.topic();
            case CONFIGURATION_TOPIC -> item.configurationId() + "__" + item.topic();
            case CONFIGURATION -> item.configurationId();
            case RUN -> item.runId();
        };
    }

    private Path write(Path directory, String key, List<Loaded> items, Granularity granularity, String conditionLabel) {
        if (items.isEmpty()) {
            return null;
        }
        String quizId = slug(key) + "__" + conditionLabel;
        List<BenchmarkQuestion> questions = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            questions.add(question(items.get(index), "q" + (index + 1)));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("granularity", granularity.name().toLowerCase(Locale.ROOT));
        metadata.put("condition", conditionLabel);
        metadata.put("configuration_ids", distinct(items, Loaded::configurationId));
        metadata.put("run_ids", distinct(items, Loaded::runId));
        metadata.put("topics", distinct(items, Loaded::topic));
        metadata.put("item_count", items.size());

        BenchmarkQuiz quiz = new BenchmarkQuiz(quizId, key + " (" + conditionLabel + ")", sourceMaterial(items), null, metadata, questions);
        Path file = directory.resolve(quizId + ".json");
        try {
            Files.writeString(file, mapper.writeValueAsString(quiz), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to write quiz " + file, e);
        }
        return file;
    }

    private static BenchmarkQuestion question(Loaded loaded, String questionId) {
        McqItem item = loaded.item();
        List<String> options = item.options().stream().map(option -> option.text().strip()).toList();
        List<String> correct = item.options().stream().filter(AnswerOption::correct).map(option -> option.text().strip()).toList();
        // Single choice is what this pipeline produces, but the type is derived rather than assumed so a
        // multi-correct item would be exported honestly instead of silently losing an answer.
        boolean single = correct.size() == 1;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("topic", loaded.topic());
        metadata.put("configuration_id", loaded.configurationId());
        metadata.put("run_id", loaded.runId());
        metadata.put("accepted", loaded.accepted());
        if (loaded.provenance() != null) {
            ItemProvenance provenance = loaded.provenance();
            metadata.put("requested_difficulty", provenance.requestedDifficulty());
            metadata.put("difficulty_level", difficultyBand(provenance.requestedDifficulty()));
            metadata.put("generator_model", provenance.generatorModel());
            metadata.put("filter_model", provenance.filterModel());
            metadata.put("damage_suspected", provenance.damageSuspectedInItem());
            if (provenance.groundingComposition() != null) {
                metadata.put("solution_fraction", provenance.groundingComposition().solutionFraction());
            }
        }
        if (loaded.decision() != null) {
            FilterDecision decision = loaded.decision();
            metadata.put("filter_aggregate_score", decision.aggregateScore());
            metadata.put("filter_mean_severity", decision.meanSeverity());
            Map<String, Object> severities = new LinkedHashMap<>();
            decision.modeVerdicts().forEach((mode, verdict) -> severities.put(mode.name().toLowerCase(Locale.ROOT), verdict.severity()));
            metadata.put("filter_severities", severities);
        }

        return new BenchmarkQuestion(questionId, single ? BenchmarkQuiz.SINGLE_CHOICE : BenchmarkQuiz.MULTIPLE_CHOICE, item.questionText().strip(), options,
                single ? correct.getFirst() : correct, sourceReference(loaded), metadata);
    }

    /**
     * @return the lecture directory the grounding came from when the whole quiz shares one, otherwise
     *         {@code "."} so the benchmark reads the entire source directory
     */
    private static String sourceMaterial(List<Loaded> items) {
        Set<String> lectures = new LinkedHashSet<>();
        for (Loaded item : items) {
            if (item.provenance() == null || item.provenance().groundingChunkIds() == null) {
                continue;
            }
            item.provenance().groundingChunkIds().stream().map(BenchmarkExporter::lectureOf).filter(lecture -> !lecture.isBlank()).forEach(lectures::add);
        }
        return lectures.size() == 1 ? lectures.iterator().next() : ".";
    }

    private static String lectureOf(String chunkId) {
        int slash = chunkId.indexOf('/');
        return slash < 0 ? "" : chunkId.substring(0, slash);
    }

    private static String sourceReference(Loaded loaded) {
        if (loaded.provenance() == null || loaded.provenance().groundingChunkIds() == null || loaded.provenance().groundingChunkIds().isEmpty()) {
            return null;
        }
        return String.join("; ", loaded.provenance().groundingChunkIds());
    }

    /**
     * @param difficulty requested difficulty from 0 to 100
     * @return a band, because the benchmark's own examples use words rather than numbers; the number is
     *         carried alongside it so nothing is lost
     */
    private static String difficultyBand(int difficulty) {
        if (difficulty < 30) {
            return "easy";
        }
        return difficulty <= 70 ? "medium" : "hard";
    }

    private static List<String> distinct(List<Loaded> items, java.util.function.Function<Loaded, String> field) {
        return items.stream().map(field).distinct().sorted().toList();
    }

    private static String slug(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
    }

    /** @return the failure modes carried in question metadata, for documentation of the export shape */
    public static List<String> exportedSeverityKeys() {
        return java.util.Arrays.stream(FailureMode.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT)).toList();
    }
}
