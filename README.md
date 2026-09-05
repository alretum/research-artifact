# mcq-pipeline

Turns lecture material into grounded multiple-choice questions with two competing approaches — **agentic**
(generate on demand, filter, top up) and **two-phase** (pre-build a judged question pool, select from it) —
and exports the assembled quizzes for independent quality evaluation.

It has no runtime dependency on Artemis, Pyris or Logos code. It needs a directory of lecture PDFs, one
chat backend, and one local embedding model.

**Quality is not measured here.** The pipeline's own filter is part of what is being tested, so it cannot
also be the measuring instrument. Independent scores come from
[`ls1intum/paper-al-quiz-generation-benchmark`](https://github.com/ls1intum/paper-al-quiz-generation-benchmark),
which this tool exports for: [`BENCHMARK.md`](./BENCHMARK.md) is the scoring handoff.

---

## Setup — three steps

**1. Build.** Any JDK 17+ on the PATH is enough to launch Gradle; the build downloads JDK 25 itself.

```bash
./gradlew build
```

**2. Embedding model.** Embeddings run locally on CPU via [Ollama](https://ollama.com):

```bash
ollama serve &                      # binds 127.0.0.1:11434
ollama pull nomic-embed-text        # 274 MB
```

**3. API key.** Chat — generation, judging, selection — runs on TUM's Logos GPUs:

```bash
cp config/logos-env.example ~/.logos-env && chmod 600 ~/.logos-env
$EDITOR ~/.logos-env                 # set LOGOS_API_KEY
source ~/.logos-env                  # or add this line to ~/.zshenv
```

The key lives in the environment, never in a file inside the repository. Logos is reachable only from the
TUM network or eduVPN, and your key must be granted the models it should use.

**Verify** — one command checks every prerequisite and says how to fix whatever is missing:

```bash
./gradlew bootRun --args='--doctor'
```

## Run an experiment — three steps

The full walkthrough with a worked example is [`EXPERIMENT.md`](./EXPERIMENT.md); this is the short form.

**1. Add material.** One folder per course under `corpus/`, one competency catalogue per course
(`mcq.competency-manifest` in `application.yml` points at it — the committed `config/competencies.yml`
serves the development corpus).

**2. Declare what to run.** A request file (what quizzes to ask for) and a sweep file (which
configurations answer them, and the pool dimensions). The committed `config/requests/logos-test.yml` and
`config/sweeps/logos-test.yml` are working examples to copy.

**3. Run it.** On a VM, build the jar once and start the sweep with `nohup` — **it needs no open
terminal and no standing SSH session**:

```bash
./gradlew bootJar && cp build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar run.jar
source ~/.logos-env
nohup java -jar run.jar --experiment=config/sweeps/logos-test.yml --as=my-run > sweep.log 2>&1 &
```

(Run from the copy, not from `build/libs/` — a rebuild that rewrites the jar under a running process
breaks its lazy class loading.)

Log out; the run keeps going. **Every unit of work is persisted, so the run is fully resumable**: if the
process dies — crash, reboot, `kill -9` — re-running the exact same command continues where it stopped, at
most one model call is repeated, finished work is never redone, and re-running a completed sweep makes no
model calls at all. `--as=<name>` also makes the run independent: it runs under that name against its own
database (`data/run-<name>.db`), sharing no pools, verdicts or quizzes with any other run.

Follow progress with `tail -f sweep.log`, or read-only from any other shell:

```bash
java -jar build/libs/mcq-pipeline-0.1.0-SNAPSHOT.jar --experiment-status=config/sweeps/logos-test.yml --as=my-run
```

(On a workstation with the terminal open, `./gradlew bootRun --args='--experiment=… --as=…'` does the
same thing attached.)

**Inspect and hand off.** `./gradlew bootRun --args='--mcq.batch.database-path=data/run-my-run.db'`
serves the web interface on <http://localhost:8080> — quizzes, the question pool with each judge's
independent verdict, and every agentic generation. Then:

```bash
./gradlew bootRun --args='--export-experiment=config/sweeps/logos-test.yml --as=my-run'   # benchmark input
./gradlew bootRun --args='--experiment-cost=config/sweeps/logos-test.yml --as=my-run'     # € per configuration
```

The export lands in `data/benchmark/my-run/`; [`BENCHMARK.md`](./BENCHMARK.md) says what to send to whoever
runs the benchmark and what must never reach the judge.

---

## Reference

### Web interface

Started by `./gradlew bootRun` with no command argument. **It has no authentication and is bound to
loopback** — reach a remote instance through an SSH tunnel, never a public port.

| page | what it is for |
|---|---|
| **Dashboard** | Runs with live progress, corpus summary, start/resume/stop a single-model run |
| **Corpus** | Upload lecture material with an extraction preview before committing |
| **Items** | Browse generated questions by run, topic and decision; open one; answer it |
| **Pool** | Every pool question with its labels and each judge's independent verdict |
| **Agentic** | Every request-time generation of the agentic approach, accepted or rejected |
| **Quizzes** | Assembled quizzes per sweep, with per-question filter decisions and export |
| **Plans** | Multi-configuration generator×filter runs over the development corpus |
| **Export** | Item-level benchmark export for single runs |
| **Setup** | The prerequisite checklist |

### Command line

Each runs one task and exits; `--as=<name>` scopes the four experiment commands to an independent run.

| command | what it does |
|---|---|
| `--experiment=FILE` | Run a sweep: build pools, judge, assemble every configured quiz. Resumable |
| `--experiment-status=FILE` | Print pool and quiz progress without a model call; safe while running |
| `--export-experiment=FILE` | Write the benchmark input for a sweep |
| `--experiment-cost=FILE` | Price every configuration and pool from recorded calls; break-even quiz count |
| `--doctor` | The prerequisite checklist |
| `--count=N`, `--resume=ID` | Single-model generation runs (the Dashboard's CLI equivalent) |
| `--plan=FILE`, `--run-plan=FILE` | Validate / run a generator×filter plan |
| `--report`, `--sweep`, `--cost`, `--redecide` | Reports over stored results; no model calls |
| `--export-benchmark=DIR` | Item-level benchmark export |
| `--retrieval-only` | Index and probe retrieval; no generation calls |

### Sizing an experiment

The pool grid is **competencies × languages × question types × difficulties**, each cell filled with
`items-per-cell` questions — generated and judged once each, plus one judging pass per additional judge.
That product is the bulk of the cost; widen it deliberately.

| what it adjusts | file | key |
|---|---|---|
| questions per cell (competency × language × type × difficulty) | sweep | `pool.items-per-cell` |
| which languages / question types / difficulties get cells | sweep | `pool.languages`, `pool.question-types`, `pool.difficulties` |
| how a cell's grounding is spread across the material | sweep | `pool.subsections`, `pool.retrieval-top-m` |
| questions per quiz | requests | `number-of-questions` |
| quizzes per configuration per request | sweep | `repetitions` |
| pool growth when a request cannot be filled (0 disables) | sweep | `selection.top-up-rounds` |
| agentic regeneration rounds per quiz | sweep | `agentic.max-rounds` |

### Models and configuration

`config/models.yml` separates **backends** (base URL plus the *name* of the environment variable holding
the key) from **models** (provider model id plus which backend serves it). A new model on an existing
backend is one catalogue entry; a new backend additionally needs its key in the environment. Azure OpenAI
is expected *not* to work as written (different credential type). Sweep files refer to models by their
catalogue keys.

Defaults bind to `LOGOS_BASE_URL`, `LOGOS_API_KEY`, `LOGOS_MODEL` and `EMBEDDING_BASE_URL`; for offline
development, `--spring.profiles.active=ollama` puts chat on Ollama too — never use it for timed or costed
claims. Every tunable lives under `mcq.*` in `application.yml`.

### Corpus conventions

Only PDFs are ingested, recursively. The first directory level names the lecture and appears in every
citation; the filename names the unit and determines the recorded source role (solution, tutorial, central
exercise, lecture deck). Extraction is deliberately unrepaired; per-document damage counts land in
`data/extraction-report.csv`. Topics and competencies come from the competency catalogue, never from
folder names; the declared Bloom taxonomy and description are exported as `bloom_intended` and
`learning_objective` for the benchmark.

### The filter

Every generated question is judged per failure mode with recorded severities. Only the modes in
`mcq.filter.gating-modes` decide acceptance — the aggregate is `1 − worst gating severity`, so any gating
mode can reject alone. `NEAR_DUPLICATE` is deliberately non-gating: recall-level questions are legitimate
for REMEMBER competencies, and the benchmark measures cognitive level downstream. Rejected questions are
kept with their verdicts, so filtered-versus-unfiltered comparisons cost nothing. In pools, every judge
records an **independent** verdict per question; a configuration only ever uses its own judge's column.

### Where output goes

Everything under `data/` is gitignored and regenerable.

| path | contents |
|---|---|
| `data/run.db` | Default SQLite store: items, verdicts, quizzes, document hashes. Authoritative |
| `data/run-<name>.db` | The isolated store of a `--as=<name>` run |
| `data/benchmark/<sweep>/` | Benchmark exports: public quizzes, intent files, private sidecars |
| `data/index.json` | Cached embedding index, rebuilt when the corpus changes |
| `data/*.csv`, `data/run-log.jsonl`, `data/items.md` | Ingestion diagnostics and item renderings |

Run stores are per-machine; do not copy them between machines. Prices in `config/pricing.yml` are applied
at report time, never at write time — revising a price is a re-report, not a re-run.

### Known limitations

- No quality evaluation here, by design — the benchmark measures quality independently.
- Concurrency stays at 1 for any run whose cost or latency will be reported.
- Cost figures for time-billed models are bounds from client wall-clock, not GPU measurements.
- Only one OpenAI-compatible backend shape is proven end to end (Logos); treat other providers as
  untested, and Azure as needing a small addition.
- No authentication on the web interface; loopback plus SSH tunnel is the mitigation.
