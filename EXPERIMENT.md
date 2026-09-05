# Running an experiment end to end

A sweep runs the full comparison: it builds the question pool, answers every request with every declared
configuration — agentic and two-phase — the declared number of times, and stores the assembled quizzes.
This walkthrough goes from a fresh checkout to inspecting quizzes in the browser and producing the
benchmark handoff. It uses one local model throughout so it runs on a laptop; scaling to real
configurations is a matter of catalogue entries and the sweep file, not code.

Prerequisites: the Setup section of `README.md` — the embedding model, an API key or the local fallback,
and lecture material in `corpus/` with a competency manifest. `--doctor` verifies all of it.

## 1. Point the pipeline at a model

For a local test, serve everything from Ollama:

```bash
ollama pull gpt-oss:20b && ollama pull nomic-embed-text && ollama serve   # if not already running
export LOGOS_BASE_URL=http://localhost:11434/v1
export LOGOS_API_KEY=ollama
export LOGOS_MODEL=gpt-oss:20b
```

The default backend in `config/models.yml` follows whatever those variables point at. Add a catalogue
entry for the test model — the sweep refers to models by these keys:

```yaml
# in config/models.yml under models:
  local:
    backend: logos          # the default backend, currently your Ollama
    model: gpt-oss:20b      # exactly as the provider reports it
```

## 2. Declare the requests

The request list is normally supplied for the experiment; for a smoke test, write a small one. Competency
keys must exist in the course model — with the committed dev manifest (`config/competencies.yml`) the
course key is `or` and keys like `lp-duality` work:

```yaml
# config/requests/smoke.yml
- key: smoke-r1
  course: or
  competencies: [lp-duality]
  language: en
  question-types: [single-choice]
  number-of-questions: 3
  difficulty: medium
```

## 3. Declare the sweep

```yaml
# config/sweeps/smoke.yml
sweep: smoke
requests-file: config/requests/smoke.yml
repetitions: 2
pool:
  items-per-cell: 4
  subsections: 2
  retrieval-top-m: 12
  languages: [en]              # keep the grid small for a smoke test
  question-types: [single-choice]
  difficulties: [medium]
configurations:
  - id: agentic-local
    approach: agentic
    generator: local
    judge: local
  - id: two-phase-local
    approach: two-phase
    generator: local
    judge: local
```

**Mind the pool size before running.** The pool grid is competencies × languages × types × difficulties ×
`items-per-cell`, generated *and* judged once each. The dev manifest has 16 competencies, so the sweep
above builds a 64-item pool ≈ 128 model calls ≈ 45–90 minutes on a 20B local model — plus the quizzes,
which are cheap by comparison. Widening `languages`, `question-types` or `difficulties` multiplies that
directly.

## 4. Run it

```bash
./gradlew bootRun --args='--experiment=config/sweeps/smoke.yml'
```

What happens, in order: the corpus is indexed (cached after the first run), the sweep is registered with a
fingerprint of its inputs, the pool is built cell by cell, extra judges add their verdicts, and then every
configuration answers every request `repetitions` times. Every completed unit logs an aggregate progress
line (`Pool pool-local: 14/32 done, 2 awaiting judge, 15 to generate, 1 failed`), each extra judge counts
its verdicts as it works, and quiz assembly logs its position (`[7/24]`).

To check progress from a second terminal — or after the run, without starting it again:

```bash
./gradlew bootRun --args='--experiment-status=config/sweeps/smoke.yml'
```

It prints each pool's state counts and the stored quizzes per configuration, makes no model call, and is
safe to run while the sweep is running.

Properties of the run worth relying on:

- **Kill it any time.** Re-running the same command resumes: finished pool items and stored quizzes are
  skipped, and an unchanged sweep re-run makes no model calls at all. An item that was mid-call when the
  process died is released on the next start and redone, so at most one model call is repeated.
- **An underfilled pool grows on demand.** When two-phase selection cannot fill a request, the sweep
  generates the shortfall into the request's pool cells — labelled, subsection-grounded and judged at pool
  entry like any other pool item — and selects again, up to `selection.top-up-rounds` times (default 3).
  Setting it to `0` restores strict pool-only serving, and a quiz is stored incomplete only once the
  rounds are exhausted. Top-up items stay in the pool for later requests.
- **A changed sweep is refused.** Editing requests, repetitions or the course model after the first run
  fails with "was created with a different configuration" — rename the sweep to start fresh.
- **The sweep name is the run id.** Everything lands in `data/run.db` under it.
- **`--as=<name>` runs the same plan as a fully independent run.** It overrides the sweep name and uses
  its own database (`data/run-<name>.db`), so it shares no pools, verdicts or quizzes with any earlier
  run — a clean slate with one flag and no file edits:

  ```bash
  ./gradlew bootRun --args='--experiment=config/sweeps/smoke.yml --as=pilot-2'
  ```

  Re-running the same command resumes `pilot-2`; the flag works the same on `--experiment-status`,
  `--export-experiment` and `--experiment-cost`. To browse a named run in the web interface, start the
  server pointed at its database: `./gradlew bootRun --args='--mcq.batch.database-path=data/run-pilot-2.db'`.

## 5. Inspect the quizzes

```bash
./gradlew bootRun          # no arguments: serves the web interface on 127.0.0.1:8080
```

**Quizzes** in the navigation lists every sweep's assembled quizzes — configuration, course, request,
repetition, question count, completeness. Opening one shows each question with its options, the correct
answer marked, the explanation, and the filter decision it entered the quiz with. An incomplete quiz means
the pool could not serve the request or the agentic loop ran out of rounds — visible here rather than
discovered downstream.

## 6. Export and price

```bash
./gradlew bootRun --args='--export-experiment=config/sweeps/smoke.yml'   # or the button on the Quizzes page
./gradlew bootRun --args='--experiment-cost=config/sweeps/smoke.yml'
```

The export writes `data/benchmark/smoke/` — public quizzes, intent files, a ready benchmark config, and the
private sidecars. From here, `BENCHMARK.md` is the handoff: what goes to whoever runs the benchmark, which
two lines of `benchmark.yaml` they edit, and what must never reach the judge. The cost report prices every
configuration and the pool from the recorded calls and prints the break-even quiz count.

## Scaling up to the real experiment

The smoke test and the real run differ only in data:

1. `corpus/<courseKey>/` gets the real course material; `mcq.competency-manifest` points at the directory
   of course catalogues (`EIDI.json`, `EIST.json`, `PSE.json`), so each course resolves its own model.
2. `config/models.yml` gains one entry per model tier, on whatever backends serve them.
3. The supplied request file replaces the smoke one.
4. The sweep file declares the real configurations — the committed `config/sweeps/example.yml` is the
   five-configuration shape — and widens the pool grid to the dimensions the requests need.

Then the same three commands, on whichever machine has model access.
