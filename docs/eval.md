# The evaluation set

A small set of your real emails and assignments, each with the right answer, to check Claude's readings whenever a prompt, the schema or the checks change (IMP-P1-004, your call on 11 Oct). It runs on this PC only: the data is in `private/eval/`, which git ignores, and nothing is sent to the API. Opus answers through Claude Code instead.

## What's where

- `private/eval/cases.json`: each case, as `EvalSetTest.Case`: an id, the job (`Email`, `Assignment` or `Effort`), when it's asked about, the task as the app stores it (from the phone's `tasks.json`, its old enrichment taken off), and what a right answer has (`expect`):
  - `kind`: `Admin` or `Event` (emails). A case labelled `Info` is retired, since a starred email is never only to read (AI-02): `talk-summary`, `dell` and `linkedin` are listed as retired in the report, not scored;
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

## A scored run through the API (review v2, AR-02)

Since phase 8 the set can be asked of Claude itself, as the phone asks it: `EvalRunTest` sends each case through the app's own `ClaudeEnricher` (the job's system prompt, the profile from the settings' defaults and the case's task, the item, the schema and the job's effort: what `dump` writes, unredacted, as the app sends it), writes each answer to `answers/<id>-<run>.json` (the run numbered on from the last), then scores every answer as `score` does. It's a paid call a case, so it runs only when asked:

```bash
./gradlew testDebugUnitTest --tests '*EvalRunTest*' -PrunEval=true
```

with `CLAUDE_DECRASTINATION_API_KEY` in the environment, which the test inherits from Gradle (it's never printed). It stops before a call that, at its dearest ($1.295, the cap's worst case), could take it past `EVAL_BUDGET_USD` (2 dollars unless set), the cases after that not asked (the score then fails them as unanswered); `-Peffort=low|medium|high|max` sets how hard Claude thinks for the run, each job's own otherwise (high for these three). Each case's outcome and cost, and the run's total, are in the test's output (`app/build/test-results/testDebugUnitTest/`). Gradle won't run it again with nothing changed: add `--rerun` for another round. Without `-PrunEval=true`, the key or `private/eval` it's skipped, so CI never runs it, nor does a plain test run here, where the key is set.

## Labels

The labels are yours: they were checked with you on 11 Oct (the work-experience email must name STEM Potential, Rosalind Franklin and Imperial Work Experience among its blocks). Change one in `make_cases.py` and run it again; keep held-out cases aside if a prompt is being tuned against these.

## Review v2, phase 1 (14 Oct): starred conversations

The Email job changed (AI-02, AI-04, DX-06, DX-01), and with it what the app stores: so the email cases' tasks were read again from Gmail, read-only, through the app's own `ImapClient`, `Mime.readable` and `GmailThreads.conversationText` (a throwaway JVM test, kept out of the repo, by each case's thread id), so each holds what a starred read now stores: the whole conversation, quotes cut but the oldest message's, attachments named. The cases as they were are kept in `private/eval/cases-2026-10-11.json`. The three labelled Info (`dell`, `linkedin`, `talk-summary`) are retired: a starred email is never only to read.

| Round | Prompt | Passed | What failed |
|---|---|---|---|
| 1 (11 Oct) | 1.8.0's | 11 of 13 | `german-assess` (a step dated after the task is due), `talk-note` (read as Info) |
| 2 | phase 1 as planned | 5 of 10 | `help`: the quote cut was John's question, which the student never had, so "read the earlier message"; `zip-card`, `dad-apply`: AI-04's fixed next step ("Read the attachment and decide") over the prompt's own "one concrete action", and over attachment names that say what they are; `german-assess` again |
| 3 | as built: the oldest message read whole; the fallback names what to read | 10 of 10 | none (`german-assess` passing is the model's luck; its dated step is AI-01's, phase 7) |

Re-reading also found two faults the round couldn't have: an Outlook forward (subject `Fw:`, no forward marker) lost all 8 600 characters it forwarded (`open-day`), and an attached email had no name (`dad-apply`'s three, now named by their subjects).

