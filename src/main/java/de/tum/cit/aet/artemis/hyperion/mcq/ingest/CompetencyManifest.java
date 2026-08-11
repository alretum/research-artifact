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
import java.util.Optional;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

/**
 * A declared competency structure for a corpus, loaded from YAML.
 * <p>
 * Field names and semantics mirror Artemis's competency model: {@link Competency} corresponds to
 * {@code BaseCompetency} plus {@code CourseCompetency}, {@link Link} to
 * {@code CompetencyLectureUnitLink} and {@code CompetencyExerciseLink}, and {@link Relation} to
 * {@code CompetencyRelation}.
 */
public record CompetencyManifest(Course course, List<Competency> competencies) {

    private static final double DEFAULT_WEIGHT = 1.0;

    /** Bloom's revised taxonomy, matching {@code CompetencyTaxonomy}. */
    public enum Taxonomy {
        REMEMBER, UNDERSTAND, APPLY, ANALYZE, EVALUATE, CREATE
    }

    /** Relation direction is tail to head, matching {@code CompetencyRelation}. */
    public enum RelationType {
        ASSUMES, EXTENDS, MATCHES
    }

    public record Course(String key, String title, String description) {
    }

    /**
     * @param document corpus-relative path of the linked document
     * @param weight   link weight, mirroring {@code CompetencyLearningObjectLink.weight}
     */
    public record Link(String document, double weight) {
    }

    /**
     * @param target key of the competency at the head of the relation
     */
    public record Relation(RelationType type, String target) {
    }

    /**
     * @param key           stable local identifier, referenced by {@link Relation#target}
     * @param query         retrieval query override; when absent, title and description are used
     * @param lectureUnits  documents teaching this competency
     * @param exercises     documents exercising this competency
     */
    public record Competency(String key, String title, String description, String knowledgeArea, Taxonomy taxonomy, boolean optional, String query, List<Link> lectureUnits,
            List<Link> exercises, List<Relation> relations) {

        /**
         * The text used to retrieve material for this competency: the explicit query when given,
         * otherwise the title followed by the description.
         *
         * @return a non-blank retrieval query
         */
        public String retrievalQuery() {
            if (query != null && !query.isBlank()) {
                return query;
            }
            return description == null || description.isBlank() ? title : title + ". " + description.replace("\n", " ").replace("- ", "");
        }

        /**
         * @return corpus-relative paths of every linked document, lecture units before exercises
         */
        public List<String> linkedDocuments() {
            List<String> documents = new ArrayList<>();
            lectureUnits.forEach(link -> documents.add(link.document()));
            exercises.forEach(link -> documents.add(link.document()));
            return List.copyOf(documents);
        }
    }

    /**
     * Load a manifest from a YAML file.
     *
     * @param file path to the manifest
     * @return the parsed manifest
     * @throws UncheckedIOException     if the file cannot be read
     * @throws IllegalArgumentException if the manifest is structurally invalid
     */
    public static CompetencyManifest load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(new Yaml().load(in));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read competency manifest " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static CompetencyManifest parse(Map<String, Object> root) {
        if (root == null) {
            throw new IllegalArgumentException("Competency manifest is empty");
        }
        Map<String, Object> courseNode = (Map<String, Object>) root.get("course");
        Course course = courseNode == null ? new Course("course", "Course", "")
                : new Course(string(courseNode, "key", "course"), string(courseNode, "title", "Course"), string(courseNode, "description", ""));

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) root.get("competencies");
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("Competency manifest declares no competencies");
        }

        List<Competency> competencies = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            Competency competency = parseCompetency(node);
            if (!keys.add(competency.key())) {
                throw new IllegalArgumentException("Duplicate competency key: " + competency.key());
            }
            competencies.add(competency);
        }
        validateRelationTargets(competencies, keys);
        return new CompetencyManifest(course, List.copyOf(competencies));
    }

    @SuppressWarnings("unchecked")
    private static Competency parseCompetency(Map<String, Object> node) {
        String key = string(node, "key", null);
        String title = string(node, "title", null);
        if (key == null || title == null) {
            throw new IllegalArgumentException("Every competency needs a key and a title, got: " + node);
        }
        return new Competency(key, title, string(node, "description", ""), string(node, "knowledgeArea", ""), taxonomy(string(node, "taxonomy", "UNDERSTAND")),
                Boolean.TRUE.equals(node.get("optional")), string(node, "query", null), links((List<Map<String, Object>>) node.get("lectureUnits")),
                links((List<Map<String, Object>>) node.get("exercises")), relations((List<Map<String, Object>>) node.get("relations")));
    }

    private static List<Link> links(List<Map<String, Object>> nodes) {
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream().map(node -> {
            String document = string(node, "document", null);
            if (document == null) {
                throw new IllegalArgumentException("Every link needs a document path, got: " + node);
            }
            Object weight = node.get("weight");
            return new Link(document, weight instanceof Number number ? number.doubleValue() : DEFAULT_WEIGHT);
        }).toList();
    }

    private static List<Relation> relations(List<Map<String, Object>> nodes) {
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream().map(node -> {
            String target = string(node, "target", null);
            String type = string(node, "type", null);
            if (target == null || type == null) {
                throw new IllegalArgumentException("Every relation needs a type and a target, got: " + node);
            }
            return new Relation(relationType(type), target);
        }).toList();
    }

    private static void validateRelationTargets(List<Competency> competencies, Set<String> keys) {
        for (Competency competency : competencies) {
            for (Relation relation : competency.relations()) {
                if (!keys.contains(relation.target())) {
                    throw new IllegalArgumentException("Competency '" + competency.key() + "' has a " + relation.type() + " relation to unknown target '" + relation.target() + "'");
                }
            }
        }
    }

    private static Taxonomy taxonomy(String value) {
        try {
            return Taxonomy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown taxonomy '" + value + "'; expected one of " + List.of(Taxonomy.values()), e);
        }
    }

    private static RelationType relationType(String value) {
        try {
            return RelationType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown relation type '" + value + "'; expected one of " + List.of(RelationType.values()), e);
        }
    }

    private static String string(Map<String, Object> node, String field, String fallback) {
        Object value = node.get(field);
        return value == null ? fallback : value.toString().strip();
    }

    /**
     * Report linked documents that are absent from the corpus.
     *
     * @param knownDocuments document ids present in the corpus
     * @return unresolved links as {@code competencyKey -> document}, empty when all links resolve
     */
    public List<String> unresolvedLinks(Set<String> knownDocuments) {
        List<String> unresolved = new ArrayList<>();
        for (Competency competency : competencies) {
            for (String document : competency.linkedDocuments()) {
                if (!knownDocuments.contains(document)) {
                    unresolved.add(competency.key() + " -> " + document);
                }
            }
        }
        return List.copyOf(unresolved);
    }

    /**
     * @param key competency key
     * @return the competency with that key, if declared
     */
    public Optional<Competency> byKey(String key) {
        return competencies.stream().filter(competency -> competency.key().equals(key)).findFirst();
    }
}
