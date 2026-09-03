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

    public UiController(CorpusIndexService corpus, RunManager runs, RunStore store, ItemView itemView, PipelineProperties properties) {
        this.corpus = corpus;
        this.runs = runs;
        this.store = store;
        this.itemView = itemView;
        this.properties = properties;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        var index = corpus.index();
        RunStore.AttemptTotals attempts = store.attemptTotals();
        model.addAttribute("topics", index.allTopics());
        model.addAttribute("chunkCount", index.chunkCount());
        model.addAttribute("documentCount", index.documentReports().size());
        model.addAttribute("configuration", properties.configurationId());
        model.addAttribute("threshold", properties.filter().acceptThreshold());
        model.addAttribute("runs", runs.runs());
        model.addAttribute("activeRunId", runs.activeRunId().orElse(null));
        model.addAttribute("attemptCount", attempts.recorded());
        model.addAttribute("attemptsCorrect", attempts.correct());
        return "dashboard";
    }

    @PostMapping("/runs")
    public String startRun(@RequestParam(required = false) List<String> topics, @RequestParam(defaultValue = "1") int itemsPerTopic,
            @RequestParam(required = false) Integer concurrency, RedirectAttributes flash) {
        try {
            String runId = runs.start(new StartRequest(topics == null ? List.of() : topics, itemsPerTopic, concurrency));
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
