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

## The chat (2.0.0)

Ten messages to *Ask Claude*, sent as the app sends them: the exact system prompt, schema and turns built from the phone's `tasks.json` and `instructions.json` (pulled into `private/eval-chat/`, addresses redacted) by a throwaway JVM test kept out of the repo, each answered by an Opus subagent, and each answer put through `ChatAnswers.parse`. 12 Oct, against `ChatPrompts` as committed in #30: all ten read, and each did what was asked.

| Message | What came back |
|---|---|
| "I need to write my UCAS personal statement by 1 December, it'll take about 8 hours" | a task due 1 Dec 23:59, 8 h in four steps |
| "the bridge and balances physics will take 90 minutes" | Minutes: 1 h 30 min (now 1 h 40 min) |
| "move the German vocabulary and translation to Thursday" | due Thu 15 Oct 23:59 |
| "I'm out on Saturday evening from 7" | Sat 17 Oct, 19:00–23:59 busy |
| "remind me to buy mum a birthday present before Sunday" | an Admin task due Sat 23:59, 1 h, the guess said |
| "I've got a test soon" | a question back (which subject, which day), nothing to apply |
| "what's due tomorrow?" | the answer from the list, nothing to apply |
| "this will take 45 min" (about *Call with Jags after school*) | Minutes: 45 min (now 5 min); an instruction it couldn't read before |
| "I've got a test soon" → "When is it…?" → "Friday, maths mechanics, about 3 hours of revision" | a Revision task (Maths), 3 h, due Thu |
| "I can't do any work on Wednesday" | Wed 14 Oct: no work |

### With the plan (#31)

Ten more, with the context the phone itself sends: written there by the debug command `chat-context` (the tasks, the calendar, the changes in use and the plan) and pulled into `private/eval-chat-plan/`, so the plan is the phone's own, calendar and instructions included. As before, the cases were built by a throwaway JVM test, answered by Opus subagents and put through `ChatAnswers.parse`. 13 Oct, at 06:47 on a Tuesday with tonight's plan 710 minutes over: all ten read, and each answered from the right list.

| Message | What came back |
|---|---|
| "what's on tomorrow?" | tomorrow's four tasks from the plan, with their minutes and the day's 102 of 300 |
| "what's due tomorrow?" | from the list, not the plan: the one thing due (the call with Jags), and the next homework after it |
| "what should I do first tonight?" | the plan's next piece and the one after; but it said Bridge "was due" at 08:30 when it was 06:47 |
| "how busy is Saturday?" | 110 of 630 minutes, its three tasks, around that day's events |
| "why is today so full?" | the overdue work and the two tasks that won't fit before their deadlines, 1025 against 315, and an offer to move or drop what's done |
| "when am I doing the chapter 18 prep?" | its pieces Wednesday to Sunday, day by day |
| "what's on this week?" | a line a day, today's overload said |
| "I'm out on Thursday evening, what happens to the work planned then?" | Thursday's 138 minutes, none due before Monday, and a question back: out from when till when? |
| "is there anything the plan has left out?" | nothing; at first it guessed which tasks made up the 850 minutes after the fortnight, so that line now names them, and the rerun didn't guess |
| "what's on tomorrow?" → (the answer) → "move the profile picture to the weekend" | can't start before Sat 17 Oct and due Sun 18 Oct, on one card |
