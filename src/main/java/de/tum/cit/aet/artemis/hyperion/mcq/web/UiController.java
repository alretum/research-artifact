package de.tum.cit.aet.artemis.hyperion.mcq.web;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import de.tum.cit.aet.artemis.hyperion.mcq.app.PipelineProperties;
import de.tum.cit.aet.artemis.hyperion.mcq.batch.RunManager;
import de.tum.cit.aet.artemis.hyperion.mcq.batch.RunManager.StartRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CorpusIndexService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkExporter;
import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkExporter.Condition;
import de.tum.cit.aet.artemis.hyperion.mcq.benchmark.BenchmarkExporter.Granularity;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.readiness.Readiness;
import de.tum.cit.aet.artemis.hyperion.mcq.readiness.ReadinessService;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;

/**
 * The web interface: corpus and topic overview, run control, item browsing, and answering.
 */
@Controller
public class UiController {

    private static final Logger log = LoggerFactory.getLogger(UiController.class);

    private static final int BROWSE_LIMIT = 200;

    private final CorpusIndexService corpus;

    private final RunManager runs;

    private final RunStore store;

    private final ItemView itemView;

    private final PipelineProperties properties;

    private final ReadinessService readiness;

    private final BenchmarkExporter exporter;

    public UiController(CorpusIndexService corpus, RunManager runs, RunStore store, ItemView itemView, PipelineProperties properties, ReadinessService readiness, BenchmarkExporter exporter) {
        this.readiness = readiness;
        this.exporter = exporter;
        this.corpus = corpus;
        this.runs = runs;
        this.store = store;
        this.itemView = itemView;
        this.properties = properties;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        Readiness setup = readiness.check();
        model.addAttribute("readyToGenerate", setup.ready());
        model.addAttribute("setupBlockers", setup.blockers());
        model.addAttribute("difficultyDefault", properties.difficulty().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")));
        model.addAttribute("thresholdDefault", properties.filter().acceptThreshold());
        model.addAttribute("generatorDefault", properties.generation().model());
        model.addAttribute("filterDefault", properties.filter().model());
        model.addAttribute("modelOptions", usableModels());
        var index = corpus.index();
        int[] attempts = store.attemptTotals();
        model.addAttribute("topics", index.allTopics());
        model.addAttribute("chunkCount", index.chunkCount());
        model.addAttribute("documentCount", index.documentReports().size());
        model.addAttribute("configuration", properties.configurationId());
        model.addAttribute("threshold", properties.filter().acceptThreshold());
        model.addAttribute("runs", runs.runs());
        model.addAttribute("activeRunId", runs.activeRunId().orElse(null));
        model.addAttribute("attemptCount", attempts[0]);
        model.addAttribute("attemptsCorrect", attempts[1]);
        return "dashboard";
    }

    @PostMapping("/runs")
    public String startRun(@RequestParam(required = false) List<String> topics, @RequestParam(defaultValue = "1") int itemsPerTopic,
            @RequestParam(required = false) Integer concurrency, @RequestParam(required = false) String difficulty, @RequestParam(required = false) Double acceptThreshold,
            @RequestParam(required = false) String generatorModel, @RequestParam(required = false) String filterModel, RedirectAttributes flash) {
        try {
            String runId = runs.start(new StartRequest(topics == null ? List.of() : topics, itemsPerTopic, concurrency, parseDifficulty(difficulty), acceptThreshold,
                    generatorModel, filterModel));
            flash.addFlashAttribute("message", "Started run " + runId);
        }
        catch (RuntimeException e) {
            log.warn("Could not start run: {}", e.getMessage());
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/runs/{runId}/resume")
    public String resumeRun(@PathVariable String runId, RedirectAttributes flash) {
        try {
            runs.resume(runId);
            flash.addFlashAttribute("message", "Resumed run " + runId);
        }
        catch (RuntimeException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/runs/{runId}/stop")
    public String stopRun(@PathVariable String runId, RedirectAttributes flash) {
        runs.requestStop(runId);
        flash.addFlashAttribute("message", "Stopping run " + runId + " once in-flight items finish");
        return "redirect:/";
    }

    @GetMapping("/api/progress/{runId}")
    @ResponseBody
    public RunManager.Progress progress(@PathVariable String runId) {
        return runs.progress(runId);
    }

    /**
     * Show whether the prerequisites for a run are in place, and what to do about any that are not.
     *
     * @param model view model
     * @return the setup view
     */
    /**
     * Show what an export would contain and let the user choose how it is grouped.
     *
     * @param model view model
     * @return the export view
     */
    /**
     * Parse a difficulty ladder typed as free text, for example {@code "20, 40, 60, 80"}.
     *
     * @param value the field value; blank means use the configured ladder
     * @return the levels, or {@code null} when nothing was entered
     * @throws IllegalArgumentException when a value is not a number
     */
    private static List<Integer> parseDifficulty(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.util.Arrays.stream(value.split("[,;\\s]+")).filter(part -> !part.isBlank()).map(Integer::parseInt).toList();
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Difficulty must be numbers separated by commas, for example 20, 40, 60, 80 — got '" + value + "'");
        }
    }

    /**
     * @return provider model names usable right now, that is those on the backend this application is
     *         configured against. Models on other declared backends have no client, so offering them would
     *         only produce a failure at run time.
     */
    private List<String> usableModels() {
        try {
            ModelCatalogue catalogue = ModelCatalogue.load(Path.of(properties.modelCataloguePath()));
            return catalogue.models().values().stream().filter(entry -> catalogue.backendFor(entry).isDefault()).map(ModelCatalogue.ModelEntry::model).distinct().sorted().toList();
        }
        catch (RuntimeException e) {
            return List.of();
        }
    }

    @GetMapping("/export")
    public String exportForm(Model model) {
        List<RunStore.CompletedItem> items = store.runIds().stream().flatMap(runId -> store.completedItems(runId).stream()).toList();
        model.addAttribute("itemCount", items.size());
        model.addAttribute("acceptedCount", items.stream().filter(item -> item.decisionJson() != null && item.decisionJson().contains("\"accepted\":true")).count());
        model.addAttribute("configurationCount", items.stream().map(item -> item.key().configurationId()).distinct().count());
        return "export";
    }

    /**
     * Export and stream the result as a zip.
     * <p>
     * Synchronous because the export reads persisted records and makes no model calls, so it finishes in
     * well under a second even for the whole store. The files are also left on disk, so the same export is
     * available to the command line and to the benchmark without re-running.
     *
     * @param granularity what becomes one quiz file
     * @param condition   which items each file holds
     * @return a zip of the export directory
     */
    @PostMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(defaultValue = "configuration-topic") String granularity,
            @RequestParam(defaultValue = "all") String condition) {
        Path directory = Path.of(properties.benchmarkExportPath());
        exporter.export(store, directory, Granularity.parse(granularity), Condition.parse(condition), properties.language());

        StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out); Stream<Path> walk = Files.walk(directory)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(new ZipEntry(directory.relativize(path).toString()));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"benchmark-export.zip\"")
                .contentType(MediaType.valueOf("application/zip")).body(body);
    }

    @GetMapping("/readiness")
    public String readiness(Model model) {
        Readiness result = readiness.check();
        model.addAttribute("checks", result.checks());
        model.addAttribute("blockers", result.blockers());
        model.addAttribute("ready", result.ready());
        return "readiness";
    }

    @GetMapping("/items")
    public String items(@RequestParam(required = false) String run, @RequestParam(required = false) String topic, @RequestParam(required = false) Boolean accepted, Model model) {
        model.addAttribute("items", store.browse(blankToNull(run), blankToNull(topic), accepted, BROWSE_LIMIT));
        model.addAttribute("topics", corpus.index().allTopics());
        model.addAttribute("selectedTopic", topic);
        model.addAttribute("selectedAccepted", accepted);
        return "items";
    }

    @GetMapping("/items/{id}")
    public String item(@PathVariable long id, @RequestParam(required = false) Integer answered, Model model) {
        Optional<RunStore.ItemDetail> detail = store.item(id);
        if (detail.isEmpty()) {
            return "redirect:/items";
        }
        model.addAttribute("view", itemView.render(detail.get()));
        model.addAttribute("attempts", store.attempts(id));
        model.addAttribute("answered", answered);
        return "item";
    }

    @PostMapping("/items/{id}/answer")
    public String answer(@PathVariable long id, @RequestParam int option, RedirectAttributes flash) {
        Optional<RunStore.ItemDetail> detail = store.item(id);
        if (detail.isEmpty()) {
            return "redirect:/items";
        }
        var view = itemView.render(detail.get());
        boolean correct = option >= 0 && option < view.options().size() && view.options().get(option).correct();
        store.recordAttempt(id, option, correct);
        flash.addAttribute("answered", option);
        return "redirect:/items/" + id;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
