package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.util.Map;

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
 * @param topicsFile           optional file of explicit topics, one per line
 * @param competencyManifest   optional competency manifest; takes precedence over topicsFile and folder names
 * @param language             ISO 639-1 language code for generated questions
 * @param difficulty           target difficulty from 0 to 100
 * @param models               model backends, keyed by the name a configuration refers to them by
 */
@Validated
@ConfigurationProperties(prefix = "mcq")
public record PipelineProperties(@NotBlank String corpusPath, @NotBlank String runLogPath, @NotBlank String itemsMarkdownPath, @NotBlank String extractionReportPath,
        @NotBlank String topicReportPath, @NotBlank String retrievalProbePath, String topicsFile, String competencyManifest, @NotBlank String language,
        @Min(0) @Max(100) int difficulty, @NotEmpty Map<String, @Valid Backend> models, @NotNull @Valid Chunking chunking, @NotNull @Valid Retrieval retrieval,
        @NotNull @Valid Generation generation, @NotNull @Valid Filter filter, @NotNull @Valid Batch batch) {

    /**
     * One OpenAI-compatible endpoint and the model served from it.
     *
     * @param baseUrl endpoint root, including the version segment
     * @param apiKey  bearer token; a placeholder where the endpoint ignores it
     * @param model   provider model name, sent with every request
     */
    public record Backend(@NotBlank String baseUrl, @NotBlank String apiKey, @NotBlank String model) {
    }

    /**
     * Page-aligned chunking parameters.
     *
     * @param targetTokens pages are merged until this estimated token count is reached
     * @param maxTokens    a single page at or above this size becomes its own chunk
     * @param indexPath    file the embedded index is cached in, so an unchanged corpus is not re-embedded
     */
    public record Chunking(@Min(1) int targetTokens, @Min(1) int maxTokens, @NotBlank String indexPath) {
    }

    /**
     * Bounds on what retrieval may hand the prompt.
     *
     * @param topK               maximum number of snippets retrieved per question
     * @param maxGroundingTokens upper bound on the assembled grounding block
     */
    public record Retrieval(@Min(1) @Max(64) int topK, @Min(1) int maxGroundingTokens) {
    }

    /**
     * Generation-stage parameters.
     *
     * @param backend     key into {@code models} naming the backend that generates
     * @param temperature sampling temperature
     * @param maxAttempts total attempts per item including the first
     */
    public record Generation(@NotBlank String backend, @DecimalMin("0.0") @DecimalMax("2.0") double temperature, @Min(1) @Max(10) int maxAttempts) {
    }

    /**
     * Filter-stage parameters.
     *
     * @param backend         key into {@code models} naming the backend that filters
     * @param temperature     sampling temperature
     * @param maxAttempts     total attempts per item including the first
     * @param acceptThreshold minimum aggregate score in [0, 1] required to accept an item
     */
    public record Filter(@NotBlank String backend, @DecimalMin("0.0") @DecimalMax("2.0") double temperature, @Min(1) @Max(10) int maxAttempts,
            @DecimalMin("0.0") @DecimalMax("1.0") double acceptThreshold) {
    }

    /**
     * Durable execution parameters.
     *
     * @param databasePath       SQLite file holding durable run and item state
     * @param concurrency        workers claiming items in parallel; 1 keeps per-item timings attributable
     * @param maxOutputAttempts  attempts allowed per stage before an item fails permanently
     */
    public record Batch(@NotBlank String databasePath, @Min(1) @Max(16) int concurrency, @Min(1) @Max(10) int maxOutputAttempts) {
    }

    /**
     * The identifier distinguishing this generator and filter pairing in persisted records.
     * <p>
     * Names backend keys rather than provider model names, so the identifier stays stable when a backend's
     * model is upgraded. The resolved model names are recorded in the run manifest and in every
     * {@code CallRecord}.
     *
     * @return the configuration identifier
     */
    public String configurationId() {
        return generation.backend() + "|" + filter.backend();
    }
}
