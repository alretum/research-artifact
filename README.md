# mcq-pipeline

A standalone pipeline that turns lecture material into grounded multiple-choice questions, filters them
with an LLM judge, and persists every item — accepted or rejected — with full provenance.

It has no runtime dependency on Artemis, Pyris or Logos. It needs a directory of PDFs and any
OpenAI-compatible model endpoint. `BUILD.md` is the build plan, `PLAN.md` the reference document, and
`THESIS_NOTES.md` the running log of findings that belong in the written thesis.

---

## 1. Requirements

| | |
|---|---|
| **JDK 25** | Non-negotiable — the code uses Java 25 language features and `build.gradle` pins `languageVersion = 25`. |
| **Gradle** | None needed; the wrapper (`./gradlew`, Gradle 9.6.1) fetches its own. |
| **Local model** | Chat (generation + filtering) runs on TUM's Logos GPUs — chair-hosted hardware, which the thesis calls the *local* model. Needs an API key and TUM network access — Logos is not reachable from the public internet. |
| **Embedding model** | Ollama on CPU, embeddings only. `nomic-embed-text` is ~137M parameters, so CPU is fine. |

Gradle resolves the JDK 25 *toolchain* independently of the JDK it runs on, but there is no
auto-provisioning configured, so a JDK 25 must already be installed and discoverable. Verify with:

```bash
./gradlew -q javaToolchains        # must list a Language Version: 25 entry
```

### The default split: chat on Logos, embeddings on this machine

Generation and filtering are the expensive calls and run on Logos GPUs. Embedding is cheap and runs
locally, which also keeps the corpus off a remote service. Spring AI resolves `base-url` and `api-key`
per capability, so one `spring.ai.openai` tree points at two providers:

| Capability | Where | Default |
|---|---|---|
| chat — generation, filtering (*local model*) | Logos | `${LOGOS_BASE_URL}`, model `${LOGOS_MODEL}` |
| embedding (*embedding model*) | Ollama on this machine | `http://localhost:11434/v1`, `nomic-embed-text` |

So the only thing to install locally is Ollama and one embedding model.

On macOS without Homebrew, use the CLI tarball rather than the desktop app — it needs no administrator
rights and installs no GUI or login item:

```bash
# check the current version and its published digest first:
#   curl -s https://api.github.com/repos/ollama/ollama/releases/latest
curl -L -o ollama-darwin.tgz \
  https://github.com/ollama/ollama/releases/download/v0.32.15/ollama-darwin.tgz
shasum -a 256 ollama-darwin.tgz     # compare against the release's published digest

mkdir -p ~/.local/ollama ~/.local/bin
tar -xzf ollama-darwin.tgz -C ~/.local/ollama/
ln -sf ~/.local/ollama/ollama ~/.local/bin/ollama
```

The archive is a flat set of binaries and co-located shared libraries, so extract it into its own
directory and symlink only the `ollama` entry point — moving the binary alone breaks library resolution.

Then start the server and pull the model:

```bash
ollama serve &                      # binds 127.0.0.1:11434
ollama pull nomic-embed-text        # 274 MB
```

`ollama serve` does not persist across reboots when installed this way; restart it, or add a LaunchAgent if
you want it always up. If it is not running, the pipeline fails as described in §5.

Building the reference corpus's index on CPU takes about a minute on Apple Silicon (495 chunks / 57s
measured) and several minutes on the VPS's two cores. It is cached afterwards (§3.6), so you pay it once
per corpus change.

A fully local alternative — chat on Ollama too, no credentials, no network — is available as the `ollama`
profile (§4.2). Note that nothing generated or timed against a local development model belongs in a cost
or quality claim, so that profile is for development only; see `THESIS_NOTES.md` N6.

## 2. Build

```bash
./gradlew build                    # compiles, runs the test suite, produces the jar
```

This produces `build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar`. Section 5 lists what you can pass it.

`data/` is created on first use, so nothing to prepare. If something else is missing — no corpus, an
unset key, an embedding server that is not running — run `--doctor`, which checks every prerequisite and
prints what to do about each one. The same checklist is in the web interface under **Setup**.

