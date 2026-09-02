package de.tum.cit.aet.artemis.hyperion.mcq.telemetry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;

import tools.jackson.core.type.TypeReference;

/**
 * Counts why attempts failed, per stage and per model.
 * <p>
 * Two counts are reported. Attempt records attached to items that eventually succeeded give the retry rate
 * and its causes. Items that exhausted their attempts are read separately from the store, because they carry
 * no question and so never reach the exported run log.
 */
@Service
public class FailureReporter {

    private static final Logger log = LoggerFactory.getLogger(FailureReporter.class);

    /**
     * @param attemptsByCategory failed attempts per category, across items that eventually succeeded
     * @param permanentByCategory items that exhausted their attempts, per final category
     * @param totalCalls          every recorded call of the run, successful or not
     */
    public record Summary(Map<String, Integer> attemptsByCategory, Map<String, Integer> permanentByCategory, int completedItems, int permanentlyFailedItems, int totalCalls) {

        /**
         * @return failed attempts as a share of all calls made
         */
        public double failedCallRate() {
            return totalCalls == 0 ? 0 : (double) attemptsByCategory.values().stream().mapToInt(Integer::intValue).sum() / totalCalls;
        }
    }

    /**
     * Summarise failures of a run.
     *
     * @param store store holding the run
     * @param runId run to summarise
     * @return counts by category from both completed and permanently failed items
     */
    public Summary summarise(RunStore store, String runId) {
        Map<String, Integer> attempts = new LinkedHashMap<>();
        int totalCalls = 0;

        List<RunStore.CompletedItem> completed = store.completedItems(runId);
        for (RunStore.CompletedItem item : completed) {
            for (CallRecord call : calls(item.callsJson())) {
                totalCalls++;
                if (call.failureCategory() != null) {
                    attempts.merge(call.stage() + ":" + call.failureCategory(), 1, Integer::sum);
                }
            }
        }

        Map<String, Integer> permanent = new LinkedHashMap<>();
        List<RunStore.FailedItem> failed = store.failedItems(runId);
        for (RunStore.FailedItem item : failed) {
            permanent.merge(item.state().name() + ":" + item.failure(), 1, Integer::sum);
            for (CallRecord call : calls(item.callsJson())) {
                totalCalls++;
                if (call.failureCategory() != null) {
                    attempts.merge(call.stage() + ":" + call.failureCategory(), 1, Integer::sum);
                }
            }
        }
        return new Summary(Map.copyOf(attempts), Map.copyOf(permanent), completed.size(), failed.size(), totalCalls);
    }

    /**
     * Summarise a run and log the result.
     *
     * @param store store holding the run
     * @param runId run to summarise
     */
    public void report(RunStore store, String runId) {
        Summary summary = summarise(store, runId);
        log.info("Run {}: {} completed, {} permanently failed, {} calls made, {}% of calls needed retrying", runId, summary.completedItems(), summary.permanentlyFailedItems(),
                summary.totalCalls(), String.format("%.1f", summary.failedCallRate() * 100));
        if (summary.attemptsByCategory().isEmpty()) {
            log.info("  no failed attempts recorded");
        }
        else {
            summary.attemptsByCategory().forEach((category, count) -> log.info("  retried: {} x{}", category, count));
        }
        summary.permanentByCategory().forEach((category, count) -> log.info("  gave up: {} x{}", category, count));
    }

    private static List<CallRecord> calls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(StructuredOutputs.outputMapper().readValue(json, new TypeReference<List<CallRecord>>() {
        }));
    }
}
