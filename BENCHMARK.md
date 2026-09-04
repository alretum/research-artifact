# Scoring an experiment with the external benchmark

This repository generates quizzes and **exports** them; it never scores them. Quality is measured by
[`ls1intum/paper-al-quiz-generation-benchmark`](https://github.com/ls1intum/paper-al-quiz-generation-benchmark),
run separately by whoever holds the evaluator budget. This file is the handoff: what the export contains,
and exactly how to run it through the benchmark.

Keeping the two tools separate is deliberate. Re-scoring the same quizzes with a different judge — for
example once the validated judge models are settled — must never require regenerating anything, and this
pipeline's own filter is part of what is being tested, so it cannot also be the measuring instrument.

## 1. Produce the export

```bash
./gradlew bootRun --args='--experiment=config/sweeps/<name>.yml'          # assemble the quizzes (resumable)
./gradlew bootRun --args='--export-experiment=config/sweeps/<name>.yml'  # write the benchmark input
```

The export lands in `data/benchmark/<sweep>/`:

| Path | What it is | Who may see it |
|---|---|---|
| `quizzes/*.json` | one public quiz per configuration × request × repetition | the benchmark and any rater |
| `instructions/*.json` | one intent file per request, shared by every configuration answering it | the benchmark |
| `benchmark.yaml` | a ready benchmark config with relative paths | the benchmark |
| `sidecars/*.key.json` | **hidden labels**: provenance, the generating configuration, explanations, filter decisions | the analysis only — see §4 |

The public files deliberately contain no explanation, no source reference wording, and no configuration
identity; each question carries the `learning_objective`, `bloom_intended`, `domain` and `language`
metadata the benchmark's criterion metrics require.

## 2. Hand over

Send the export directory. **Remove or withhold `sidecars/` from whatever the benchmark or a rater can
read** — it names which configuration generated each quiz, which is exactly the provenance the judge must
stay blind to. The sidecars go to the analysis holder separately (the key-holder role in the validation
corpus's two-file model).

The receiving side also needs the course slide PDFs, laid out one subdirectory per course key
(`EIDI/`, `EIST/`, `PSE/`): each quiz's `source_material` names its course's subdirectory, and the
`accuracy` and `coverage` metrics read the material from there.

## 3. Run the benchmark

```bash
git clone https://github.com/ls1intum/paper-al-quiz-generation-benchmark
cd paper-al-quiz-generation-benchmark && python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt          # needs Python 3.13+ and an LLM provider key in config/.env

cd <the export directory>                # paths in benchmark.yaml are relative to it
# edit benchmark.yaml first — see below
python <benchmark checkout>/main.py --config benchmark.yaml
```

Two edits in `benchmark.yaml` before running:

1. **`source_directory`** — point it at the course-material directory from §2.
2. **The evaluator** — the placeholder must become a real model, and it must be one **no configuration of
   the sweep used** as generator, judge or selector; an evaluator that produced or filtered these questions
   measures self-agreement, not quality. Preferably a judge the validation study validated. Several
   evaluators may be listed; every enabled metric names which it uses.

Leave `runs: 3` unless there is a reason not to: repeated judge runs are what make test-retest reliability
estimable. Do not add a `custom_prompt` to any instructions file — the field already carries exactly what
each request asked for, an added prompt roughly 2.4×-es the judge cost and injects grading hints.

## 4. Use the results

Results land in `results/` as rows keyed by `(quiz_id, question_id)` with `metric_name`, `score`,
`evaluator_model` and the judge's raw response. Joining them to experimental variables:

- `quiz_id` → the sidecar's `quiz.generator_id` gives the configuration (`approach|generator|judge`);
  the quiz filename and the `quiz` table in this repo's `data/run.db` give course, request and repetition.
- `question_id` → the sidecar's per-question entry gives the explanation and the pipeline's own filter
  decision, for comparing the internal filter against the independent judge.

Three traps for whoever aggregates:

1. **Filter on `applicable` before averaging.** `objective_alignment` and `homogeneous_options` report
   items they cannot judge as `applicable: false` **with a score of 100.0**; a naive mean counts every such
   item as perfect.
2. **Do not pool the two scopes.** `coverage` (and grammatical correctness in some versions) score per
   quiz; the other metrics score per question. One mean over both is uninterpretable.
3. **Binary metrics are proportions.** `answer_key_correctness` and `absence_of_cueing` only ever score
   100 or 0 — analyse them as rates, not as means with symmetric errors.

## 5. What stays in this repository

The cost half of the comparison never reaches the benchmark — its token usage is the *judge's*, not ours.
Generation, filtering and selection costs come from this repo's own call records:

```bash
./gradlew bootRun --args='--experiment-cost=config/sweeps/<name>.yml'
```

which prices every configuration per quiz, attributes each pool build per model, and reports the break-even
quiz count at which a pooled configuration overtakes the agentic one. Local GPU time in those figures is
client wall-clock — an upper bound until server-side timings are joined in.
