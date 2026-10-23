# Readings

How Claude reads a task (its "enrichment"): what each job is sent and returns, how an answer is checked, how long steps become sittings, and when a task is read again.
The rules give every task a reading too, and stand in whenever Claude can't. Claude is the model; Felix, who talks to you, is in felix.md.

## What's read

- A reading is an `Enrichment` (`core/Enrichment.kt`), kept on the task and laid over the source's values at every merge (sources.md). It records who made it (`by`: "rules", or the model that answered), from what (`inputHash`), and under which prompts (`promptVersion`).
- Three jobs run on tasks (`Enrichments.jobFor`): **Email** for a Gmail conversation, **Assignment** for a Teams assignment, **Effort** for a Power Planner task.
- Not read: a task of your own, Anki's derived tasks, feedback to read (NF-06), a Power Planner event, a task you've said isn't one, a closed task, and an email whose text isn't yet read as far as reads go (`GmailThreads.textRead`).
- Claude reads only while it's on: the switch in Settings, a key in use (`aiKeyActive`) and the key in the encrypted secrets file (`ModelGateway.claudeKey`). These change at once, armed or not. Off, the rules read everything.
- `RuleEnricher` (`enrich/Enricher.kt`) and `ClaudeEnricher` (`enrich/ClaudeEnricher.kt`) both implement `Enricher`. The prompts and the checks are `ReadingPrompts` and `ReadingAnswers` (`enrich/ReadingPrompts.kt`, `enrich/ReadingAnswers.kt`).

## The three jobs

What a job is sent is `ReadingPrompts.describe`; what it answers is `ReadingPrompts.schema`, every field required, optional ones nullable, every object closed.

**Email**: a starred conversation, always something to do (AI-02).
- Sent: today, when its newest message came, who from ("the student (a note to self)" for one of yours), the subject, how many messages, attachments by name (AI-04), your answers, its blocks as last planned, and the text up to 16 000 characters (`ReadingPrompts.MAX_TEXT`), the rest said to be left out.
- Returns: `kind` (only "Admin"), `actionableFrom`, `deadline`, `deadlineQuoted` (AI-12), `effortMin` (reading included; at least 5 for one only to read and decide), `nextStep` (one concrete action), `question` (AI-10), `reason`, `blocks` and `summary`.
- Blocks only for separate pieces of work with dates of their own, a calendar of deadlines (Q12): each with its minutes, `from` and `due`. One job, however long, has none. A rough window runs from its first day to its last.
- Where what it asks turns on an attachment or an earlier message Claude can't see, its next step says to read that first, naming it, at 10 minutes.

**Assignment**: a Teams assignment.
- Sent: today, its title, class and the source's due time, the Anki decks by topic (DX-10), its reference materials by name and its links (DX-08), your answers, its steps as last planned, and its instructions.
- Returns: `subSteps` (each with title, minutes, `ankiSections`, `where`, `from`, `due` and `anyOrder`), `effortMin` (their sum), `ankiSections`, `testDate`, `question`, `reason` and `summary`.
- Revising for a test after the hand-in gets no step: its day goes in `testDate`, read as 08:30 that day, which brings the task's date forward when it's earlier than the source's.

**Effort**: a Power Planner task.
- Sent: today, its title, class, due date, its blocks as last planned, and its details. Returns `effortMin`, `blocks` only where its details list pieces of work with dates of their own, and `summary`.
- An appointment isn't work: only the time to prepare for it, at least a minute.

Every job:
- Its prompt (`ReadingPrompts.system`) is its brief, the limits its answer is checked against, from the checks' own values (`ReadingPrompts.limits`, AI-03), and made-up worked examples (`enrich/Examples.kt`, AI-07), each an answer the checks pass whole.
- The schema says what the minutes, the dates, `where`, a step's `ankiSections`, a step's or block's `anyOrder`, `reason` and `summary` are for, with the limits of the minutes, `reason` and `summary` (AI-20); the brief describes the rest. The year group comes from the profile (AI-06).
- `reason` (Email and Assignment) quotes the phrase that gave the date, kind or test date, at most 160 characters (AI-09), shown under the due date on the task's sheet.
- `summary`, last (D-93): for a text over about 600 characters, what it asks of the student from all of it, in at most 400 characters (500 kept): an email's what to do and by when, what to bring, send or pay, and what a later message changed; an assignment's work, parts, needs, dates and hand-in. Empty for a shorter text. Felix is told it in place of a text too long for his room ([felix.md](felix.md)); the readings made before it have none, and his room keeps the text's start and end instead.

