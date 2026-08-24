package de.tum.cit.aet.artemis.hyperion.mcq.cost;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.yaml.snakeyaml.Yaml;

/**
 * Prices for the models a run used, loaded from {@code config/pricing.yml}.
 * <p>
 * Deliberately read at report time and never during a run: every call records physical quantities
 * (tokens in and out, wall-clock milliseconds, model name) and cost is derived from them afterwards, so
 * revising a price is a re-report rather than a re-run.
 */
public record PricingCatalogue(Map<String, ModelPrice> models, GpuRates gpu) {

    /** How a model's cost is derived. */
    public enum Billing {

        /** Charged per token, at the published input and output prices. */
        TOKENS,

        /** Served on hardware we account for by time, so cost comes from occupancy and an hourly rate. */
        GPU_TIME
    }

    /**
     * @param billing            how this model is charged
     * @param inputEurPerMillion price per million prompt tokens; null for {@link Billing#GPU_TIME}
     * @param outputEurPerMillion price per million completion tokens; null for {@link Billing#GPU_TIME}
     */
    public record ModelPrice(Billing billing, Double inputEurPerMillion, Double outputEurPerMillion) {
    }

    /**
     * Hourly rates for time-billed hardware.
     * <p>
     * Both bounds are carried rather than one being privileged: the proposal leaves the choice open, so a
     * figure is reported as a band. Rental reflects opportunity cost, electricity the marginal energy cost
     * of hardware that would otherwise idle.
     *
     * @param rentalEurPerHour      commercial hourly rate for comparable hardware
     * @param electricityEurPerHour marginal energy cost only
     */
    public record GpuRates(double rentalEurPerHour, double electricityEurPerHour) {
    }

    /**
     * @param modelName model name as recorded on the call
     * @return the price entry, or empty when the model is not priced
     */
    public Optional<ModelPrice> priceFor(String modelName) {
        return Optional.ofNullable(models.get(modelName));
    }

    /**
     * Load a catalogue from a YAML file.
     *
     * @param file the pricing file
     * @return the parsed catalogue
     * @throws UncheckedIOException     if the file cannot be read
     * @throws IllegalArgumentException if the file is absent or a model entry is malformed
     */
    public static PricingCatalogue load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No pricing file at " + file.toAbsolutePath() + "; cost cannot be reported without one");
        }
        try (InputStream in = Files.newInputStream(file)) {
            return parse(new Yaml().load(in));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read pricing file " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    static PricingCatalogue parse(Map<String, Object> root) {
        if (root == null) {
            throw new IllegalArgumentException("Pricing file is empty");
        }
        Map<String, ModelPrice> models = new LinkedHashMap<>();
        Map<String, Object> declared = (Map<String, Object>) root.getOrDefault("models", Map.of());
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            models.put(entry.getKey(), modelPrice(entry.getKey(), (Map<String, Object>) entry.getValue()));
        }
        Map<String, Object> gpu = (Map<String, Object>) root.getOrDefault("gpu", Map.of());
        GpuRates rates = new GpuRates(number(gpu.get("rental-eur-per-hour"), 0), number(gpu.get("electricity-eur-per-hour"), 0));
        return new PricingCatalogue(Map.copyOf(models), rates);
    }

    private static ModelPrice modelPrice(String model, Map<String, Object> entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Model " + model + " has no pricing body");
        }
        String billing = String.valueOf(entry.get("billing"));
        if ("gpu-time".equals(billing)) {
            return new ModelPrice(Billing.GPU_TIME, null, null);
        }
        if ("tokens".equals(billing)) {
            Object in = entry.get("input-eur-per-million");
            Object out = entry.get("output-eur-per-million");
            if (in == null || out == null) {
                throw new IllegalArgumentException("Model " + model + " is billed per token but is missing input or output price");
            }
            return new ModelPrice(Billing.TOKENS, number(in, 0), number(out, 0));
        }
        throw new IllegalArgumentException("Model " + model + " has unknown billing '" + billing + "'; expected 'tokens' or 'gpu-time'");
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }
}