The first build downloads the full Spring Boot 4.1 / Spring AI 2.0 dependency tree and takes several
minutes; later builds are seconds.

---

## 3. Adding course content

This is the step that turns an empty checkout into a working tool. **`corpus/` is not in git** — it holds
TUM lecture material that is not ours to redistribute (`PLAN.md` D13), so a fresh clone has no content and
the pipeline has nothing to ingest. You supply it.

### 3.1 Layout

Create `corpus/` at the repository root and put one directory per lecture inside it:

```
corpus/
├── 01 Linear Programming - Modeling/
│   ├── 1 Organization.pdf
│   ├── 2 Linear programming.pdf
│   ├── CE 1 Solution.pdf
│   ├── Tutorial 1 Exercises.pdf
│   └── Tutorial 1 Solution.pdf
├── 02 Linear Programming - Simplex/
│   └── ...
└── 11 Nonlinear  Convex Optimization/
    └── ...
```

Two rules, and they are the whole convention:

- **The first directory below `corpus/` is the lecture name.** Nesting deeper is allowed — the walk is
  recursive — but only that first element is recorded as the lecture.
- **The filename (minus `.pdf`) is the unit name.** It is what appears in grounding citations as
  `[source – unit]`, and it is what the source-role heuristic reads.

There is no fixed number of lectures and no required naming scheme. The reference corpus happens to use
`NN Topic Name`, which is a delivery schedule rather than a topic taxonomy — see `THESIS_NOTES.md` N7 for
why that distinction matters and why topics come from `config/competencies.yml` instead.

### 3.2 What actually gets ingested

**PDFs only.** `CorpusLoader` walks the tree recursively and filters on the `.pdf` extension; everything
else is silently ignored. In the reference corpus that means 70 PDFs are read while 17 `.html` files and
2 `.ipynb` notebooks are not. Put whatever you like in the directory — only PDFs reach the index.

Within each PDF, extraction is deliberately unrepaired (`BUILD.md` §3.1) — text is indexed exactly as
PDFBox returns it, ligature damage and all. Three things happen automatically:

- Pages yielding **fewer than 40 characters** are treated as text-poor and excluded from the index, but
  are counted in the extraction report.
- Pages whose text has **already been seen verbatim** anywhere in the corpus are skipped, which removes
  repeated course-outline boilerplate before it can be merged into a chunk.
- **Unreadable PDFs are logged and skipped** rather than failing the load.

### 3.3 Filenames drive the source role

Every chunk records a `SourceRole`, inferred from the unit name and used for the grounding-composition
report. The rules are checked in this order, case-insensitively:

| Matches | Role |
|---|---|
| contains `solution`, `loesung`, `lösung` | `SOLUTION` |
| starts with `ce `, or contains `central exercise`, `demoaufgaben` | `CENTRAL_EXERCISE` |
| contains `tutorial`, `uebungsaufgaben`, `übungsaufgaben` | `TUTORIAL` |
| starts with a digit followed by a space or underscore (`5 Linear programming.pdf`) | `LECTURE_DECK` |
| anything else | `OTHER` |

Roles are **recorded, not used for weighting** — retrieval stays uniform. If your filenames do not follow
these patterns everything still works; the material is simply classified `OTHER`, and the composition
report becomes less informative. Renaming files is the cheapest way to fix that.

The heuristic is filename-only, so it misses whatever the names do not say. On the reference corpus it
classifies 68 of 70 documents — 25 `SOLUTION`, 17 `LECTURE_DECK`, 16 `CENTRAL_EXERCISE`, 10 `TUTORIAL` —
and drops two into `OTHER`: `CEs_full_slides_only.pdf`, a central-exercise deck whose name does not begin
with `ce `, and `Exercise Sheet 11.pdf`, a tutorial sheet that says "Exercise Sheet" rather than "Tutorial".
Both are exactly the kind of near-miss to expect. Check your own distribution in
`data/extraction-report.csv` after the first index and rename anything conspicuous.

That solutions are the largest single class is not an accident of this corpus, and it matters: retrieval is
uniform, so answer keys compete with lecture slides for the grounding window. See `THESIS_NOTES.md` N10.