## Steps: as few as the work has

The Assignment prompt asks for the steps the student will actually do, as few as the work really has:
- one step, the whole of it, unless the instructions list separate pieces of work (questions and an essay; vocabulary and a reading);
- never one piece split by what's in it (pages, a share of the questions, research then writing): the app can't know how long each part takes, so it cuts by time instead (Sittings, below);
- a run of pages, questions or exercises is one step; a few minutes' printing, reading the task, marking, uploading or handing in goes in the title of the step it belongs to;
- dates on a step only where the instructions give them, and none after the hand-in.
- Learning numbered vocabulary is a step of its own, its sections in `ankiSections`: the planner leaves it out where your Anki deck tasks hold them. A step asking for anything else too is planned whole (`AnkiRules.vocabularyOnly`).
- **Where** (PL-15): each of an assignment's steps is "home" (the laptop, books or quiet) or "anywhere" (a free period at school). The planner keeps "home" steps out of school time (planner.md); a step without one, an email's or planner item's block included, goes by its task's.
- **In any order** (D-109): each step and block says whether it needs the ones before it done first (`anyOrder`): true for a separate piece of work (separate questions or exercises, a reading task beside vocabulary to learn, separate applications), false for one that builds on them, and for the first; of a step or block cut into parts as it's read, only the first part keeps it. A day's slots may lay one marked so, in one piece, ahead of them (slots-and-calendar.md).

Emails' and planner items' blocks follow the same rule: only separately dated pieces, never one job cut up.

## The checks on an answer

`ReadingAnswers.read` checks every answer; what's out of range is dropped, not trusted.
- **A task's dates** (deadline, start, test date): a deadline is `YYYY-MM-DD` or `YYYY-MM-DDTHH:MM`, a date alone being 23:59; a start is read as its day's midnight, and a test date as 08:30 that day. One that can't be read, or is over 400 days ahead, is a complaint; one more than a month gone is left out quietly.
- **A step's or block's dates** out of that range, or unreadable, fail all the steps.
- **Lengths**: a title 80 characters, a next step 120, a question 200, a reason 160. Only sections like "1.2" are kept.
- **An email's kind** must be Admin (an older answer's Event is read as Admin); any other is no answer, and the rules' reading stands.
- **An estimate** is 1 to 600 minutes (`ReadingAnswers.MAX_EFFORT`, ten hours), and so is each step or block.
- **Steps**: at most 12 (`ReadingAnswers.MAX_STEPS`). With more, the first eleven stay and the rest are folded, each run of plain steps into one "The rest: …" step, while vocabulary and dated steps keep their own (`ReadingAnswers.fold`).
- They must add up to `effortMin` within a tenth or 10 minutes, whichever is more, both within 600 minutes. Otherwise the steps are dropped (below), and the task is planned as one piece at Claude's total, or at its own estimate where that total is out of range.
- **Blocks**: at most 30 (`ReadingPrompts.MAX_BLOCKS`), 20 hours in all (`ReadingAnswers.MAX_BLOCKS_EFFORT`), adding up to `effortMin` as steps must. With blocks, the estimate is their total.
- **Dates in order**: a step or block can't start after its own due, nor start or be due after the task's due (or the test), nor start after a later one's due; an email's block can't be due ahead of the email's start.
- Felix's steps have their own rule, AI-19 (below).

**Put back once, then salvaged** (AI-01):
- The first answer is read strictly (`salvage = false`). A failed check becomes a complaint naming the step and what's wrong ("The steps add up to 135 minutes, not the 200 effortMin gives").
- The answer is sent back once, as a call of its own under the cap: the item, Claude's answer as its turn, and "Your answer had a problem: …. Return the whole answer again with only that put right." (`ClaudeEnricher.repair`, `ReadingPrompts.repair`).
- It isn't sent back where that would leave the item under 4 000 characters of room.
- Read with salvage, a step whose own dates can't be (a start after its due, or a start or due after the task's) keeps its place and minutes with no dates, planned by its task's; the sheet says so (`Enrichment.adjusted`). Whatever still fails is dropped.
- Meanwhile, and if the second answer gives nothing, the first answer read with salvage stands.

