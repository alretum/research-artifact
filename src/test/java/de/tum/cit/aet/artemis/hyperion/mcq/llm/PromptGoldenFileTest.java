package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.GroundingContext;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.Snippet;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.SourceRole;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;

/**
 * Compares every rendered prompt byte-for-byte against its golden file, so an accidental edit to a
 * template — the experiment's independent variable — fails a test instead of silently changing what every
 * model sees. Run with {@code -DupdateGolden=true} to rewrite the golden files after an intentional change,
 * and commit them together with the template.
 */
class PromptGoldenFileTest {

    private static final Path GOLDEN_DIRECTORY = Path.of("src/test/resources/golden");

    private final PromptTemplateService templates = new PromptTemplateService();

    @Test
    void generateSystemPromptMatchesItsGoldenFile() throws IOException {
        check("/prompts/mcq/mcq_generate_system.st", Map.of());
    }

    @Test
    void generateUserPromptMatchesItsGoldenFile() throws IOException {
        check("/prompts/mcq/mcq_generate_user.st",
                Map.of("groundingBlock", grounding().renderedBlock(), "topic", "Duality", "language", "en", "optionCount", 4, "difficulty", 50, "format", "<format>"));
    }

    @Test
    void filterSystemPromptMatchesItsGoldenFile() throws IOException {
        check("/prompts/mcq/mcq_filter_system.st", Map.of());
    }

    @Test
    void filterUserPromptMatchesItsGoldenFile() throws IOException {
        check("/prompts/mcq/mcq_filter_user.st", withQuestion(Map.of("groundingBlock", grounding().renderedBlock())));
    }

    @Test
    void requestFitSystemPromptMatchesItsGoldenFile() throws IOException {
        check("/prompts/mcq/mcq_filter_request_system.st", Map.of());
    }

    @Test
    void requestFitUserPromptMatchesItsGoldenFile() throws IOException {
        check("/prompts/mcq/mcq_filter_request_user.st", withQuestion(requestVariables()));
    }

    @Test
    void combinedSystemPromptMatchesItsGoldenFile() throws IOException {
        check("/prompts/mcq/mcq_filter_combined_system.st", Map.of());
    }

    @Test
    void combinedUserPromptMatchesItsGoldenFile() throws IOException {
        Map<String, Object> variables = new java.util.HashMap<>(requestVariables());
        variables.put("groundingBlock", grounding().renderedBlock());
        check("/prompts/mcq/mcq_filter_combined_user.st", withQuestion(variables));
    }

    private void check(String templatePath, Map<String, Object> variables) throws IOException {
        String rendered = templates.render(templatePath, variables);
        Path golden = GOLDEN_DIRECTORY.resolve(templatePath.substring(templatePath.lastIndexOf('/') + 1).replace(".st", ".txt"));
        if (Boolean.getBoolean("updateGolden")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, rendered);
            return;
        }
        assertThat(golden).as("golden file for %s; generate it with ./gradlew test -DupdateGolden=true", templatePath).exists();
        assertThat(rendered).isEqualTo(Files.readString(golden));
    }

    private static Map<String, Object> requestVariables() {
        return Map.of("competencies", "Arrays (APPLY)\n- Du kannst Arrays erstellen.\n- Du kannst auf Elemente im Array zugreifen.", "difficulty", 50, "instructions", "(none)");
    }

    private static Map<String, Object> withQuestion(Map<String, Object> variables) {
        Map<String, Object> merged = new java.util.HashMap<>(variables);
        merged.put("questionTitle", "HTTP Methods");
        merged.put("questionText", "Which method is idempotent?");
        merged.put("options", "- [correct] PUT\n- [wrong] POST\n- [wrong] PATCH\n- [wrong] GET");
        merged.put("explanation", "PUT is idempotent.");
        merged.put("format", "<format>");
        return merged;
    }

    private static GroundingContext grounding() {
        List<Snippet> snippets = List.of(new Snippet("05 Linear Programming - Duality", "7 Duality", "Pages 12–14", "The dual of the dual is the primal.", "7-duality.pdf#12-14",
                SourceRole.LECTURE_DECK, 0.91), new Snippet("05 Linear Programming - Duality", "CE 4 Exercises", "Page 3", "Construct the dual of the given program.",
                        "ce4.pdf#3-3", SourceRole.CENTRAL_EXERCISE, 0.84));
        return new GroundingAssemblyService().assemble("Duality", snippets, 6000);
    }
}
