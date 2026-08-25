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
import org.springframework.web.multipart.MultipartFile;

import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelCatalogue;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.RunPlan;
import de.tum.cit.aet.artemis.hyperion.mcq.upload.CorpusUploadService;
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

    private final CorpusUploadService uploads;

    /** Staged uploads awaiting a decision, so the preview can show what was refused. */
    private final java.util.Map<String, CorpusUploadService.Staged> pending = new java.util.concurrent.ConcurrentHashMap<>();

    public UiController(CorpusIndexService corpus, RunManager runs, RunStore store, ItemView itemView, PipelineProperties properties, ReadinessService readiness, BenchmarkExporter exporter, CorpusUploadService uploads) {
        this.readiness = readiness;
        this.exporter = exporter;
        this.uploads = uploads;
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
     * @return provider model names that can be used right now: every declared model whose backend has its
     *         key set. A backend with no key is skipped rather than offered, because selecting it would
     *         only fail when the run starts.
     */
    private List<String> usableModels() {
        try {
            ModelCatalogue catalogue = ModelCatalogue.load(Path.of(properties.modelCataloguePath()));
            return catalogue.models().values().stream().filter(entry -> {
                String key = System.getenv(catalogue.backendFor(entry).apiKeyEnv());
                return key != null && !key.isBlank();
            }).map(ModelCatalogue.ModelEntry::model).distinct().sorted().toList();
        }
        catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Show the corpus and any pending upload.
     *
     * @param uploadId a staging area to preview, when one has just been created
     * @param model    view model
     * @return the corpus view
     */
    /**
     * A plan as the interface shows it, including why it cannot be run when that is the case.
     *
     * @param file           path of the plan file
     * @param problem        why it cannot run, or {@code null} when it can
     */
    public record PlanView(String file, String name, int itemsPerTopic, List<String> topics, List<RunPlan.RunConfiguration> configurations, String problem) {
    }

    /**
     * List the plans in the plan directory, validating each so a broken one is reported rather than offered.
     *
     * @param model view model
     * @return the plans view
     */
    @GetMapping("/plans")
    public String plans(Model model) {
        List<PlanView> views = new java.util.ArrayList<>();
        Path directory = Path.of("config/runs");
        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".yml")).sorted().toList()) {
                    try {
                        RunPlan plan = RunPlan.load(file);
                        String problem = null;
                        try {
                            ModelCatalogue catalogue = ModelCatalogue.load(Path.of(properties.modelCataloguePath()));
                            plan.validateAgainst(catalogue);
                            for (String key : plan.referencedModels()) {
                                String env = catalogue.backendFor(catalogue.requireModel(key)).apiKeyEnv();
                                if (System.getenv(env) == null || System.getenv(env).isBlank()) {
                                    problem = "Model '" + key + "' needs $" + env + ", which is not set.";
                                }
                            }
                        }
                        catch (RuntimeException e) {
                            problem = e.getMessage();
                        }
                        views.add(new PlanView(file.toString(), plan.plan(), plan.itemsPerTopic(), plan.topics(), plan.configurations(), problem));
                    }
                    catch (RuntimeException e) {
                        views.add(new PlanView(file.toString(), file.getFileName().toString(), 0, List.of(), List.of(), "Could not read: " + e.getMessage()));
                    }
                }
            }
            catch (java.io.IOException e) {
                model.addAttribute("error", "Could not list " + directory + ": " + e.getMessage());
            }
        }
        model.addAttribute("plans", views);
        model.addAttribute("plan", runs.planProgress().orElse(null));
        model.addAttribute("activeRunId", runs.activeRunId().orElse(null));
        return "plans";
    }

    /**
     * Start every configuration of a plan.
     *
     * @param file  plan file to run
     * @param flash redirect attributes
     * @return a redirect to the plans view
     */
    @PostMapping("/plans/run")
    public String runPlan(@RequestParam String file, RedirectAttributes flash) {
        try {
            runs.startPlan(RunPlan.load(Path.of(file)));
            flash.addFlashAttribute("message", "Plan started. Cells run one after another; this page shows progress.");
        }
        catch (RuntimeException e) {
            log.warn("Could not start plan: {}", e.getMessage());
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/plans";
    }

    @GetMapping("/corpus")
    public String corpus(@RequestParam(required = false) String uploadId, Model model) {
        model.addAttribute("lectures", uploads.lectures());
        model.addAttribute("activeRunId", runs.activeRunId().orElse(null));
        if (uploadId != null && !uploadId.isBlank()) {
            try {
                CorpusUploadService.Preview preview = uploads.preview(uploadId);
                model.addAttribute("staged", pending.get(uploadId));
                model.addAttribute("documents", preview.documents());
                model.addAttribute("existing", preview.existing());
            }
            catch (RuntimeException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "upload";
    }

    /**
     * Take an upload into staging. Nothing reaches the corpus until it is committed.
     *
     * @param files uploaded parts
     * @param paths corpus-relative path per file, as a folder picker supplies
     * @param flash redirect attributes
     * @return a redirect to the preview
     */
    @PostMapping("/corpus/upload")
    public String upload(@RequestParam("files") List<MultipartFile> files, @RequestParam(name = "paths", required = false) List<String> paths, RedirectAttributes flash) {
        try {
            CorpusUploadService.Staged staged = uploads.stage(files, paths);
            if (staged.files().isEmpty()) {
                uploads.discard(staged.uploadId());
                flash.addFlashAttribute("error", "Nothing usable in that upload. " + String.join("; ", staged.rejected()));
                return "redirect:/corpus";
            }
            pending.put(staged.uploadId(), staged);
            return "redirect:/corpus?uploadId=" + staged.uploadId();
        }
        catch (RuntimeException e) {
            log.warn("Upload failed: {}", e.getMessage());
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/corpus";
        }
    }

    /**
     * Move a staged upload into the corpus.
     *
     * @param uploadId the staging area
     * @param flash    redirect attributes
     * @return a redirect to the corpus view
     */
    @PostMapping("/corpus/upload/{uploadId}/commit")
    public String commitUpload(@PathVariable String uploadId, RedirectAttributes flash) {
        if (runs.activeRunId().isPresent()) {
            flash.addFlashAttribute("error", "A run is executing. Committing now would change the material it is generating from.");
            return "redirect:/corpus?uploadId=" + uploadId;
        }
        try {
            int moved = uploads.commit(uploadId);
            pending.remove(uploadId);
            flash.addFlashAttribute("message", "Added " + moved + " file(s). The index was dropped and will rebuild on the next run.");
        }
        catch (RuntimeException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/corpus";
    }

    /**
     * Throw away a staged upload.
     *
     * @param uploadId the staging area
     * @param flash    redirect attributes
     * @return a redirect to the corpus view
     */
    @PostMapping("/corpus/upload/{uploadId}/discard")
    public String discardUpload(@PathVariable String uploadId, RedirectAttributes flash) {
        try {
            uploads.discard(uploadId);
            pending.remove(uploadId);
            flash.addFlashAttribute("message", "Discarded. Nothing was added to the corpus.");
        }
        catch (RuntimeException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/corpus";
    }

    /**
     * Remove a lecture, so a mistaken commit can be undone without filesystem access.
     *
     * @param lecture lecture directory name
     * @param flash   redirect attributes
     * @return a redirect to the corpus view
     */
    @PostMapping("/corpus/lectures/delete")
    public String deleteLecture(@RequestParam String lecture, RedirectAttributes flash) {
        if (runs.activeRunId().isPresent()) {
            flash.addFlashAttribute("error", "A run is executing; removing material now would change what it is generating from.");
            return "redirect:/corpus";
        }
        try {
            flash.addFlashAttribute("message", "Removed " + uploads.deleteLecture(lecture) + " file(s) from '" + lecture + "'.");
        }
        catch (RuntimeException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/corpus";
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