### 3.4 Bulk-adding content

Nothing is added one file at a time. Copy a whole tree in:

```bash
# from another machine
rsync -avz --exclude '*.mp4' --exclude '.DS_Store' \
  /path/to/materials/ ./corpus/

# or locally
cp -R "/path/to/Course Materials/." ./corpus/
```

Exclude video: `.mp4` files hold no extractable text, and transcription is out of scope. In the reference
corpus that also avoids moving 448 MB for nothing.

### 3.5 Define the topics

Questions are generated per *topic*, and topics do not come from the folder names. `config/competencies.yml`
is the authority — it defines each topic's key, its retrieval query, and its competency statements. Edit it
to match your material:

```yaml
- key: column-generation
  query: column generation, restricted master problem, pricing subproblem, Dantzig-Wolfe decomposition
  competencies:
    - You separate a problem into a restricted master and a pricing subproblem.
```

Precedence is: `mcq.competency-manifest` (default `config/competencies.yml`) beats `mcq.topics-file`, which
beats folder names. A topic with no material that retrieves against it is reported as ungrounded and
skipped. See `THESIS_NOTES.md` N9 for why this structure is an experimental input that must be described
rather than presented as found.

### 3.6 Index it and check the result

The first run embeds every chunk and caches the index at `data/index.json`, so later runs start in
seconds. Verify the corpus landed correctly before generating anything:

```bash
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --retrieval-only
```

Then read the two CSVs it writes:

- **`data/extraction-report.csv`** — per document: pages, text-poor pages, characters, approximate tokens,
  suspected ligature-damaged tokens, screen-reader alt-text lines, detected language. Diagnostics only;
  nothing in the pipeline acts on them.
- **`data/topics.csv`** — every derived topic and whether it has linked material.
- **`data/retrieval-probe.csv`** — what each topic actually retrieves, which is the fastest way to catch a
  topic whose query matches the wrong pages.

Expect a log line like `Loaded 70 PDFs, N usable pages, ~M tokens`. If the PDF count is zero, `corpus/` is
empty or in the wrong place; if a lecture is missing, check it contains at least one `.pdf`.

Delete `data/index.json` to force a rebuild after changing the corpus.

---

## 4. Model backends

### 4.1 Logos (default)

Four environment variables drive it; only the key is required.

| Variable | Default | Notes |
|---|---|---|
| `LOGOS_API_KEY` | `unset` | **Required.** With the placeholder, calls fail with 401. |
| `LOGOS_BASE_URL` | `https://logos.aet.cit.tum.de/v1` | |
| `LOGOS_MODEL` | `openai/gpt-oss-120b` | Must match an id from `GET /v1/models` exactly. |
| `EMBEDDING_BASE_URL` | `http://localhost:11434/v1` | Local Ollama. |

**Never put a key in a file under the repository.** Two templates are committed; copy each into place
and edit the copy. Neither copy is tracked.

```bash
# 1. credentials — lives OUTSIDE the repo, in your home directory
cp config/logos-env.example ~/.logos-env && chmod 600 ~/.logos-env
$EDITOR ~/.logos-env                 # set LOGOS_API_KEY, confirm LOGOS_MODEL

# 2. machine-specific settings — no secrets, gitignored via **/*-local.yml
cp config/application-local.yml.example config/application-local.yml
$EDITOR config/application-local.yml # loopback binding and concurrency

# 3. load and run
source ~/.logos-env
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --count=5
```

`~/.logos-env` must be re-sourced in every new shell; add `source ~/.logos-env` to `~/.zshenv` if you would
rather it always be present.

**`config/application-local.yml` is NOT loaded automatically.** It is a *profile-specific* file, so it
applies only when that profile is active:

```bash
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Only `application.yml` (no profile suffix) is picked up from `./config/` on its own. Spring logs
`No active profile set, falling back to 1 default profile: "default"` when nothing is activated, which is
the signal that a profile file was ignored. Nothing safety-critical depends on it — the loopback binding
lives in `application.yml` as the default (§5) precisely so that forgetting the flag cannot expose the
interface.

The division of labour matters: **the key only ever lives in the environment**, and
`application-local.yml` only ever holds non-secret machine settings. Keeping them separate is what lets the
second file be shared, diffed or pasted without leaking anything.

Confirm the model id before the first real run — a wrong id fails every call:

```bash
curl -s https://logos.aet.cit.tum.de/v1/models \
  -H "Authorization: Bearer $LOGOS_API_KEY" | python3 -m json.tool
