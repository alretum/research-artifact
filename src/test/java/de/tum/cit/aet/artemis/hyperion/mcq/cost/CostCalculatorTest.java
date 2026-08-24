package de.tum.cit.aet.artemis.hyperion.mcq.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tum.cit.aet.artemis.hyperion.mcq.cost.PricingCatalogue.Billing;
import de.tum.cit.aet.artemis.hyperion.mcq.cost.PricingCatalogue.GpuRates;
import de.tum.cit.aet.artemis.hyperion.mcq.cost.PricingCatalogue.ModelPrice;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.Mcq.CallRecord;

class CostCalculatorTest {

    private static final GpuRates RATES = new GpuRates(1.20, 0.08);

    private static CostCalculator calculator(Map<String, ModelPrice> models) {
        return new CostCalculator(new PricingCatalogue(models, RATES));
    }

    private static CallRecord call(String model, Integer in, Integer out, long ms) {
        return new CallRecord("req", "generation", model, in, out, ms, 0, "success", null, null);
    }

    @Test
    void pricesTokenBilledModelsExactly() {
        var cost = calculator(Map.of("cloud", new ModelPrice(Billing.TOKENS, 2.0, 10.0))).costOf(List.of(call("cloud", 1_000_000, 500_000, 1234)));

        // 1M input at 2.00 plus 0.5M output at 10.00
        assertThat(cost.lowEur()).isEqualTo(7.0);
        assertThat(cost.highEur()).isEqualTo(7.0);
        assertThat(cost.exact()).isTrue();
    }

    @Test
    void reportsTimeBilledModelsAsABandBetweenElectricityAndRental() {
        var cost = calculator(Map.of("local", new ModelPrice(Billing.GPU_TIME, null, null))).costOf(List.of(call("local", 900, 100, 3_600_000)));

        assertThat(cost.lowEur()).isEqualTo(0.08);
        assertThat(cost.highEur()).isEqualTo(1.20);
        assertThat(cost.exact()).isFalse();
        assertThat(cost.billedMs()).isEqualTo(3_600_000);
    }

    @Test
    void ignoresTokensOfTimeBilledModelsSinceTheyAreNotCharged() {
        var cost = calculator(Map.of("local", new ModelPrice(Billing.GPU_TIME, null, null))).costOf(List.of(call("local", 5_000, 5_000, 0)));

        assertThat(cost.promptTokens()).isZero();
        assertThat(cost.completionTokens()).isZero();
        assertThat(cost.lowEur()).isZero();
    }

    @Test
    void namesModelsItCannotPriceRatherThanGuessing() {
        var cost = calculator(Map.of()).costOf(List.of(call("mystery", 100, 100, 500)));

        assertThat(cost.unpricedModels()).containsExactly("mystery");
        assertThat(cost.lowEur()).isZero();
    }

    @Test
    void countsFailedCallsBecauseTheyWereStillPaidFor() {
        var failed = new CallRecord("req", "generation", "cloud", 1_000_000, 0, 10, 2, "error", "boom", "AUTH");
        var cost = calculator(Map.of("cloud", new ModelPrice(Billing.TOKENS, 2.0, 10.0))).costOf(List.of(failed));

        assertThat(cost.lowEur()).isEqualTo(2.0);
    }

    @Test
    void treatsMissingTokenCountsAsZeroRatherThanFailing() {
        var cost = calculator(Map.of("cloud", new ModelPrice(Billing.TOKENS, 2.0, 10.0))).costOf(List.of(call("cloud", null, null, 5)));

        assertThat(cost.lowEur()).isZero();
    }

    @Test
    void rejectsATokenBilledModelWithNoPrices() {
        assertThatThrownBy(() -> PricingCatalogue.parse(Map.of("models", Map.of("cloud", Map.of("billing", "tokens")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing input or output price");
    }

    @Test
    void rejectsAnUnknownBillingMode() {
        assertThatThrownBy(() -> PricingCatalogue.parse(Map.of("models", Map.of("cloud", Map.of("billing", "vibes")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown billing");
    }

    @Test
    void parsesTheProjectsOwnPricingFile() {
        var catalogue = PricingCatalogue.load(java.nio.file.Path.of("config/pricing.yml"));

        assertThat(catalogue.priceFor("openai/gpt-oss-120b")).isPresent().get().extracting(ModelPrice::billing).isEqualTo(Billing.GPU_TIME);
        assertThat(catalogue.gpu().rentalEurPerHour()).isGreaterThan(catalogue.gpu().electricityEurPerHour());
    }
}
