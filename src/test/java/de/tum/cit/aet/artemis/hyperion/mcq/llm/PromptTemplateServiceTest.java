package de.tum.cit.aet.artemis.hyperion.mcq.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PromptTemplateServiceTest {

    private final PromptTemplateService service = new PromptTemplateService();

    @Test
    void substitutesEveryPlaceholderInTheGenerationTemplate() {
        String rendered = service.render("/prompts/mcq/mcq_generate_user.st",
                Map.of("groundingBlock", "GROUNDING", "topic", "TOPIC", "language", "en", "difficulty", 50, "optionCount", 4, "format", "SCHEMA"));

        assertThat(rendered).contains("GROUNDING", "TOPIC", "SCHEMA").doesNotContain("{{");
    }

    @Test
    void leavesUnmatchedPlaceholdersInPlaceRatherThanBlanking() {
        String rendered = service.render("/prompts/mcq/mcq_generate_user.st", Map.of("topic", "TOPIC"));

        assertThat(rendered).contains("{{groundingBlock}}").contains("TOPIC");
    }

    @Test
    void rendersTheFilterTemplateWithoutLeftoverPlaceholders() {
        String rendered = service.render("/prompts/mcq/mcq_filter_user.st",
                Map.of("groundingBlock", "G", "questionTitle", "T", "questionText", "Q", "options", "O", "explanation", "E", "format", "F"));

        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    void systemPromptsNeedNoVariables() {
        assertThat(service.render("/prompts/mcq/mcq_generate_system.st", Map.of())).doesNotContain("{{");
        assertThat(service.render("/prompts/mcq/mcq_filter_system.st", Map.of())).doesNotContain("{{");
    }

    @Test
    void failsOnAMissingTemplate() {
        assertThatThrownBy(() -> service.render("/prompts/mcq/does-not-exist.st", Map.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to load");
    }
}
