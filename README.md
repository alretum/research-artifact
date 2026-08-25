# mcq-pipeline

Turns lecture material into grounded multiple-choice questions, filters them, and exports them for
independent quality evaluation.

It has no runtime dependency on Artemis, Pyris or Logos code. It needs a directory of lecture PDFs, one
chat model, and one embedding model. After the first setup, everything runs in the browser.

**Quality is not measured here.** This pipeline's own filter is part of what is being tested, and it
currently runs on the same model as the generator, so its accept rate partly measures the model agreeing
with itself. Independent measurement comes from
[`ls1intum/paper-al-quiz-generation-benchmark`](https://github.com/ls1intum/paper-al-quiz-generation-benchmark),
which this tool exports for. Step 7 covers that.

---

# Part 1 — First run, start to finish

Eight steps from an empty checkout to a JSON export. Steps 1–4 are once per machine; 5–8 are the loop you
repeat.

## 1. Install a JDK 25

Non-negotiable: the code uses Java 25 language features and the build pins `languageVersion = 25`. Gradle
resolves the toolchain independently of the JDK it runs on, but there is no auto-provisioning, so a JDK 25
must be installed and discoverable.

On macOS without Homebrew, the Temurin tarball needs no administrator rights:

```bash
# check the current version and its published digest first:
#   curl -s https://api.github.com/repos/adoptium/temurin25-binaries/releases/latest
curl -L -o jdk25.tar.gz \
  https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.4.1_1.tar.gz
shasum -a 256 jdk25.tar.gz          # compare against the published digest

mkdir -p ~/Library/Java/JavaVirtualMachines
tar -xzf jdk25.tar.gz -C ~/Library/Java/JavaVirtualMachines/
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

Put that `export` in `~/.zshenv` rather than `~/.zshrc` — `~/.zshrc` runs only for interactive shells, so
scripts and IDE-launched builds would still get whichever JDK is default.

Verify: `./gradlew -q javaToolchains` must list a `Language Version: 25` entry.

## 2. Install the embedding model

Embeddings run locally on CPU. `nomic-embed-text` is ~137M parameters, so CPU is fine, and it keeps the
corpus off a remote service. On macOS without Homebrew, use the CLI tarball rather than the desktop app —
no administrator rights, no GUI, no login item:

```bash
curl -L -o ollama-darwin.tgz \
  https://github.com/ollama/ollama/releases/download/v0.32.15/ollama-darwin.tgz
shasum -a 256 ollama-darwin.tgz     # compare against the release's published digest

mkdir -p ~/.local/ollama ~/.local/bin
tar -xzf ollama-darwin.tgz -C ~/.local/ollama/
ln -sf ~/.local/ollama/ollama ~/.local/bin/ollama

ollama serve &                      # binds 127.0.0.1:11434
ollama pull nomic-embed-text        # 274 MB
```

The archive is a flat set of binaries with co-located shared libraries, so extract it into its own
directory and symlink only the `ollama` entry point — moving the binary alone breaks library resolution.
`ollama serve` does not survive a reboot when installed this way; restart it, or add a LaunchAgent.

## 3. Set the API key

Chat — generation and filtering — runs on TUM's Logos GPUs. Two templates are committed; copy each into
place and edit the copy. Neither copy is tracked.

```bash
cp config/logos-env.example ~/.logos-env && chmod 600 ~/.logos-env
$EDITOR ~/.logos-env                 # set LOGOS_API_KEY
source ~/.logos-env
```

**The key belongs in the environment, never in a file inside the repository.** Re-source it in every new
shell, or add `source ~/.logos-env` to `~/.zshenv`.

Logos is not reachable from the public internet: the host resolves but refuses connections from outside
TUM, so you need campus network or eduVPN. Without it, chat calls fail while embedding keeps working.

Your key must also be *granted* the model. If `GET /v1/models` lists fewer models than you expect, that is
a permissions matter for whoever administers Logos, not a configuration problem.

## 4. Build and start

```bash
./gradlew build                      # compiles and runs the test suite
java --enable-native-access=ALL-UNNAMED -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar
```

The first build downloads the whole Spring Boot 4.1 / Spring AI 2.0 dependency tree and takes several
minutes; later builds are seconds. `data/` is created on first use, so there is nothing to prepare.

With no command argument the web interface starts. Open <http://localhost:8080>.

**It has no authentication and is bound to loopback by default.** Do not change that binding: exposed,
anyone could spend your model budget and read lecture material you may not hold redistribution rights to.
Reach a remote instance through an SSH tunnel (`ssh -L 8080:localhost:8080 …`), never a public port.

## 5. Check Setup

Open **Setup**. It checks every prerequisite and, for each one that is not satisfied, says what to do:

```
[ok  ] Java: 25.0.3
[ok  ] Corpus: 70 PDF(s) under corpus
[ok  ] Embedding model: nomic-embed-text available at http://localhost:11434/v1
[ok  ] Local model API key: $LOGOS_API_KEY set (145 characters)
[ok  ] Local model: openai/gpt-oss-120b available at https://logos.aet.cit.tum.de/v1
[warn] Cloud model: none declared
```

Fix anything marked `FAIL` before going on — those are the things that make generation fail. `warn` rows
are informational. The same checklist is available on the command line as `--doctor`.

Three tiers appear here, named as the thesis names them: the **embedding model** on this machine, the
**local model** on chair-hosted Logos GPUs, and optionally a **cloud model** from a commercial provider.

## 6. Add lecture material

Open **Corpus**. Upload a folder, or a zip of folders. Nothing enters the corpus until you confirm it: the
upload is staged first and you get an extraction preview showing pages, inferred role, detected language and
suspected extraction damage per file.

**Upload folders, not loose files.** The first directory level names the lecture, and that name appears in
every citation. A flat selection of files has no lecture and shows as `(root)`.

```
corpus/
├── 01 Linear Programming - Modeling/
│   ├── 2 Linear programming.pdf
│   ├── CE 1 Solution.pdf
│   └── Tutorial 1 Exercises.pdf
└── 02 Linear Programming - Simplex/
    └── ...
```

Only PDFs are ingested; anything else in the upload is ignored. Committing drops the cached index, so the
next run re-embeds — about a minute on Apple Silicon, several on two CPU cores.

Topics do not come from the folder names. `config/competencies.yml` is the authority; see
[Corpus conventions](#corpus-conventions) for why, and for how filenames determine a document's role.

## 7. Generate

Open **Dashboard**. Choose topics (none selected means every grounded topic) and items per topic, then
**Start run**. Progress is live.

Optional per-run settings, each falling back to the configured default when left blank:

| field | what it does |
|---|---|
| Difficulty ladder | Comma-separated, 0–100. Each topic walks the ladder independently, so four levels need four items per topic to be covered. |
| Accept threshold | The score is 1 minus the worst severity among gating modes, so any gating mode above `1 − threshold` rejects the item. |
| Generator / filter model | Only shown when more than one model is available. |

For several generator/filter pairings in one go, use **Plans** instead — see
[Run plans and the 2×2](#run-plans-and-the-22).

## 8. Export

Open **Export**. It shows how many items are available, then writes the benchmark's input format and
downloads it as a zip containing `quizzes/`, `instructions/` and a `benchmark.yaml` already pointing at
both.

Two choices, both explained on the page: what becomes one quiz file, and which items to include. The
defaults are right for most cases.

**Fill in an evaluator model in `benchmark.yaml` before running it.** The generated config leaves it blank
on purpose — a model that generated or filtered these items cannot independently judge them.

That is the loop. Everything below is reference.

---

# Part 2 — Reference

## The web interface

| page | what it is for |
|---|---|
| **Dashboard** | Corpus and pool summary, start/resume/stop a run, live progress |
| **Corpus** | Upload lecture material, preview before committing, remove a lecture |
| **Plans** | Create, run and delete multi-configuration plans |
| **Export** | Write and download the benchmark input |
| **Items** | Browse the pool by run, topic and decision; open an item; answer it |
| **Setup** | The prerequisite checklist |

## Command line

Any of these runs one task and exits; with no command argument the web interface starts instead.

| command | what it does |
|---|---|
| `--count=N` | Generate N items, spread round-robin across topics, then filter each |
| `--topic=KEY` | Restrict to one topic; repeatable |
| `--resume=RUNID` | Resume an interrupted run with the settings it started with |
| `--plan=FILE` | Validate a run plan and print what it would run. Generates nothing |
| `--run-plan=FILE` | Run every configuration in a plan, one after another |
| `--report` | Grounding composition and requested difficulty against item quality |
| `--sweep` | Accept rate and per-mode trigger rates across thresholds |
| `--cost` | Cost per configuration from recorded calls |
| `--redecide` | Recompute stored decisions under the current threshold and gating modes |
| `--export-benchmark=DIR` | Write the benchmark input format |
| `--retrieval-only` | Index and probe retrieval; no generation calls |
| `--doctor` | The prerequisite checklist |

Everything except `--count`, `--resume` and `--run-plan` makes no model calls, so reporting and exporting
are free and repeatable.

`--count=N` is **N items in total**, distributed round-robin across topics — not N per topic. With fewer
items than topics, only the first N topics get one each, in `competencies.yml` order.

## Model backends

`config/models.yml` separates **backends** (a base URL and the *name* of the environment variable holding
its key) from **models** (a provider model id and which backend serves it). That split is why one backend
serves any number of models: the model name travels with each request.

| tier | where | default |
|---|---|---|
| local model — generation, filtering | Logos GPUs | `${LOGOS_BASE_URL}`, model `${LOGOS_MODEL}` |
| embedding model | Ollama on this machine | `http://localhost:11434/v1`, `nomic-embed-text` |
| cloud model | a commercial provider | not configured |

Adding a **second model on an existing backend** is one catalogue entry and no code. Adding a model on a
**new backend** additionally needs its key in the environment; the tool builds the client itself.

| variable | default | notes |
|---|---|---|
| `LOGOS_API_KEY` | `unset` | Required. With the placeholder, calls fail with 401 |
| `LOGOS_BASE_URL` | `https://logos.aet.cit.tum.de/v1` | |
| `LOGOS_MODEL` | `openai/gpt-oss-120b` | Must match an id from `GET /v1/models` exactly |
| `EMBEDDING_BASE_URL` | `http://localhost:11434/v1` | |

Two alternative profiles: `--spring.profiles.active=ollama` puts chat on Ollama too, for offline
development only — nothing timed against a local development model belongs in a cost or quality claim.
`config/application-local.yml` holds machine-specific settings, but note it is **profile-specific**: it
applies only with `--spring.profiles.active=local`. Only `application.yml` is picked up from `./config/` on
its own.

## Run plans and the 2×2

A plan runs several generator/filter pairings over the same topics from one button. Create one on the
**Plans** page by choosing generator models and filter models; every generator is paired with every filter,
so two of each gives the four cells of a 2×2.

Cells run one after another, not concurrently: the model server is the scarce resource, and concurrent
cells inflate each other's per-call latency so no cell's timings can be reported. The page shows the total
item count before you start, and takes an items-per-topic override so a plan can be tried cheaply first.

Each cell gets its own run id and carries the plan's configuration id, so cells never collide and
`--cost`, `--report` and the export all group by configuration.

The cells where one model judges another are the only ones whose accept rate is not partly a model agreeing
with itself. A plan whose every cell is self-judging is flagged as such.

## Corpus conventions

**Only PDFs are ingested.** The loader walks the corpus recursively and filters on the `.pdf` extension;
HTML, notebooks and video are silently ignored.

**The first directory level is the lecture, the filename is the unit.** The unit name appears in grounding
citations as `[source – unit]`.

Extraction is deliberately unrepaired — text is indexed exactly as PDFBox returns it, ligature damage and
all. Three things happen automatically: pages under 40 characters are treated as text-poor and excluded
but counted; pages whose text has already been seen verbatim anywhere in the corpus are skipped, which
removes repeated boilerplate; and unreadable PDFs are logged and skipped rather than failing the load.

**Filenames determine the source role**, checked in this order, case-insensitively:

| matches | role |
|---|---|
| contains `solution`, `loesung`, `lösung` | `SOLUTION` |
| starts with `ce `, or contains `central exercise`, `demoaufgaben` | `CENTRAL_EXERCISE` |
| contains `tutorial`, `uebungsaufgaben`, `übungsaufgaben` | `TUTORIAL` |
| starts with a digit then a space or underscore (`5 Linear programming.pdf`) | `LECTURE_DECK` |
| anything else | `OTHER` |

Roles are **recorded, not used for weighting** — retrieval stays uniform. The heuristic is filename-only,
so it misses what the names do not say: on the reference corpus it classifies 68 of 70 documents, missing a
central-exercise deck not starting with `ce ` and a tutorial sheet saying "Exercise Sheet". Check your own
distribution in `data/extraction-report.csv` after the first index.

**Topics come from `config/competencies.yml`**, not from folder names — folder names encode a delivery
schedule rather than a topic taxonomy. Each competency declares a title, a retrieval query, a Bloom
`taxonomy`, and the documents it links to; a topic with no linked material is reported as ungrounded and
skipped. The declared taxonomy is exported as `bloom_intended`, and the description as
`learning_objective`, which is what the benchmark's cognitive-level and objective-alignment metrics judge
against.

## The filter

Five failure modes are judged on every item and recorded with per-mode severities: `FACTUAL_ERROR`,
`AMBIGUOUS_CORRECT_ANSWER`, `OFF_TOPIC`, `NEAR_DUPLICATE`, `ILL_FORMED_DISTRACTORS`.

Only the modes in `mcq.filter.gating-modes` decide acceptance, and the aggregate is `1 − worst severity
among those`, so **any gating mode can reject an item on its own**. `NEAR_DUPLICATE` is deliberately not in
the default set: for a grounded generator, resembling the material is unavoidable, and one competency in
the reference corpus is declared at `taxonomy: REMEMBER`, where a recall question is correct by design. It
is still judged and exported, just not acted on.

Rejected items are kept with their verdicts, so comparing filtered against unfiltered costs nothing.
`--redecide` recomputes every stored decision under the current threshold and gating set, with no model
calls, since the severities are already stored.

## Where output goes

Everything below `data/` is gitignored.

| path | contents |
|---|---|
| `data/run.db` | SQLite store: runs, items, answer attempts. Authoritative |
| `data/run-log.jsonl` | One JSON record per completed item. Replaced on each export |
| `data/items.md` | Human-readable rendering of each item |
| `data/index.json` | Cached embedding index. Deleted automatically when the corpus changes |
| `data/benchmark/` | The most recent benchmark export |
| `data/extraction-report.csv`, `data/topics.csv`, `data/retrieval-probe.csv` | Ingestion diagnostics |

Items are keyed `(run_id, configuration_id, topic_key, item_index)`, so a run killed with `kill -9` and
resumed produces no duplicates and loses nothing.

Run stores are per-machine. Do not copy `data/run.db` between machines — mixing two machines' runs in one
store makes the run manifest meaningless.

## Cost reporting

`--cost` groups by configuration and reports cost per generated item and per *accepted* item, the second
being the one that compares configurations: a configuration that generates cheaply but is rejected often is
not cheap. Failed calls count, because they were paid for.

Prices come from `config/pricing.yml` and are applied at report time, never during a run, so revising a
price is a re-report rather than a re-run. Token-billed models give an exact figure. Time-billed models
give a **band** between the electricity and rental rates, derived from client wall-clock — which overstates
occupancy because it includes queueing and network time. Both rates in `pricing.yml` are placeholders until
the hardware and tariff are known, so treat those figures as order-of-magnitude bounds rather than results.

## Evidence for the thesis

`evidence/` is the one data directory kept in git: it holds run artefacts cited in `THESIS_NOTES.md`, so a
claim can be traced to the output supporting it. Add one by copying the relevant record out of `data/` under
a name beginning with the finding it supports:

```bash
cp data/run-log.jsonl evidence/n4-token-counts.jsonl
```

## Known limitations

- **No quality evaluation here** — by design. `--report` covers grounding composition, requested difficulty
  and the filter's own verdicts; independent scores come from the benchmark.
- **Generator and filter share a model** in the default configuration, so accept rate is partly
  self-agreement. A second model in `config/models.yml` fixes this; the models exist on Logos but need to
  be granted to your key.
- **Concurrency defaults to 1** and should stay there for any run whose cost or latency you intend to
  report: concurrent calls inflate per-call latency and make wall-clock-derived cost double-count.
- **Cost figures are bounds, not measurements**, until the GPU rates in `pricing.yml` are real.
- **Only PDFs are ingested**, and `SourceRole.NOTEBOOK` is consequently unreachable.
- **No authentication** on the web interface. Loopback binding and an SSH tunnel are the mitigation, not a
  solution; anything beyond single-user local access needs real authentication first.
- **`corpus/manifest.yml` does not exist** and the current `.gitignore` cannot express it: the rule is
  `corpus/`, which ignores the directory outright, so committing a manifest would need `corpus/*` plus
  `!corpus/manifest.yml`.
