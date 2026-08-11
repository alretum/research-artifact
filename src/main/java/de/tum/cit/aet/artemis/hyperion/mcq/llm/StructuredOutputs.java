package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import org.springframework.ai.converter.BeanOutputConverter;

import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds {@link BeanOutputConverter}s that tolerate the JSON deviations language models commonly emit.
 * <p>
 * Models writing mathematical notation produce escapes such as {@code \(} and {@code \[} inside JSON
 * strings, which a strict parser rejects. This mapper accepts any backslash escape, treating the escaped
 * character literally, and tolerates single quotes, trailing commas, unescaped control characters and
 * unknown fields.
 */
public final class StructuredOutputs {

    private static final JsonMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS, JsonReadFeature.ALLOW_SINGLE_QUOTES,
                    JsonReadFeature.ALLOW_TRAILING_COMMA)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private StructuredOutputs() {
    }

    /**
     * Create a converter for the given output type.
     *
     * @param type the class the model output is deserialised into
     * @param <T>  the output type
     * @return a converter backed by the lenient mapper
     */
    public static <T> BeanOutputConverter<T> converterFor(Class<T> type) {
        return new BeanOutputConverter<>(type, LENIENT_MAPPER);
    }

    /**
     * @return a mapper suitable for writing pipeline output as JSON
     */
    public static JsonMapper outputMapper() {
        return JsonMapper.builder().build();
    }
}
