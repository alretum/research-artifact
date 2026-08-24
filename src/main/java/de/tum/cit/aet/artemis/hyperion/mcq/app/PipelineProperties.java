package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the pipeline, bound and validated from the {@code mcq} prefix.
 * <p>
 * Invalid values fail at startup rather than at the point of use.
 *
 * @param corpusPath           directory holding the course material
 * @param runLogPath           newline-delimited JSON file that run records are appended to
 * @param itemsMarkdownPath    Markdown file that a human-readable rendering of each item is appended to
 * @param extractionReportPath CSV describing the state of each source document
 * @param topicReportPath      CSV listing derived topics and whether each has linked material
 * @param retrievalProbePath   CSV written by {@code --retrieval-only}, listing what each topic retrieves
 * @param pricingPath          YAML holding model prices, read only when reporting cost
 * @param topicsFile           optional file of explicit topics, one per line
 * @param competencyManifest   optional competency manifest; takes precedence over topicsFile and folder names
 * @param language             ISO 639-1 language code for generated questions
 * @param difficulty           target difficulties from 0 to 100, walked as a ladder so one run produces a
 *                             range rather than a single level. A single value keeps every item the same.
 */
@Validated
@ConfigurationProperties(prefix = "mcq")
public record PipelineProperties(@NotBlank String corpusPath, @NotBlank String runLogPath, @NotBlank String itemsMarkdownPath, @NotBlank String extractionReportPath,
        @NotBlank String topicReportPath, @NotBlank String retrievalProbePath, @NotBlank String pricingPath, String topicsFile, String competencyManifest, @NotBlank String language,
        @NotEmpty List<@Min(0) @Max(100) Integer> difficulty, @NotNull @Valid Chunking chunking, @NotNull @Valid Retrieval retrieval, @NotNull @Valid Generation generation,
        @NotNull @Valid Filter filter, @NotNull @Valid Batch batch) {

    /**
     * @param targetTokens pages are merged until this estimated token count is reached
     * @param maxTokens    a single page at or above this size becomes its own chunk
     * @param indexPath    file the embedded index is cached in, so an unchanged corpus is not re-embedded
     */
    public record Chunking(@Min(1) int targetTokens, @Min(1) int maxTokens, @NotBlank String indexPath) {
    }

    /**
     * @param topK               maximum number of snippets retrieved per question
     * @param maxGroundingTokens upper bound on the assembled grounding block
     */
    public record Retrieval(@Min(1) @Max(64) int topK, @Min(1) int maxGroundingTokens) {
    }

    /**
     * @param model       provider model name used for generation, sent with each request
     * @param temperature sampling temperature
     * @param maxAttempts total attempts per item including the first
     */
    public record Generation(@NotBlank String model, @DecimalMin("0.0") @DecimalMax("2.0") double temperature, @Min(1) @Max(10) int maxAttempts) {
    }

    /**
     * @param model           provider model name used for filtering, sent with each request
     * @param temperature     sampling temperature
     * @param maxAttempts     total attempts per item including the first
     * @param acceptThreshold minimum aggregate score in [0, 1] required to accept an item
     */
    public record Filter(@NotBlank String model, @DecimalMin("0.0") @DecimalMax("2.0") double temperature, @Min(1) @Max(10) int maxAttempts,
            @DecimalMin("0.0") @DecimalMax("1.0") double acceptThreshold) {
    }

    /**
     * @param databasePath       SQLite file holding durable run and item state
     * @param concurrency        workers claiming items in parallel; 1 keeps per-item timings attributable
     * @param maxOutputAttempts  attempts allowed per stage before an item fails permanently
     */
    public record Batch(@NotBlank String databasePath, @Min(1) @Max(16) int concurrency, @Min(1) @Max(10) int maxOutputAttempts) {
    }

    /**
     * @return the identifier distinguishing this generator and filter pairing in persisted records
     */
    public String configurationId() {
        return generation.model() + "|" + filter.model();
    }
}
