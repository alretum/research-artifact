package de.tum.cit.aet.artemis.hyperion.mcq.cost;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.app.SweepPlan;
import de.tum.cit.aet.artemis.hyperion.mcq.cost.CostCalculator.Cost;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.StoredQuiz;

/**
 * Prices a sweep from its stored calls: what each configuration paid per quiz at request time, what each
 * pool cost to build, and after how many quizzes a two-phase configuration overtakes the agentic one.
 * <p>
 * Everything is derived from persisted {@code CallRecord}s and the pricing file, so revising a price is a
 * re-report, never a re-run. Costs are ranges: the low bound prices GPU time as electricity, the high bound
 * as rental. Local wall-clock time includes queue and network waits, so locally served calls are an upper
 * bound on true GPU time.
 */
@Service
public class SweepCostReporter {

    private static final Logger log = LoggerFactory.getLogger(SweepCostReporter.class);

    private final JsonMapper reader = StructuredOutputs.outputMapper();

    /**
     * Request-time cost of one configuration.
     *
     * @param quizzes quizzes the configuration assembled
     */
    public record ConfigurationCost(String configurationId, int quizzes, Cost total, double midEurPerQuiz) {
    }

    /**
     * Build cost of one pool, attributed per model so it can be re-attributed per configuration.
     *
     * @param generatorKey  the pool's generator catalogue key
     * @param costsByModel  the pool's calls priced per provider model name — generation under the
     *                      generator's model, judging under each judge's
     */
    public record PoolCost(String generatorKey, Map<String, Cost> costsByModel) {
    }

    /**
     * When a two-phase configuration overtakes the agentic one.
     *
     * @param poolMidEur       what the configuration's pool share cost to build
     * @param breakEvenQuizzes quizzes after which pool + selection undercuts agentic generation, or
     *                         {@code -1} when it never does
     */
    public record Amortisation(String configurationId, String against, double poolMidEur, double agenticMidEurPerQuiz, double twoPhaseMidEurPerQuiz, int breakEvenQuizzes) {
    }

    /** Everything the report derives. */
    public record Report(List<ConfigurationCost> configurations, List<PoolCost> pools, List<Amortisation> amortisations) {
    }

    /**
     * Derive the cost report for one sweep, without a single model call.
     *
     * @param store       store holding the sweep's quizzes, pool items and verdicts
     * @param plan        the sweep plan
     * @param keyToModel  provider model name per catalogue key the plan references
     * @param pricingFile the pricing file, read at report time
     * @return the report
     */
    public Report report(RunStore store, SweepPlan plan, Map<String, String> keyToModel, Path pricingFile) {
        CostCalculator calculator = new CostCalculator(PricingCatalogue.load(pricingFile));

        Map<String, List<CallRecord>> requestCalls = new LinkedHashMap<>();
        Map<String, Integer> quizCounts = new LinkedHashMap<>();
        for (StoredQuiz quiz : store.quizzes(plan.sweep())) {
            requestCalls.computeIfAbsent(quiz.configurationId(), _ -> new ArrayList<>()).addAll(calls(quiz.callsJson()));
            quizCounts.merge(quiz.configurationId(), 1, Integer::sum);
        }
        List<ConfigurationCost> configurations = new ArrayList<>();
        for (Map.Entry<String, List<CallRecord>> entry : requestCalls.entrySet()) {
            Cost total = calculator.costOf(entry.getValue());
            int quizzes = quizCounts.get(entry.getKey());
            configurations.add(new ConfigurationCost(entry.getKey(), quizzes, total, quizzes == 0 ? 0 : total.midEur() / quizzes));
        }

        List<PoolCost> pools = poolCosts(store, plan, calculator);
        List<Amortisation> amortisations = amortisations(plan, keyToModel, configurations, pools);
        Report report = new Report(List.copyOf(configurations), pools, amortisations);
        log.info("Sweep {} cost:\n{}", plan.sweep(), render(report));
        return report;
    }

    private List<PoolCost> poolCosts(RunStore store, SweepPlan plan, CostCalculator calculator) {
        List<String> verdictCalls = store.verdictCalls();
        List<PoolCost> pools = new ArrayList<>();
        for (String generatorKey : plan.configurations().stream().filter(configuration -> configuration.approach() == SweepPlan.Approach.TWO_PHASE)
                .map(SweepPlan.Configuration::generator).distinct().toList()) {
            String poolRunId = "pool-" + generatorKey;
            Map<String, List<CallRecord>> byModel = new LinkedHashMap<>();
            for (RunStore.CompletedItem item : store.completedItems(poolRunId)) {
                calls(item.callsJson()).forEach(call -> byModel.computeIfAbsent(call.model(), _ -> new ArrayList<>()).add(call));
            }
            for (RunStore.FailedItem item : store.failedItems(poolRunId)) {
                calls(item.callsJson()).forEach(call -> byModel.computeIfAbsent(call.model(), _ -> new ArrayList<>()).add(call));
            }
            for (String json : verdictCalls) {
                calls(json).forEach(call -> byModel.computeIfAbsent(call.model(), _ -> new ArrayList<>()).add(call));
            }
            Map<String, Cost> costs = new LinkedHashMap<>();
            byModel.forEach((model, records) -> costs.put(model, calculator.costOf(records)));
            pools.add(new PoolCost(generatorKey, Map.copyOf(costs)));
        }
        return List.copyOf(pools);
    }

