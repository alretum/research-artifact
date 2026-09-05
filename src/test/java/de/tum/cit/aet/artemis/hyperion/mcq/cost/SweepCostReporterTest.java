package de.tum.cit.aet.artemis.hyperion.mcq.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.app.SweepPlan;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Difficulty;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Language;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.QuestionType;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.ItemState;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.ItemKey;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.PoolItem;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.StoredQuiz;

class SweepCostReporterTest {

    @TempDir
    private Path directory;

    private RunStore store;

    private Path pricing;

    private final JsonMapper mapper = StructuredOutputs.outputMapper();

    private final SweepCostReporter reporter = new SweepCostReporter();

    @BeforeEach
    void setUp() throws IOException {
        store = new RunStore(directory.resolve("run.db"));
        pricing = directory.resolve("pricing.yml");
        Files.writeString(pricing, """
                models:
                  "cloud-model":
                    billing: tokens
                    input-eur-per-million: 1.0
                    output-eur-per-million: 10.0
                  "local-model":
                    billing: gpu-time
                gpu:
                  rental-eur-per-hour: 3.6
                  electricity-eur-per-hour: 0.36
                """);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void report_pricesEachConfigurationFromItsQuizCalls() throws IOException {
        store.registerRun("s1", "sweep", "m");
        // agentic: 1M prompt + 100k completion cloud tokens -> 1.0 + 1.0 = 2.00 EUR
        saveQuiz("agentic|cloud|cloud", "q1", List.of(call("cloud-model", 1_000_000, 100_000, 0)));
        // two-phase: selection only, 100k prompt + 10k completion -> 0.1 + 0.1 = 0.20 EUR
        saveQuiz("two-phase|local|cloud", "q2", List.of(call("cloud-model", 100_000, 10_000, 0)));

        SweepCostReporter.Report report = reporter.report(store, plan(), Map.of("cloud", "cloud-model", "local", "local-model"), pricing);

        assertThat(report.configurations()).hasSize(2);
        SweepCostReporter.ConfigurationCost agentic = byId(report, "agentic|cloud|cloud");
        assertThat(agentic.quizzes()).isEqualTo(1);
        assertThat(agentic.total().midEur()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(byId(report, "two-phase|local|cloud").midEurPerQuiz()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void report_attributesPoolCostPerModelIncludingSecondJudgePasses() throws IOException {
        store.registerRun("pool-local", "pool|local", "m");
        seedPoolItem(List.of(call("local-model", null, null, 3_600_000), call("cloud-model", 100_000, 0, 0)));
        long id = store.rowIdOf(new ItemKey("pool-local", "pool|local", cell().key(), 0)).orElseThrow();
        store.recordVerdict(id, "cloud-model", "GENERAL", true, "{}", mapper.writeValueAsString(List.of(call("cloud-model", 200_000, 0, 0))));

        SweepCostReporter.Report report = reporter.report(store, plan(), Map.of("cloud", "cloud-model", "local", "local-model"), pricing);

        assertThat(report.pools()).hasSize(1);
        Map<String, CostCalculator.Cost> costs = report.pools().getFirst().costsByModel();
        assertThat(costs.get("local-model").lowEur()).isCloseTo(0.36, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(costs.get("local-model").highEur()).isCloseTo(3.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(costs.get("cloud-model").midEur()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void report_computesTheBreakEvenQuizCount() throws IOException {
        store.registerRun("s1", "sweep", "m");
        store.registerRun("pool-local", "pool|local", "m");
        saveQuiz("agentic|cloud|cloud", "q1", List.of(call("cloud-model", 1_000_000, 100_000, 0)));
        saveQuiz("two-phase|local|cloud", "q2", List.of(call("cloud-model", 100_000, 10_000, 0)));
        seedPoolItem(List.of(call("local-model", null, null, 3_600_000)));

        SweepCostReporter.Report report = reporter.report(store, plan(), Map.of("cloud", "cloud-model", "local", "local-model"), pricing);

        assertThat(report.amortisations()).hasSize(1);
        SweepCostReporter.Amortisation amortisation = report.amortisations().getFirst();
        // pool mid = (0.36 + 3.6) / 2 = 1.98; margin = 2.00 - 0.20 = 1.80; break-even = ceil(1.98 / 1.80) = 2
        assertThat(amortisation.poolMidEur()).isCloseTo(1.98, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(amortisation.breakEvenQuizzes()).isEqualTo(2);
    }

    private static SweepPlan plan() {
        return new SweepPlan("s1", "r.yml", 1, new SweepPlan.Pool(2, 2, 4, Set.of(Language.DE), Set.of(QuestionType.SINGLE_CHOICE), Set.of(Difficulty.MEDIUM)),
                new SweepPlan.Selection(40, 0.7, 1, 0), new SweepPlan.Agentic(3), List.of(new SweepPlan.Configuration("a", SweepPlan.Approach.AGENTIC, "cloud", "cloud", "cloud"),
                        new SweepPlan.Configuration("t", SweepPlan.Approach.TWO_PHASE, "local", "cloud", "cloud")));
    }

    private static SweepCostReporter.ConfigurationCost byId(SweepCostReporter.Report report, String configurationId) {
        return report.configurations().stream().filter(cost -> cost.configurationId().equals(configurationId)).findFirst().orElseThrow();
    }

    private void saveQuiz(String configurationId, String quizId, List<CallRecord> calls) throws IOException {
        store.saveQuiz(new StoredQuiz(quizId, "s1", configurationId, "EIDI", "r1", 1, true, "[]", "[]", mapper.writeValueAsString(calls)));
    }

    private void seedPoolItem(List<CallRecord> calls) throws IOException {
        store.enqueuePool(List.of(new PoolItem(new ItemKey("pool-local", "pool|local", cell().key(), 0), cell(), 0, "local-model")));
        var claim = store.claimNext("pool-local").orElseThrow();
        store.recordGenerated(claim.key(), "{}", "{}", "[]");
        var filterClaim = store.claimNext("pool-local").orElseThrow();
        assertThat(filterClaim.state()).isEqualTo(ItemState.FILTERING);
        store.recordFiltered(filterClaim.key(), "{}", mapper.writeValueAsString(calls));
    }

    private static PoolCell cell() {
        return new PoolCell("EIDI", "arrays", Language.DE, QuestionType.SINGLE_CHOICE, Difficulty.MEDIUM);
    }

    private static CallRecord call(String model, Integer promptTokens, Integer completionTokens, long wallClockMs) {
        return new CallRecord("id", "generation", model, promptTokens, completionTokens, wallClockMs, 0, "success", null, null);
    }
}
