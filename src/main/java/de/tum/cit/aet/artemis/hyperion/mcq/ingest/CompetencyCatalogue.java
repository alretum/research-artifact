package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Competency;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Course;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest.Taxonomy;

/**
 * Reads a course competency catalogue into a {@link CompetencyManifest}.
 * <p>
 * A catalogue is a JSON file of the shape {@code {course, name, language, sources, competencies}}, where
 * each competency carries {@code title}, {@code taxonomy} and a {@code description} list of learning
 * objectives. Competency keys are derived from the titles, so titles must be unique within a catalogue.
 * The resulting manifest carries no document links; grounding for a catalogue-backed course comes from
 * retrieval over the course's corpus.
 */
public final class CompetencyCatalogue {

    private CompetencyCatalogue() {
    }

    /**
     * Reads one catalogue file.
     *
     * @param file the catalogue JSON file
     * @return the manifest, competencies in file order
     * @throws UncheckedIOException     if the file cannot be read
     * @throws IllegalArgumentException if the catalogue is empty, a competency is malformed, or two
     *                                  competencies share a title
     */
    public static CompetencyManifest load(Path file) {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            root = new Yaml().load(in);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read competency catalogue " + file, e);
        }
        if (root == null) {
            throw new IllegalArgumentException("Competency catalogue " + file + " is empty");
        }

        String courseKey = string(root, "course");
        if (courseKey == null || courseKey.isBlank()) {
            throw new IllegalArgumentException("Competency catalogue " + file + " names no course");
        }
        Course course = new Course(courseKey, string(root, "name") == null ? courseKey : string(root, "name"), "");

        if (!(root.get("competencies") instanceof List<?> nodes) || nodes.isEmpty()) {
            throw new IllegalArgumentException("Competency catalogue " + file + " declares no competencies");
        }

        List<Competency> competencies = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Object node : nodes) {
            Competency competency = parse(node, courseKey);
            if (!keys.add(competency.key())) {
                throw new IllegalArgumentException("Catalogue " + courseKey + " derives key '" + competency.key() + "' from more than one title");
            }
            competencies.add(competency);
        }
        return new CompetencyManifest(course, List.copyOf(competencies));
    }

    private static Competency parse(Object node, String courseKey) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Catalogue " + courseKey + " holds a competency entry that is not a mapping");
        }
        Object title = map.get("title");
        if (title == null || String.valueOf(title).isBlank()) {
            throw new IllegalArgumentException("Catalogue " + courseKey + " holds a competency without a title");
        }
        Taxonomy taxonomy;
        try {
            taxonomy = Taxonomy.valueOf(String.valueOf(map.get("taxonomy")));
        }
        catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("Competency '" + title + "' in catalogue " + courseKey + " has unknown taxonomy '" + map.get("taxonomy") + "'");
        }
        return new Competency(slug(String.valueOf(title)), String.valueOf(title), description(map), null, taxonomy, false, null, List.of(), List.of(), List.of());
    }

    private static String description(Map<?, ?> map) {
        if (!(map.get("description") instanceof List<?> bullets)) {
            return "";
        }
        return String.join("\n", bullets.stream().map(String::valueOf).toList());
    }

    private static String slug(String title) {
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("Title '" + title + "' yields an empty key");
        }
        return slug;
    }

    private static String string(Map<String, Object> map, String field) {
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }
}
