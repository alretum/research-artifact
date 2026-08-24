package de.tum.cit.aet.artemis.hyperion.mcq.cost;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import de.tum.cit.aet.artemis.hyperion.mcq.cost.PricingCatalogue.Billing;
import de.tum.cit.aet.artemis.hyperion.mcq.cost.PricingCatalogue.ModelPrice;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;

/**
 * Derives cost from recorded calls.
 * <p>
 * Token-billed models yield an exact figure. Time-billed models yield a band, because the hourly rate is a
 * choice rather than a fact, and because client wall-clock overstates occupancy: it includes queueing and
 * network time that the provider does not charge for. Figures derived from wall-clock are therefore upper
 * bounds, and are reported as such rather than being presented as measurements.
 */
public class CostCalculator {

    private final PricingCatalogue catalogue;

    public CostCalculator(PricingCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * Cost of a set of calls.
     *
     * @param lowEur          lower bound in euro; equals {@code highEur} when every model is token-billed
     * @param highEur         upper bound in euro
     * @param promptTokens    prompt tokens summed across priced calls
     * @param completionTokens completion tokens summed across priced calls
     * @param billedMs        wall-clock milliseconds attributed to time-billed models
     * @param exact           whether the figure is exact, that is no time-billed model contributed
     * @param unpricedModels  models encountered that the catalogue does not price
     */
    public record Cost(double lowEur, double highEur, long promptTokens, long completionTokens, long billedMs, boolean exact, Set<String> unpricedModels) {

        /** @return the midpoint of the band, for ranking where a single number is needed */
        public double midEur() {
            return (lowEur + highEur) / 2;
        }
    }

    /**
     * @param calls calls to price; failed calls count, because a failed call consumes capacity too
     * @return the derived cost
     */
    public Cost costOf(Collection<CallRecord> calls) {
        double tokenEur = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        long billedMs = 0;
        Set<String> unpriced = new LinkedHashSet<>();

        for (CallRecord call : calls) {
            ModelPrice price = catalogue.priceFor(call.model()).orElse(null);
            if (price == null) {
                unpriced.add(call.model());
                continue;
            }
            if (price.billing() == Billing.GPU_TIME) {
                billedMs += call.wallClockMs();
                continue;
            }
            long in = call.promptTokens() == null ? 0 : call.promptTokens();
            long out = call.completionTokens() == null ? 0 : call.completionTokens();
            promptTokens += in;
            completionTokens += out;
            tokenEur += in / 1_000_000d * price.inputEurPerMillion() + out / 1_000_000d * price.outputEurPerMillion();
        }

        double hours = billedMs / 3_600_000d;
        double low = tokenEur + hours * catalogue.gpu().electricityEurPerHour();
        double high = tokenEur + hours * catalogue.gpu().rentalEurPerHour();
        return new Cost(low, high, promptTokens, completionTokens, billedMs, billedMs == 0, Set.copyOf(unpriced));
    }
}
