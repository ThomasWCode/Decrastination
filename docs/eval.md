# The evaluation set

A small set of your real emails and assignments, each with the right answer, to check Claude's readings whenever a prompt, the schema or the checks change (IMP-P1-004, your call on 11 Oct). It runs on this PC only: the data is in `private/eval/`, which git ignores, and nothing is sent to the API. Opus answers through Claude Code instead.

## What's where

- `private/eval/cases.json`: each case, as `EvalSetTest.Case`: an id, the job (`Email`, `Assignment` or `Effort`), when it's asked about, the task as the app stores it (from the phone's `tasks.json`, its old enrichment taken off), and what a right answer has (`expect`):
  - `kind`: `Admin`, `Event` or `Info` (emails);
  - `deadline`: its day (`YYYY-MM-DD`), or `none`;
  - `testDate`: an assignment's test day;
  - `effortMin`: the range its minutes must fall in, as the app plans them;
  - `mentions`: words that some block, step or the next step must hold, case aside ("STEM Potential");
  - `blocksAtLeast`: the fewest blocks;
  - `ankiSections`: the vocabulary sections it must name (an empty list: none).
- `private/eval/make_cases.py`: builds `cases.json` from a fresh copy of the phone's `tasks.json`, with the labels. Run it with `python -I`, the copy's path and `private/eval` as arguments.
- `private/eval/prompts/<id>.txt`: each request as the app makes it, email addresses redacted.
- `private/eval/answers/<id>-<run>.json`: Opus's answers.
- `private/eval/report.md`: the score.

## Running it

1. A fresh copy of the tasks, if the cases should change: `adb shell run-as com.thomaswcode.decrastination cat files/tasks.json > <scratch>/tasks.json`, then `make_cases.py`.
2. The prompts: `./gradlew testDebugUnitTest --tests "*EvalSetTest.dump"`.
3. The answers: for each prompt, an Opus subagent in Claude Code reads it and writes one JSON object to `answers/<id>-<run>.json` (a run number per round, so rounds can be compared).
4. The score: `./gradlew testDebugUnitTest --tests "*EvalSetTest.score"`, then read `report.md`. Every answer goes through `Answers.parse`, the app's own checks (an answer they'd drop fails), and the planner, as the app would.

Elsewhere (CI, another PC) the two tests find no `private/eval` and are skipped.

## Labels

The labels are yours: they were checked with you on 11 Oct (the work-experience email must name STEM Potential, Rosalind Franklin and Imperial Work Experience among its blocks). Change one in `make_cases.py` and run it again; keep held-out cases aside if a prompt is being tuned against these.
