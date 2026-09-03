package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;

/**
 * Reads a request file into {@link GenerationRequest}s.
 * <p>
 * The file is a YAML list of requests. Every entry must carry a unique {@code key}, and an entry naming a
 * field outside the known set is rejected rather than ignored, so a misspelled field fails the run at load
 * time instead of silently binding to a default.
 */
public final class RequestFileReader {

    private static final Set<String> KNOWN_FIELDS = Set.of("key", "course", "topic", "competencies", "optional-prompt", "language", "question-types", "number-of-questions",
            "difficulty");

    private RequestFileReader() {
    }

    /**
     * Reads every request in the given file.
     *
     * @param file the request file
     * @return the requests, in file order
     * @throws UncheckedIOException     if the file cannot be read
     * @throws IllegalArgumentException if the file is not a list, an entry is malformed, or two entries
     *                                  share a key
     */
    public static List<GenerationRequest> read(Path file) {
        Object root;
        try (InputStream in = Files.newInputStream(file)) {
            root = new Yaml().load(in);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read request file " + file, e);
        }
        if (!(root instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalArgumentException("Request file " + file + " must be a non-empty YAML list of requests");
        }

        List<GenerationRequest> requests = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            GenerationRequest request = parse(entries.get(index), index);
            if (!keys.add(request.key())) {
                throw new IllegalArgumentException("Request file " + file + " uses key '" + request.key() + "' more than once");
            }
            requests.add(request);
        }
        return requests;
    }

    private static GenerationRequest parse(Object entry, int index) {
        if (!(entry instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Request at index " + index + " is not a mapping");
        }
        for (Object field : map.keySet()) {
            if (!KNOWN_FIELDS.contains(String.valueOf(field))) {
                throw new IllegalArgumentException("Request at index " + index + " names unknown field '" + field + "', known fields are " + KNOWN_FIELDS);
            }
        }
        String key = text(map, "key");
        try {
            return new GenerationRequest(key, text(map, "course"), text(map, "topic"), texts(map, "competencies"), text(map, "optional-prompt"), language(map),
                    questionTypes(map), number(map), difficulty(map));
        }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Request at index " + index + (key == null ? "" : " ('" + key + "')") + " is invalid: " + e.getMessage(), e);
        }
    }

    private static Language language(Map<?, ?> map) {
        String code = text(map, "language");
        return code == null ? null : Language.fromCode(code);
    }

    private static Difficulty difficulty(Map<?, ?> map) {
        String value = text(map, "difficulty");
        return value == null ? null : Difficulty.fromValue(value);
    }

    private static Set<QuestionType> questionTypes(Map<?, ?> map) {
        List<String> values = texts(map, "question-types");
        if (values == null) {
            return null;
        }
        Set<QuestionType> types = new LinkedHashSet<>();
        for (String value : values) {
            types.add(QuestionType.fromValue(value));
        }
        return types;
    }

    private static int number(Map<?, ?> map) {
        Object value = map.get("number-of-questions");
        if (value instanceof Integer count) {
            return count;
        }
        throw new IllegalArgumentException("field 'number-of-questions' must be an integer, got " + value);
    }

    private static String text(Map<?, ?> map, String field) {
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> texts(Map<?, ?> map, String field) {
        Object value = map.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("field '" + field + "' must be a list, got " + value);
        }
        return list.stream().map(String::valueOf).toList();
    }
}
