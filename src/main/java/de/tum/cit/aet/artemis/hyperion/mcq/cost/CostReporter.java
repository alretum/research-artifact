package de.tum.cit.aet.artemis.hyperion.mcq.cost;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.cost.CostCalculator.Cost;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.RunRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;

/**
 * Reports cost per configuration from a run log.
 * <p>
 * Both figures the thesis needs are here: cost per generated item, and cost per <em>accepted</em> item.
 * The second is the one that matters for comparing configurations, because a configuration that generates
 * cheaply but is rejected often is not cheap. Every call an item consumed counts towards it, including
 * failed attempts and the filter call, since all of them were paid for.
 */
@Service
public class CostReporter {

    private static final Logger log = LoggerFactory.getLogger(CostReporter.class);

    private final JsonMapper mapper = StructuredOutputs.outputMapper();

    /**
     * @param configurationId generator and filter pairing
     * @param items           items recorded for this configuration
     * @param accepted        items the filter accepted
     * @param cost            cost of every call these items consumed
     */
    public record Row(String configurationId, int items, int accepted, Cost cost) {

        public double perItemLow() {
            return items == 0 ? 0 : cost.lowEur() / items;
        }

        public double perItemHigh() {
            return items == 0 ? 0 : cost.highEur() / items;
        }

        public double perAcceptedLow() {
            return accepted == 0 ? Double.NaN : cost.lowEur() / accepted;
        }

        public double perAcceptedHigh() {
            return accepted == 0 ? Double.NaN : cost.highEur() / accepted;
        }
    }

    /**
     * Group a run log by configuration and price each group.
     *
     * @param runLog  newline-delimited JSON run log
     * @param pricing pricing file
     * @return one row per configuration, in the order first seen
     */
    public List<Row> tabulate(Path runLog, Path pricing) {
        CostCalculator calculator = new CostCalculator(PricingCatalogue.load(pricing));
        Map<String, List<RunRecord>> byConfiguration = new LinkedHashMap<>();
        for (RunRecord record : read(runLog)) {
            byConfiguration.computeIfAbsent(record.configurationId(), key -> new ArrayList<>()).add(record);
        }

        List<Row> rows = new ArrayList<>();
        byConfiguration.forEach((configurationId, records) -> {
            List<de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord> calls = records.stream().filter(record -> record.calls() != null).flatMap(record -> record.calls().stream())
                    .toList();
            int accepted = (int) records.stream().filter(record -> record.filterDecision() != null && record.filterDecision().accepted()).count();
            rows.add(new Row(configurationId, records.size(), accepted, calculator.costOf(calls)));
        });
        return List.copyOf(rows);
    }

    /**
     * Tabulate and log the result.
     *
     * @param runLog  newline-delimited JSON run log
     * @param pricing pricing file
     */
    public void report(Path runLog, Path pricing) {
        List<Row> rows = tabulate(runLog, pricing);
        if (rows.isEmpty()) {
            log.warn("Run log {} holds no items to price", runLog);
            return;
        }
        log.info("Cost per configuration:\n{}", render(rows));

        Set<String> unpriced = new LinkedHashSet<>();
        rows.forEach(row -> unpriced.addAll(row.cost().unpricedModels()));
        if (!unpriced.isEmpty()) {
            log.warn("No price for {}: their calls are excluded from the figures above, which are therefore understated. Add them to the pricing file.", unpriced);
        }
        if (rows.stream().anyMatch(row -> !row.cost().exact())) {
            log.warn("Figures involving time-billed models are upper bounds derived from client wall-clock, which includes queueing and network time. "
                    + "The band spans the electricity and rental rates in the pricing file, both of which are placeholders until the hardware and tariff are known.");
        }
    }

    /**
     * @param rows rows to render
     * @return a Markdown table
     */
    public String render(List<Row> rows) {
        StringBuilder out = new StringBuilder("| configuration | items | accepted | accept rate | total EUR | EUR/item | EUR/accepted item |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            out.append("| ").append(row.configurationId()).append(" | ").append(row.items()).append(" | ").append(row.accepted()).append(" | ")
                    .append(row.items() == 0 ? "-" : String.format("%.0f%%", 100d * row.accepted() / row.items())).append(" | ").append(band(row.cost().lowEur(), row.cost().highEur()))
                    .append(" | ").append(band(row.perItemLow(), row.perItemHigh())).append(" | ").append(band(row.perAcceptedLow(), row.perAcceptedHigh())).append(" |\n");
        }
        return out.toString();
    }

    private static String band(double low, double high) {
        if (Double.isNaN(low) || Double.isNaN(high)) {
            return "n/a";
        }
        return low == high ? String.format("%.4f", low) : String.format("%.4f-%.4f", low, high);
    }

    private List<RunRecord> read(Path runLog) {
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
        return records;
    }
}
