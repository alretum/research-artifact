# CLAUDE.md — engineering standards for this repository

Read this before every change. It is prescriptive: every rule is meant to be checkable by a reviewer
against a diff, without asking the author what they intended. Where reputable sources disagree, the
disagreement is marked and the local choice is stated.

Sections 1–9 are the standard and change only when the standard changes. **[§10](#10-open-deviations)
lists where the current working tree does not yet meet it** — that section is a snapshot, and each entry
is deleted as it is fixed. References to code are by file and symbol, not line number, because line
numbers rot.

**Division of labour between the documents in this repo:**

| Document | Answers | Never contains |
|---|---|---|
| `BUILD.md` | *What* we build, in what order, and **why each design choice was made** | how to write the code |
| `PLAN.md` | Reference: what exists in Artemis, Logos/Pyris facts, the decision register | anything superseded by `BUILD.md` |
| **`CLAUDE.md`** (this file) | *How* code here is written, structured, commented, tested | design arguments |

If you want to argue for a design, the argument belongs in `BUILD.md`. See [§4](#4-comment-policy).

---

## 1. Purpose and scope

**What this repo is.** A standalone Java 25 / Spring Boot 4.1 / Spring AI 2.0 batch pipeline. Lecture
PDFs in `corpus/` go in; grounded multiple-choice questions come out, LLM-filtered, quality-scored, and
appended to a JSONL run log (later also a SQLite pool). One command, no server, no database, no
credentials beyond a model endpoint.

**What this repo is not.**

- Not an Artemis module. No Artemis, Pyris, or Logos code is imported and no such service is called at
  runtime. Delete both sibling repos from the machine and nothing here breaks (`BUILD.md` §0).
- Not a web application. `spring.main.web-application-type: none`; no REST layer, no JPA, no security.
  Do not add a web starter without editing `BUILD.md` first.
- Not a library Artemis will depend on. The port **copies** code into Artemis (`PLAN.md` §4.3), so
  portability is a matter of *shape* — package names, field names, prompt files, test idiom — not of
  packaging.

**Three audiences, and what each demands.**

| Audience | Demand | Concrete consequence |
|---|---|---|
| **Thesis experiment** (Iterations 1–2, then the 2×2) | Reproducibility and measurement | Every LLM call recorded, including failures. Every persisted record carries `schemaVersion` and `runId`. Prices applied at report time, never at write time. |
| **Standalone tool** | Someone who has never seen the repo can run it | Everything tunable in `application.yml`; no path, model name, or threshold compiled in. |
| **Eventual Artemis port** (Iteration 3) | An Artemis PR reviewer must recognise the style | Artemis's package root, formatting, test idiom, DTO field names. [§5](#5-java-and-spring-conventions), [§7](#7-testing). |

When they conflict, that order is the tie-break: an experiment that cannot be reproduced is worthless, a
tool nobody can run is a script, and a port is a week of work at worst.

---

## 2. Build and run

Verified against `build.gradle`, `gradle.properties`, `gradle-wrapper.properties`, and a real
`./gradlew tasks --all` on this checkout.

| | Version | Where |
|---|---|---|
| Gradle | **9.6.1** — always `./gradlew`, never a system `gradle` | `gradle-wrapper.properties` |
| Java | **25**, via Gradle toolchain; auto-download on | `build.gradle`, `java { toolchain { … } }` |
| Spring Boot | 4.1.0 | `gradle.properties` |
| Spring AI | 2.0.0 (BOM + `spring-ai-starter-model-openai` only) | `gradle.properties` |
| PDFBox 3.0.5 · JUnit 6.1.1 · AssertJ 3.27.6 | | `gradle.properties` |

Jackson is **not** declared: it arrives transitively as Jackson 3 (`tools.jackson`) via
`spring-boot-starter-jackson` and Spring AI. See [§5.11](#511-json-and-serialization).

**Commands that exist:**

```bash
./gradlew build                 # compile + test + jar — this is the gate
./gradlew test                  # tests only
./gradlew test --tests 'PageChunkerTest'
./gradlew test --tests 'PageChunkerTest.chunk_startsNewChunkPerDocument'
./gradlew compileJava           # fastest "does it still build"
./gradlew javadoc               # doc-comment syntax check; must not fail
./gradlew bootJar               # -> build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar
./gradlew bootRun --args='--count=5 --topic=05 Linear Programming - Duality'
./gradlew clean
./gradlew dependencies --configuration compileClasspath
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --count=5
```

**Commands that do not exist — do not invoke or document them.** `spotlessApply`, `spotlessCheck`,
`checkstyleMain`, `modernizer`, `jacocoTestReport`, `run`. Neither the Spotless nor the Checkstyle plugin
is applied, and no `checkstyle.xml` / `artemis-spotless-style.xml` / `artemis-spotless.importorder`
exists here. Formatting is currently maintained by hand against Artemis's settings
([§5.10](#510-naming-and-formatting)). `PLAN.md` D20 says to adopt those plugins on day one; that has not
happened. Do it in one commit touching only `build.gradle` and the three config files, so the reformat
diff stays separable from behaviour.

**Runtime prerequisites for anything that calls a model:**

```bash
ollama pull gpt-oss:20b && ollama pull nomic-embed-text
ollama serve                       # must be listening on :11434
```

`PipelineRunner` injects `EmbeddingModel` and `ChatClient.Builder` as **required** beans, so the
application will not start without a reachable OpenAI-compatible endpoint. Unit tests must therefore
never boot the Spring context ([§7](#7-testing)).

---

## 3. Architecture and package layout

**One Gradle module.** `settings.gradle` declares a single project. `PLAN.md` §4.3's two-module
(`mcq-core` / `mcq-app`) and five-port design is **superseded** by `BUILD.md` §1 ("one interface, not
five"). Do not reintroduce modules or ports without editing `BUILD.md`.

Root package: `de.tum.cit.aet.artemis.hyperion.mcq` — Artemis's, so the port is a move.

Package-by-feature (here: by pipeline stage) is the layout Spring Boot's own reference documentation
demonstrates, and it mandates nothing further
([Spring Boot, *Structuring Your Code*](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)).
Two honest caveats: Boot's example nests the value type *inside* the feature package (`customer/Customer`)
rather than in a shared `domain/`, so our `domain` package is a layer name in a feature-sliced tree — a
deliberate deviation, justified because `domain` is exactly the unit copied at port time. And Simon Brown
argues both package-by-layer and package-by-feature are inferior to *package-by-component*
([simonbrown.je/modular-monolith](https://simonbrown.je/modular-monolith/)); we are not adopting that, but
a reviewer who knows it will notice.

### 3.1 Packages, and what each may import

| Package | Holds | May import | Must **never** import |
|---|---|---|---|
| `domain` | Shared vocabulary: records and enums, no behaviour beyond derived accessors | JDK only | Spring, Spring AI, Jackson, PDFBox, **and every sibling package** |
| `ingest` | Reading `corpus/`: page extraction, chunking, the topic catalogue, and this stage's own extraction diagnostics | `domain`, PDFBox, JDK | Spring AI, `grounding`, `retrieval`, `generation`, `filter`, `telemetry`, `app` |
| `retrieval` | Embedding index, similarity ranking; **implements** `grounding.SnippetSource` | `domain`, `grounding` (abstraction only), `org.springframework.ai.embedding` | PDFBox, `ingest`, `generation`, `filter`, `app` |
| `grounding` | The `SnippetSource` abstraction; assembly of the prompt block | `domain` | any implementation package, Spring AI |
| `llm` | Plumbing shared by every model-calling stage: structured-output converters, prompt template rendering, `CallRecord` construction | `domain`, Spring AI, Jackson | `generation`, `filter`, `ingest`, `retrieval`, `telemetry`, `app` |
| `generation` | The generation call, output mapping, item validation | `domain`, `llm`, `grounding` types, `org.springframework.ai.chat` | `ingest`, `retrieval`, `filter`, `app`, PDFBox |
| `filter` | Deterministic pre-checks, the judge call, threshold application | `domain`, `llm`, `grounding` types, `org.springframework.ai.chat` | `generation`, `ingest`, `retrieval`, `app` |
| `telemetry` | Run-log writing, Markdown/CSV rendering, reporting | `domain`, Jackson | Spring AI, `llm`, `generation`, `filter`, `ingest`, `retrieval`, `app` |
| `app` | `@SpringBootApplication`, `PipelineProperties`, wiring, CLI arguments | everything | — |

Reserved, in this order: `persistence` (SQLite pool), `report`, `experiment` (the 2×2 runner).

### 3.2 The rule that decides where new code goes

> **A type belongs to the package that owns the *stage* it runs in. If two stages need it, it moves
> down — a data type to `domain`, behaviour to a package of its own (`llm` for model plumbing). It never
> moves sideways.**

Corollaries, each `grep`-checkable:

1. **`domain` has no outbound edges.** `grep -rn "^import" src/main/java/**/domain/` must show only
   `java.*`. This is what makes the port a copy of one file.
2. **Sibling stage packages do not import each other.** Adapters depend on abstractions; abstractions
   never depend on adapters. `retrieval` → `grounding` is correct; the reverse would not be.
3. **Shared behaviour goes in `llm` (if it touches a model) or its own package (if it does not) — never
   in whichever stage needed it first.** `llm.StructuredOutputs`, shared by `generation` and `filter`, is
   this rule applied correctly.
4. **Result objects carry telemetry outward; nothing calls into `telemetry`.** `generation` and `filter`
   return `Mcq.CallRecord` inside their own `Result` records; `app` routes them to
   `telemetry.RunLogWriter`. (`PLAN.md` §4.3 dropped a `TelemetrySink` port for this reason: a return
   value is easier to test than an injected sink.) The corollary runs both ways — `telemetry` writes
   records and knows nothing about how a model produced them, so it must not import `llm` or Spring AI.

**Enforce it, don't remember it.** Add `com.tngtech.archunit:archunit-junit5` and one test encoding the
table above. Artemis does this (`Artemis/src/test/java/.../shared/architecture/ArchitectureTest.java`,
`.../hyperion/architecture/`) and demonstrates a habit worth copying: the *rationale* for a rule lives in
ArchUnit's `.because(...)` message, printed at the moment of violation, rather than in a comment beside
the code it constrains.

```java
@Test
void domainDependsOnNothing() {
    noClasses().that().resideInAPackage("..mcq.domain..").should().dependOnClassesThat()
            .resideOutsideOfPackages("java..", "..mcq.domain..")
            .because("domain is copied verbatim into Artemis at port time; any dependency has to be ported with it")
            .check(classes);
}
```

### 3.3 When to introduce an interface

`BUILD.md` §1 is explicit: **one interface, not five.**

- Introduce one when **two implementations exist, or one is named in `BUILD.md` for a milestone that has
  started.** `grounding.SnippetSource` qualifies only because `PyrisSnippetSource` is a named deliverable
  (`PLAN.md` §4.4). `QualityEvaluator` qualifies because the stub and the real framework coexist.
- **"Easier to mock" is not a justification, and neither is "Spring needs it".** Mockito 5's inline mock
  maker is the default and mocks final classes and static methods without an interface
  ([Mockito 5 release notes](https://github.com/mockito/mockito/releases/tag/v5.0.0)), and Spring Boot
  sets `spring.aop.proxy-target-class=true` by default, so CGLIB subclass proxies are used whether or not
  an interface exists
  ([`AopAutoConfiguration`](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/aop/AopAutoConfiguration.html)).
  A single-implementation interface is the *Speculative Generality* smell —
  [Fowler, *InterfaceImplementationPair*](https://martinfowler.com/bliki/InterfaceImplementationPair.html):
  *"Using interfaces when you aren't going to have multiple implementations is extra effort to keep
  everything in sync"* and it *"hides the cases where you actually do provide multiple
  implementations."* Same conclusion from
  [Fowler, *Yagni*](https://martinfowler.com/bliki/Yagni.html) (cost of carry) and the Rule of Three
  ([Fowler, *Refactoring*](https://martinfowler.com/books/refactoring.html)).
- **Contested — know which side you are on.** Ports-and-adapters
  ([Cockburn](https://alistair.cockburn.us/hexagonal-architecture/)) argues for an abstraction at every
  boundary regardless of implementation count. Against it: Spring's structuring guidance prescribes no
  port layer (link above; it points at [Spring Modulith](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
  for teams wanting enforced boundaries), and Fowler warns against paying for separation you do not need
  ([*PresentationDomainDataLayering*](https://martinfowler.com/bliki/PresentationDomainDataLayering.html)).
  **This project uses package-by-feature with inward-facing abstractions only where a second
  implementation is scheduled** — at ~14 production classes the hexagonal ceremony costs more reading than
  it saves, and since the port copies code rather than linking to it, a port layer buys nothing at the
  boundary that matters.

### 3.4 Worked example: adding Pyris as a second grounding backend

| Part | Lands in | Why there |
|---|---|---|
| HTTP client for `POST /api/v1/search/lectures`, mapping to `Mcq.Snippet` | **new** `retrieval/PyrisSnippetSource.java` implementing `grounding.SnippetSource` | it is an adapter; adapters implement the abstraction |
| Nothing | `domain` | `Snippet` already has the fields; a `pyrisUrl` here would give `domain` an outbound concern |
| Nothing | `grounding` | `GroundingAssemblyService` consumes `List<Snippet>`; a new source is invisible to it. **If assembly had to change, the abstraction is wrong.** |
| `mcq.retrieval.backend: local\|pyris`, `mcq.retrieval.pyris.{base-url,token}` | `application.yml` + `app/PipelineProperties.java` | every tunable is config ([§8](#8-configuration-and-secrets)) |
| Bean selection | `app` (`@Configuration` method or `@ConditionalOnProperty`) | wiring is `app`'s only job |
| One unit test with a stubbed HTTP exchange asserting the mapping | `src/test/java/.../retrieval/PyrisSnippetSourceTest.java` | no network in tests ([§7](#7-testing)) |
| The argument for having two backends at all | `BUILD.md` | not a comment ([§4](#4-comment-policy)) |

One new file, two config keys, one test. Nothing in `generation`, `filter`, `ingest`, `domain`, `llm` is
touched. **If a change of this kind needs edits in more than one stage package, stop and re-read §3.2.**

---

## 4. Comment policy

**The section most likely to be violated, so the longest.** It has a documented failure mode with AI
assistants specifically:
[anthropics/claude-code#61305](https://github.com/anthropics/claude-code/issues/61305) reports exactly
this — explanatory narration reappearing in generated code across sessions *despite* repeated instructions
in a project's `CLAUDE.md`, self-corrected when reminded and regressing on the next turn. Treat this
section as load-bearing, not as background.

### 4.1 The rule

> **Comments describe behaviour, contracts, and non-obvious mechanics. They never justify a design
> decision, narrate the author's reasoning, reference a milestone, or compare this codebase to another
> one.**

Javadoc on public types and methods — what it does, its parameters, its contract — is wanted. Prose
explaining *why the design is as it is* belongs in `BUILD.md` or `PLAN.md`.

### 4.2 The discriminator

Ask: **does a reader about to change this code need the sentence in order not to break something?** If
yes, it is a contract or a mechanic — keep it. If it only satisfies curiosity about how the code came to
be, it is rationale — move it.

The grammar gives it away:

| In the code | Not in the code |
|---|---|
| Declarative: *"Templates are cached for the lifetime of the bean."* | Causal: *"…because re-reading the file per call would be slow."* |
| Imperative constraint: *"Component names must stay identical to Artemis's `GeneratedQuizQuestionDTO`."* | Comparative: *"This mirrors Artemis's DTO, which keeps the port cheap."* |
| Contract for the caller: *"so `accepted` can be recomputed from `aggregateScore` without a new call."* | Justification for the author: *"applying the threshold outside the call is better than letting the model decide."* |
| Precondition: *"`index` must be called before `search`."* | Roadmap: *"M1 will persist the vectors."* |
| Units, ranges, encodings: *"severity in [0, 1], 1 means no defect."* | History: *"was 0–5 before the pilot."* |

Banned outright, no judgement needed:

- References to `BUILD.md` / `PLAN.md` sections, decision ids (`D13`), milestones (`M0`–`M5`).
- *deliberately*, *intentionally*, *I chose*, *we decided*, *rather than* / *instead of* when comparing
  designs rather than describing a branch.
- Comparisons of this codebase to Artemis, Pyris, Iris, or Logos — **except** as an imperative
  compatibility constraint (row 2 above).
- Commented-out code, and any comment describing *the change* rather than *the code*.
- `TODO` without an owner. Prefer a checklist item in `BUILD.md`. One narrow exception is worth borrowing
  from Git: a `NEEDSWORK:` comment marks a **design decision not yet made**, and removing it with an
  explanation in the commit message is itself a valid change
  ([Git `Documentation/CodingGuidelines`](https://github.com/git/git/blob/master/Documentation/CodingGuidelines)).

### 4.3 Four before/after examples from this repo

**(1) Design justification + external comparison + a statistic that will drift.** `ingest/PageChunker`

```java
// BEFORE
/**
 * Page-aligned chunking: consecutive pages of one document are merged until a token target is
 * reached, so a chunk never straddles two documents and always names a page range.
 * <p>
 * Pyris chunks lecture units per page. Pages are the right <em>unit</em> — they are a real semantic
 * boundary and they make source attribution possible — but they are the wrong <em>size</em> for this
 * corpus: at ~138 tokens per page (BUILD.md §3.1) a single slide is too thin for an embedding to
 * discriminate on. Hence pages as the unit, a token target as the size.
 */
```

```java
// AFTER — what the file says today; keep it this way
/**
 * Groups consecutive pages of a document into chunks of roughly {@code targetTokens}.
 * <p>
 * A chunk never spans two documents, and always covers a contiguous page range. A page whose own
 * length reaches {@code maxTokens} becomes a chunk on its own.
 */
```

Everything a caller needs survives; the argument, the corpus statistic, and the Pyris comparison move to
`BUILD.md` §4, where they already live.

**(2) Milestone references and a roadmap.** `retrieval/EmbeddingSnippetSource`

```java
// BEFORE
/**
 * In-memory vector index with cosine top-k retrieval.
 * <p>
 * Deliberately the simplest thing that works for M0: this corpus is ~370 chunks, so a linear scan is
 * microseconds and a vector database would be infrastructure with no benefit. M1 persists the vectors
 * so re-runs skip re-embedding; the retrieval maths does not change.
 */
```

```java
// AFTER — current state
/**
 * A {@link SnippetSource} backed by an in-memory embedding index with cosine similarity ranking.
 * <p>
 * {@link #index(List)} must be called before {@link #search(String, int, String)}. Chunks with
 * byte-identical text are indexed once.
 */
```

The "after" adds a real precondition the "before" never stated, and drops three sentences that become
false the moment M1 lands. Stale rationale is worse than none — the standard argument for keeping decision
records outside the source
([Nygard, *Documenting Architecture Decisions*](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions);
Git's guideline puts it bluntly: *"comments invariably tend to stale out when the code they were describing
changes"*).

**(3) Provenance plus a port argument.** `llm/PromptTemplateService` class Javadoc, as it once stood:

```java
// BEFORE
/**
 * Renders {@code {{placeholder}}} templates from the classpath.
 * <p>
 * Copied from Artemis's {@code HyperionPromptTemplateService} (the only code borrowed from Artemis,
 * BUILD.md §0) so prompt files move between the two projects unchanged.
 */
```

```java
// AFTER
/**
 * Renders {@code {{placeholder}}} templates loaded from the classpath.
 * <p>
 * Each template is read once and cached for the lifetime of this bean, so edits to a {@code .st} file
 * take effect only after a restart. A placeholder with no matching variable is left verbatim in the
 * output and logged at WARN.
 */
```

Two facts a caller can be surprised by replace one fact about the repo's history. Attribution of copied
code is a licensing matter: record it in `README.md`/`NOTICE`, not in Javadoc.

**(4) A comparison that carries a constraint — the one case where naming Artemis is allowed.**
`domain/Mcq` class Javadoc

```java
// BEFORE — descriptive, so a future edit could "improve" the field names without noticing
/**
 * Immutable domain types for the MCQ pipeline.
 * <p>
 * {@link McqItem} and {@link AnswerOption} are field-compatible with Artemis's
 * {@code GeneratedQuizQuestionDTO} and {@code GeneratedQuizAnswerOptionDTO}; research-side metadata is
 * held separately in {@link ItemProvenance}.
 */
```

```java
// AFTER — what the file says today (imperative: states the constraint, not its benefit)
/**
 * Immutable domain types for the MCQ pipeline.
 * <p>
 * The component names of {@link McqItem} and {@link AnswerOption} must stay identical to Artemis's
 * {@code GeneratedQuizQuestionDTO} and {@code GeneratedQuizAnswerOptionDTO}. Research metadata belongs
 * in {@link ItemProvenance} and must not be added to {@link McqItem}.
 */
```

Contrast the sentence `grounding/SnippetSource` used to carry, *"The signature mirrors
`IrisLectureSearchApi.searchLectures(query, limit, courseIds)`."* It constrained nothing and told an
implementer nothing the `@param` tags did not already say, so it was deleted.

**A positive example, for calibration.** `llm/StructuredOutputs`' class Javadoc is exactly right:

```java
/**
 * Builds {@link BeanOutputConverter}s that tolerate the JSON deviations language models commonly emit.
 * <p>
 * Models writing mathematical notation produce escapes such as {@code \(} and {@code \[} inside JSON
 * strings, which a strict parser rejects. This mapper accepts any backslash escape, treating the escaped
 * character literally, and tolerates single quotes, trailing commas, unescaped control characters and
 * unknown fields.
 */
```

It states the failure it handles and the exact leniencies enabled — facts a maintainer needs before
tightening the mapper. It never says *why* leniency was chosen over a retry, or when the problem was
discovered. That sentence would have gone in `BUILD.md`.

### 4.4 Javadoc rules

- **Required** on every `public` type and method, except accessors and overrides whose behaviour is fully
  implied by name and signature. Note the caveat that comes with that exemption:
  [Google Java Style §7.3.1](https://google.github.io/styleguide/javaguide.html#s7.3-javadoc-where-required)
  — *"it is not appropriate to cite this exception to justify omitting relevant information that a typical
  reader might need to know."*
- **Start with a summary fragment** — a noun or verb phrase, not a full sentence
  ([Google §7.2](https://google.github.io/styleguide/javaguide.html#s7.2-summary-fragment)).
  `/** @return the customer ID */` is called out there as incorrect; write
  `/** Returns the customer ID. */`. A Javadoc block of nothing but `@param`/`@return` produces
  `warning: no main description`, and Artemis's Spotless config actively strips such stubs
  (`Artemis/gradle/spotless.gradle`, *"Remove unhelpful javadoc stubs"*). Check with
  `./gradlew javadoc 2>&1 | grep "no main description"`.
- **Third person, describing behaviour:** *"Gets the label."* not *"Get the label."*
  ([Oracle, *How to Write Doc Comments*](https://www.oracle.com/technical-resources/articles/java/javadoc-tool.html)).
- **Records: all components or none.** Document a component only when its meaning is not obvious from name
  and type — then document the rest, or `javadoc` reports the gaps. Prefer documenting units, ranges,
  nullability, and wire values (`Mcq.ModeVerdict.severity`, `Mcq.CallRecord.stage` are good).
- `@throws` for every unchecked exception a caller should handle or avoid — `CorpusLoader.load` and
  `GroundingAssemblyService.assemble` already do.
- **Interface documentation never describes the implementation.** Oracle's rule for the JDK (*"The
  Specification describes all aspects of the behavior of each method on which a caller can rely. It does
  not describe implementation details"*) and Ousterhout's (*"Important to separate these: do not describe
  the implementation in the interface documentation!"*).

### 4.5 Where the sources agree, where they disagree, and what this project chose

**The popular maxim is two claims wearing one coat, and its vocabulary is incoherent across sources.** The
Linux kernel says *"NEVER try to explain HOW your code works… you want your comments to tell WHAT your code
does, not HOW"*
([kernel coding style §8](https://docs.kernel.org/process/coding-style.html#commenting)). Atwood says
*"Code can only tell you how the program works; comments can tell you why"*
([Coding Horror](https://blog.codinghorror.com/code-tells-you-how-comments-tell-you-why/)). Same target,
inverted words. Do not settle an argument here by quoting the slogan.

**Uncontested (every source agrees):** do not restate mechanics the reader can see. Linux §8, Google C++
("Do not state the obvious"), LLVM (*"Avoid restating the information that can be inferred from the API
name or signature"* — [LLVM Coding Standards](https://llvm.org/docs/CodingStandards.html#commenting)),
Oracle (*"The ideal comment goes beyond those words"*), McConnell's *Code Complete 2* ch. 32 checklist
(*"Have redundant, extraneous, and self-indulgent comments been removed?"*), Martin, Ousterhout
(*"Mistake #1: comments duplicate code"*), and
[Google's *To Comment or Not to Comment?*](https://testing.googleblog.com/2017/07/code-health-to-comment-or-not-to-comment.html)
(*"avoid comments that just repeat what the code does. These are just noise"*).

**Also uncontested, and directly supporting the bans in §4.2:** no authoritative source anywhere endorses
change-narration, milestone references, cross-codebase comparison, or author-reasoning narration in source.
Git routes the first to the log **by name** — *"in-code comments explain how the code works and what is
assumed from the surrounding context. The log messages explain what the changes wanted to achieve and why
the changes were necessary"* — and the kernel routes it to the commit changelog by name.

**Contested — and this project overrules two respectable positions:**

1. **The canonical "good WHY comment" in the literature is an algorithm-choice justification.** Atwood's
   argument is Jef Raskin's (*"Comments Are More Important Than Code"*, ACM Queue 3(2), 2005), whose
   example is a comment explaining why Boyer–Moore was chosen over binary search. **This policy forbids
   that comment as written.** Note the honest reframing, though: *"Boyer–Moore; O(n/m) expected on the
   alphabet sizes we see. Do not replace without re-measuring."* is a constraint on future edits and
   survives the rule. **The strong form of this policy is not "delete the rationale" but "rewrite it as a
   constraint, or move it to `BUILD.md`."**
2. **[Google's C++ Style Guide](https://google.github.io/styleguide/cppguide.html#Comments) explicitly
   permits, in function *definitions*, "explain why you chose to implement the function in the way you did
   rather than using a viable alternative."** We narrow that: it is confined there to one function's body,
   never to interface comments and never to project history. If you want the carve-out, phrase it in
   rule 1's constraint form.
3. **Ousterhout — the most credible pro-comment authority — lists "Rationale for the current design: why
   the code is this way" as something higher-level comments should carry**
   ([Stanford CS 190 notes](https://web.stanford.edu/~ouster/cgi-bin/cs190-winter18/lecture.php?topic=comments);
   *A Philosophy of Software Design*, ch. 12–16). His disagreement with Martin is real and unresolved —
   see [aposd-vs-clean-code](https://github.com/johnousterhout/aposd-vs-clean-code), where they conclude
   *"we struggled to find areas of agreement on this topic."* **But the same source supplies our rule for
   the case that matters:** *"Document each thing exactly once: don't duplicate documentation (it won't get
   maintained)"* and *"don't use comments in one place to describe design decisions elsewhere"*, with
   cross-module decisions going into a central design-notes document. In a repo that already has
   `BUILD.md`, a rationale comment is a **second copy that will drift** — Ousterhout's own argument,
   applied to his own carve-out.

**The local formulation, stated so it can be cited:**

> A comment must be something a reader needs in order to **use or safely modify this code**: its
> behaviour, contract, invariants, units, boundary conditions, non-obvious mechanics, and the constraints
> that make the current implementation necessary. It must **not** be an account of **how the code came to
> be**: alternatives weighed, decisions taken, milestones, comparisons to other systems. The first kind is
> a fact about the code and stays true as long as the code does. The second is a fact about a moment in the
> project's history and starts rotting the day it is written.

That framing is consistent with Oracle's deliberate split between an API specification and a programming
guide kept in *separate documents*; with Google Java §7.3.4 (*"whenever an implementation comment would be
used to define the overall purpose or behavior of a class or member, that comment is written as Javadoc
instead"*); with Google C++'s declaration-describes-use / definition-describes-operation axis; with Git's
comments-vs-log split; with Ousterhout's document-once rule; and with ADRs as the accepted home for
rationale — a technique in ThoughtWorks' **Adopt** ring, with the explicit recommendation to
[store them in source control rather than a wiki](https://www.thoughtworks.com/en-us/radar/techniques/lightweight-architecture-decision-records),
which is what `BUILD.md` is.

Two notes, for honesty:

- This repo is **stricter than Artemis**, whose guideline is only *"Add Javadoc and inline comments to
  clarify code and intent"* (`server-development.mdx` §Comments). A deliberate local tightening, not a
  claim that Artemis is wrong.
- The asymmetry here — Javadoc wanted, narration not — is the right one for AI-assisted work. An
  evaluation of LLM-generated Javadoc found experts rated 58.8% equivalent to and 27.7% *better than* the
  human originals ([arXiv:2408.14007](https://arxiv.org/abs/2408.14007)); the problem is not that
  assistants write bad doc comments, it is that they narrate. Where that narration is genuinely valuable,
  [capture it as a decision log on the PR](https://www.oreilly.com/radar/agentic-code-review/) or as a
  paragraph in `BUILD.md` — not as a comment.

---

## 5. Java and Spring conventions

### 5.1 Dependency injection

- **Constructor injection only.** No field or setter injection, no `@Autowired` (optional on a single
  constructor; Spring's reference documentation recommends constructors for mandatory dependencies —
  [*Dependency Injection*](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html);
  Artemis says the same). Every current class complies.
- Injected fields are `private final`, one per line, at the top of the class before the constructor —
  Artemis's layout, so diffs against Hyperion stay readable.
- **No `@Lazy`, no `ObjectProvider`, no `@Value` on constructor parameters.** Circular dependencies are a
  design error, not something to defer (Artemis forbids the workarounds outright). `@Value` is replaced by
  `PipelineProperties` ([§8](#8-configuration-and-secrets)).
- **Not everything is a bean.** Classes with nothing to inject are plain objects constructed where used:
  `CorpusLoader`, `PageChunker`, `EmbeddingSnippetSource` are correctly un-annotated and built by
  `PipelineRunner`; `StructuredOutputs` is a `final` class with a private constructor and static factories.
  Add `@Service` only when Spring must supply a collaborator or the object must be a singleton.

### 5.2 Immutability

Immutable by default (*Effective Java*, 3rd ed., item 17: *"Classes should be immutable unless there's a
very good reason to make them mutable"*): fields `private final`, no setters, collections copied in and out
(`List.copyOf`, `.toList()` — already used in `GroundingAssemblyService.assemble` and `PageChunker.flush`).

**Records are only shallowly immutable, and this repo relies on that being fixed.** Oracle's own guidance
is explicit: *"If the components of your record are not immutable, you should consider making defensive
copies of them in both the canonical constructor and the accessors"*
([dev.java, *Records*](https://dev.java/learn/records/)). Every `Mcq` record carrying a `List` or `Map` —
`McqItem.options`, `GroundingContext.snippets`, `ItemProvenance.groundingChunkIds`,
`FilterDecision.modeVerdicts`, `RunRecord.calls` — is currently handed a caller's collection and stores the
reference. A compact constructor with `List.copyOf(...)` / `Map.copyOf(...)` closes it in one line per
component and also gives you the `Objects.requireNonNull` check (item 49: *"detect errors as soon as
possible after they occur"*). Records also enforce this on the deserialization path, because
*"deserialization creates a new record object by invoking a record class's canonical constructor"*
([Oracle, *Records*](https://docs.oracle.com/en/java/javase/25/language/records.html)).

**`final` fields always; `final` classes and methods almost never on a Spring bean.** `private final`
fields are unconditionally safe. But Spring Boot proxies with CGLIB by default, and
[*Understanding AOP Proxies*](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)
states *"Final classes cannot be proxied, because they cannot be extended"* and *"Final methods cannot be
advised, because they cannot be overridden."* The failure is **silent**: the context starts and the advice
simply never runs. So never mark a `@Service`/`@Component` class — or any method carrying `@Transactional`,
`@Cacheable`, `@Retryable`, `@Async`, `@Observed` — as `final`. `final` on a non-bean helper such as
`llm.StructuredOutputs` is correct and expected.

The one mutable object, `EmbeddingSnippetSource.entries`, is a **known hazard**: `index(...)` clears and
refills it while `search(...)` streams over it, so the class is not thread-safe and a failure mid-`index`
leaves a partial index. Concurrency defaults to 1 today. Before raising it, either build the list locally
and assign once, or state the single-threaded contract in Javadoc. Do not reach for a lock first.

### 5.3 Records for data, classes for behaviour

- **Records** ([JEP 395](https://openjdk.org/jeps/395)) for values: domain types, configuration,
  structured-output shapes, result envelopes. `Mcq`, `PipelineProperties`, `McqGenerationService.Result`
  are all correct.
- **Classes** for anything with collaborators or a lifecycle; `final` with a private constructor for
  static-only helpers.
- A structured-output record stays **private or package-private, nested in the service that calls the
  model** (`McqGenerationService.GeneratedItem`, `McqFilterService.FilterOutput`). It is a wire shape, not
  domain — as in `HyperionQuizQuestionGenerationService.GeneratedQuestionsOutput`.
- **`domain/Mcq.java` should be split.** ~190 lines, 13 nested types, four stages. Artemis's convention —
  one top-level type per file under `domain/` and `dto/` — is what a port reviewer expects, and it makes
  imports name the concept instead of `Mcq.Page`, `Mcq.Chunk`, `Mcq.CallRecord`. Split when the next type
  is added.
- **Sealed interfaces** ([JEP 409](https://openjdk.org/jeps/409)) with pattern-matching `switch`
  ([JEP 441](https://openjdk.org/jeps/441)) are right when a result has genuinely alternative shapes —
  Artemis uses this for `QuizQuestionRefinementResponseDTO`. The current
  `Result(item, prompt, call, failure)` with a nullable `item` and a `succeeded()` predicate is weaker: it
  permits `item != null && failure != null`. Prefer
  `sealed interface Result { record Generated(…); record Failed(…); }` once a caller has to branch.

### 5.4 Java version features

**Final features only. No `--enable-preview`, ever** — a preview flag makes every recorded run
non-reproducible on the next JDK. Verified against the
[JDK 25 feature list](https://openjdk.org/projects/jdk/25/):

| Final in 25 — use | Preview/incubator in 25 — banned |
|---|---|
| records, sealed types, pattern-matching `switch`, text blocks, `var`, enhanced `instanceof`; unnamed variables `_` ([JEP 456](https://openjdk.org/jeps/456), final since 22 — Artemis uses `thenAnswer(_ -> …)`); stream gatherers ([485](https://openjdk.org/jeps/485)); Markdown doc comments ([467](https://openjdk.org/jeps/467)); scoped values ([506](https://openjdk.org/jeps/506)); module import declarations ([511](https://openjdk.org/jeps/511)); flexible constructor bodies ([513](https://openjdk.org/jeps/513)); virtual threads ([444](https://openjdk.org/jeps/444)) | primitive types in patterns ([507](https://openjdk.org/jeps/507), third preview); structured concurrency ([505](https://openjdk.org/jeps/505), fifth preview); PEM encodings (470); stable values (502); Vector API (508, incubator); JFR CPU-time profiling (509, experimental) |

Style limits regardless of availability:

- `var` only for long generic or DTO types, never for `int`, `long`, `String`, or where `List` vs `Set`
  matters (Artemis's rule). Oracle's own guidance relaxes "program to the interface" for locals
  specifically — *"Don't worry too much about 'programming to the interface' with local variables"*
  ([OpenJDK LVTI style guide, G5](https://github.com/openjdk/amber-docs/blob/master/site/guides/lvti-style-guide.md)).
- **Do not use `import module`** ([JEP 511](https://openjdk.org/jeps/511)), even though it is final in 25.
  Google Java Style now rules on it directly —
  [§3.3.1.1 *No module imports*](https://google.github.io/styleguide/javaguide.html#s3.3.1.1-module-imports):
  *"Module imports are not used"*, with `import module java.base;` as the example — alongside
  [§3.3.1](https://google.github.io/styleguide/javaguide.html#s3.3.1-wildcard-imports): *"Wildcard
  ('on-demand') imports, static or otherwise, are not used."* Both are what Artemis's Spotless import
  ordering and its wildcard-refusing custom step assume.
- Do not mix `///` Markdown doc comments and `/** */` in one file. Note `\uNNNN` escapes are **not**
  processed inside text blocks, which matters when a fixture contains `{{…}}` or LaTeX; and per Oracle's
  [text-block guidelines](https://docs.oracle.com/en/java/javase/21/text-blocks/index.html) (G2, G6) do not
  use a text block for a single line, and extract one out of a complex expression into a local.
- **Structured concurrency is preview *and was redesigned in 25*** (static `StructuredTaskScope.open()` plus
  a `Joiner`, replacing subclassing), so anything written against it today needs edits at the next JDK. The
  final-API equivalent for fan-out is `Executors.newVirtualThreadPerTaskExecutor()` with `invokeAll` in
  try-with-resources.

**Virtual threads are final, and the answer here is still no.** Oracle is blunt about what they are:
*"Virtual threads are not faster threads… They exist to provide scale (higher throughput), not speed (lower
latency)"*, and the adoption threshold is *"if your application never has 10,000 virtual threads or more, it
is unlikely to benefit"*
([Oracle, *Virtual Threads*](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html)). Three
further reasons specific to this project:

- Measurement runs must execute at concurrency 1 so GPU occupancy is attributable (`PLAN.md` §3.4, D17).
- A rate-limited model endpoint is a **bounded** resource. `newVirtualThreadPerTaskExecutor()` will happily
  open unbounded requests against a backend that answers 429. If concurrency is ever raised, the shape is a
  `Semaphore` for admission control *plus* virtual threads for the blocking wait — never virtual threads
  alone. Never pool them: *"each should represent not some shared, pooled, resource but a task."*
- **Virtual threads are daemon threads**, so a CLI JVM can exit before work finishes;
  `spring.main.keep-alive=true` is required if `spring.threads.virtual.enabled` is ever turned on
  ([Boot, *SpringApplication*](https://docs.spring.io/spring-boot/reference/features/spring-application.html)).
  Enabling them also makes every thread-pool property a no-op
  ([Boot, *Task Execution and Scheduling*](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html)).

Raising concurrency is an M2 decision with a measurement consequence, not a performance tweak.

### 5.5 `Optional`

- **Return type only.** The JDK's own API note: *"`Optional` is primarily intended for use as a method
  return type where there is a clear need to represent 'no result'… A variable whose type is `Optional`
  should never itself be `null`"*
  ([`java.util.Optional`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html)).
- Use it when **absence is a normal lookup outcome** — `CorpusLoader.extract` returning
  `Optional<Extracted>` for an unreadable PDF is exactly right.
- **Never a parameter.** This part is unanimous: Brian Goetz, *"You should almost never use it as a field of
  something or a method parameter"*; Stuart Marks, *"it doesn't really work for making parameters optional —
  forces call sites to create Optionals for everything."* Overload instead.
- **Never wrap a container** — collection, map, array, stream, or another `Optional`. Return an empty
  `List`. Use `OptionalInt`/`OptionalLong`/`OptionalDouble` rather than an `Optional` of a boxed primitive
  (*Effective Java* item 55).
- **`orElseThrow()`, never `get()`.** Goetz: *"During the Java 9 time frame, we proposed to deprecate
  `Optional.get()`… we introduced `orElseThrow()` in 10 as a more transparently named synonym for the
  current pernicious behavior of `get()`."* Prefer `ifPresent`, `map`, `orElse`, `orElseThrow`.
- `Optional` is **not `Serializable`** and is a value-based class — never synchronise on one, never persist
  one.
- Import it; never write `java.util.Optional` inline.

**Fields and record components: this is genuinely contested, so the local choice is stated rather than
asserted.** Four reputable positions exist:

| Position | Held by |
|---|---|
| Ban outright | [Oracle dev.java, *Optionals* rule 5](https://dev.java/learn/api/streams/optionals/); Stuart Marks, rule 6; JetBrains' `OptionalUsedAsFieldOrParameterType` inspection |
| Nullable field, `Optional` **getter** | [Colebourne, *Java SE 8 Optional, a pragmatic approach*](https://blog.joda.org/2015/08/java-se-8-optional-pragmatic-approach.html) |
| `Optional` fields as the default | [Parlog, rebutting Colebourne](https://nipafx.dev/stephen-colebourne-java-optional-strict-approach/) |
| **Permitted under named conditions** — many independently-optional fields, subclassing infeasible, primitive types where `null` cannot express absence | *Effective Java* item 55 (the `NutritionFacts` discussion) — Bloch does **not** hold the blanket ban he is usually cited for |

**This project follows Colebourne's shape, which is also what Goetz's own Amber design note does**
([*Data-Oriented Programming for Java: Beyond Records*](https://openjdk.org/projects/amber/design-notes/beyond-records),
where an `Optional<String>` state description is backed by a nullable field): **nullable record components
marked with JSpecify `@Nullable`, plus a non-component `Optional` accessor where a caller genuinely
benefits.** No `Optional` components — a record component's type is simultaneously the field, the accessor
return, and the constructor parameter type, so an `Optional` component forces `Optional` into the
constructor and still permits a bare `null` being passed in. What is *not* acceptable is the current state:
components documented as "may be `null`" with nothing the compiler can check ([§5.6](#56-nullability)).

### 5.6 Nullability

- Use **JSpecify** `org.jspecify.annotations.@Nullable` — what Artemis's Hyperion code uses and what
  Spring Framework 7 / Boot 4 standardised on
  ([Spring, *Null Safety*](https://docs.spring.io/spring-framework/reference/core/null-safety.html),
  [jspecify.dev](https://jspecify.dev/)). **Currently unused here** and it should be added: several record
  components and return values are nullable and say so only in prose (`Mcq.AnswerOption.hint`,
  `Mcq.CallRecord.errorMessage`, `McqGenerationService.Result.item`).
- **`@NullMarked` goes in `package-info.java`, one file per package.** Packages are **not** hierarchical —
  marking `…mcq` does not mark `…mcq.domain`. That is 9 files for the current layout, which is what Spring
  itself does (`spring-core`'s and `spring-boot`'s `package-info.java` are literally `@NullMarked package …;`).
  Do **not** mark `module-info` instead: *"Even if you annotate the module for a library as `@NullMarked`,
  this has no effect for users who place the library on the class path"* — and a Boot fat jar is classpath.
- Declare the dependency as `implementation "org.jspecify:jspecify"` (version from the Boot BOM), never
  `compileOnly`: the annotations are `@Retention(RUNTIME)` and Spring reads them reflectively via
  `org.springframework.core.Nullness`.
- **Placement is semantic, because these annotate type *usage*.** `List<@Nullable String>` differs from
  `@Nullable List<String>`, and for arrays the meaning inverts: `@Nullable Object[]` means nullable
  *elements*, `Object @Nullable []` means a nullable *array*. Spring's style is the annotation immediately
  before the type on the same line: `private @Nullable String fileEncoding;`.
- **Annotations are not inherited when overriding.** *"JSpecify annotations are not inherited from the
  original method. That means the JSpecify annotations should be copied to the overriding method."* Every
  `SnippetSource` implementation must repeat the interface's nullability.
- `@NonNull` is redundant inside a `@NullMarked` scope and *"should rarely be needed"*; keep it for generic
  bounds and type arguments only.
- **Enforce it, and stage it.** Use the `io.spring.nullability` Gradle plugin — what Spring Boot itself uses
  — with `requireExplicitNullMarking` so a new unmarked package fails the build. Enable
  `NullAway:OnlyNullMarked=true` first; `JSpecifyMode=true` only as a second step, since Spring's own docs
  warn *"the nullability of generic types and generic methods is not yet fully supported by NullAway."* Any
  `@SuppressWarnings("NullAway")` carries a trailing comment naming the reason (dataflow limitation, lambda,
  reflection, well-known map keys, un-annotated overridden method, or a NullAway issue link).
- Prefer designing `null` out: an empty collection, an empty string, or a sealed result beats a nullable
  field. Never return `null` from a method returning a collection, or a `String` used for rendering.

### 5.7 Exceptions

- **Unchecked for programming errors and unrecoverable conditions** (*Effective Java* items 70–72). Current
  usage is right: `IllegalArgumentException` for a bad argument (`GroundingAssemblyService.assemble`,
  `PageChunker`'s constructor, `CorpusLoader.load`), `IllegalStateException` for bad call order
  (`EmbeddingSnippetSource.search`), `UncheckedIOException` wrapping an unactionable `IOException`
  (`RunLogWriter.append`).
- **Every message names the offending value.** `"Require 0 < targetTokens <= maxTokens, got " +
  targetTokens + " and " + maxTokens` is the standard.
- **`catch (Exception e)` only at a model-call boundary**, and only when all three hold: it wraps a single
  statement; the failure becomes a typed category (`McqGenerationService.Failure`); a `CallRecord` is still
  produced. There are exactly four such sites today (two per model-calling service), all compliant, and
  Artemis uses the same narrow pattern (`HyperionQuizQuestionGenerationService`). Elsewhere, catch the
  specific type.
- **Never swallow silently.** *"An empty catch block defeats the purpose of exceptions"* (*Effective Java*
  item 77), and per item 77 an intentionally ignored exception gets a comment saying why. Where the
  exception variable is genuinely unused, use the unnamed variable — `catch (NumberFormatException _)` — which
  is what [Google Java Style §6.2](https://google.github.io/styleguide/javaguide.html#s6.2-caught-exceptions)
  now shows and what Sonar's `java:S1166` recognises. Pick this form and not `ignored`; do not mix the two.
- **Log or rethrow, never both.** A `catch` block either rethrows (letting the caller log once) or handles
  the failure and logs it. Logging then rethrowing produces the same failure at several stack levels:
  *"you end up with miles-long logs that contain multiple instances of the same exception… in
  multi-threaded applications debugging this type of log can be particularly hellish"*
  ([Sonar `java:S2139`](https://sonarsource.github.io/rspec/#/rspec/S2139/java); same conclusion in
  [TheServerSide, *Log or Re-Throw, but Don't Do Both*](https://www.theserverside.com/tip/Troubleshooting-Java-Code-Log-or-Re-Throw-but-Dont-Do-Both)).
  Note the actual harm is duplicate
  and interleaved entries — `throw e;` does *not* reset the stack trace — and that exception chaining already
  carries the cause's trace forward (*Effective Java* item 73), so declining to log loses nothing. In a batch
  run over hundreds of items, duplicate entries are what makes "how many distinct failures occurred?"
  unanswerable, which is exactly the number the thesis reports.
- Failures expected at scale — a malformed model response, an unreadable PDF — are **data, not
  exceptions**: a `Failure` enum value or a report field, so they can be counted. The failure taxonomy is a
  thesis deliverable (`BUILD.md` M1).

### 5.8 Logging

- SLF4J, `private static final Logger log = LoggerFactory.getLogger(X.class);`, always named `log`
  (Artemis's one naming exception).
- **Parameterised, never concatenated:** `log.info("Loaded {} PDFs", n)`. Concatenation builds the message
  even when the level is disabled; SLF4J puts the gap at *"a factor of at least 30, in case of a disabled
  logging statement"*, and `toString()` on an argument is only invoked *"after it has ascertained that the log
  statement was enabled"*
  ([SLF4J FAQ](https://www.slf4j.org/faq.html#logging_performance), [manual](https://www.slf4j.org/manual.html)).
  An `isDebugEnabled()` guard is unnecessary — evaluating the logger *"takes less than 1% of the time it takes
  to actually log"* — and is justified only when computing an *argument* is expensive.
- **The exception is the last argument and gets no `{}`:** `log.warn("Skipping {}", id, e)`. One placeholder,
  two arguments. Adding a second `{}` consumes the exception as a formatted value and **the stack trace is
  silently lost** — *"If the exception is not the last argument, it will be treated as a plain object and its
  stack trace will NOT be printed"*
  ([SLF4J FAQ](https://www.slf4j.org/faq.html#paramException)).
- **No logging inside a per-page or per-chunk loop.** Logback: *"placing log statements in tight loops… is a
  lose-lose proposal… Logging in tight loops will slow down your application even if logging is turned off,
  and if logging is turned on, will generate massive (and hence useless) output."* Aggregate and log once, as
  `CorpusLoader.load` and `EmbeddingSnippetSource.index` do.

| Level | Use for | Example here |
|---|---|---|
| `ERROR` | the run cannot continue | none yet — reserve it |
| `WARN` | one unit of work abandoned, run continues | `CorpusLoader` unreadable PDF; `McqGenerationService` failed call |
| `INFO` | one line per stage or per item — the operator's view of a long run | `PipelineRunner` stage lines, `RunLogWriter.logSummary` |
| `DEBUG` | per-item detail an operator does not need | `McqFilterService` unknown failure mode |
| `TRACE` | not used; prompts are persisted in `ItemProvenance.promptText` instead | |

- **Never log a credential, and never log a full prompt at INFO.** Spring AI's own content-logging
  properties (`spring.ai.chat.observations.log-prompt`, `…log-completion`) default to `false` for the same
  reason — leave them off.
- A log line is not telemetry. Anything that will appear in a thesis table goes into a `CallRecord` or a
  `RunRecord`.

### 5.9 Streams and loops

*Effective Java* items 45–48: streams where they read better, loops where they do not.

Bloch's own decision procedure is the rule, near-verbatim: if a computation is best expressed by reading or
modifying local variables, returning from the enclosing method, `break`/`continue`, or throwing a checked
exception, *"then it's probably not a good match for streams."* And the tiebreak: *"If you're not sure which
version you prefer, the iterative version is probably the safer choice."*

- **Loop** when the body has side effects, mutates an accumulator, needs `break`/`continue`, needs an index,
  needs values from two pipeline stages at once, or can throw a checked exception. `PageChunker.chunk` and
  `GroundingAssemblyService.assemble` are loops and should stay loops — both accumulate and both `break`.
- **Stream** for map/filter/sort/collect over a collection, one pipeline that fits on a screen.
  `EmbeddingSnippetSource.search` is the right shape.
- Never `forEach` to accumulate: *"The `forEach` operation should be used only to report the result of a
  stream computation, not to perform the computation"* (item 46). `forEach(x -> map.merge(…))` is a loop with
  extra steps.
- Extract a named helper method rather than nesting a pipeline — item 45: *"Using helper methods is even more
  important for readability in stream pipelines than in iterative code."*
- `.toList()`, never `.collect(Collectors.toList())` (Artemis enforces this with
  `ArchitectureTest.testNoCollectorsToList`).
- **No `parallelStream()` in this repo.** Item 48: *"Do not parallelize stream pipelines indiscriminately"*,
  and *"all parallel stream pipelines in a program run in a common fork-join pool. A single misbehaving
  pipeline can harm the performance of others."* Here it also hides thread count from the measurement, and
  the measurement is the product. Artemis uses it in `refineAllQuizQuestions`; that is an interactive
  feature, not an experiment. For I/O-bound fan-out the tool is a bounded executor, never the FJ pool.
- **Return `Collection`, not `Stream`, from a method — contested.** Item 47 says *"`Collection` or an
  appropriate subtype is generally the best return type for a public, sequence-returning method"*, because a
  caller cannot for-each a `Stream`. Goetz argues the opposite default — *"most of the time, `Stream` is the
  right answer"* — weighting flexibility and avoiding materialisation. **We follow item 47:** every sequence
  in this pipeline is bounded, small, logged, and iterated more than once.
- Locale-sensitive string operations always take an explicit locale: `toLowerCase(Locale.ROOT)`,
  `toUpperCase(Locale.ROOT)`. A bare `toLowerCase()` uses the default locale and silently changes behaviour
  on a Turkish JVM — for a duplicate-option check that is a correctness bug, not a nicety.

### 5.10 Naming and formatting

- `PascalCase` types, `camelCase` members, `SCREAMING_SNAKE_CASE` constants, `log` for the logger.
  Intention-revealing names; no single letters except loop indices.
- Type names say what the thing is: `*Service` for a Spring-managed collaborator, `*Loader`, `*Chunker`,
  `*Writer`, `*Source` for a role. Never name a class after a milestone or an experiment.
- **Formatting matches Artemis exactly**, because the port lands in Artemis's Spotless check:
  - line width **180**, 4-space indent, LF, UTF-8, final newline, no trailing whitespace
  - import order `java`, `jakarta`, `javax`, `org`, `com`, then everything else (`de.tum…`, `tools.jackson`
    last), blank line between groups (`Artemis/artemis-spotless.importorder`)
  - **no wildcard imports; no fully qualified names where an import will do**
  - `catch` on its own line after the closing brace, as in every existing file here and in Artemis
  - braces always, even for single statements (Artemis `checkstyle.xml`, `NeedBraces` with
    `allowSingleLineStatement=false`)
  - one blank line between field declarations
- Until Spotless is wired up, this is the check:
  `awk 'length > 180 {printf "%s:%d (%d)\n", FILENAME, FNR, length}' $(find src -name '*.java')` must print
  nothing.

### 5.11 JSON and serialization

**Boot 4 ships Jackson 3 and deprecates Jackson 2.** Facts that decide the rule
([Boot, *JSON*](https://docs.spring.io/spring-boot/reference/features/json.html);
[Boot 4.0 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes);
[Jackson 3 migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)):

- Boot 4 auto-configures a **`JsonMapper`** bean (Jackson 3, `tools.jackson.databind.json.JsonMapper`), not
  an `ObjectMapper`. Declaring `@Bean ObjectMapper` no longer replaces it.
- *"Support for Jackson 2 is deprecated and will be removed in a future Spring Boot 4.x release."*
- Jackson 3 has `java.time` support **built into databind** — there is no `JavaTimeModule` to register.
- Annotations stay in `com.fasterxml.jackson.annotation` for both majors; `@JsonSerialize` /
  `@JsonDeserialize` moved to `tools.jackson.databind.annotation`.
- Spring AI 2.0 is on Jackson 3: `BeanOutputConverter` takes a `tools.jackson…JsonMapper`, which is what
  `llm.StructuredOutputs.converterFor` relies on.

**Rules:**

1. **Jackson 3 only.** `com.fasterxml.jackson.databind.*` must not appear anywhere in `src/`, and the two
   majors must never be mixed in one file.
2. **Do not declare a Jackson dependency.** It arrives via `spring-boot-starter-jackson` and Spring AI.
   Pinning it re-introduces the risk of a version skew between our mapper and Spring AI's.
3. **One mapper per purpose, both in `llm`:** a lenient one for parsing model output
   (`StructuredOutputs.converterFor`) and a strict one for writing pipeline output. A model's JSON and our
   own JSON have opposite requirements — never share a mapper between them.
4. **Structured-output records annotate with `com.fasterxml.jackson.annotation`** (`@JsonProperty`,
   `@JsonPropertyDescription`), as Artemis's Hyperion DTOs do.
5. Note the tension honestly: Artemis's Hyperion code still uses Jackson 2 `ObjectMapper` on Boot 4.1, so
   the port needs one adaptation. That is the correct trade — record it in `BUILD.md`, not in a comment.

---

## 6. LLM-specific engineering rules

These cost a rewrite if broken later, so they are not negotiable per-change.

**R1. Model access is a call parameter, never a constructor-injected singleton.**
`generate(grounding, difficulty, language, model, chatClient)` and
`evaluate(item, grounding, threshold, model, chatClient)` are the correct signatures (`BUILD.md` §1,
hook 1). A service must not hold a `ChatClient` field. Both services comply.

**R1a. The parameter must *bind* the model, not merely label it.** The `model` argument must reach the
request, not only the `CallRecord`. Spring AI 2.0 takes an options **builder**, not a built `ChatOptions`:

```java
// verified against spring-ai-client-chat-2.0.0.jar:
//   <B extends ChatOptions.Builder<?>> ChatClientRequestSpec options(B)
chatClient.prompt().options(ChatOptions.builder().model(model)).system(system).user(user).call()
```

Note the missing `.build()` — that is not a typo. This project does **both**: `llm.ChatCall` binds the model
onto every request, and `llm.ModelRegistry` resolves a backend key to a distinct `ChatClient`, because
`OpenAiChatModel` fixes its HTTP client at construction and silently ignores a per-request `baseUrl` or
`apiKey` — two endpoints therefore require two clients, never two option sets. Either way, **a test must
assert that the model reaching the request equals the model recorded**, because the 2×2's entire claim rests
on it — `ChatCallTest` and `ModelRegistryTest` do.

**R2. One client for every backend.** `spring-ai-starter-model-openai` is the only model starter; it serves
Ollama, LM Studio, Azure/Foundry, and vLLM behind Logos. **Never add a provider-specific starter**
(`spring-ai-starter-model-ollama`, `-azure-openai`, …) and never write `if (isLocal)`. A backend swap is
`base-url`, `api-key`, `chat.model`, plus `microsoft-foundry` (`PLAN.md` §3.3, §4.2).

**R3. Prompts live in `src/main/resources/prompts/mcq/*.st`.** Instructions to the model are never
string-concatenated in Java and never assembled with `String.format`. Rendering goes through
`PromptTemplateService.render(path, vars)` with `{{placeholder}}`, so files move to Artemis unchanged. The
only permitted concatenation is **rendering caller data into a placeholder value** —
`McqFilterService.renderOptions` and `GroundingAssemblyService.render` are the sanctioned cases, both
private static next to their single caller.

*For anyone tempted to switch:* Spring AI 2.0 has its own `TemplateRenderer` / `StTemplateRenderer` with
`{}` delimiters ([Spring AI, *Prompts*](https://docs.spring.io/spring-ai/reference/api/prompt.html)). We do
not use it, because `{{…}}` and `HyperionPromptTemplateService` are what the port needs. Do not introduce a
second templating mechanism.

**R4. Structured output via `BeanOutputConverter`, obtained from `llm.StructuredOutputs`.** Inject
`converter.getFormat()` into the template as `{{format}}` and convert the response text back through the same
converter — the pattern in `McqGenerationService.generate` and `HyperionQuizQuestionGenerationService`
([Spring AI, *Structured Output Converter*](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)).
Never hand-write a JSON schema in a `.st` file, and never construct a bare `new BeanOutputConverter<>(type)` —
it would use a strict mapper and reject output the lenient one accepts.

Three verified specifics worth knowing before touching this code:

- `.call().responseEntity(GeneratedItem.class)` returns `ResponseEntity<ChatResponse, T>` with `getEntity()`
  **and** `getResponse()` — parsed object and token usage from one call. Cleaner than manual
  convert-then-read-metadata, but it does **not** take a custom converter's mapper unless you pass the
  converter (`responseEntity(StructuredOutputConverter<T>)`), so pass ours if you adopt it. (Verified in
  `spring-ai-client-chat-2.0.0.jar`.)
- `.entity(T.class, spec -> spec.useProviderStructuredOutput().validateSchema())` enables provider-native
  structured output plus auto-retry on schema-validation failure. Adopting it is a **disclosable
  serving-layer difference** across backends (`PLAN.md` D7c) — decide it in `BUILD.md`, not ad hoc.
- Two traps for this setup: OpenAI-style native structured output **rejects a top-level array** (keep the
  wrapper record), and Ollama reasoning models can emit thinking traces or LaTeX escapes instead of clean
  JSON. `gpt-oss:20b` is a reasoning model, which is why `StructuredOutputs` exists;
  `Failure.MALFORMED_JSON` is an outcome to measure, not a bug to be surprised by.

**R5. Every LLM call produces a `CallRecord` — including failures, timeouts, and malformed output.**
`requestId`, `stage`, `model`, prompt/completion tokens, wall-clock ms, retry count, outcome, error message.
Prices are **never** applied at write time; `pricing.yml` is read at report time so a price change is a
re-report, not a re-run (`BUILD.md` §1, `PLAN.md` §4.7).

- Usage is **response-level**: `chatResponse.getMetadata().getUsage()`. `ChatGenerationMetadata` (on a
  `Generation`) has no `getUsage()`; snippets written as `response.getResult().getMetadata().getUsage()` are
  wrong. Both services get this right today.
- Spring AI 2.0's `Usage` adds `getCacheReadInputTokens()` and `getCacheWriteInputTokens()` (verified in
  `spring-ai-model-2.0.0.jar`). Add them to `CallRecord` before any cost claim — cached input is priced
  differently and would otherwise silently distort €/item.
- **Exactly one place constructs a `CallRecord` from a `ChatResponse`**, and it lives in `llm`:
  `CallRecords.from(requestId, Stage.GENERATION, model, startNanos, response)`, with `stage` and `outcome`
  as enums in `domain`. Two copies of a telemetry writer is how the two stages end up reporting
  incomparable numbers. `llm.ChatCall` is that place; `stage` and `outcome` remain strings — see
  [§10](#10-open-deviations).

**R6. No provider-specific type crosses into `domain` or `telemetry`.** `ChatClient`, `ChatResponse`,
`Usage`, `ChatOptions`, `Prompt`, `EmbeddingModel` belong to `llm`, `generation`, `filter`, `retrieval` and
the `chatClient` parameter of R1 — nowhere else.

**R7. Course material is fenced, always.** Every assembled grounding block is wrapped in
`-----BEGIN UNTRUSTED INPUT-----` / `-----END UNTRUSTED INPUT-----` (`GroundingAssemblyService`), and every
system prompt states that instructions inside the fences must not be followed — both
`mcq_generate_system.st` and `mcq_filter_system.st` do. Adding a grounding source adds no new fencing site:
it goes through `GroundingAssemblyService`.

**R8. Sampling and retry come from configuration, and must be set explicitly.** Two Spring AI 2.0 facts make
this a correctness rule rather than a preference:

- **Spring AI 2.0 removed its default `temperature=0.7`**; the provider default now applies. A reproducible
  pipeline must set temperature explicitly — `application.yml` does, and it must stay.
- **`spring.ai.retry.*` defaults are 10 attempts, 2 s initial, ×5 multiplier, 3 min cap**
  ([Spring AI OpenAI chat, *Retry Properties*](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)).
  Unpinned, one bad document can stall a batch for tens of minutes. `application.yml` pins 3 / 1 s / ×2.0 /
  30 s — keep it pinned and record the values per run.
- `spring.ai.openai.chat.seed` exists but is documented as Beta and best-effort. **Never assert on it in a
  test** and never present it as making a run deterministic.

**R9. Attribution is not optional.** A chunk header carries lecture, unit **and page range**
(`Mcq.Chunk.header()`), because attribution is what makes an item reviewable (`BUILD.md` §2, habit 4). Every
rendering path from chunk to prompt must preserve all three.

**R10. When observability is added, use Spring AI's own names.** Spring AI 2.0 emits
`spring.ai.chat.client` and `gen_ai.client.operation` observations and a `gen_ai.client.token.usage` counter
([Spring AI, *Observability*](https://docs.spring.io/spring-ai/reference/observability/index.html)). Do not
invent a parallel metric scheme; add `@Observed(name = "hyperion.mcq.<stage>")` mirroring Hyperion's
convention on top, and leave content logging off.

---

## 7. Testing

Every test must meet the standard below; the suite runs in a few seconds and boots no Spring context.

**This section fills a documented gap, it does not restate official guidance.** Spring AI 2.0's only testing
chapter covers *evaluation* against live models
([Spring AI, *Testing*](https://docs.spring.io/spring-ai/reference/api/testing.html) — `Evaluator`,
`RelevancyEvaluator`, `FactCheckingEvaluator`), and its Testcontainers support
([*Testcontainers*](https://docs.spring.io/spring-ai/reference/api/testcontainers.html)) covers local models
and vector stores only — there is no connection-details factory for a hosted LLM API, no fake `ChatModel`,
and no `spring-ai-test` module. The rule below is ours.

### 7.1 Non-negotiables

1. **Deterministic.** No `Random`, no `Instant.now()` in an assertion, no dependence on file iteration
   order. Fixed fixtures, fixed expected values.
2. **No network and no Spring context in unit tests.** The Hyperion service tests this repo models are plain
   JUnit classes with `MockitoAnnotations.openMocks(this)` and no `@SpringBootTest` — which is why they run
   in milliseconds and are hermetic. *Software Engineering at Google* ch. 14 gives the reason precisely:
   *"if a large test is nonhermetic, it is almost impossible to guarantee determinism"*
   ([abseil.io ch. 14](https://abseil.io/resources/swe-book/html/ch14.html)).
3. **A `@SpringBootTest` is allowed for exactly one purpose:** asserting that `application.yml` binds to
   `PipelineProperties`. It must not reach a model; give it a property overlay and no runner. If you ever
   need a mocked bean in a context, the Boot 4 annotation is **`@MockitoBean`** — `@MockBean` was removed.
4. Package-private test classes and methods, named `*Test`, mirroring the production package. Artemis
   enforces both (`ArchitectureTest.testClassNameAndVisibility`).
5. **AssertJ only**, with the most specific overload (`hasSize`, `isPresent`, `containsExactly`). No JUnit
   `Assertions`, which Artemis forbids outright.

### 7.2 How to fake an LLM

Mock `ChatModel`, not `ChatClient`; wrap with `ChatClient.create(chatModel)`; stub with canned JSON. This is
the idiom in
`Artemis/src/test/java/de/tum/cit/aet/artemis/hyperion/service/HyperionQuizQuestionGenerationServiceTest.java`
and it is what an Artemis reviewer expects:

```java
@Mock
private ChatModel chatModel;

@BeforeEach
void setup() {
    mocks = MockitoAnnotations.openMocks(this);
    // ChatClient merges request options into the model's options, which must be non-null
    lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    chatClient = ChatClient.create(chatModel);
    service = new McqGenerationService(new PromptTemplateService());
}

@AfterEach
void tearDown() throws Exception {
    mocks.close();
}

@Test
void generate_returnsItemWhenResponseIsValid() {
    String json = """
            { "title": "Dual feasibility", "questionText": "…",
              "options": [ {"text":"…","correct":true} ], "explanation": "…" }
            """;
    when(chatModel.call(any(Prompt.class)))
            .thenAnswer(_ -> new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
    …
}
```

Notes that save time:

- Stub `getOptions()`, `lenient()`, or `ChatClient` throws on a null merge. Do not stub
  `getDefaultOptions()` — it is deprecated for removal and unused by the merge.
- Return a **new** `ChatResponse` per invocation via `thenAnswer`, not a shared instance. Vary responses
  across calls with an `AtomicInteger`, as `HyperionConsistencyCheckServiceTest` does.
- Build metadata into the `ChatResponse` when asserting on token counts; `CallRecord` must tolerate absent
  usage (it does — both services null-check).
- **Do not** use WireMock or recorded HTTP for unit tests. Mocking `ChatModel` tests our mapping and
  validation, which is what breaks; replaying HTTP tests Spring AI's transport, which is not our code.

**A real disagreement, and how it is resolved here.** *SWE at Google* ch. 13 is explicitly
anti-mocking-framework — *"the pendulum at Google has now begun swinging in the other direction, with many
engineers avoiding mocking frameworks in favor of writing more realistic tests"*, warning that hand-doubled
dependencies go stale ([ch. 13](https://abseil.io/resources/swe-book/html/ch13.html)). Fowler and Vocke are
comfortable stubbing collaborators at the unit level and using a fake HTTP server at the integration level
([*The Practical Test Pyramid*](https://martinfowler.com/articles/practical-test-pyramid.html),
[*TestDouble*](https://martinfowler.com/bliki/TestDouble.html)). Google's "use the real implementation"
branch is unavailable by construction for an LLM, and ch. 14 supplies its own answer for that case —
record/replay. **So: mocked `ChatModel` for the many small tests; one optional recorded-response test at the
HTTP seam if Spring AI's own mapping is ever suspected, tagged `*IT` and excluded from `./gradlew test`.** A
real-model evaluation test, if ever added, is named `*IT`, gated behind `@EnabledIfEnvironmentVariable`, and
kept out of the default task.

### 7.3 Golden-file tests for prompt rendering

Prompts are the experiment's independent variable, so an accidental whitespace change is a silent confound.
For each `.st` template and mode:

- render with a fixed fixture context,
- compare byte-for-byte against `src/test/resources/golden/<template-name>.txt`,
- fail with the diff, and update the golden file **in the same commit** as the template change so the prompt
  diff is visible in review.

Include the fenced grounding block, so R7 is checked rather than assumed. `PromptTemplateService.render` is a
pure function of (template, variables), which makes this the cheapest high-value regression guard in a
prompt-heavy codebase.

### 7.4 What deserves a test

| Test it | Because | Do not test |
|---|---|---|
| `PageChunker` boundaries: document change, target reached, oversized page, empty input | pure function, all branches, no I/O | `@Service`/`@Component` annotations |
| `CorpusLoader.inferRole`, `detectLanguage` (already package-private for this) | classification rules that silently change results | that Spring wires beans |
| `McqGenerationService` validation: wrong option count, duplicate options, zero or two correct, blank fields | the gate on every generated item | PDFBox, Spring AI, Jackson |
| Every `Failure` path: transport, empty response, malformed JSON, schema violation, validation violation | the failure taxonomy is a reported result | getters and record accessors |
| `StructuredOutputs.converterFor` against real malformed output: LaTeX escapes, single quotes, trailing comma | the leniencies are a claim about model behaviour | log message wording |
| `GroundingAssemblyService`: budget truncation, at-least-one-snippet, fences present, header preserved | truncation and lost attribution silently change what the model saw | |
| `RunLogWriter.append`: one valid JSON object per line, appends, creates parent dirs (`@TempDir`) | a corrupt run log is an unrepeatable loss | |
| The model recorded equals the model requested (R1a) | otherwise the 2×2 is unfalsifiable | |
| Prompt golden files (§7.3) | | |
| `application.yml` → `PipelineProperties` binding | a typo'd key silently binds to 0/null | |

### 7.5 Naming

`methodUnderTest_expectedOutcome`, optionally `…_whenCondition`: `chunk_startsNewChunkPerDocument`,
`generate_reportsMalformedJsonWhenResponseIsNotJson`.

**A documented divergence:** Artemis's written guideline is `should<ExpectedBehavior>When<StateUnderTest>`
(`server-tests.mdx`), but every test in the Hyperion package — the package this code ports into — uses
`method_outcome` (`generateQuizQuestions_returnsGeneratedQuestions`, `checkConsistency_mapsStructuredIssues`).
**Follow the Hyperion convention**, so ported files match their neighbours. Group with `@Nested` once a class
passes ~10 tests.

---

## 8. Configuration and secrets

**One prefix.** Everything this project tunes lives under `mcq.*` in `src/main/resources/application.yml` and
binds to `app/PipelineProperties.java`, a `@ConfigurationProperties(prefix = "mcq")` record. Spring AI
settings live under `spring.ai.*` and are not duplicated under `mcq.*`
([Boot, *Type-safe Configuration Properties*](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties)).

1. **No `@Value`.** A new tunable is a component on `PipelineProperties` (or a nested record) plus a key in
   `application.yml`. Nothing else.
2. **No magic numbers or strings in code.** Any value a reviewer might want to change is either a named
   `private static final` constant or — if it is a *tuning* value, i.e. one that changes results — a config
   key. A number that appears in a report or a thesis table is always a config key.
3. **Validate the binding.** `spring-boot-starter-validation` is already a dependency and unused. Add
   `@Validated` plus jakarta constraints (`@Positive` on token targets, `@Min(1) @Max(20)` on `topK` — Pyris
   caps `limit` at 20, so larger is invalid, not merely unusual) so a bad `application.yml` fails at startup
   rather than at item 400 of an overnight run.
4. **Defaults in `application.yml`, not implied by the record.** A record component with no matching key
   binds to `0`/`null` silently; a missing key in the yml is visible in review.
5. **Use the current Spring AI property names, and re-check after every upgrade.** Spring AI 2.0 flattened
   the model options; the `…chat.options.*` forms still bind but carry an explicit deprecation replacement.
   The authority is the metadata on the classpath, not a blog post:

   ```bash
   unzip -p ~/.gradle/caches/modules-2/files-2.1/org.springframework.ai/\
   spring-ai-autoconfigure-model-openai/2.0.0/*/spring-ai-autoconfigure-model-openai-2.0.0.jar \
     META-INF/spring-configuration-metadata.json | python3 -m json.tool | grep -B6 '"replacement"'
   ```

   | Deprecated form | Current form |
   |---|---|
   | `spring.ai.openai.chat.options.model` | `spring.ai.openai.chat.model` |
   | `spring.ai.openai.chat.options.temperature` | `spring.ai.openai.chat.temperature` |
   | `spring.ai.openai.embedding.options.model` | `spring.ai.openai.embedding.model` |
   | `spring.ai.openai.chat.options.microsoft-foundry` | `spring.ai.openai.microsoft-foundry` |

   `spring.ai.model.chat`, `spring.ai.model.embedding` and `spring.ai.retry.*` are already current.

**Secrets — never committed, in any form.**

| Gitignored | Why |
|---|---|
| `corpus/` | TUM lecture material, ~495 MB, not ours to redistribute (`PLAN.md` D13). Provenance in `corpus/manifest.yml`; the fetch step in `README.md`. |
| `data/` | Run outputs: JSONL logs, Markdown dumps, SQLite pools, vector indexes. Regenerable, large, reviewer-noise. |
| `config/application-local.yml`, `config/*-local.yml`, `.env` | Logos / Azure / Pyris credentials. |
| `.gradle/`, `build/`, `.idea/`, `*.iml`, `.DS_Store` | tool output |

The committed `application.yml` may contain only the local Ollama placeholder (`api-key: ollama`); a real key
comes from a profile file or an environment variable, never from a tracked file. `.gitignore`'s credential
patterns must cover wherever profile files actually live — see [§10](#10-open-deviations).

---

## 9. Definition of done

**Build and behaviour**

- [ ] `./gradlew build` is green. (`compileJava` alone is not enough — it has passed on a broken tree here
      before.)
- [ ] `./gradlew javadoc` does not fail, and you added no new `no main description` warning.
- [ ] `awk 'length > 180 {printf "%s:%d (%d)\n", FILENAME, FNR, length}' $(find src -name '*.java')` prints
      nothing.
- [ ] If the change touches a prompt, the chunker, a validator, or the grounding assembler, you ran the
      pipeline end to end against Ollama and **read the items it produced**.

**Structure**

- [ ] The new code is in the package §3.2 dictates, and adds no forbidden import (§3.1).
- [ ] No new interface with a single implementation and no scheduled second one (§3.3).
- [ ] No `@Value`, no field injection, no `@Lazy`, no `parallelStream()`, no bare `toLowerCase()`, no
      `Optional` parameter or record component, no `Optional.get()`.
- [ ] Any new record with a collection or map component copies it in its compact constructor (§5.2).
- [ ] No `final` on a Spring bean class or on any advised method (§5.2).
- [ ] No preview language feature, no `import module`, no `--enable-preview` in the build (§5.4).
- [ ] `grep -rn "com.fasterxml.jackson.databind" src/` is empty (§5.11).
- [ ] Any new tunable is a `PipelineProperties` component plus an `application.yml` key.

**Comments**

- [ ] `grep -rn "BUILD.md\|PLAN.md\|deliberately\| M[0-5]\b" src/` finds nothing you added.
- [ ] Every comment you wrote passes §4.2: *a reader about to change this code needs it in order not to break
      something.* If it is rationale, it is now a sentence in `BUILD.md` instead.
- [ ] Public types and methods have a Javadoc summary fragment; nullability, units, and ranges stated.

**LLM work**

- [ ] Every model call takes a `chatClient` **and** a `model` that is applied to the request, and produces a
      `CallRecord` on every path including failure (R1, R1a, R5).
- [ ] Structured output goes through `llm.StructuredOutputs`, not a bare `new BeanOutputConverter<>` (R4).
- [ ] Prompt text changed only in a `.st` file; the golden file was updated in the same commit (R3, §7.3).
- [ ] No provider type crossed into `domain` or `telemetry` (R6).
- [ ] Temperature and retry remain explicitly pinned (R8).

**Tests**

- [ ] New behaviour has a test from the §7.4 list; it uses the mocked-`ChatModel` idiom, touches no network,
      and boots no Spring context.
- [ ] Names follow `method_outcome`; assertions are AssertJ with the specific overload.

**Records and reproducibility**

- [ ] Anything newly persisted carries `schemaVersion`, `runId`, `configurationId`.
- [ ] No price, cost, or currency conversion is applied at write time.
- [ ] No credential appears in a committed file, a log line, or a test fixture.

---

## 10. Open deviations

Snapshot of where the working tree does not yet meet §1–9. **Delete each entry when it is fixed.** Ordered by
consequence, not by effort.

| # | Rule | Where | What is wrong |
|---|---|---|---|
| 3 | **§5.2** | every `Mcq` record with a collection component: `McqItem.options`, `GroundingContext.snippets`, `ItemProvenance.groundingChunkIds`, `FilterDecision.modeVerdicts`, `RunRecord.calls` | No compact constructors, so each stores the caller's mutable collection — the records are shallowly immutable only. One `List.copyOf` / `Map.copyOf` per component fixes it and adds the null check. Cheap now, and it is the class of bug that shows up as an inexplicable difference between the JSONL row and the in-memory item. |
| 4 | **R5** | `Mcq.CallRecord.stage`, `.outcome`; `ChatCall` | `CallRecord` construction is centralised in `llm.ChatCall`, but `stage` and `outcome` are still free-form strings (`"generation"`, `"success"`). Make both enums in `domain` so a typo cannot split one category into two at report time. |
| 6 | **§3.2 cor. 4** | `RunLogWriter` imports `llm.StructuredOutputs` | `telemetry` reaches into `llm` for `outputMapper()`, which is a plain `JsonMapper.builder().build()` and has nothing to do with model output. Inject Boot's auto-configured `JsonMapper` bean instead; then `telemetry` has no `llm` edge and `StructuredOutputs` keeps a single purpose. |
| 8 | **§8.2** | `McqGenerationService.REQUIRED_OPTION_COUNT`; `CorpusLoader.MIN_PAGE_CHARS` | Two values that change reported results are compiled in: `mcq.item.option-count` and `mcq.ingest.min-page-chars`. (The hard-coded topic strings this entry also used to list are fixed — `TopicCatalogue` now derives topics from the corpus folders and `mcq.topics-file` overrides them.) |
| 14 | **§2** | repo root | No `checkstyle.xml` / Spotless wiring (`PLAN.md` D20), no `README.md`, and no `LICENSE` (`PLAN.md` D23 selects MIT). |
| 16 | **§5.6** | whole tree | JSpecify is not used anywhere. Add `@NullMarked` in `package-info.java` per package and `@Nullable` on the components documented as optional. |