**A dropped plan is said.** Steps or blocks not trusted leave `Enrichment.dropped` saying why, and the task is planned as one piece.
- Work tags the task "Claude's plan dropped" and its sheet says why; a notification says so once for that content (`ModelAlerts.dropped`), withdrawn once the task has a plan again or goes. The rules' readings are never dropped.

## Sittings

A step over an hour is cut into even sittings by time, never by content (`core/Sittings.kt`, `ReadingAnswers.fitParts`).
- `Sittings.count`: a sitting an hour, or part of one (`Sittings.MAX_MIN`, 60). `Sittings.even`: even minutes, the odd ones first (100 is 50 and 50; 125 is 42, 42 and 41).
- Titles: the first is the step's own, then "Continue: …", and the last "Continue and finish: …". Each sitting keeps its step's dates.
- `ReadingAnswers.sittings` cuts after folding, so a folded step is cut too; the rules' steps are cut the same way.
- **A step done isn't cut.** Each new step first takes the mark of the old step of its exact title at the same place among those of that name (`carryMarks`: the second "Review" the second's). One that takes a done mark stays whole, so all of it keeps its tick.
- The planner cuts a step still over an hour as it plans it, by the minutes it plans (the pace learned, your minutes shared out), in the same way (`Planner.sittings`, PL-12, planner.md).
- Never your steps, or Felix's (below).

## Your steps, and Felix's

- Steps you give a task, or Felix gives it with a `Steps` change (`TaskItem.mine.steps`, with who gave them), are laid over the reading's at every merge (`TaskItem.enriched`), their ticks carried by id (`carryMarks`). Taken back, the reading's steps return, ticks kept by name.
- Every step has an id, given as it's stored, and where it came from (`SubStep.origin`: the source, a reading, the rules, you, Felix, your own task's form): a reading's steps are told from yours by it, not by their names. Read again, a step of the same name, at the same place among its name's, keeps its id as well as its tick (`carryMarks`).
- They never pass through a reading: no reading's check, fold or sitting touches them, and a reading never replaces them. The planner neither cuts them nor scales them by your pace (`TaskItem.yourSteps`, `Calibration.multiplierFor`).
- Felix's steps must add up to the task's minutes, or come with a `Minutes` change for their new total (AI-19, `InstructionAnswers.stepsProblem`); otherwise they're left out, and said. An Anki task's steps are its cards, and can't be given.
- A task with your steps is still read, its reading kept in its `Enrichment`, and your steps stay the plan. Claude is sent them as the task's steps as last planned (`ClaudeEnricher.previous`).
- A task of your own isn't read at all: its steps and minutes are what you gave.

## The rules' reading

`RuleEnricher` reads without a model, always, as the floor; its readings are `by = "rules"`, and never dropped.
- An assignment whose instructions list two or more parts (lines starting "1." or "2)", not a section like "1.2", or a dash or bullet) gets them as steps, sharing its estimate (each at least 5 minutes), titles cut to 60 characters, then cut into sittings.
- A part that's only learning numbered vocabulary is tagged with its sections.
- An email's kind, estimate, due date and next step come from `EmailRules` at the source (sources.md). A Power Planner task keeps its kind's default estimate.

## When a task is read