```

**Logos is not reachable from the public internet.** The host resolves (131.159.89.51) but refuses
connections from outside TUM, so you need campus network or eduVPN. Without it every chat call times out
while embedding keeps working, which looks like a hang rather than a network error — check reachability
first if a run stalls with no output.

`config/application-logos.yml` predates this and now duplicates the defaults. It is harmless but
redundant; the defaults in `application.yml` are the authority.

### 4.2 Chat on Ollama too (`ollama` profile) — development only

Chat and embedding both on Ollama. No credentials, no network.

```bash
ollama pull gpt-oss:20b            # 13 GB
ollama pull nomic-embed-text
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --spring.profiles.active=ollama --count=5
```

Development and offline work only — see `THESIS_NOTES.md` N6.

### 4.3 A cloud model (Azure, OpenAI)

Any OpenAI-compatible endpoint works. Override the chat side and leave embedding alone:

```bash
LOGOS_BASE_URL=https://api.example.com/v1 LOGOS_API_KEY=... LOGOS_MODEL=some-model \
  java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --count=5
```

Files matching `*-local.yml` are gitignored, so `config/application-local.yml` is the place for
machine-specific overrides. Note `config/application-logos.yml` is **not** covered by that pattern — it is
safe as written because it only references environment variables, but do not paste a literal key into it.

The model name reaching each request is `mcq.generation.model` / `mcq.filter.model`, and the pair also
forms the `configuration_id` stamped on every persisted item. Keep them consistent with the chat model
actually being served, or the provenance records will name a model that never ran.

---

## 5. Run it

With no command argument the application starts the web interface on port 8080 and does nothing else.

| Command | What it does |
|---|---|
| `--count=N` | Generate N items per topic, filter each, persist everything. Starts a new run. |
| `--topic=KEY` | Restrict to one topic; repeatable. Without it, all grounded topics are used. |
| `--resume=RUNID` | Resume an interrupted run. Completed items are not regenerated. |
| `--retrieval-only` | Index and probe retrieval; no LLM generation calls. |
| `--report` | Grounding composition against item quality, from stored records. No model calls. |
| `--sweep` | Accept rate and per-mode trigger rates across a range of thresholds. No model calls. |
| `--cost` | Cost per configuration from recorded calls, using `config/pricing.yml`. No model calls. |
| `--plan=FILE` | Validate a run plan and print what it would run. Generates nothing. |
| `--run-plan=FILE` | Run every configuration in a plan, one after another. |
| `--export-benchmark=DIR` | Write the items as input for the external quality benchmark. No model calls. |
| `--doctor` | Check every prerequisite and say what is missing. No model calls. |

```bash
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --count=10
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --resume=a1b2c3d4
```

Reports read persisted decisions, so re-reporting never means re-generating.

### Measuring quality

Quality is not measured here. This pipeline's filter is part of what is being tested — and it currently
runs on the same model as the generator, so its accept rate partly measures the model agreeing with
itself. Independent measurement comes from
[`ls1intum/paper-al-quiz-generation-benchmark`](https://github.com/ls1intum/paper-al-quiz-generation-benchmark),
which brings its own evaluator models.

```bash
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar \
  --export-benchmark=data/benchmark \
  --export-granularity=configuration-topic \
  --export-condition=split
