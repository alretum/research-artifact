package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Renders {@code {{placeholder}}} templates from the classpath.
 * <p>
 * Copied from Artemis's {@code HyperionPromptTemplateService} (the only code borrowed from Artemis,
 * BUILD.md §0) so prompt files move between the two projects unchanged.
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * Render the template at the given classpath location.
     *
     * @param resourcePath classpath location of the template, e.g. {@code /prompts/mcq/x.st}
     * @param variables    values substituted for {@code {{key}}} placeholders
     * @return the rendered template
     */
    public String render(String resourcePath, Map<String, ?> variables) {
        String template = templateCache.computeIfAbsent(resourcePath, path -> {
            try {
                return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                throw new IllegalStateException("Failed to load prompt template " + path, e);
            }
        });

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = variables.get(key);
            if (value == null) {
                log.warn("Template placeholder '{{{}}}' has no value in '{}'", key, resourcePath);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value != null ? value.toString() : matcher.group(0)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