`Readings.enrichNow` (`enrich/Readings.kt`), run by `enrich/EnrichWorker.kt` after a sync leaves any task's reading out of date (online, where Claude's to be asked), when Claude is switched on or off, and when a question gets your answer.
It runs again once Claude can be asked again (its rest over, `EnrichWorker.retryAfter`; a higher cap). A new photo reads its task at once.
- **Out of date** (`Enrichments.stale`): no reading; its content changed (`inputHash`); Claude on and the reading the rules'; Claude off and the reading Claude's (the rules' replaces it); an email read as only to read; or, Claude on, an open task Claude read under older prompts (AC-06).
- **Order** (`Enrichments.NEED`): never read first, then the oldest prompts' readings, each soonest due first. At most 20 calls to Claude a run (`Readings.MAX_MODEL_CALLS`), second asks counted.
- Each task is looked up afresh just ahead of its call, and the reading laid only over the task as read: one that changes during the call is read in the next run. One task is read at a time, whatever's reading it (a sync's run, a rest's retry, the sheet's *Read again*, a photo's): a second run at once waits for the first's answer and finds the task read, so an unchanged task isn't paid for twice (4.3.15); *Read again* still reads it again, as asked.
- **A call that fails** gives the task the rules' reading (one stale by the version alone keeps its own), and leaves Claude alone for the rest of the run; but a request the API won't take leaves only that task to the rules, till its content changes (`ModelState.enrichFailed`).
- **Declined or unreadable** (a refusal, an answer cut off at the token limit, one that still can't be read once put back): the rules' reading is kept under Claude's name and these prompts, so it isn't asked for again till something changes.
- **Read again with Claude** (the sheet, `Readings.readAgain`) reads that task at once, whatever read it. Its reading stands till the new one comes, and you're told if none did. Not while Claude is off, resting or at its cap.
- A reading that finds new vocabulary sections, or moves a deck task's date, has Anki read again at once.

## The input hash

`Enrichments.inputHash`: the first 8 bytes of a SHA-256, in hex, of what a reading reads.
- In it: the source; the source's title (not yours, so a rename isn't new content); the text; the class; the source's own deadline (not the overlay's, which would change the hash itself); an email's sender and when its newest message came.
- Only where there are some: attachments, a Teams assignment's links, your answers (AI-10), the photo's hash (AI-23).
- Not in it: your steps, minutes, due date or other overrides, the prompts' version, the time. A change to the decks' topics clears the hash of the assignments it touches (`Syncer`), so they're read again.
- Used for a reading's freshness (`Enrichments.current`), a micro-plan's (`MicroPlans.hashOf`), a request the API refused (`enrichFailed`), and whether a total Felix paced still teaches your pace (`TaskItem.pacedTeaches`, learning.md).

## The prompts' version (AC-06)