```

That writes `quizzes/`, `instructions/` and a `benchmark.yaml` pointing at both, then prints the command to
run from the benchmark checkout. Fill in an evaluator model first — the config leaves it blank on purpose,
because a model that generated or filtered these items cannot independently judge them.

| flag | values | what it changes |
|---|---|---|
| `--export-granularity` | `topic`, `configuration-topic` (default), `configuration`, `run` | What becomes one quiz file. The benchmark's `coverage` and `homogeneous_options` metrics compare a whole quiz against its source material, so the per-topic groupings keep one quiz to one lecture; the coarser ones are for comparing configurations, and the generated config disables those two metrics automatically. |
| `--export-condition` | `all` (default), `accepted`, `rejected`, `split` | Which items each file holds. Rejected items are kept deliberately (§6), so the unfiltered condition costs nothing. `split` writes accepted and rejected to separate files, which the two quiz-level metrics need in order to describe one condition rather than a mixture. |

Every question also carries our own variables in its `metadata` — requested difficulty, solution fraction,
configuration id, accept decision, per-mode filter severities. Nothing reads them back; the pipeline is
one-directional by design, and they are there as a provenance trail.

### Web interface

```bash
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar
```

Then open <http://localhost:8080>: a dashboard with run progress, a browsable item pool filterable by run,
topic and accept/reject decision, an item view showing the stem, options, explanation, grounding sources
and per-mode filter severities, and controls to start, resume and stop a run.

**The embedding backend must be reachable before the dashboard loads.** The dashboard reports corpus state,
which indexes the corpus, which embeds. With Ollama not running, every page returns HTTP 500 and the log
shows `OpenAIIoException: Request failed` / `Connection refused` — the corpus itself is fine and will have
logged `Loaded N PDFs` just above. Start Ollama first, or point `EMBEDDING_BASE_URL` at something that is
up.

**The interface has no authentication and must not be exposed.** Exposed, `POST /runs` lets anyone spend
your model budget and `/items` serves lecture material you may not hold redistribution rights to.

Spring binds **all** interfaces by default, and a fresh clone has nothing that changes this — the file that
does, `config/application-local.yml`, is gitignored and so is absent until you create it. On any host
reachable from a network, set it explicitly before first start:

```yaml
# config/application-local.yml
server:
  address: 127.0.0.1
```

Reach a remote instance through an SSH tunnel (`ssh -L 8080:localhost:8080 …`), never a public port — see
`VPS_SETUP.md`.

---

## 6. Where output goes

Everything below `data/` is gitignored.

| Path | Contents |
|---|---|
| `data/run.db` | SQLite store: runs, items, answer attempts. Authoritative. |
| `data/run-log.jsonl` | One JSON record per completed item, including rejected ones. |
| `data/items.md` | Human-readable rendering of each item. |
| `data/index.json` | Cached embedding index. Delete to force a rebuild. |
| `data/extraction-report.csv`, `data/topics.csv`, `data/retrieval-probe.csv` | Ingestion diagnostics. |

Items are keyed `(run_id, configuration_id, topic_key, item_index)`, so a run killed with `kill -9` and
resumed produces no duplicates and loses nothing.

Run stores are per-machine. Do not copy `data/run.db` between machines — mixing two machines' runs in one
store makes the run manifest meaningless.

## 7. Evidence for the thesis

`evidence/` is the one data directory kept **in** git: it holds run artefacts cited in `THESIS_NOTES.md`,
so a claim in the thesis can be traced to the output that supports it. Add an artefact by copying the
relevant record out of `data/` under a name that begins with the finding it supports:

```bash
cp data/run-log.jsonl evidence/n4-token-counts.jsonl
```

Currently only N1 has a committed artefact. Populating the rest is open work.

---

## 8. Known limitations

- **PDFs only** — notebooks and HTML in the corpus are ignored, and `SourceRole.NOTEBOOK` is currently
  unreachable.
- **No quality evaluation** — the `QualityEvaluator` interface does not exist yet; `--report` covers
  grounding composition and the filter's own verdicts only.
- **No cost reporting** — `config/pricing.yml` is not yet read by any code, so €/item figures are not
  produced.
- **No 2×2 runner** — generator × filter matrix runs are not yet expressible; one configuration per run.
- **Banned constructions are requested, not enforced** — "all of the above" and options containing other
  options are forbidden in the generation prompt but not validated in code.
- **The grounding token budget is a soft bound** — see `THESIS_NOTES.md` N8.
- **Retrieval favours exercise and solution material over lecture slides** — measured, net effect on item
  quality not yet measured; see `THESIS_NOTES.md` N10.
