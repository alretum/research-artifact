package de.tum.cit.aet.artemis.hyperion.mcq.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusLoader.DocumentReport;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.TopicCatalogue.Topic;

/**
 * Writes the corpus extraction diagnostics as CSV.
 * <p>
 * The report describes the state of the source material and is intended for whoever owns the course
 * content. Nothing in the pipeline reads it.
 */
@Service
public class ExtractionReportWriter {

    private static final String HEADER = "documentId,role,pages,textPoorPages,chars,estimatedTokens,suspectedDamagedTokens,altTextLines,detectedLanguage";

    /**
     * Write one row per document, replacing any existing file.
     *
     * @param file    target CSV file; parent directories are created if absent
     * @param reports per-document reports
     * @throws UncheckedIOException if the file cannot be written
     */
    public void write(Path file, List<DocumentReport> reports) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (DocumentReport report : reports) {
            csv.append(quote(report.documentId())).append(',').append(report.role()).append(',').append(report.pages()).append(',').append(report.textPoorPages()).append(',')
                    .append(report.chars()).append(',').append(report.approxTokens()).append(',').append(report.suspectedDamagedTokens()).append(',').append(report.altTextLines())
                    .append(',').append(report.detectedLanguage()).append('\n');
        }
        write(file, csv.toString());
    }

    /**
     * Write the topic inventory, naming any topic that has no material of its own.
     *
     * @param file   target CSV file
     * @param topics topics derived from the corpus
     * @throws UncheckedIOException if the file cannot be written
     */
    public void writeTopics(Path file, List<Topic> topics) {
        StringBuilder csv = new StringBuilder("topic,query,chunks,grounded\n");
        for (Topic topic : topics) {
            csv.append(quote(topic.key())).append(',').append(quote(topic.query())).append(',').append(topic.chunkCount()).append(',').append(topic.grounded()).append('\n');
        }
        write(file, csv.toString());
    }

    private static void write(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to write report to " + file, e);
        }
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
