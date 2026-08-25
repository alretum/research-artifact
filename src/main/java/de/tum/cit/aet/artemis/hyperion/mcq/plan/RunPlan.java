package de.tum.cit.aet.artemis.hyperion.mcq.plan;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

/**
 * A declared set of configurations to run, from {@code config/runs/<name>.yml}.
 * <p>
 * Each configuration is one generator/filter pairing with an explicit id. The id is stamped on every item
 * as {@code configuration_id}, which is what keeps configurations from colliding in the store and lets
 * reports group by them. It is declared rather than derived from the model names so that it stays stable
 * and readable when a model id changes.
 */
public record RunPlan(String plan, int itemsPerTopic, List<String> topics, List<RunConfiguration> configurations) {

    /**
     * @param id        stable identifier, stamped on every item this configuration produces
     * @param generator catalogue key of the model that writes questions
     * @param filter    catalogue key of the model that judges them
     */
    public record RunConfiguration(String id, String generator, String filter) {

        /** @return whether one model both writes and judges, so its accept rate includes self-agreement */
        public boolean isSelfJudging() {
            return generator.equals(filter);
        }
    }

    /**
     * @return every model key this plan references, generator or filter, without duplicates
     */
    public Set<String> referencedModels() {
        Set<String> keys = new LinkedHashSet<>();
        configurations.forEach(configuration -> {
            keys.add(configuration.generator());
            keys.add(configuration.filter());
        });
        return keys;
    }

    /**
     * Check every model this plan names is declared, failing before any work is queued.
     *
     * @param catalogue the model catalogue
     * @throws IllegalArgumentException naming the first unknown model key
     */
    public void validateAgainst(ModelCatalogue catalogue) {
        referencedModels().forEach(catalogue::requireModel);
    }

    /**
     * Load a plan from YAML.
     *
     * @param file the plan file
     * @return the parsed plan
     * @throws IllegalArgumentException if the file is absent or malformed
     * @throws UncheckedIOException     if it cannot be read
     */
    public static RunPlan load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No run plan at " + file.toAbsolutePath());
        }
        try (InputStream in = Files.newInputStream(file)) {
            return parse(new Yaml().load(in));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read run plan " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    static RunPlan parse(Map<String, Object> root) {
        if (root == null) {
            throw new IllegalArgumentException("Run plan is empty");
        }
        String plan = String.valueOf(root.getOrDefault("plan", "unnamed"));
        Object items = root.get("items-per-topic");
        if (!(items instanceof Number number) || number.intValue() < 1) {
            throw new IllegalArgumentException("Run plan '" + plan + "' must set items-per-topic to at least 1");
        }
        List<String> topics = root.get("topics") == null ? List.of() : List.copyOf((List<String>) root.get("topics"));

        List<Map<String, Object>> declared = (List<Map<String, Object>>) root.get("configurations");
        if (declared == null || declared.isEmpty()) {
            throw new IllegalArgumentException("Run plan '" + plan + "' declares no configurations");
        }
        List<RunConfiguration> configurations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> entry : declared) {
            String id = required(entry, "id", plan);
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Run plan '" + plan + "' declares configuration id '" + id + "' twice; ids must be unique because items are keyed by them");
            }
            configurations.add(new RunConfiguration(id, required(entry, "generator", plan), required(entry, "filter", plan)));
        }
        return new RunPlan(plan, number.intValue(), topics, List.copyOf(configurations));
    }

    private static String required(Map<String, Object> entry, String field, String plan) {
        Object value = entry.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Run plan '" + plan + "' has a configuration missing '" + field + "'");
        }
        return String.valueOf(value);
    }
}
