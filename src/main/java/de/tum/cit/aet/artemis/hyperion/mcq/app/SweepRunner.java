package de.tum.cit.aet.artemis.hyperion.mcq.app;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.hyperion.mcq.approach.AgenticApproach;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.ApproachContext;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.ModelCall;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.Quiz;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.QuizGenerator.SelectionSettings;
import de.tum.cit.aet.artemis.hyperion.mcq.approach.TwoPhaseApproach;
import de.tum.cit.aet.artemis.hyperion.mcq.batch.PoolBuilder;
import de.tum.cit.aet.artemis.hyperion.mcq.domain.GenerationRequest;
import de.tum.cit.aet.artemis.hyperion.mcq.filter.McqFilterService;
import de.tum.cit.aet.artemis.hyperion.mcq.generation.McqGenerationService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.GroundingAssemblyService;
import de.tum.cit.aet.artemis.hyperion.mcq.grounding.SnippetSource;
import de.tum.cit.aet.artemis.hyperion.mcq.ingest.CompetencyManifest;
import de.tum.cit.aet.artemis.hyperion.mcq.llm.StructuredOutputs;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelRegistry;
import de.tum.cit.aet.artemis.hyperion.mcq.plan.ModelRegistry.ResolvedModel;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore;
import de.tum.cit.aet.artemis.hyperion.mcq.store.RunStore.StoredQuiz;

/**
 * Runs one sweep plan to completion: builds the pools its two-phase configurations need, then answers every
 * request with every configuration, the declared number of times.
 * <p>
 * The sweep name is the run id, so re-running the same plan resumes: pools grow only for changed material,
 * quizzes already stored are skipped, and a plan whose course model or catalogue changed since the run was
 * registered is refused rather than silently mixed. Pools are keyed by generator alone, so configurations
 * that differ only in judge or selector share one generated pool and differ only in verdicts.
 */
public class SweepRunner {

    private static final Logger log = LoggerFactory.getLogger(SweepRunner.class);

    /**
     * Everything the runner needs from the surrounding pipeline.
     *
     * @param manifests the course model per course key, for every course the requests name
     */
    public record Dependencies(Map<String, CompetencyManifest> manifests, SnippetSource snippets, GroundingAssemblyService groundingAssembly, McqGenerationService generation,
            McqFilterService filter, AgenticApproach agentic, TwoPhaseApproach twoPhase, RunStore store, ModelRegistry models, PipelineProperties properties) {
    }

    private final SweepPlan plan;

    private final List<GenerationRequest> requests;

    private final Dependencies dependencies;

    public SweepRunner(SweepPlan plan, List<GenerationRequest> requests, Dependencies dependencies) {
        this.plan = plan;
        this.requests = requests;
        this.dependencies = dependencies;
    }

    /**
     * Run the sweep to completion.
     *
     * @param documentHashes content hash per corpus-relative document, as the corpus stands now
     * @return the number of quizzes newly assembled
     * @throws IllegalStateException if the sweep was registered with a different course model or catalogue
     */
    public int run(Map<String, String> documentHashes) {
        dependencies.store().registerRun(plan.sweep(), "sweep", fingerprint(documentHashes));
        buildPools(documentHashes);
        return assembleQuizzes();
    }

    private void buildPools(Map<String, String> documentHashes) {
        Map<String, Set<String>> judgesByGenerator = new LinkedHashMap<>();
        for (SweepPlan.Configuration configuration : plan.configurations()) {
            if (configuration.approach() == SweepPlan.Approach.TWO_PHASE) {
                judgesByGenerator.computeIfAbsent(configuration.generator(), _ -> new LinkedHashSet<>()).add(configuration.judge());
            }
        }

        Set<String> courses = new LinkedHashSet<>(requests.stream().map(GenerationRequest::courseKey).toList());
        for (Map.Entry<String, Set<String>> entry : judgesByGenerator.entrySet()) {
            String generatorKey = entry.getKey();
            List<String> judges = List.copyOf(entry.getValue());
            String primaryJudge = judges.getFirst();
            ResolvedModel generator = dependencies.models().resolve(generatorKey);
            ResolvedModel judge = dependencies.models().resolve(primaryJudge);

            for (String courseKey : courses) {
                PoolBuilder builder = new PoolBuilder(dependencies.store(), poolSettings(generatorKey, courseKey, generator, judge),
                        new PoolBuilder.Dependencies(manifestOf(courseKey), dependencies.snippets(), dependencies.groundingAssembly(), dependencies.generation(),
                                dependencies.filter(), dependencies.store()));
                int enqueued = builder.enqueue(documentHashes);
                int completed = builder.build(generator.client(), judge.client());
                log.info("Pool of generator {} for course {}: {} enqueued, {} completed", generatorKey, courseKey, enqueued, completed);

                for (String judgeKey : judges.subList(1, judges.size())) {
                    ResolvedModel additional = dependencies.models().resolve(judgeKey);
                    builder.judgeWith(additional.model(), dependencies.properties().filter().temperature(), dependencies.properties().filter().maxAttempts(),
                            additional.client());
                }
            }
        }
    }

