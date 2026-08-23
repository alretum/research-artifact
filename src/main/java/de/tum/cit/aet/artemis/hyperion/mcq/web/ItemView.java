package de.tum.cit.aet.artemis.hyperion.mcq.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FailureMode;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.FilterDecision;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ItemProvenance;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.McqItem;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.ModeVerdict;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;

import tools.jackson.core.type.TypeReference;

/**
 * Turns a stored item into a flat shape the templates can render without deserialising JSON themselves.
 */
@Service
public class ItemView {

    /**
     * @param correct whether this option is the key
     */
    public record Option(int index, String text, boolean correct) {
    }

    /**
     * @param severity  0 when the defect is absent, 1 when severe
     * @param triggered the model's own verdict, recorded but not part of the accept decision
     */
    public record Verdict(String mode, double severity, boolean triggered, String justification) {
    }

    /**
     * @param decision {@code null} when the item has not been judged
     */
    public record Rendered(long id, String runId, String topicKey, int itemIndex, String state, String title, String questionText, String explanation, List<Option> options,
            FilterDecision decision, List<Verdict> verdicts, ItemProvenance provenance, List<CallRecord> calls, String compositionSummary) {

        public boolean judged() {
            return decision != null;
        }
    }

    /**
     * Render a stored item.
     *
     * @param stored the item as held in the store
     * @return a flat view of it
     */
    public Rendered render(RunStore.ItemDetail stored) {
        McqItem item = read(stored.itemJson(), new TypeReference<McqItem>() {
        });
        ItemProvenance provenance = read(stored.provenanceJson(), new TypeReference<ItemProvenance>() {
        });
        FilterDecision decision = stored.decisionJson() == null ? null : read(stored.decisionJson(), new TypeReference<FilterDecision>() {
        });
        List<CallRecord> calls = stored.callsJson() == null ? List.of() : read(stored.callsJson(), new TypeReference<List<CallRecord>>() {
        });

        List<Option> options = new ArrayList<>();
        for (int i = 0; i < item.options().size(); i++) {
            var option = item.options().get(i);
            options.add(new Option(i, option.text(), option.correct()));
        }

        List<Verdict> verdicts = new ArrayList<>();
        if (decision != null) {
            Map<FailureMode, ModeVerdict> modes = decision.modeVerdicts();
            for (FailureMode mode : FailureMode.values()) {
                ModeVerdict verdict = modes.get(mode);
                if (verdict != null) {
                    verdicts.add(new Verdict(mode.name(), verdict.severity(), verdict.triggered(), verdict.justification()));
                }
            }
        }

        String composition = provenance != null && provenance.groundingComposition() != null ? provenance.groundingComposition().describe() : "unknown";
        return new Rendered(stored.id(), stored.key().runId(), stored.key().topicKey(), stored.key().itemIndex(), stored.state().name(), item.title(), item.questionText(),
                item.explanation(), options, decision, verdicts, provenance, calls, composition);
    }

    private static <T> T read(String json, TypeReference<T> type) {
        return StructuredOutputs.outputMapper().readValue(json, type);
    }
}