    private List<Amortisation> amortisations(SweepPlan plan, Map<String, String> keyToModel, List<ConfigurationCost> configurations, List<PoolCost> pools) {
        Map<String, ConfigurationCost> byId = new LinkedHashMap<>();
        configurations.forEach(cost -> byId.put(cost.configurationId(), cost));

        List<Amortisation> amortisations = new ArrayList<>();
        for (SweepPlan.Configuration twoPhase : plan.configurations()) {
            if (twoPhase.approach() != SweepPlan.Approach.TWO_PHASE) {
                continue;
            }
            SweepPlan.Configuration agentic = plan.configurations().stream().filter(candidate -> candidate.approach() == SweepPlan.Approach.AGENTIC).findFirst().orElse(null);
            ConfigurationCost twoPhaseCost = byId.get(twoPhase.configurationId());
            ConfigurationCost agenticCost = agentic == null ? null : byId.get(agentic.configurationId());
            if (twoPhaseCost == null || agenticCost == null || twoPhaseCost.quizzes() == 0 || agenticCost.quizzes() == 0) {
                continue;
            }

            PoolCost pool = pools.stream().filter(candidate -> candidate.generatorKey().equals(twoPhase.generator())).findFirst().orElse(null);
            double poolMid = pool == null ? 0
                    : pool.costsByModel().entrySet().stream()
                            .filter(entry -> entry.getKey().equals(keyToModel.get(twoPhase.generator())) || entry.getKey().equals(keyToModel.get(twoPhase.judge())))
                            .mapToDouble(entry -> entry.getValue().midEur()).sum();
            double margin = agenticCost.midEurPerQuiz() - twoPhaseCost.midEurPerQuiz();
            int breakEven = margin <= 0 ? -1 : (int) Math.ceil(poolMid / margin);
            amortisations.add(new Amortisation(twoPhase.configurationId(), agentic.configurationId(), poolMid, agenticCost.midEurPerQuiz(), twoPhaseCost.midEurPerQuiz(),
                    breakEven));
        }
        return List.copyOf(amortisations);
    }

    private List<CallRecord> calls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return reader.readValue(json, new TypeReference<List<CallRecord>>() {
        });
    }

    private static String render(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("| configuration | quizzes | € per quiz (mid) | € total (low–high) | prompt tok | completion tok | gpu ms |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (ConfigurationCost cost : report.configurations()) {
            out.append("| ").append(cost.configurationId()).append(" | ").append(cost.quizzes()).append(" | ").append("%.4f".formatted(cost.midEurPerQuiz())).append(" | ")
                    .append("%.4f–%.4f".formatted(cost.total().lowEur(), cost.total().highEur())).append(" | ").append(cost.total().promptTokens()).append(" | ")
                    .append(cost.total().completionTokens()).append(" | ").append(cost.total().billedMs()).append(" |\n");
        }
        for (PoolCost pool : report.pools()) {
            out.append("\npool of generator '").append(pool.generatorKey()).append("':");
            pool.costsByModel().forEach((model, cost) -> out.append("\n  ").append(model).append(": ").append("%.4f–%.4f €".formatted(cost.lowEur(), cost.highEur())));
        }
        for (Amortisation amortisation : report.amortisations()) {
            out.append("\n").append(amortisation.configurationId()).append(" vs ").append(amortisation.against()).append(": pool ")
                    .append("%.4f €".formatted(amortisation.poolMidEur())).append(", per quiz ").append("%.4f".formatted(amortisation.twoPhaseMidEurPerQuiz())).append(" vs ")
                    .append("%.4f €".formatted(amortisation.agenticMidEurPerQuiz())).append(" → break-even after ")
                    .append(amortisation.breakEvenQuizzes() < 0 ? "∞ (never)" : amortisation.breakEvenQuizzes() + " quizzes");
        }
        out.append("\nLocal GPU time is client wall-clock, which includes queue and network waits: an upper bound until server-side timings are joined in.");
        return out.toString();
    }
}
