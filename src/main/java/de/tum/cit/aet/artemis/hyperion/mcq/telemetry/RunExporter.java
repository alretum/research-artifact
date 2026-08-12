package de.tum.cit.aet.artemis.hyperion.mcq.telemetry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ItemProvenance;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.RunRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;

import tools.jackson.core.type.TypeReference;

/**
 * Renders a run from the store into the JSONL run log and the readable Markdown file.
 * <p>
 * The store is the source of truth, so both outputs are rewritten from scratch on every export and can be
 * regenerated at any time without re-running the pipeline.
 */
@Service
public class RunExporter {

    private static final Logger log = LoggerFactory.getLogger(RunExporter.class);

    private final RunLogWriter writer;

    public RunExporter(RunLogWriter writer) {
        this.writer = writer;
    }

    /**
     * Export every completed item of a run.
     *
     * @param store    store to read from
     * @param runId    run to export
     * @param runLog   JSONL file to write, replaced if present
     * @param markdown Markdown file to write, replaced if present
     * @return the number of items exported
     * @throws UncheckedIOException if either output cannot be replaced
     */
    public int export(RunStore store, String runId, Path runLog, Path markdown) {
        List<RunStore.CompletedItem> completed = store.completedItems(runId);
        replace(runLog);
        replace(markdown);

        for (RunStore.CompletedItem stored : completed) {
            RunRecord record = new RunRecord(RunRecord.SCHEMA_VERSION, runId, stored.key().configurationId(), read(stored.itemJson(), new TypeReference<McqItem>() {
            }), read(stored.provenanceJson(), new TypeReference<ItemProvenance>() {
            }), read(stored.decisionJson(), new TypeReference<FilterDecision>() {
            }), read(stored.callsJson(), new TypeReference<List<CallRecord>>() {
            }));
            writer.append(runLog, record);
            writer.appendMarkdown(markdown, record);
        }
        log.info("Exported {} items of run {} to {} and {}", completed.size(), runId, runLog, markdown);
        return completed.size();
    }

    private static void replace(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.deleteIfExists(file);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to replace " + file, e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        return StructuredOutputs.outputMapper().readValue(json, type);
    }
}