    private int assembleQuizzes() {
        JsonMapper mapper = StructuredOutputs.outputMapper();
        int assembled = 0;
        for (SweepPlan.Configuration configuration : plan.configurations()) {
            QuizGenerator generator = configuration.approach() == SweepPlan.Approach.AGENTIC ? dependencies.agentic() : dependencies.twoPhase();
            for (GenerationRequest request : requests) {
                for (int repetition = 1; repetition <= plan.repetitions(); repetition++) {
                    if (dependencies.store().quizExists(plan.sweep(), configuration.configurationId(), request.key(), repetition)) {
                        continue;
                    }
                    ApproachContext context = context(configuration, request);
                    Quiz quiz = generator.generate(request, context);
                    String quizId = plan.sweep() + "-" + configuration.id() + "-" + request.key() + "-r" + repetition;
                    dependencies.store().saveQuiz(new StoredQuiz(quizId, plan.sweep(), configuration.configurationId(), request.courseKey(), request.key(), repetition,
                            quiz.complete(), mapper.writeValueAsString(quiz.accepted()), mapper.writeValueAsString(quiz.calls())));
                    assembled++;
                    log.info("Assembled {} ({} of {} questions{})", quizId, quiz.accepted().size(), request.numberOfQuestions(), quiz.complete() ? "" : ", INCOMPLETE");
                }
            }
        }
        return assembled;
    }

    private ApproachContext context(SweepPlan.Configuration configuration, GenerationRequest request) {
        PipelineProperties properties = dependencies.properties();
        ResolvedModel generator = dependencies.models().resolve(configuration.generator());
        ResolvedModel judge = dependencies.models().resolve(configuration.judge());
        SelectionSettings selection = null;
        if (configuration.approach() == SweepPlan.Approach.TWO_PHASE) {
            ResolvedModel selector = dependencies.models().resolve(configuration.selector());
            selection = new SelectionSettings(new ModelCall(selector.client(), selector.model(), plan.selection().temperature(), plan.selection().maxAttempts()),
                    plan.selection().maxCandidates(), null);
        }
        return new ApproachContext(manifestOf(request.courseKey()), dependencies.snippets(),
                new ModelCall(generator.client(), generator.model(), properties.generation().temperature(), properties.generation().maxAttempts()),
                new ModelCall(judge.client(), judge.model(), properties.filter().temperature(), properties.filter().maxAttempts()), properties.retrieval().topK(),
                properties.retrieval().maxGroundingTokens(), properties.filter().acceptThreshold(), plan.agentic().maxRounds(), selection);
    }

    private PoolBuilder.Settings poolSettings(String generatorKey, String courseKey, ResolvedModel generator, ResolvedModel judge) {
        PipelineProperties properties = dependencies.properties();
        return new PoolBuilder.Settings("pool-" + generatorKey, "pool|" + generatorKey, courseKey, plan.pool().languages(), plan.pool().questionTypes(),
                plan.pool().difficulties(), plan.pool().itemsPerCell(), plan.pool().subsections(), plan.pool().retrievalTopM(), properties.retrieval().maxGroundingTokens(),
                properties.filter().acceptThreshold(), properties.filter().gatingModes(), generator.model(), properties.generation().temperature(),
                properties.generation().maxAttempts(), judge.model(), properties.filter().temperature(), properties.filter().maxAttempts(),
                properties.batch().maxOutputAttempts());
    }

    private CompetencyManifest manifestOf(String courseKey) {
        CompetencyManifest manifest = dependencies.manifests().get(courseKey);
        if (manifest == null) {
            throw new IllegalArgumentException("No course model for course '" + courseKey + "', have " + dependencies.manifests().keySet());
        }
        return manifest;
    }

    private String fingerprint(Map<String, String> documentHashes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            documentHashes.forEach((document, hash) -> {
                digest.update(document.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
            for (String courseKey : new java.util.TreeSet<>(dependencies.manifests().keySet())) {
                CompetencyManifest manifest = dependencies.manifests().get(courseKey);
                digest.update(courseKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                manifest.competencies().forEach(competency -> digest.update((competency.key() + competency.description()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            digest.update(String.valueOf(plan.repetitions()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            requests.forEach(request -> digest.update(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return "sweep-fingerprint=" + HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Computes the content hash of every regular file under a corpus directory.
     *
     * @param corpus the corpus root
     * @return corpus-relative path to SHA-256 hash, in path order
     */
    public static Map<String, String> hashDocuments(Path corpus) {
        try (var files = java.nio.file.Files.walk(corpus)) {
            Map<String, String> hashes = new LinkedHashMap<>();
            for (Path file : files.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(java.nio.file.Files.readAllBytes(file));
                hashes.put(corpus.relativize(file).toString(), HexFormat.of().formatHex(digest.digest()));
            }
            return hashes;
        }
        catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to hash the corpus at " + corpus, e);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