`ReadingPrompts.VERSION` is 9. Each reading keeps the version of the prompts that made it (`Enrichment.promptVersion`; 1 where none is stored).
- With Claude on, an open task Claude read under an older version is read again once, within the run's 20 calls, the oldest first.
- **Not a task with a step of its reading done** (`partDone`, `core/Enrichment.kt`): newer prompts could give its work as other steps, and a tick goes only to a step of its name, so work done would be planned again. It's read again only when its content changes or you ask.
- Read for such a reason, Claude is told which steps are done: each listed "(N min, done)", to be kept exactly as it is (title and minutes, still in `effortMin`), with only the work left given in the others (AI-08, `ReadingPrompts.describe`).
- A re-read for the version alone that gets no answer keeps its reading, marked as these prompts'; one past the run's calls waits for the next run.
- **The version is raised** with any change to a reading's prompt, schema or examples, or to the profile's shape.
- `GoldenPromptsTest` (AR-01) keeps a golden copy of every prompt and schema in `app/src/test/resources/prompts/`, but the web search's prompt (which `FelixInputsDocTest` holds in [felix-inputs.md](felix-inputs.md)), and fails on any difference, with its diff; `-PupdateGolden=true` writes them again.
- Where an `enrich-*` copy or `profile.txt` differs from HEAD's, the same test fails unless `ReadingPrompts.VERSION` differs from HEAD's too.
- **Every schema compiles.** The API turns a schema into a grammar that holds the answer to it as it's written, and refuses one whose grammar is too large. A nullable field is a union in it, and unions inside a list's items weigh most: the chat's and the instructions reader's lists have none, an unused field being empty (a number -1). `SchemaLimitsTest` holds every schema but the week's summary's (one plain string) to 16 unions and two inside a list; `SchemaCompileTest`, asked for with `-PcheckSchemas=true`, has the API compile each of those ([development.md](../development.md#building-and-testing)).

## Questions (AI-10)

- An Email or Assignment reading may carry the one question that would settle what its text can't (which Friday; whether the test is on the 12th or the 19th), rarely.
- Felix puts it to you when he can (felix.md), and not again within a day for that task (`Looks.queueQuestion`). A newer reading's question takes the place of one waiting; a reading with none takes it away.
- Your answer is an instruction on the task, and kept with it (`TaskItem.answers`, the latest five). It's in the input hash, so the task is read again at once, Claude told "The student says: …".

## Quoted deadlines (AI-12)

- The Email job says whether its deadline is the email's own (`deadlineQuoted`): a date it states or clearly implies, or the start of what it's about.
- An email's deadline is real only when quoted, by its reading as it stands: then it's shown as missed once it passes (`TaskItem.realDeadline`, PL-14). A day the email only suggests is a plan, moved when it passes (planner.md). An invite's start is real too, and your own due date always is.
- A quick action with no date of its own (replying, signing up) has none, nor do its blocks: it's planned as undated work.
- A deadline dropped as out of range quotes nothing. The rules' dates are never quoted.

## Photos (AI-23)

- *Add a photo of the work*, on a task Claude reads, keeps one photo with it (`Photos.attachPhoto`: the app's own files, one a task, a new one replacing the last). Its hash is in the input hash, so the task is read again at once.
- The photo goes first in the item, Claude told it's the work itself, to estimate and split from, saying in `reason` what it saw (`ReadingPrompts.photo`). Such a reading is `fromPhoto`, and the sheet says "From your photo".
- Photos no task holds are let go when the app starts (`Photos.sweepPhotos`), but for one a chat delete can still undo.
- `Readings` and `Photos` are built like `Focus` (their stores, the clock, the gateway and small callbacks); `ReadingsTest` and `PhotosTest` drive them on temporary stores, Claude's API a stand-in.
- A photo of homework, taken in Work, is read into tasks of your own instead (NF-01, `PhotoTasks`, `PhotoTaskReader`), each checked in the form (sources.md) and kept with the photo; or *Attach to an existing task instead* makes it the photo of a task Claude reads, read again with it.

## The request

Each reading is one Messages API call through the Anthropic Java SDK (`ClaudeEnricher.send`):
- model `claude-opus-5-5`, `max_tokens` 16 000 (thinking counts towards it, and a call costs only what it uses), at high effort for the three readings (AC-02, `ModelEffort`);
- two system blocks (`ModelCalls.systemBlocks`): the job's prompt, marked to be cached for an hour (AC-01), then the profile (AI-05, `enrich/Profile.kt`), not cached, as it changes;
- the answer held to the job's schema (`output_config.format`), and the server-side fallback on (`fallbacks: "default"` and its beta header), so a request the model's safety classifiers decline runs on a model the API picks;
- the item cut to 40 000 characters (`Pricing.MAX_INPUT_CHARS`), a 3-minute timeout and two retries.
- A refusal, or an answer cut off at the token limit, is no answer. A fallback's answer is recorded and priced as the model that gave it (BUG-P3-001).
- The profile: the year group, your subjects (by Settings' class map), the week's lessons and study periods, working hours, days off coming, the textbook, your lines about yourself, how you've asked Felix to behave and what he remembers (one held for a while saying till when, or with which task: "(until Sun 25 Oct)"; what's run out left out, D-103); its own part at most 2 000 characters, the whole 16 000.

## Web research

Felix's look-ups are a call of their own (`enrich/WebResearcher.kt`), never part of the chat's: the search tool beside the chat's answer schema makes a grammar the API refuses as too large.
- When his answer asks to look something up (`research`), `WebResearcher.look` asks Claude with its web search tool (`web_search_20250305`): at most 3 searches, located in the UK and your zone, no profile sent, at medium effort.
- Its answer is in words, at most 15 short lines (3 000 characters), with up to 4 pages kept. One that searches past its turn isn't carried on. Felix is then asked again with what it found (felix.md).
- Settings → Felix has its switch (`felixSearches`), on to start with.

## Micro-plans (AI-15)

A piece's first action, under two minutes and concrete, the next two, and what done looks like (`enrich/MicroPlans.kt`).
- Written ahead after each plan (`plan/Ahead.kt`) for today's and tomorrow's work slots' pieces: the piece of Felix's next message first, then the rest; at most 8 a run, one run at a time, each a call under the cap at medium effort (`MicroPlanner`).
- Sent, with the profile: the task's title and subject, the piece and its minutes, attachments by name, the notes kept with its task (its own and the standing ones it matches), and its instructions (1 500 characters).
- Written once for a piece and its task as they stand (`MicroPlans.hashOf`: the input hash, the piece, and the task's notes where it has any). One declined or unreadable is kept empty, so it isn't paid for again; those for days gone go.
- Shown on Day's slot and *Do now* card, the piece's and task's sheets, the widget, the calendar's event, and in Felix's messages.

## Cost, the cap and rest after failures

`enrich/AiUsage.kt`, `enrich/Failures.kt`, `enrich/ModelHold.kt`, and `enrich/ModelGateway.kt`, which every call goes through (`ModelGateway.call`).
- **Cost** per call from its tokens at list price (`Pricing`): Opus 5.5 at $4 in, $20 out, $0.20 cache reads, and $8 a million written to the cache kept an hour (every system prompt but the web search's, AC-01) or $5 to the five minutes' (a web search's results); an older Opus's $5, $25, $0.50, $10 and $6.25; $0.01 a web search. The writes go by the answer's own breakdown, any it doesn't give at the hour's rate. Counted per calendar month, in US dollars.
- **No cap** unless you set one (`aiMonthlyCapUsd`). With one, a call goes only if the month's spend, plus every call in flight and this one at their dearest ($1.35, `Pricing.WORST_CALL_USD`: the instructions counted as written to the hour's cache), stays within it (AC-05).
- A call is counted once it comes back, whatever stopped waiting for it: once sent, it's paid for.
- **Rest after a failure** (AC-04, `Failures.restMs`): busy (429, 529) 5, 15, then 60 minutes as failures come in a row; the server or a timeout 15, then 60; no network none; a request the API won't take none; the key 60; anything else 15.
- A longer `Retry-After` stands, and more than three failures in an hour rest an hour. A failed call sent earlier than one that's gone through brings no rest.
- A rest holds what runs by itself (readings, micro-plans, messages written ahead); what you're waiting for goes through it (the chat, an instruction, a photo check, a photo of homework), unless the key failed.
- **Key problems** (`KeyProblem`): rejected (401), not allowed (403), no credit (402, a billing error, a 400 about credit). Alerted once a stretch, Setup saying what puts it right; a call that works, or a new key, ends it.
- **The record** (AC-07): the last 100 calls (`ModelState.calls`), each with its job, what it's about, effort, tokens in, out and from the cache, time taken, why it stopped, how it went and its cost; Settings → Claude → *Recent calls*.
- **One client** (`ModelGateway.client`): every reader sends through the one client the key and the endpoint have, made once and again only when either changes, with its job's timeout (`ModelEffort.timeout`: the review and the tidy five minutes; Felix, first steps and the summary two; the rest three); `ModelGateway.build` is the one place a client is built.

## Claude's other calls

Every call goes through `ModelGateway.call`, so the cap and rests above apply; effort and timeout are set by job (`ModelEffort`).

| Calls | Effort | Spec |
|---|---|---|
| Email, Assignment, Effort | high | this file |
| A photo of homework read as tasks (NF-01) | high | this file, sources.md |
| An instruction | high | felix.md |
| The weekly review | high | learning.md |
| Felix's messages, the chat, the tidy of what he remembers, web research | medium | felix.md |
| Micro-plans | medium | this file |
| A photo check | medium | blocking-and-protection.md |
| The week's summary | medium | learning.md |

## The evaluation set

A small set of your real emails and assignments, each with its right answer, kept in `private/eval/` (git-ignored, never committed). It checks the readings whenever a prompt, a schema or the checks change (IMP-P1-004).
- `EvalSetTest.dump` writes each case's request as the app makes it to `private/eval/prompts/`, addresses redacted. Opus answers them through Claude Code, at no API cost.
- `EvalSetTest.score` puts every answer through `ReadingAnswers.parse` and the planner, as the app would, against your labels, into `private/eval/report.md`.
- `EvalRunTest` (AR-02) asks each case of Claude through the real `ClaudeEnricher` instead, a paid call a case, then scores them. It runs only with `-PrunEval=true` and the API key (never printed).
- It stops ahead of a call whose dearest case could pass its budget (`EVAL_BUDGET_USD`, $2 unless set); `-Peffort=` sets the effort for the run.
- Both are skipped where `private/eval/` isn't, so CI never runs them. A case labelled "only to read" is retired, as a starred email never is. The labels are yours to change.
