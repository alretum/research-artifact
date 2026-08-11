package de.tum.cit.aet.artemis.hyperion.mcq.telemetry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.RunRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;

import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-tabulates item quality against the source-role composition of the grounding it was generated
 * from, to show how the presence of worked solutions in the retrieval window relates to the filter's
 * per-mode severities.
 */
@Service
public class CompositionReporter {

    private static final Logger log = LoggerFactory.getLogger(CompositionReporter.class);

    private static final double[] BUCKET_UPPER_BOUNDS = { 0.0, 0.25, 0.5, 1.0 };

    private static final String[] BUCKET_LABELS = { "0%", "1-25%", "26-50%", ">50%" };

    private final JsonMapper mapper = StructuredOutputs.outputMapper();

    /**
     * One row of the cross-tabulation.
     *
     * @param items      number of items whose solution fraction fell in this bucket
     * @param accepted   number of those items the filter accepted
     * @param severities mean severity per failure mode across those items
     */
    public record Bucket(String label, int items, int accepted, Map<FailureMode, Double> severities) {

        public double acceptRate() {
            return items == 0 ? 0 : (double) accepted / items;
        }
    }

    /**
     * Read a run log and cross-tabulate quality against grounding composition.
     *
     * @param runLog newline-delimited JSON run log
     * @return one bucket per solution-fraction band, in ascending order
     * @throws UncheckedIOException if the log cannot be read
     */
    public List<Bucket> tabulate(Path runLog) {
        if (!Files.isRegularFile(runLog)) {
            throw new UncheckedIOException(new IOException("No run log at " + runLog));
        }

        List<RunRecord> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(runLog, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    records.add(mapper.readValue(line, RunRecord.class));
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read run log " + runLog, e);
        }

        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < BUCKET_LABELS.length; i++) {
            buckets.add(bucket(BUCKET_LABELS[i], records, lowerBound(i), BUCKET_UPPER_BOUNDS[i]));
        }
        return List.copyOf(buckets);
    }

    /**
     * Render the cross-tabulation as a Markdown table.
     *
     * @param buckets rows to render
     * @return a Markdown table, one row per bucket
     */
    public String render(List<Bucket> buckets) {
        StringBuilder out = new StringBuilder("| solution fraction | items | accept rate |");
        for (FailureMode mode : FailureMode.values()) {
            out.append(" mean ").append(mode.name()).append(" |");
        }
        out.append("\n|---|---|---|");
        out.append("---|".repeat(FailureMode.values().length));
        out.append('\n');

        for (Bucket bucket : buckets) {
            out.append("| ").append(bucket.label()).append(" | ").append(bucket.items()).append(" | ");
            out.append(bucket.items() == 0 ? "-" : String.format("%.0f%%", bucket.acceptRate() * 100)).append(" |");
            for (FailureMode mode : FailureMode.values()) {
                Double severity = bucket.severities().get(mode);
                out.append(' ').append(severity == null ? "-" : String.format("%.2f", severity)).append(" |");
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Tabulate a run log and log the resulting table.
     *
     * @param runLog newline-delimited JSON run log
     */
    public void report(Path runLog) {
        List<Bucket> buckets = tabulate(runLog);
        int total = buckets.stream().mapToInt(Bucket::items).sum();
        if (total == 0) {
            log.warn("Run log {} holds no items to tabulate", runLog);
            return;
        }
        log.info("Grounding composition vs item quality across {} items:\n{}", total, render(buckets));
        if (total < 40) {
            log.warn("Only {} items: too few for the gradients to mean anything. Interpret after the bulk run.", total);
        }
    }

    private static double lowerBound(int index) {
        return index == 0 ? 0 : BUCKET_UPPER_BOUNDS[index - 1];
    }

    private static Bucket bucket(String label, List<RunRecord> records, double lowerExclusive, double upperInclusive) {
        List<RunRecord> matching = records.stream().filter(record -> inBucket(solutionFraction(record), lowerExclusive, upperInclusive)).toList();
        int accepted = (int) matching.stream().filter(record -> record.filterDecision() != null && record.filterDecision().accepted()).count();

        Map<FailureMode, Double> severities = new LinkedHashMap<>();
        for (FailureMode mode : FailureMode.values()) {
            matching.stream().filter(record -> record.filterDecision() != null).map(record -> record.filterDecision().modeVerdicts().get(mode)).filter(verdict -> verdict != null)
                    .mapToDouble(verdict -> verdict.severity()).average().ifPresent(mean -> severities.put(mode, mean));
        }
        return new Bucket(label, matching.size(), accepted, Map.copyOf(severities));
    }

    private static boolean inBucket(double fraction, double lowerExclusive, double upperInclusive) {
        if (lowerExclusive == 0 && upperInclusive == 0) {
            return fraction == 0;
        }
        return fraction > lowerExclusive && fraction <= upperInclusive;
    }

    private static double solutionFraction(RunRecord record) {
        var composition = record.provenance() == null ? null : record.provenance().groundingComposition();
        return composition == null ? 0 : composition.solutionFraction();
    }
}
