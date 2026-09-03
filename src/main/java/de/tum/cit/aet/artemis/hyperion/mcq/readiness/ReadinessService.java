package de.tum.cit.aet.artemis.hyperion.mcq.readiness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.app.PipelineProperties;
import de.tum.cit.aet.artemis.hyperion.mcq.cost.PricingCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.readiness.Readiness.Check;

/**
 * Checks the prerequisites for a run and says what to do about any that are missing.
 * <p>
 * Every network probe uses a short timeout on purpose. An unreachable backend previously blocked for
 * minutes because the configured request timeout is ten minutes, which is right for a generation call and
 * useless for a health check.
 */
@Service
public class ReadinessService {

    private static final Logger log = LoggerFactory.getLogger(ReadinessService.class);

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);

    /** Value the configuration falls back to when the key variable is unset, so it is detectable. */
    private static final String UNSET = "unset";

    private static final JsonMapper MAPPER = de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs.outputMapper();

    private final PipelineProperties properties;

    private final Environment environment;

    public ReadinessService(PipelineProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * @return every prerequisite check, in the order a reader should work through them
     */
    public Readiness check() {
        List<Check> checks = new ArrayList<>();
        checks.add(javaVersion());
        checks.add(dataDirectory());
        checks.add(corpus());
        checks.add(index());
        checks.addAll(backend("Embedding model", "spring.ai.openai.embedding.base-url", "spring.ai.openai.embedding.api-key",
                environment.getProperty("spring.ai.openai.embedding.options.model"), false));
        checks.addAll(chatBackends());
        checks.add(pricing());
        checks.add(catalogue());
        return new Readiness(List.copyOf(checks));
    }

    private static Check javaVersion() {
        String version = System.getProperty("java.version", "unknown");
        int major = major(version);
        if (major >= 25) {
            return Check.ok("Java", version);
        }
        return Check.blocked("Java", version + " is too old", "The code needs Java 25. Install a JDK 25 and point JAVA_HOME at it.");
    }

    private static int major(String version) {
        try {
            return Integer.parseInt(version.split("[.\\-+]")[0]);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    private Check dataDirectory() {
        Path database = Path.of(properties.batch().databasePath());
        Path directory = database.getParent() == null ? Path.of(".") : database.getParent();
        if (!Files.isDirectory(directory)) {
            return Check.warn("Output directory", directory + " does not exist yet", "It is created on first use; nothing to do.");
        }
        if (!Files.isWritable(directory)) {
            return Check.blocked("Output directory", directory + " is not writable", "Grant write permission on " + directory + ".");
        }
        return Check.ok("Output directory", directory + " is writable");
    }

    private Check corpus() {
        Path corpus = Path.of(properties.corpusPath());
        if (!Files.isDirectory(corpus)) {
            return Check.blocked("Corpus", corpus + " does not exist",
                    "Put lecture PDFs under " + corpus + "/<lecture name>/. It is gitignored, so a fresh clone has none. See README.md section 3.");
        }
        long pdfs;
        try (Stream<Path> walk = Files.walk(corpus)) {
            pdfs = walk.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")).count();
        }
        catch (IOException e) {
            return Check.blocked("Corpus", "could not read " + corpus, e.getMessage());
        }
        if (pdfs == 0) {
            return Check.blocked("Corpus", corpus + " holds no PDFs",
                    "Only PDFs are ingested. Put them under " + corpus + "/<lecture name>/ so the first directory level names the lecture.");
        }
        return Check.ok("Corpus", pdfs + " PDF(s) under " + corpus);
    }

    private Check index() {
        Path index = Path.of(properties.chunking().indexPath());
        if (!Files.isRegularFile(index)) {
            return Check.warn("Embedding index", "not built yet",
                    "The first run builds it, which takes about a minute on Apple Silicon and several on two CPU cores. It is then cached at " + index + ".");
        }
        return Check.ok("Embedding index", "cached at " + index);
    }

    /**
     * Probe one backend: is a key configured, is the endpoint reachable, and is the model granted.
     *
     * @param label       display label
     * @param urlProperty property holding the base URL
     * @param keyProperty property holding the key
     * @param model       model expected on this backend
     * @param keyRequired whether a missing key blocks; Ollama accepts any value, so it does not
     * @return one check for the key and one for reachability
     */
    private List<Check> backend(String label, String urlProperty, String keyProperty, String model, boolean keyRequired) {
        List<Check> checks = new ArrayList<>();
        String baseUrl = environment.getProperty(urlProperty, environment.getProperty("spring.ai.openai.base-url", ""));
        String key = environment.getProperty(keyProperty, environment.getProperty("spring.ai.openai.api-key", ""));
        boolean haveKey = key != null && !key.isBlank() && !UNSET.equals(key);

        String keyName = label + " API key";
        if (haveKey) {
            checks.add(Check.ok(keyName, "set (" + key.length() + " characters)"));
        }
        else if (keyRequired) {
            checks.add(Check.blocked(keyName, "not set", "Export it in your shell, then re-source: see README.md section 4.1. It is never read from a file in the repository."));
        }
        else {
            checks.add(Check.warn(keyName, "not set", "Not required by Ollama, which accepts any value. Set it only if your embedding backend needs one."));
        }

        if (baseUrl.isBlank()) {
            checks.add(Check.blocked(label, "no base URL configured", "Set " + urlProperty + "."));
            return checks;
        }
        checks.add(reachable(label, baseUrl, haveKey ? key : null, model, keyRequired));
        return checks;
    }

    /**
     * Probe an endpoint.
     *
     * @param required whether a failure here blocks generation. An optional backend that is unreachable or
     *                 rejects its key is worth reporting but must not make the tool refuse to run: nothing
     *                 depends on it until a run plan names one of its models.
     */
    private Check reachable(String label, String baseUrl, String key, String model, boolean required) {
        String endpoint = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(PROBE_TIMEOUT).GET();
            if (key != null) {
                request.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return verdict(required, label, "reachable but rejected the key (HTTP " + response.statusCode() + ")", "Check the key is current and active.");
            }
            if (response.statusCode() >= 400) {
                return Check.warn(label, "reachable, HTTP " + response.statusCode() + " listing models", "The endpoint answered but not with a model list. Check the base URL.");
            }
            List<String> offered = modelIds(response.body());
            if (model != null && !model.isBlank() && !offered.isEmpty() && offered.stream().noneMatch(id -> sameModel(id, model))) {
                return verdict(required, label, "reachable, but " + model + " is not offered to this key",
                        "Offered here: " + offered + ". Either use one of those, or ask for " + model + " to be granted to your key.");
            }
            return Check.ok(label, model == null || model.isBlank() ? "reachable at " + baseUrl : model + " available at " + baseUrl);
        }
        catch (IOException e) {
            return verdict(required, label, "unreachable at " + baseUrl,
                    baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1") ? "Start the local server, for example: ollama serve"
                            : "Check the network. A TUM-hosted endpoint needs eduVPN or the campus network.");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Check.warn(label, "probe interrupted", null);
        }
    }

    /**
     * @param body a {@code /v1/models} response
     * @return the model ids it lists, or empty when the shape is unexpected
     */
    private static List<String> modelIds(String body) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {
            });
            Object data = parsed.get("data");
            if (!(data instanceof List<?> entries)) {
                return List.of();
            }
            return entries.stream().filter(Map.class::isInstance).map(entry -> ((Map<?, ?>) entry).get("id")).filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Compare a provider-reported id with a configured model name, tolerating a tag.
     * <p>
     * Ollama reports {@code nomic-embed-text:latest} while requests using the untagged name resolve to it,
     * so an exact comparison reports a working setup as broken.
     *
     * @param offered    id as the provider reports it
     * @param configured name as configured here
     * @return whether they refer to the same model
     */
    static boolean sameModel(String offered, String configured) {
        return offered.equals(configured) || offered.startsWith(configured + ":") || configured.startsWith(offered + ":");
    }

    /**
     * Check every chat backend the model catalogue declares.
     * <p>
     * Named the way the project names them: the default backend is the chair-hosted one the thesis calls
     * the local model, and any further backend is a commercial provider -- the cloud arm of the comparison.
     * Deriving these from the catalogue rather than hardcoding them means declaring a new backend puts it
     * on this checklist without touching this class.
     *
     * @return one key check and one reachability check per declared backend
     */
    private List<Check> chatBackends() {
        Path file = Path.of(properties.modelCataloguePath());
        if (!Files.isRegularFile(file)) {
            return List.of(Check.blocked("Local model", "no model catalogue at " + file, "Restore " + file + "; it declares which chat backends and models exist."));
        }
        ModelCatalogue catalogue;
        try {
            catalogue = ModelCatalogue.load(file);
        }
        catch (RuntimeException e) {
            return List.of(Check.blocked("Local model", "model catalogue is invalid", e.getMessage()));
        }

        List<Check> checks = new ArrayList<>();
        boolean sawCloud = false;
        for (ModelCatalogue.Backend backend : catalogue.backends().values()) {
            List<String> models = catalogue.models().values().stream().filter(entry -> entry.backend().equals(backend.name())).map(ModelCatalogue.ModelEntry::model).toList();
            if (backend.isDefault()) {
                checks.addAll(chatBackend("Local model", backend, models, true));
            }
            else {
                sawCloud = true;
                checks.addAll(chatBackend("Cloud model (" + backend.name() + ")", backend, models, false));
            }
        }
        if (!sawCloud) {
            checks.add(Check.warn("Cloud model", "none declared",
                    "Optional. Generation works without one. Declare a commercial backend in " + file + " for the cloud arm of the generator x filter comparison."));
        }
        return checks;
    }

    /**
     * @param label       display label
     * @param backend     the declared backend
     * @param models      models this backend is expected to serve
     * @param required    whether its absence blocks generation
     * @return a key check and a reachability check
     */
    private List<Check> chatBackend(String label, ModelCatalogue.Backend backend, List<String> models, boolean required) {
        List<Check> checks = new ArrayList<>();
        String key = System.getenv(backend.apiKeyEnv());
        boolean haveKey = key != null && !key.isBlank();

        if (haveKey) {
            checks.add(Check.ok(label + " API key", "$" + backend.apiKeyEnv() + " set (" + key.length() + " characters)"));
        }
        else if (required) {
            checks.add(Check.blocked(label + " API key", "$" + backend.apiKeyEnv() + " is not set",
                    "Export it in your shell, then re-source. It is never read from a file in the repository; see README.md section 4.1."));
        }
        else {
            checks.add(Check.warn(label + " API key", "$" + backend.apiKeyEnv() + " is not set", "Optional. Set it only if you want to use this backend."));
            checks.add(Check.warn(label, "not checked", "Skipped because its key is unset."));
            return checks;
        }

        checks.add(reachable(label, backend.baseUrl(), key, models.size() == 1 ? models.getFirst() : null, required));
        return checks;
    }

    /**
     * @param required whether this backend's failure blocks generation
     * @return a blocking check for a required backend, a warning for an optional one
     */
    static Check verdict(boolean required, String name, String detail, String fix) {
        return required ? Check.blocked(name, detail, fix) : Check.warn(name, detail, fix + " Optional, so it does not block generation.");
    }

    private Check pricing() {
        Path file = Path.of(properties.pricingPath());
        if (!Files.isRegularFile(file)) {
            return Check.warn("Pricing", file + " is missing", "Only needed for --cost. Without it, cost cannot be reported.");
        }
        try {
            PricingCatalogue catalogue = PricingCatalogue.load(file);
            String model = properties.generation().model();
            if (catalogue.priceFor(model).isEmpty()) {
                return Check.warn("Pricing", model + " has no price entry", "Add it to " + file + ", or its calls are silently excluded from cost figures.");
            }
            return Check.ok("Pricing", model + " is priced");
        }
        catch (RuntimeException e) {
            return Check.warn("Pricing", "could not read " + file, e.getMessage());
        }
    }

    private Check catalogue() {
        Path file = Path.of(properties.modelCataloguePath());
        if (!Files.isRegularFile(file)) {
            return Check.warn("Model catalogue", file + " is missing", "Only needed for --plan and --run-plan.");
        }
        try {
            ModelCatalogue loaded = ModelCatalogue.load(file);
            return Check.ok("Model catalogue", loaded.models().size() + " model(s) declared across " + loaded.backends().size() + " backend(s)");
        }
        catch (RuntimeException e) {
            return Check.blocked("Model catalogue", "invalid: " + e.getMessage(), "Fix " + file + ".");
        }
    }

    /**
     * Log the checklist.
     *
     * @return whether the tool is ready
     */
    public boolean report() {
        Readiness readiness = check();
        log.info("Readiness:");
        for (Check c : readiness.checks()) {
            String mark = switch (c.status()) {
                case OK -> "ok  ";
                case WARN -> "warn";
                case BLOCKED -> "FAIL";
            };
            log.info("  [{}] {}: {}", mark, c.name(), c.detail());
            if (c.fix() != null) {
                log.info("         -> {}", c.fix());
            }
        }
        if (readiness.ready()) {
            log.info("Ready to generate.");
        }
        else {
            log.error("Not ready: {} blocker(s) above.", readiness.blockers().size());
        }
        return readiness.ready();
    }
}