## Review v2, phase 2 (15 Oct): a quoted deadline (AI-12)

The Email job now says whether its deadline is the email's own words (`deadlineQuoted`), and a quick action with no date of its own has none (no more "due within the next two weeks"). The scorer checks the new label where a case has one (`deadlineQuoted`, added to three cases: the open day's start is the email's own date; `dad-apply` and `zip-card` give none).

| Round | Prompt | Passed | What failed |
|---|---|---|---|
| 4 (15 Oct) | phase 2's | 8 of 10 | `dad-apply`: its three blocks are the three applications, now undated rather than each due two weeks on, as AI-12 means, but none says "apply", which its label asks for (round 3's had a "Reply … agreeing to apply" block); `german-assess`: a step dated after the task is due, as in round 1 (AI-01, phase 7) |

All three quoted labels passed: the open day quoted, the two notes with no date.

## Review v2, phase 3 (15 Oct): where a step can be done (PL-15)

The Assignment job now says of each step whether it's for home (the laptop, books or quiet) or can be done anywhere, a free period at school included (`where`). Only that job's prompt changed, so round 5 is the four assignment cases: the scorer now takes a round of one job, failing a missing answer only among that job's cases. `where` has no labels yet (they'd be yours): the report shows each step's reading.

| Round | Prompt | Passed | What failed |
|---|---|---|---|
| 5 (15 Oct) | phase 3's | 4 of 4 | none |

The readings: printing sheets, a listening exercise, joining an online group and practice exam questions and their marking for home; reading a text, learning vocabulary, translating, revising notes and a final run-through anywhere.

## Review v2, phase 8 (15 Oct): the first run through the API (AR-02)

Phases 6 and 7 changed the readings' prompts (the limits, the examples, a reason, the steps kept), and phase 8 raised `Prompts.VERSION` to 2 for them. Round 6 is the whole set through the API, by `EvalRunTest`, at each job's own effort (high).

| Round | Prompt | Passed | Cost | What failed |
|---|---|---|---|---|
| 6 (15 Oct) | phase 8's (version 2), through the API | 10 of 10 | $0.245, 94 s | none |

No answer needed putting back (AI-01): each passed the app's checks as it came.

## Review v2, phase 5 (15 Oct): Felix, the chat as him, the tidy, one question

Twenty requests made as the app makes them, from the phone's data of 15 Oct 06:45 (its tasks, settings, school calendar and the changes in use, in `private/eval-felix/`), by a throwaway JVM test kept out of the repository: Felix's prompt for each of his reasons, about tonight's real slots; the chat, now Felix, with tonight's plan; the weekly tidy of a month's overlapping facts; and the Email job's new question. Each was answered by an Opus subagent and read by the app's own readers (`FelixAnswers.parse`, `ChatAnswers.parse`, `MemoryTidy.after`, `Answers.parse`). The real calls are the phone's, at its checks.

| Group | Passed | What came back |
|---|---|---|
| Felix, ten reasons | 10 of 10 | Each named its piece and its first action, in two sentences at most, with no emoji, guilt or praise for nothing. At 07:00 with "don't message me before 8" standing, an empty message. Around slots he set his next check 8 to 15 minutes on; on the day done, none (the default then). The photo ask said which page to photograph. One case was built past its slot's end, and he asked what happened, as the rules would have him. |
| The chat as Felix, six messages | 6 of 6 | A time and the minutes for tonight's first piece, made at once and said as done ("Moved … to 19:30 tonight"). An INSET day that falls inside the half term already kept: nothing added. A preference remembered as a fact, and "don't message me before 8" as a rule. "What's due tomorrow?" answered from the list, with two replies to offer. |
| The weekly tidy | 1 of 1 | 16 overlapping facts (2 240 characters) into 10 lines (768), within its two-thirds limit: the newest kept on each topic, and two pairs merged. |
| One question (AI-10) | 3 of 3 | An email whose date turns on a booking asked which Friday; two clear ones asked nothing. |

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
