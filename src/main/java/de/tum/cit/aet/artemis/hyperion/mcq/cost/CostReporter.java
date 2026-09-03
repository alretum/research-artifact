package de.tum.cit.aet.artemis.hyperion.mcq.cost;

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
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
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
     * Cost totals for one configuration.
     *
     * @param configurationId generator and filter pairing
     * @param items           completed items recorded for this configuration
     * @param accepted        items the filter accepted
     * @param failed          items that exhausted their attempts; they produced nothing but cost money
     * @param cost            cost of every call these items consumed, successes and failures alike
     */
    public record Row(String configurationId, int items, int accepted, int failed, Cost cost) {

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
     * Group every run in the store by configuration and price each group.
     * <p>
     * Reads the store rather than the exported run log for two reasons: the log is replaced on each
     * export, so it only ever holds the most recent run, and it omits items that failed permanently --
     * which consumed calls and therefore money without producing a question.
     *
     * @param store   the run store
     * @param pricing pricing file
     * @return one row per configuration, ordered by configuration id
     */
    public List<Row> tabulate(RunStore store, Path pricing) {
        CostCalculator calculator = new CostCalculator(PricingCatalogue.load(pricing));
        Map<String, List<CallRecord>> callsByConfiguration = new LinkedHashMap<>();
        Map<String, int[]> countsByConfiguration = new LinkedHashMap<>();

        for (String runId : store.runIds()) {
            for (RunStore.CompletedItem item : store.completedItems(runId)) {
                String configurationId = item.key().configurationId();
                callsByConfiguration.computeIfAbsent(configurationId, key -> new ArrayList<>()).addAll(calls(item.callsJson()));
                int[] counts = countsByConfiguration.computeIfAbsent(configurationId, key -> new int[3]);
                counts[0]++;
                if (accepted(item.decisionJson())) {
                    counts[1]++;
                }
            }
            for (RunStore.FailedItem item : store.failedItems(runId)) {
                String configurationId = item.key().configurationId();
                callsByConfiguration.computeIfAbsent(configurationId, key -> new ArrayList<>()).addAll(calls(item.callsJson()));
                countsByConfiguration.computeIfAbsent(configurationId, key -> new int[3])[2]++;
            }
        }

        return countsByConfiguration.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            int[] counts = entry.getValue();
            return new Row(entry.getKey(), counts[0], counts[1], counts[2], calculator.costOf(callsByConfiguration.getOrDefault(entry.getKey(), List.of())));
        }).toList();
    }

    private List<CallRecord> calls(String callsJson) {
        if (callsJson == null || callsJson.isBlank()) {
            return List.of();
        }
        return mapper.readValue(callsJson, new tools.jackson.core.type.TypeReference<List<CallRecord>>() {
        });
    }

    private boolean accepted(String decisionJson) {
        if (decisionJson == null || decisionJson.isBlank()) {
            return false;
        }
        return mapper.readValue(decisionJson, FilterDecision.class).accepted();
    }

    /**
     * Tabulate and log the result.
     *
     * @param store   the run store
     * @param pricing pricing file
     */
    public void report(RunStore store, Path pricing) {
        List<Row> rows = tabulate(store, pricing);
        if (rows.isEmpty()) {
            log.warn("The run store holds no items to price");
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
        StringBuilder out = new StringBuilder("| configuration | items | accepted | failed | accept rate | total EUR | EUR/item | EUR/accepted item |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            out.append("| ").append(row.configurationId()).append(" | ").append(row.items()).append(" | ").append(row.accepted()).append(" | ").append(row.failed()).append(" | ")
                    .append(row.items() == 0 ? "-" : String.format("%.0f%%", 100d * row.accepted() / row.items())).append(" | ")
                .append(band(row.cost().lowEur(), row.cost().highEur()))
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

}
