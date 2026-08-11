package de.tum.cit.aet.artemis.hyperion.mcq.telemetry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.RunRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;

import tools.jackson.databind.json.JsonMapper;

/**
 * Appends run records to a newline-delimited JSON file.
 * <p>
 * Records are appended and never rewritten, so a partially completed run leaves valid output.
 */
@Service
public class RunLogWriter {

    private static final Logger log = LoggerFactory.getLogger(RunLogWriter.class);

    private final JsonMapper mapper = StructuredOutputs.outputMapper();

    /**
     * Append one record.
     *
     * @param file   target file; parent directories and the file itself are created if absent
     * @param record record to append
     * @throws UncheckedIOException if the record cannot be serialised or written
     */
    public void append(Path file, RunRecord record) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to append run record to " + file, e);
        }
    }

    /**
     * Append a human-readable rendering of a record.
     *
     * @param file   target Markdown file, created if absent
     * @param record record to render
     * @throws UncheckedIOException if the file cannot be written
     */
    public void appendMarkdown(Path file, RunRecord record) {
        var item = record.item();
        var provenance = record.provenance();
        var decision = record.filterDecision();

        StringBuilder out = new StringBuilder();
        out.append("## ").append(item.title()).append('\n');
        out.append("`").append(record.configurationId()).append("` · topic: ").append(provenance.topic()).append(" · ");
        out.append(decision == null ? "**filter failed**" : (decision.accepted() ? "**ACCEPTED**" : "**REJECTED**") + String.format(" (score %.2f)", decision.aggregateScore()));
        out.append("\n\n").append(item.questionText()).append("\n\n");
        for (var option : item.options()) {
            out.append(option.correct() ? "- **[correct]** " : "- [ ] ").append(option.text()).append('\n');
        }
        if (item.explanation() != null) {
            out.append("\n_").append(item.explanation()).append("_\n");
        }
        if (decision != null) {
            out.append("\n| failure mode | severity | triggered | justification |\n|---|---|---|---|\n");
            decision.modeVerdicts().forEach((mode, verdict) -> out.append("| ").append(mode).append(" | ").append(String.format("%.1f", verdict.severity())).append(" | ")
                    .append(verdict.triggered() ? "yes" : "no").append(" | ").append(verdict.justification() == null ? "" : verdict.justification().replace('|', '/'))
                    .append(" |\n"));
        }
        var composition = provenance.groundingComposition();
        out.append("\nGrounding: ").append(provenance.groundingChunkIds().size()).append(" chunks");
        if (composition != null) {
            out.append(" — ").append(composition.describe());
        }
        out.append("\n\nSources: ").append(String.join(", ", provenance.groundingChunkIds())).append('\n');
        out.append("\n---\n\n");

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to append Markdown to " + file, e);
        }
    }

    /**
     * Log a compact human-readable summary of a record.
     *
     * @param record record to summarise
     */
    public void logSummary(RunRecord record) {
        var item = record.item();
        var decision = record.filterDecision();
        log.info("[{}] {} | accepted={} score={} | {}", record.configurationId(), item.title(), decision != null && decision.accepted(),
                decision == null ? "n/a" : String.format("%.2f", decision.aggregateScore()), item.questionText());
    }
}
