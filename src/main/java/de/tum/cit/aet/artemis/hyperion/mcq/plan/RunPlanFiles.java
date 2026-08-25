package de.tum.cit.aet.artemis.hyperion.mcq.plan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and removes plan files, so a matrix can be defined without editing YAML by hand.
 * <p>
 * Plans are written as files rather than rows in the store deliberately: they are experiment definitions,
 * so they belong with the configuration a run is reproducible from, and they can be reviewed, diffed and
 * committed alongside the results they produced.
 */
public final class RunPlanFiles {

    private static final Logger log = LoggerFactory.getLogger(RunPlanFiles.class);

    private RunPlanFiles() {
    }

    /**
     * List the plan files in a directory.
     *
     * @param directory the plan directory
     * @return the files, sorted, or empty when the directory does not exist
     */
    public static List<Path> list(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + directory, e);
        }
    }

    /**
     * Write a plan whose configurations are every pairing of the given generators and filters.
     * <p>
     * The cross product is the point: a generator by filter matrix is what makes an accept rate
     * interpretable, because the cells where one model judges another are the only ones not measuring a
     * model's agreement with itself.
     *
     * @param directory     the plan directory, created when absent
     * @param name          plan name, also the basis of the file name
     * @param itemsPerTopic items to generate per topic per configuration
     * @param topics        topics to restrict to; empty means every grounded topic
     * @param generators    catalogue keys of models that write questions
     * @param filters       catalogue keys of models that judge them
     * @return the file written
     * @throws IllegalArgumentException if the input is unusable or a plan of that name already exists
     */
    public static Path create(Path directory, String name, int itemsPerTopic, List<String> topics, List<String> generators, List<String> filters) {
        String slug = slug(name);
        if (slug.isBlank()) {
            throw new IllegalArgumentException("Give the plan a name using letters, digits or dashes");
        }
        if (itemsPerTopic < 1) {
            throw new IllegalArgumentException("Items per topic must be at least 1");
        }
        if (generators == null || generators.isEmpty() || filters == null || filters.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one generator model and one filter model");
        }
        Path file = resolveInside(directory, slug + ".yml");
        if (Files.exists(file)) {
            throw new IllegalArgumentException("A plan named '" + slug + "' already exists. Delete it first or choose another name.");
        }

        List<String> lines = new ArrayList<>();
        lines.add("# Written from the web interface. Cells run one after another.");
        lines.add("plan: " + slug);
        lines.add("items-per-topic: " + itemsPerTopic);
        lines.add(topics.isEmpty() ? "topics: []   # every grounded topic" : "topics: [" + topics.stream().map(RunPlanFiles::quote).reduce((a, b) -> a + ", " + b).orElse("") + "]");
        lines.add("configurations:");
        for (String generator : generators) {
            for (String filter : filters) {
                lines.add("  - id: " + slug(generator) + "-gen-" + slug(filter) + "-filter");
                lines.add("    generator: " + generator);
                lines.add("    filter: " + filter);
            }
        }

        try {
            Files.createDirectories(directory);
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to write plan " + file, e);
        }
        // Parse it back rather than trusting what was written: a plan that cannot be loaded is worse than
        // one that was refused, because it fails later and further from the cause.
        try {
            RunPlan.load(file);
        }
        catch (RuntimeException e) {
            deleteQuietly(file);
            throw new IllegalArgumentException("The plan could not be written in a usable form: " + e.getMessage());
        }
        log.info("Wrote plan {} with {} configuration(s)", file, generators.size() * filters.size());
        return file;
    }

    /**
     * Delete a plan file.
     *
     * @param directory the plan directory
     * @param fileName  name of the file within it
     * @throws IllegalArgumentException if the name escapes the directory or no such plan exists
     */
    public static void delete(Path directory, String fileName) {
        Path file = resolveInside(directory, fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No plan '" + fileName + "'");
        }
        try {
            Files.delete(file);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + file, e);
        }
        log.info("Deleted plan {}", file);
    }

    /**
     * Resolve a file name inside a directory, refusing anything that escapes it.
     *
     * @param directory the directory
     * @param fileName  a plain file name
     * @return the resolved path
     * @throws IllegalArgumentException when the name is not a plain file name inside the directory
     */
    static Path resolveInside(Path directory, String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("Invalid plan file name: " + fileName);
        }
        Path candidate = directory.resolve(fileName).normalize();
        if (!candidate.startsWith(directory.normalize())) {
            throw new IllegalArgumentException("Invalid plan file name: " + fileName);
        }
        return candidate;
    }

    /**
     * @param value free text
     * @return a file- and identifier-safe form: lower case, non-alphanumerics collapsed to dashes
     */
    static String slug(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        }
        catch (IOException ignored) {
            // Nothing useful to do; the caller is already reporting a failure.
        }
    }

    /** @return the plan name a file declares, without failing when it cannot be read */
    public static Optional<String> nameOf(Path file) {
        try {
            return Optional.of(RunPlan.load(file).plan());
        }
        catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
