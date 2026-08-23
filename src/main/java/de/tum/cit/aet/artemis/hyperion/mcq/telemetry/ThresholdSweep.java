package de.tum.cit.aet.artemis.hyperion.mcq.telemetry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.RunRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;

/**
 * Recomputes accept rates across a range of thresholds from stored decisions, without issuing model calls.
 * <p>
 * Because the accept rule is {@code 1 - max(severity) >= threshold} and depends on nothing else, every row
 * is derivable from data already on disk. Each threshold also reports which failure mode was the worst
 * offender in the items it rejected, and the accept rate the mean-based aggregation would have produced,
 * which shows how much the choice of aggregation matters.
 */
@Service
public class ThresholdSweep {

    private static final Logger log = LoggerFactory.getLogger(ThresholdSweep.class);

    private static final double STEP = 0.05;

    /**
     * One threshold's outcome.
     *
     * @param accepted           items accepted under {@code 1 - max(severity) >= threshold}
     * @param acceptedUnderMean  items that would be accepted under {@code 1 - mean(severity) >= threshold}
     * @param worstModeCounts    among rejected items, how often each mode was the worst offender
     */
    public record Row(double threshold, int accepted, int acceptedUnderMean, int total, Map<FailureMode, Integer> worstModeCounts) {

        public double acceptRate() {
            return total == 0 ? 0 : (double) accepted / total;
        }

        public double acceptRateUnderMean() {
            return total == 0 ? 0 : (double) acceptedUnderMean / total;
        }
    }

    /**
     * Sweep thresholds from 0 to 1.
     *
     * @param runLog newline-delimited JSON run log
     * @return one row per threshold step, ascending
     * @throws UncheckedIOException if the log cannot be read
     */
    public List<Row> sweep(Path runLog) {
        List<RunRecord> records = read(runLog);
        List<Row> rows = new ArrayList<>();
        for (double threshold = 0; threshold <= 1.0001; threshold += STEP) {
            rows.add(rowFor(threshold, records));
        }
        return List.copyOf(rows);
    }

    private static Row rowFor(double threshold, List<RunRecord> records) {
        int accepted = 0;
        int acceptedUnderMean = 0;
        Map<FailureMode, Integer> worstModes = new EnumMap<>(FailureMode.class);

        for (RunRecord record : records) {
            var decision = record.filterDecision();
            if (decision == null) {
                continue;
            }
            double worst = decision.modeVerdicts().values().stream().mapToDouble(verdict -> verdict.severity()).max().orElse(1);
            double mean = decision.modeVerdicts().values().stream().mapToDouble(verdict -> verdict.severity()).average().orElse(1);
            if (1 - worst >= threshold) {
                accepted++;
            }
            else {
                decision.modeVerdicts().entrySet().stream().filter(entry -> entry.getValue().severity() == worst).findFirst()
                        .ifPresent(entry -> worstModes.merge(entry.getKey(), 1, Integer::sum));
            }
            if (1 - mean >= threshold) {
                acceptedUnderMean++;
            }
        }
        return new Row(threshold, accepted, acceptedUnderMean, (int) records.stream().filter(record -> record.filterDecision() != null).count(), Map.copyOf(worstModes));
    }

    /**
     * Render the sweep as a Markdown table.
     *
     * @param rows rows to render
     * @return a table with one row per threshold
     */
    public String render(List<Row> rows) {
        StringBuilder out = new StringBuilder("| threshold | accepted | accept rate | rate under mean-aggregation | worst offender among rejects |\n|---|---|---|---|---|\n");
        for (Row row : rows) {
            String offenders = row.worstModeCounts().isEmpty() ? "—"
                    : row.worstModeCounts().entrySet().stream().map(entry -> entry.getKey().name() + " ×" + entry.getValue()).reduce((a, b) -> a + ", " + b).orElse("—");
            out.append(String.format("| %.2f | %d/%d | %.0f%% | %.0f%% | %s |%n", row.threshold(), row.accepted(), row.total(), row.acceptRate() * 100,
                    row.acceptRateUnderMean() * 100, offenders));
        }
        return out.toString();
    }

    /**
     * Sweep a run log and log the resulting table.
     *
     * @param runLog newline-delimited JSON run log
     */
    public void report(Path runLog) {
        List<Row> rows = sweep(runLog);
        int total = rows.isEmpty() ? 0 : rows.getFirst().total();
        if (total == 0) {
            log.warn("Run log {} holds no decisions to sweep", runLog);
            return;
        }
        log.info("Threshold sweep over {} decided items, no model calls:\n{}", total, render(rows));
    }

    private static List<RunRecord> read(Path runLog) {
        if (!Files.isRegularFile(runLog)) {
            throw new UncheckedIOException(new IOException("No run log at " + runLog));
        }
        List<RunRecord> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(runLog, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    records.add(StructuredOutputs.outputMapper().readValue(line, RunRecord.class));
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read run log " + runLog, e);
        }
        return records;
    }
}
